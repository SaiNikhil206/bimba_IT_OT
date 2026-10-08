using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Sockets;
using System.Threading.Tasks;
using Microsoft.Win32;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// Data sources this machine offers, suggested in the console's "Add data source": OPC UA servers listening on
    /// their usual local ports (Ignition, KEPServerEX, WinCC, FactoryTalk Linx, simulators) and the OPC DA (classic)
    /// servers registered in Windows (COM component categories).
    /// </summary>
    internal static class Discovery
    {
        private static readonly (int Port, string Product, string Path)[] OpcUaPorts =
        {
            (62541, "Ignition", ""),
            (49320, "KEPServerEX", ""),
            (4862, "WinCC OPC UA", ""),
            (4890, "WinCC Unified", ""),
            (4990, "FactoryTalk Linx Gateway", ""),
            (53530, "Prosys OPC UA Simulation Server", "/OPCUA/SimulationServer"),
            (48010, "OPC UA server", ""),
            (4840, "OPC UA server", ""),
            (4841, "OPC UA server", ""),
        };

        /// <summary>OPC DA 1.0 / 2.0 / 3.0 component categories</summary>
        private static readonly string[] DaCategories =
        {
            "{63D5F430-CFE4-11d1-B2C8-0060083BA1FB}", "{63D5F432-CFE4-11d1-B2C8-0060083BA1FB}", "{CC603642-66D7-48f1-B69A-B625E73652D7}",
        };

        private static List<object> daCache;
        private static DateTime daCachedAt;

        /// <summary>[{kind:"opcua", endpoint, product}] for every OPC UA port that accepts a connection on this machine</summary>
        public static List<object> OpcUaEndpoints()
        {
            var checks = OpcUaPorts.Select(p => Task.Run(() => Listening(p.Port) ? p : default)).ToArray();
            Task.WaitAll(checks, TimeSpan.FromSeconds(3));
            return checks.Where(t => t.IsCompleted && t.Result.Port != 0)
                .Select(t => (object)new Dictionary<string, object>
                {
                    ["kind"] = "opcua",
                    ["endpoint"] = "opc.tcp://localhost:" + t.Result.Port + t.Result.Path,
                    ["product"] = t.Result.Product,
                }).ToList();
        }

        private static bool Listening(int port)
        {
            try
            {
                using (var c = new TcpClient())
                {
                    var t = c.ConnectAsync("127.0.0.1", port);
                    return t.Wait(400) && c.Connected;
                }
            }
            catch
            {
                return false;
            }
        }

        /// <summary>[{kind:"opcda", progId, name, clsid, bits}] from the registry (cached 10 minutes)</summary>
        public static List<object> OpcDaServers()
        {
            if (daCache != null && DateTime.UtcNow - daCachedAt < TimeSpan.FromMinutes(10)) return daCache;
            var found = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
            {
                try
                {
                    using (var root = RegistryKey.OpenBaseKey(RegistryHive.ClassesRoot, view))
                    using (var clsids = root.OpenSubKey("CLSID"))
                    {
                        if (clsids == null) continue;
                        foreach (var clsid in clsids.GetSubKeyNames())
                        {
                            using (var cats = clsids.OpenSubKey(clsid + @"\Implemented Categories"))
                            {
                                if (cats == null) continue;
                                var names = cats.GetSubKeyNames();
                                if (!names.Any(n => DaCategories.Contains(n, StringComparer.OrdinalIgnoreCase))) continue;
                                using (var k = clsids.OpenSubKey(clsid))
                                using (var prog = clsids.OpenSubKey(clsid + @"\ProgID"))
                                {
                                    string progId = prog?.GetValue(null) as string;
                                    if (string.IsNullOrWhiteSpace(progId) || found.ContainsKey(progId)) continue;
                                    found[progId] = new Dictionary<string, object>
                                    {
                                        ["kind"] = "opcda",
                                        ["progId"] = progId,
                                        ["name"] = (k?.GetValue(null) as string) ?? progId,
                                        ["clsid"] = clsid,
                                        ["bits"] = view == RegistryView.Registry32 ? 32 : 64,
                                    };
                                }
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    Log.Warn("OPC DA server list (" + view + "): " + e.Message);
                }
            }
            daCache = found.Values.ToList();
            daCachedAt = DateTime.UtcNow;
            return daCache;
        }

        public static Dictionary<string, object> All() => new Dictionary<string, object>
        {
            ["opcua"] = OpcUaEndpoints(),
            ["opcda"] = OpcDaServers(),
        };
    }
}
