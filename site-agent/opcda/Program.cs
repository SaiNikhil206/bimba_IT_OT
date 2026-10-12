using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Bimba.SiteAgent;

namespace Bimba.OpcDa
{
    /// <summary>
    /// bimba-opcda.exe: OPC DA bridge, JSON lines over stdin/stdout (one request per line, answered in any order):
    /// <pre>
    /// in   {"id":..,"op":"status","progId":..,"host":..,"session":..}       server status (vendor, version, state)
    ///      {"id":..,"op":"read","progId":..,"host":..,"items":[item ids],"rateMs":1000,"fromDevice":false}
    ///      {"id":..,"op":"readOnce","progId":..,"host":..,"items":[..]}      one device read, outside the collection group
    ///      {"id":..,"op":"browse","progId":..,"host":..,"branch":null,"limit":1000}
    ///      {"id":..,"op":"keep","progId":..,"host":..,"items":[..]}         drop items no longer collected
    ///      {"id":..,"op":"release","progId":..,"host":..}                   disconnect
    /// out  {"type":"hello","version":..,"bits":32}
    ///      {"type":"result","id":..,"ok":true,"result":..} / {"type":"result","id":..,"ok":false,"error":..,"code":"0x80070005"}
    ///      read result: {"values":[{"v":..,"q":192,"t":"2026-..Z","e":null,"type":"number"}]}
    /// </pre>
    /// Each server gets its own worker thread (COM multithreaded apartment), so a slow server does not hold up the others.
    /// The process ends when stdin closes (the edge collector stopped).
    /// </summary>
    internal static class Program
    {
        private static readonly object OutGate = new object();
        private static readonly TextWriter Out = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true };
        private static readonly ConcurrentDictionary<string, Worker> Workers = new ConcurrentDictionary<string, Worker>(StringComparer.OrdinalIgnoreCase);

        [MTAThread]
        private static int Main()
        {
            // before any COM call: authenticate and allow impersonation for DCOM to other computers (integrity: the
            // level Windows requires since the 2023 DCOM hardening)
            int hr = Native.CoInitializeSecurity(IntPtr.Zero, -1, IntPtr.Zero, IntPtr.Zero, Native.RPC_C_AUTHN_LEVEL_PKT_INTEGRITY,
                                                 Native.RPC_C_IMP_LEVEL_IMPERSONATE, IntPtr.Zero, Native.EOAC_NONE, IntPtr.Zero);
            if (hr < 0) Err($"CoInitializeSecurity: 0x{hr:X8} (continuing with the defaults)");
            Send(new Dictionary<string, object>
            {
                ["type"] = "hello",
                ["version"] = typeof(Program).Assembly.GetName().Version.ToString(3),
                ["bits"] = IntPtr.Size * 8,
            });
            var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
            string line;
            while ((line = input.ReadLine()) != null)
            {
                if (line.Trim().Length == 0) continue;
                var req = Json.Parse(line);
                string id = Json.Str(req, "id");
                string progId = Json.Str(req, "progId");
                if (string.IsNullOrWhiteSpace(progId))
                {
                    Fail(id, "progId is missing", 0);
                    continue;
                }
                string host = Json.Str(req, "host");
                // session: the collector's connection ("collect") is kept apart from console requests ("request")
                string key = progId.Trim() + "|" + (host ?? "").Trim() + "|" + (Json.Str(req, "session") ?? "");
                Workers.GetOrAdd(key, k => new Worker(progId.Trim(), host)).Post(req);
            }
            foreach (var w in Workers.Values) w.Stop();
            return 0;
        }

        // ------------------------------------------------------------------ one server

        private sealed class Worker
        {
            private readonly BlockingCollection<Dictionary<string, object>> queue = new BlockingCollection<Dictionary<string, object>>();
            private readonly string progId, host;
            private readonly Thread thread;
            private OpcDaConnection conn;

            public Worker(string progId, string host)
            {
                this.progId = progId;
                this.host = host;
                thread = new Thread(Run) { IsBackground = true, Name = "opcda " + progId };
                thread.SetApartmentState(ApartmentState.MTA);
                thread.Start();
            }

            public void Post(Dictionary<string, object> req) => queue.Add(req);

            public void Stop()
            {
                queue.CompleteAdding();
                thread.Join(5000);
            }

