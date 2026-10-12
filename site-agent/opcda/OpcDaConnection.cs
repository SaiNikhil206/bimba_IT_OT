using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using FILETIME = System.Runtime.InteropServices.ComTypes.FILETIME;

namespace Bimba.OpcDa
{
    /// <summary>
    /// One OPC DA server (ProgID or CLSID, local or on another computer through DCOM) with one active group that holds
    /// every item read so far. Reads come from the server's cache, which it refreshes at the group's update rate, or
    /// straight from the device. Not thread-safe: the bridge calls each connection from its own thread.
    /// </summary>
    internal sealed class OpcDaConnection : IDisposable
    {
        private readonly object server;
        private readonly IOPCServer opc;
        private object group;
        private int groupHandle;
        private int updateRate;
        private readonly Dictionary<string, Item> items = new Dictionary<string, Item>(StringComparer.Ordinal);
        private int nextClientHandle = 1;

        public string ProgId { get; }
        public string Host { get; }
        public Guid Clsid { get; }

        private sealed class Item
        {
            public int Server;
            public string Error;           // AddItems refused it (unknown item id, ...)
            public short CanonicalType;
        }

        public OpcDaConnection(string progId, string host)
        {
            ProgId = progId;
            Host = string.IsNullOrWhiteSpace(host) || IsLocal(host) ? null : host.Trim();
            Clsid = Resolve(ProgId, Host);
            var type = Host == null ? Type.GetTypeFromCLSID(Clsid, true) : Type.GetTypeFromCLSID(Clsid, Host, true);
            server = Activator.CreateInstance(type);
            opc = server as IOPCServer ?? throw new InvalidOperationException(ProgId + " is not an OPC DA server (no IOPCServer)");
            try
            {
                if (server is IOPCCommon common) common.SetClientName("BIMBA Site Agent");
            }
            catch (COMException)
            {
                // optional (OPC DA 2.0)
            }
        }