            private void Run()
            {
                foreach (var req in queue.GetConsumingEnumerable())
                {
                    string id = Json.Str(req, "id");
                    string op = Json.Str(req, "op");
                    try
                    {
                        if (op == "release")
                        {
                            Drop();
                            Ok(id, new Dictionary<string, object> { ["released"] = true });
                            continue;
                        }
                        if (conn == null) conn = new OpcDaConnection(progId, host);
                        switch (op)
                        {
                            case "status":
                                Ok(id, conn.Status());
                                break;
                            case "read":
                                Ok(id, new Dictionary<string, object>
                                {
                                    ["values"] = conn.Read(Items(req), Json.Int(req, "rateMs", 1000), req.TryGetValue("fromDevice", out var d) && d is bool b && b),
                                });
                                break;
                            case "readOnce":
                                Ok(id, new Dictionary<string, object> { ["values"] = conn.ReadOnce(Items(req)), ["status"] = conn.Status() });
                                break;
                            case "browse":
                                Ok(id, conn.Browse(Json.Str(req, "branch"), Math.Max(1, Math.Min(5000, Json.Int(req, "limit", 1000)))));
                                break;
                            case "keep":
                                conn.Keep(new HashSet<string>(Items(req), StringComparer.Ordinal));
                                Ok(id, new Dictionary<string, object> { ["kept"] = true });
                                break;
                            default:
                                Fail(id, "unknown op " + op, 0);
                                break;
                        }
                    }
                    catch (Exception e)
                    {
                        int code = e is COMException ce ? ce.ErrorCode : e is UnauthorizedAccessException ? unchecked((int)0x80070005)
                                 : e.InnerException is COMException ie ? ie.ErrorCode : 0;
                        Fail(id, Explain(e, code), code);
                        // the server went away or never started: connect again with the next request
                        if (code != 0 || conn == null) Drop();
                    }
                }
                Drop();
            }

            private void Drop()
            {
                try { conn?.Dispose(); } catch (Exception) { /* gone */ }
                conn = null;
            }

            private string Explain(Exception e, int code)
            {
                string server = progId + (string.IsNullOrWhiteSpace(host) ? "" : " on " + host);
                switch ((uint)code)
                {
                    case 0x80070005:
                        return $"access denied by {server} (DCOM). Give the agent's service account (NT AUTHORITY\\LOCAL SERVICE) launch and access " +
                               "rights for this server in dcomcnfg, or run the agent service as an account the OPC server allows.";
                    case 0x80040154:
                        return $"{server} is not registered on this computer (class not registered): check the ProgID or install the server.";
                    case 0x800401F3:
                        return $"unknown ProgID {progId}: pick the server from the list or enter its exact ProgID.";
                    case 0x800706BA:
                    case 0x800706BE:
                        return $"{server} cannot be reached (RPC server unavailable): is the computer on, DCOM allowed through its firewall (TCP 135)?";
                    case 0x80010108:
                    case 0x80010114:
                        return $"{server} disconnected (the OPC server stopped or restarted).";
                    case 0x80080005:
                        return $"{server} failed to start (CO_E_SERVER_EXEC_FAILURE): check the OPC server itself.";
                    case 0x80004002:
                        return $"{server} does not offer the OPC DA interfaces to this 32-bit client (E_NOINTERFACE): install the " +
                               "OPC Core Components redistributable (it registers the OPC proxy/stub) and try again.";
                    default:
                        return (e.Message ?? e.GetType().Name).Trim() + (code != 0 ? $" (0x{code:X8})" : "");
                }
            }
        }

        private static List<string> Items(Dictionary<string, object> req) =>
            req.TryGetValue("items", out var v) && v is object[] a ? a.Select(x => Convert.ToString(x)).Where(x => !string.IsNullOrEmpty(x)).ToList()
                : new List<string>();

        // ------------------------------------------------------------------ output

        private static void Ok(string id, object result) =>
            Send(new Dictionary<string, object> { ["type"] = "result", ["id"] = id, ["ok"] = true, ["result"] = result });

        private static void Fail(string id, string error, int code) =>
            Send(new Dictionary<string, object>
            {
                ["type"] = "result", ["id"] = id, ["ok"] = false, ["error"] = error, ["code"] = code == 0 ? null : $"0x{code:X8}",
            });

        private static void Send(object o)
        {
            string json = Json.Serialize(o);
            lock (OutGate) Out.WriteLine(json);
        }

        private static void Err(string msg)
        {
            lock (OutGate) Console.Error.WriteLine("[opcda] " + msg);
        }
    }
}