        private static bool IsLocal(string host)
        {
            var h = host.Trim();
            return h.Equals("localhost", StringComparison.OrdinalIgnoreCase) || h == "127.0.0.1" || h == "."
                   || h.Equals(Environment.MachineName, StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>"{clsid}" or a ProgID registered here; on another computer, its OpcEnum resolves the ProgID</summary>
        private static Guid Resolve(string progId, string host)
        {
            if (Guid.TryParse(progId, out var g)) return g;
            var t = Type.GetTypeFromProgID(progId, false);
            if (t != null) return t.GUID;
            if (host == null) throw new InvalidOperationException("the OPC DA server " + progId + " is not installed on this computer");
            var enumType = Type.GetTypeFromCLSID(Opc.OpcEnumClsid, host, true);
            object list = Activator.CreateInstance(enumType);
            try
            {
                ((IOPCServerList)list).CLSIDFromProgID(progId, out var clsid);
                return clsid;
            }
            finally
            {
                Marshal.ReleaseComObject(list);
            }
        }

        // ------------------------------------------------------------------ status

        public Dictionary<string, object> Status()
        {
            opc.GetStatus(out var p);
            try
            {
                var s = Marshal.PtrToStructure<OPCSERVERSTATUS>(p);
                Marshal.DestroyStructure<OPCSERVERSTATUS>(p);
                return new Dictionary<string, object>
                {
                    ["progId"] = ProgId,
                    ["clsid"] = Clsid.ToString("B"),
                    ["host"] = Host,
                    ["product"] = s.szVendorInfo,
                    ["version"] = $"{(ushort)s.wMajorVersion}.{(ushort)s.wMinorVersion}.{(ushort)s.wBuildNumber}",
                    ["state"] = s.dwServerState >= 0 && s.dwServerState < Opc.States.Length ? Opc.States[s.dwServerState] : "state " + s.dwServerState,
                    ["startTime"] = Time(s.ftStartTime),
                    ["serverTime"] = Time(s.ftCurrentTime),
                    ["groups"] = s.dwGroupCount,
                };
            }
            finally
            {
                Marshal.FreeCoTaskMem(p);
            }
        }

        // ------------------------------------------------------------------ read

        /// <summary>One value per item id: {v, q (OPC quality), t (server time stamp), e (error or null)}, from the active group.</summary>
        public List<Dictionary<string, object>> Read(IList<string> itemIds, int rateMs, bool fromDevice)
        {
            EnsureGroup(rateMs);
            var missing = itemIds.Where(id => !items.ContainsKey(id)).Distinct().ToList();
            if (missing.Count > 0)
                foreach (var kv in AddItems(group, missing, true)) items[kv.Key] = kv.Value;
            return ReadFrom(group, items, itemIds, fromDevice);
        }

        /// <summary>
        /// One read straight from the device in a temporary inactive group (console requests: test, browse values), so the
        /// collection group keeps only the collected items.
        /// </summary>
        public List<Dictionary<string, object>> ReadOnce(IList<string> itemIds)
        {
            var iid = Opc.IID_IOPCItemMgt;
            opc.AddGroup("BIMBA-request-" + Guid.NewGuid().ToString("N"), 0, 1000, 2, IntPtr.Zero, IntPtr.Zero, 0x409,
                         out int handle, out _, ref iid, out object temp);
            try
            {
                var map = AddItems(temp, itemIds.Distinct().ToList(), false);
                return ReadFrom(temp, map, itemIds, true);
            }
            finally
            {
                try { Marshal.ReleaseComObject(temp); opc.RemoveGroup(handle, 1); } catch (Exception) { /* server gone */ }
            }
        }

        private List<Dictionary<string, object>> ReadFrom(object grp, Dictionary<string, Item> map, IList<string> itemIds, bool fromDevice)
        {
            var readable = itemIds.Where(id => map[id].Error == null).Distinct().ToList();
            var values = new Dictionary<string, Dictionary<string, object>>(StringComparer.Ordinal);
            if (readable.Count > 0)
            {
                var handles = readable.Select(id => map[id].Server).ToArray();
                ((IOPCSyncIO)grp).Read(fromDevice ? Opc.DS_DEVICE : Opc.DS_CACHE, handles.Length, handles, out var pValues, out var pErrors);
                var errors = Ints(pErrors, handles.Length);
                int size = Marshal.SizeOf<OPCITEMSTATE>();
                try
                {
                    for (int i = 0; i < handles.Length; i++)
                    {
                        var at = pValues + i * size;
                        var st = Marshal.PtrToStructure<OPCITEMSTATE>(at);
                        Marshal.DestroyStructure<OPCITEMSTATE>(at);            // frees the VARIANT (strings, arrays)
                        values[readable[i]] = errors[i] < 0
                            ? Value(null, 0, null, ErrorText(errors[i]))
                            : Value(st.vDataValue, (ushort)st.wQuality, Time(st.ftTimeStamp), null);
                    }
                }
                finally
                {
                    if (pValues != IntPtr.Zero) Marshal.FreeCoTaskMem(pValues);
                }
            }
            return itemIds.Select(id => values.TryGetValue(id, out var v) ? v : Value(null, 0, null, map[id].Error)).ToList();
        }

        private void EnsureGroup(int rateMs)
        {
            int rate = Math.Max(100, Math.Min(600_000, rateMs <= 0 ? 1000 : rateMs));
            if (group != null && rate >= updateRate) return;       // a faster reader shortens the rate, a slower one keeps it
            if (group != null) DropGroup();
            var iid = Opc.IID_IOPCItemMgt;
            opc.AddGroup("BIMBA", 1, rate, 1, IntPtr.Zero, IntPtr.Zero, 0x409, out groupHandle, out updateRate, ref iid, out group);
            items.Clear();
        }

        private Dictionary<string, Item> AddItems(object grp, List<string> ids, bool active)
        {
            var defs = ids.Select(id => new OPCITEMDEF { szItemID = id, szAccessPath = "", bActive = active ? 1 : 0, hClient = nextClientHandle++ }).ToArray();
            ((IOPCItemMgt)grp).AddItems(defs.Length, defs, out var pResults, out var pErrors);
            var errors = Ints(pErrors, defs.Length);
            int size = Marshal.SizeOf<OPCITEMRESULT>();
            var map = new Dictionary<string, Item>(StringComparer.Ordinal);
            try
            {
                for (int i = 0; i < defs.Length; i++)
                {
                    var r = Marshal.PtrToStructure<OPCITEMRESULT>(pResults + i * size);
                    if (r.pBlob != IntPtr.Zero) Marshal.FreeCoTaskMem(r.pBlob);
                    map[ids[i]] = errors[i] < 0
                        ? new Item { Error = ErrorText(errors[i]) }
                        : new Item { Server = r.hServer, CanonicalType = r.vtCanonicalDataType };
                }
            }
            finally
            {
                if (pResults != IntPtr.Zero) Marshal.FreeCoTaskMem(pResults);
            }
            return map;
        }

        /// <summary>removes the items not asked for any more (the driver's tag list changed)</summary>
        public void Keep(ICollection<string> ids)
        {
            var gone = items.Where(kv => kv.Value.Error == null && !ids.Contains(kv.Key)).ToList();
            if (gone.Count == 0 || group == null) return;
            ((IOPCItemMgt)group).RemoveItems(gone.Count, gone.Select(kv => kv.Value.Server).ToArray(), out var pErrors);
            Ints(pErrors, gone.Count);
            foreach (var kv in gone) items.Remove(kv.Key);
        }

        // ------------------------------------------------------------------ browse

        /// <summary>
        /// The children of a branch (null = root): folders (nodeClass Object, nodeId = their fully qualified branch id) and
        /// items (Variable, nodeId = item id) with their current value.
        /// </summary>
        public Dictionary<string, object> Browse(string branch, int limit)
        {
            var b = server as IOPCBrowseServerAddressSpace ?? throw new InvalidOperationException("this OPC DA server does not support browsing");
            b.QueryOrganization(out int org);
            var list = new List<Dictionary<string, object>>();
            bool truncated = false;
            if (org == Opc.NS_FLAT)
            {
                foreach (var id in Strings(b, Opc.FLAT, limit + 1, ref truncated))
                    list.Add(Node(id, id, "Variable"));
            }
            else
            {
                b.ChangeBrowsePosition(Opc.BROWSE_TO, branch ?? "");
                foreach (var name in Strings(b, Opc.BRANCH, limit + 1, ref truncated))
                    list.Add(Node(FullId(b, name), name, "Object"));
                foreach (var name in Strings(b, Opc.LEAF, limit + 1 - list.Count, ref truncated))
                    list.Add(Node(FullId(b, name), name, "Variable"));
            }
            if (list.Count > limit)
            {
                truncated = true;
                list = list.Take(limit).ToList();
            }
            // current values of the items shown (one read; servers refuse unknown ids per item, not as a whole)
            var vars = list.Where(n => (string)n["nodeClass"] == "Variable").Take(200).ToList();
            if (vars.Count > 0)
            {
                try
                {
                    var vals = ReadOnce(vars.Select(n => (string)n["nodeId"]).ToList());
                    for (int i = 0; i < vars.Count; i++)
                    {
                        vars[i]["value"] = vals[i]["v"];
                        vars[i]["good"] = vals[i]["e"] == null && ((int)vals[i]["q"] & 0xC0) == 0xC0;
                        vars[i]["dataType"] = vals[i]["type"];
                    }
                }
                catch (Exception)
                {
                    // values are a convenience
                }
            }
            return new Dictionary<string, object>
            {
                ["node"] = branch,
                ["organization"] = org == Opc.NS_FLAT ? "flat" : "hierarchical",
                ["items"] = list,
                ["truncated"] = truncated,
            };
        }

        private static string FullId(IOPCBrowseServerAddressSpace b, string name)
        {
            try
            {
                b.GetItemID(name, out var id);
                return string.IsNullOrEmpty(id) ? name : id;
            }
            catch (COMException)
            {
                return name;
            }
        }

        private static Dictionary<string, object> Node(string id, string name, string cls) =>
            new Dictionary<string, object> { ["nodeId"] = id, ["name"] = name, ["nodeClass"] = cls };

        private static List<string> Strings(IOPCBrowseServerAddressSpace b, int type, int max, ref bool truncated)
        {
            var list = new List<string>();
            if (max <= 0) { truncated = true; return list; }
            IEnumString e;
            try
            {
                b.BrowseOPCItemIDs(type, "", 0, 0, out e);
            }
            catch (COMException)
            {
                return list;                                 // nothing of that type here
            }
            if (e == null) return list;
            try
            {
                var buf = new string[100];
                while (list.Count < max)
                {
                    var fetched = Marshal.AllocCoTaskMem(4);
                    try
                    {
                        if (e.Next(buf.Length, buf, fetched) < 0) break;
                        int n = Marshal.ReadInt32(fetched);
                        if (n <= 0) break;
                        list.AddRange(buf.Take(n));
                    }
                    finally
                    {
                        Marshal.FreeCoTaskMem(fetched);
                    }
                }
                if (list.Count >= max) truncated = true;
            }
            finally
            {
                Marshal.ReleaseComObject(e);
            }
            return list;
        }

        // ------------------------------------------------------------------ helpers

        private Dictionary<string, object> Value(object v, int quality, string time, string error)
        {
            var (plain, type) = Plain(v);
            return new Dictionary<string, object> { ["v"] = plain, ["q"] = quality, ["t"] = time, ["e"] = error, ["type"] = type };
        }

        /// <summary>VARIANT contents as JSON: bool, number, string (dates ISO, arrays joined)</summary>
        private static (object, string) Plain(object v)
        {
            switch (v)
            {
                case null: return (null, null);
                case bool b: return (b, "boolean");
                case string s: return (s, "string");
                case DateTime d: return (d.ToUniversalTime().ToString("o"), "string");
                case double d: return (double.IsNaN(d) || double.IsInfinity(d) ? null : (object)d, "number");
                case float f: return (float.IsNaN(f) || float.IsInfinity(f) ? null : (object)(double)f, "number");
                case decimal m: return ((double)m, "number");
                case sbyte _: case byte _: case short _: case ushort _: case int _: case uint _: case long _: case ulong _:
                    return (Convert.ToDouble(v), "number");
                case Array a: return (string.Join(",", a.Cast<object>().Select(x => Convert.ToString(x, System.Globalization.CultureInfo.InvariantCulture))), "string");
                default: return (Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture), "string");
            }
        }

        private static string Time(FILETIME ft)
        {
            long t = ((long)(uint)ft.dwHighDateTime << 32) | (uint)ft.dwLowDateTime;
            if (t <= 0) return null;
            try { return DateTime.FromFileTimeUtc(t).ToString("o"); } catch (ArgumentOutOfRangeException) { return null; }
        }

        private static int[] Ints(IntPtr p, int count)
        {
            var a = new int[count];
            if (p == IntPtr.Zero) return a;
            Marshal.Copy(p, a, 0, count);
            Marshal.FreeCoTaskMem(p);
            return a;
        }

        public string ErrorText(int hr)
        {
            try
            {
                opc.GetErrorString(hr, 0x409, out var s);
                if (!string.IsNullOrWhiteSpace(s)) return s.Trim() + $" (0x{hr:X8})";
            }
            catch (Exception)
            {
                // fall through
            }
            switch ((uint)hr)
            {
                case 0xC0040007: return "unknown item id (OPC_E_UNKNOWNITEMID)";
                case 0xC0040008: return "invalid item id (OPC_E_INVALIDITEMID)";
                case 0xC0040006: return "item not readable (OPC_E_BADRIGHTS)";
                case 0xC0040004: return "data type not supported (OPC_E_BADTYPE)";
                default: return $"error 0x{hr:X8}";
            }
        }

        private void DropGroup()
        {
            try
            {
                if (group != null) Marshal.ReleaseComObject(group);
                opc.RemoveGroup(groupHandle, 1);
            }
            catch (Exception)
            {
                // server gone
            }
            group = null;
            items.Clear();
        }

        public void Dispose()
        {
            DropGroup();
            try { Marshal.FinalReleaseComObject(server); } catch (Exception) { /* already gone */ }
        }
    }
}
