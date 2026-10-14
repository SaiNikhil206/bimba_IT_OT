using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// The agent as edge collector. Reads the data sources configured for this machine in the Setu console
    /// (GET /system/bimba-agent-config, refetched when the heartbeat's configVersion changes or the console says so),
    /// collects them with the edge process (Java: OPC UA, Modbus TCP; OPC DA through its 32-bit COM bridge bimba-opcda.exe),
    /// keeps every reading in a disk buffer and uploads
    /// the buffer in batches (POST /system/bimba-agent-readings), so nothing is lost while Setu is unreachable.
    /// Console requests (test a connection, browse tags) arrive through a long-poll (POST /system/bimba-agent-commands).
    /// </summary>
    internal sealed class EdgeCollector : IDisposable
    {
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("BIMBA Site Agent sources v1");
        private const int MaxUploadChars = 250 * 1024;   // Setu refuses requests over 256 KB (413)

        private readonly AgentConfig cfg;
        private readonly Func<X509Certificate2> certificate;
        private readonly WaitHandle stop;
        private readonly EdgeProcess edge;
        private readonly Spool spool = new Spool(Paths.SpoolDir);
        private readonly List<Thread> threads = new List<Thread>();
        private readonly object configGate = new object();
        private IDictionary<string, object> config;       // last config from Setu
        private string appliedVersion;
        private volatile string wantedVersion;
        private long sent, rejected;
        private string lastUpload, lastUploadError, lastConfigError;

        public static EdgeCollector Current { get; private set; }

        public EdgeCollector(AgentConfig cfg, Func<X509Certificate2> certificate, WaitHandle stop)
        {
            this.cfg = cfg;
            this.certificate = certificate;
            this.stop = stop;
            edge = new EdgeProcess(line => spool.Add(line));
        }

        public void Start()
        {
            Current = this;
            config = LoadCache();
            if (config != null)
            {
                appliedVersion = Json.Str(config, "version");
                Apply(config);
            }
            Run("uploader", UploadLoop);
            Run("commands", CommandLoop);
            Run("edge-watch", WatchLoop);
        }

        private void Run(string name, Action body)
        {
            var t = new Thread(() =>
            {
                try { body(); }
                catch (Exception e) { Log.Error(name + " stopped: " + e); }
            }) { IsBackground = true, Name = name };
            threads.Add(t);
            t.Start();
        }

        private GatewayClient Client(TimeSpan timeout) => new GatewayClient(cfg.Server, cfg.PinnedCertSha256, certificate(), timeout);

        // ------------------------------------------------------------------ config

        /// <summary>called with every heartbeat answer: fetch the config when Setu has a newer one</summary>
        public void OnHeartbeat(IDictionary<string, object> resp)
        {
            string v = Json.Str(resp, "configVersion");
            if (v == null) return;                     // gateway without edge collection
            wantedVersion = v;
            if (v != appliedVersion) ThreadPool.QueueUserWorkItem(_ => Refresh());
        }

        public void Refresh()
        {
            lock (configGate)
            {
                try
                {
                    IDictionary<string, object> fresh;
                    using (var c = Client(TimeSpan.FromSeconds(30)))
                        fresh = c.GetConfig(cfg);
                    string v = Json.Str(fresh, "version");
                    if (v == appliedVersion && config != null) return;
                    SaveCache(fresh);
                    config = fresh;
                    appliedVersion = v;
                    lastConfigError = null;
                    Apply(fresh);
                }
                catch (Exception e)
                {
                    lastConfigError = e.Message;
                    Log.Warn("could not fetch the data sources from Setu: " + e.Message);
                }
            }
        }

        private void Apply(IDictionary<string, object> c)
        {
            var sources = (c.TryGetValue("sources", out var s) ? s as object[] : null)?.OfType<IDictionary<string, object>>().ToList()
                          ?? new List<IDictionary<string, object>>();
            var forEdge = sources.Where(x => Collectable(Json.Str(x, "kind"))).ToList();
            int da = forEdge.Count(x => Json.Str(x, "kind") == "opcda");
            Log.Info($"data sources: {sources.Count} ({forEdge.Count - da} OPC UA / Modbus / MQTT / S7 / EtherNet/IP / DNP3 / IEC 104 / SQL, {da} OPC DA)");
            var line = new Dictionary<string, object>
            {
                ["op"] = "config",
                ["plantId"] = Json.Str(c, "plantId"),
                ["edgeId"] = Json.Str(c, "edgeId") ?? Environment.MachineName,
                ["sources"] = forEdge,
            };
            edge.Configure(Json.Serialize(line), forEdge.Count > 0);
        }

        private static bool Collectable(string kind) =>
            kind == "opcua" || kind == "modbus" || kind == "mqtt" || kind == "s7" || kind == "eip" || kind == "dnp3" || kind == "iec104"
            || kind == "sql" || kind == "opcda";

        private static IDictionary<string, object> LoadCache()
        {
            try
            {
                if (!File.Exists(Paths.SourcesFile)) return null;
                var json = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(Paths.SourcesFile), Entropy, DataProtectionScope.LocalMachine));
                return Json.Parse(json);
            }
            catch (Exception e)
            {
                Log.Warn("cached data sources unreadable: " + e.Message);
                return null;
            }
        }

        private static void SaveCache(IDictionary<string, object> c)
        {
            try
            {
                var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(Json.Serialize(c)), Entropy, DataProtectionScope.LocalMachine);
                File.WriteAllBytes(Paths.SourcesFile + ".tmp", bytes);
                if (File.Exists(Paths.SourcesFile)) File.Delete(Paths.SourcesFile);
                File.Move(Paths.SourcesFile + ".tmp", Paths.SourcesFile);
            }
            catch (Exception e)
            {
                Log.Warn("could not cache the data sources: " + e.Message);
            }
        }

        // ------------------------------------------------------------------ upload

        private void UploadLoop()
        {
            GatewayClient client = null;
            string clientCert = null;
            int failures = 0;
            try
            {
                while (!stop.WaitOne(0))
                {
                    spool.FlushIfDue();
                    var file = spool.Oldest();
                    if (file == null)
                    {
                        if (stop.WaitOne(1000)) return;
                        continue;
                    }
                    var thumb = certificate()?.Thumbprint;
                    if (client == null || thumb != clientCert)
                    {
                        client?.Dispose();
                        client = Client(TimeSpan.FromSeconds(60));
                        clientCert = thumb;
                    }
                    int wait = 0;
                    var lines = new List<string>();
                    try
                    {
                        lines = File.ReadAllLines(file).Where(l => l.Length > 1).ToList();
                        var body = "{\"messages\":[" + string.Join(",", lines) + "]}";
                        // a file from an older agent can be over Setu's request limit: upload it in halves
                        if (body.Length > MaxUploadChars && spool.Split(file, lines)) continue;
                        if (lines.Count > 0)
                        {
                            var resp = client.PostReadings(cfg, body);
                            Interlocked.Add(ref sent, Json.Int(resp, "accepted", 0));
                            Interlocked.Add(ref rejected, Json.Int(resp, "rejected", 0));
                        }
                        spool.Done(file);
                        lastUpload = DateTime.UtcNow.ToString("o");
                        if (failures > 0) Log.Info($"readings upload works again after {failures} failure(s)");
                        failures = 0;
                        lastUploadError = null;
                    }
                    catch (GatewayException e) when (e.Status == 400 || e.Status == 413 || e.Status == 422)
                    {
                        if (e.Status == 413 && spool.Split(file, lines))
                        {
                            Log.Info($"Setu found a batch of {lines.Count} readings too large; sending it in two halves");
                        }
                        else
                        {
                            Log.Warn("Setu refused a batch of readings (" + e.Status + "): " + e.Message + "; moved to spool\\rejected");
                            spool.Reject(file);
                        }
                    }
                    catch (Exception e)
                    {
                        failures++;
                        lastUploadError = e.Message;
                        if (failures == 1 || failures % 20 == 0) Log.Warn($"readings upload failed ({failures}x): {e.Message} (kept on disk)");
                        int status = e is GatewayException g ? g.Status : 0;
                        wait = status == 404 ? 300_000 : Math.Min(60_000, 2000 * Math.Min(failures, 30));
                        client?.Dispose();
                        client = null;
                    }
                    if (wait > 0 && stop.WaitOne(wait)) return;
                }
            }
            finally
            {
                spool.Flush();
                client?.Dispose();
            }
        }

        // ------------------------------------------------------------------ console commands

        private void CommandLoop()
        {
            int failures = 0;
            while (!stop.WaitOne(0))
            {
                IDictionary<string, object> cmd;
                try
                {
                    using (var c = Client(TimeSpan.FromSeconds(45)))
                        cmd = c.NextCommand(cfg);
                    failures = 0;
                }
                catch (Exception e)
                {
                    failures++;
                    int status = e is GatewayException g ? g.Status : 0;
                    if (failures == 1) Log.Warn("console requests unavailable: " + e.Message);
                    // 404: a gateway without edge collection; 410: revoked (the heartbeat loop stops the service)
                    if (stop.WaitOne(status == 404 ? 300_000 : Math.Min(60_000, 5000 * failures))) return;
                    continue;
                }
                if (cmd == null) continue;
                ThreadPool.QueueUserWorkItem(_ => Answer(cmd));
            }
        }

        private void Answer(IDictionary<string, object> cmd)
        {
            string id = Json.Str(cmd, "id");
            string kind = Json.Str(cmd, "kind");
            var req = Json.Obj(cmd, "request") ?? new Dictionary<string, object>();
            var answer = new Dictionary<string, object>();
            try
            {
                if (kind == "config")
                {
                    Refresh();
                    answer["ok"] = lastConfigError == null;
                    answer["result"] = new Dictionary<string, object> { ["version"] = appliedVersion };
                    if (lastConfigError != null) answer["error"] = lastConfigError;
                }
                else if (kind == "discover")
                {
                    answer["ok"] = true;
                    answer["result"] = Discovery.All();
                }
                else if (kind == "write" && !WritesAllowedHere(out string why))
                {
                    // a machine command: this machine's own setting wins over Setu
                    answer["ok"] = false;
                    answer["error"] = why;
                    answer["refusedBy"] = "computer";
                }
                else if (kind == "write" && !Ran(id))
                {
                    answer["ok"] = false;
                    answer["error"] = "This command was already carried out.";
                }
                else
                {
                    var source = Json.Obj(req, "source");
                    string sourceKind = Json.Str(source, "kind");
                    if (Collectable(sourceKind))
                    {
                        var r = edge.Request(kind, req, TimeSpan.FromSeconds(40));
                        answer["ok"] = r.TryGetValue("ok", out var ok) && ok is bool b && b;
                        answer["result"] = r.TryGetValue("result", out var res) ? res : null;
                        answer["error"] = Json.Str(r, "error");
                    }
                    else
                    {
                        answer["ok"] = false;
                        answer["error"] = "unknown data source type " + sourceKind;
                    }
                }
            }
            catch (Exception e)
            {
                answer["ok"] = false;
                answer["error"] = e.Message;
            }
            try
            {
                using (var c = Client(TimeSpan.FromSeconds(30)))
                    c.CommandResult(cfg, id, answer);
            }
            catch (Exception e)
            {
                Log.Warn("could not send the answer to console request " + kind + ": " + e.Message);
            }
        }

        private static bool WritesAllowedHere(out string why)
        {
            var now = AgentConfig.TryLoad();
            why = now == null ? "The agent cannot read its own settings, so it refuses machine commands."
                : "Machine commands are turned off on this machine (BIMBA-Site-Agent-Setup.exe writes on).";
            return now != null && now.WritesAllowed;
        }

        /// <summary>machine command ids carried out lately: a command is never repeated, even if Setu handed it out twice</summary>
        private readonly Queue<string> ranOrder = new Queue<string>();
        private readonly HashSet<string> ranIds = new HashSet<string>();

        private bool Ran(string id)
        {
            if (id == null) return false;
            lock (ranIds)
            {
                if (!ranIds.Add(id)) return false;
                ranOrder.Enqueue(id);
                if (ranOrder.Count > 500) ranIds.Remove(ranOrder.Dequeue());
                return true;
            }
        }

        // ------------------------------------------------------------------ supervision + status

        private void WatchLoop()
        {
            while (!stop.WaitOne(5000))
            {
                edge.Watch();
                if (wantedVersion != null && wantedVersion != appliedVersion && config != null) Refresh();
            }
        }

        /// <summary>sent with every heartbeat ("collector"): shown on the machine's page in the console</summary>
        public Dictionary<string, object> Status()
        {
            var sources = (config != null && config.TryGetValue("sources", out var s) ? s as object[] : null) ?? new object[0];
            return new Dictionary<string, object>
            {
                ["configVersion"] = appliedVersion,
                ["configError"] = lastConfigError,
                ["sources"] = sources.Length,
                ["edge"] = edge.Status(),
                ["spool"] = spool.Status(),
                ["sent"] = Interlocked.Read(ref sent),
                ["rejected"] = Interlocked.Read(ref rejected),
                ["lastUpload"] = lastUpload,
                ["lastUploadError"] = lastUploadError,
                ["writes"] = WritesAllowedHere(out _) ? "allowed" : "refused",
                ["at"] = DateTime.UtcNow.ToString("o"),
            };
        }

        public void Dispose()
        {
            foreach (var t in threads) t.Join(TimeSpan.FromSeconds(5));
            edge.Dispose();
            spool.Flush();
            if (Current == this) Current = null;
        }
    }

    /// <summary>
    /// The Java edge process (Paths.EdgeDir: runtime\bin\java.exe + lib\*.jar), JSON lines over stdin/stdout.
    /// Started when there is something to collect or a console request needs it; restarted when it dies.
    /// </summary>
    internal sealed class EdgeProcess : IDisposable
    {
        private const string ReadingPrefix = "{\"type\":\"reading\",\"m\":";
        private readonly Action<string> onReading;
        private readonly object gate = new object();
        private readonly Dictionary<string, TaskCompletionSource<IDictionary<string, object>>> pending =
            new Dictionary<string, TaskCompletionSource<IDictionary<string, object>>>();
        private Process process;
        private string configLine;
        private bool wanted;
        private int restarts;
        private DateTime nextStart = DateTime.MinValue;
        private string lastError;
        private IDictionary<string, object> hello, lastStatus;
        private bool disposed;

        public EdgeProcess(Action<string> onReading)
        {
            this.onReading = onReading;
        }

        private static string JavaExe => Path.Combine(Paths.EdgeDir, "runtime", "bin", "java.exe");
        /// <summary>the 32-bit OPC DA (COM) bridge the edge process starts for OPC DA sources</summary>
        private static string OpcDaExe => Path.Combine(Paths.EdgeDir, "opcda", "bimba-opcda.exe");
        private static string LibDir => Path.Combine(Paths.EdgeDir, "lib");
        public static bool Installed => File.Exists(JavaExe) && Directory.Exists(LibDir);

        /// <param name="collect">false = nothing to collect: the process is stopped (and started again for a request)</param>
        public void Configure(string line, bool collect)
        {
            lock (gate)
            {
                configLine = line;
                wanted = collect;
                if (!collect)
                {
                    if (Running) Send(line);          // stops collecting; the process may still answer requests
                    return;
                }
                if (!EnsureRunning()) return;
                Send(line);
            }
        }

        public IDictionary<string, object> Request(string op, IDictionary<string, object> req, TimeSpan timeout)
        {
            var tcs = new TaskCompletionSource<IDictionary<string, object>>();
            string id = Guid.NewGuid().ToString("N");
            var msg = new Dictionary<string, object>(req) { ["op"] = op, ["id"] = id };
            lock (gate)
            {
                if (!EnsureRunning()) throw new InvalidOperationException(lastError ?? "the edge collector is not available");
                pending[id] = tcs;
                Send(Json.Serialize(msg));
            }
            if (!tcs.Task.Wait(timeout))
            {
                lock (gate) pending.Remove(id);
                throw new TimeoutException("the edge collector did not answer within " + (int)timeout.TotalSeconds + " s");
            }
            return tcs.Task.Result;
        }

        private bool Running => process != null && !process.HasExited;

        /// <summary>call with the gate held</summary>
        private bool EnsureRunning()
        {
            if (disposed) return false;
            if (Running) return true;
            if (!Installed)
            {
                lastError = "the edge collector files are missing (" + Paths.EdgeDir + "); reinstall the agent with version 1.4 or later";
                return false;
            }
            if (DateTime.UtcNow < nextStart) return false;
            try
            {
                Directory.CreateDirectory(Paths.EdgeTempDir);
                Directory.CreateDirectory(Paths.EdgePkiDir);
                var psi = new ProcessStartInfo(JavaExe)
                {
                    Arguments = string.Join(" ", new[]
                    {
                        "-Xms32m", "-Xmx256m", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                        Q("-Djava.io.tmpdir=" + Paths.EdgeTempDir), Q("-Djdk.net.unixdomain.tmpdir=" + Paths.EdgeTempDir),
                        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-Dorg.slf4j.simpleLogger.log.BIMBA=info",
                        "-Dorg.slf4j.simpleLogger.showDateTime=false", "-Dfile.encoding=UTF-8",
                        "-cp", Q(Path.Combine(LibDir, "*")), "com.bimba.edge.EdgeMain",
                        "--pki", Q(Paths.EdgePkiDir), "--host", Q(Environment.MachineName),
                    }.Concat(File.Exists(OpcDaExe) ? new[] { "--opcda", Q(OpcDaExe) } : new string[0])),
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardInput = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8,
                    WorkingDirectory = Paths.EdgeDir,
                };
                var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
                p.Start();
                p.StandardInput.AutoFlush = true;
                process = p;
                var outReader = new Thread(() => ReadOut(p)) { IsBackground = true, Name = "edge-out" };
                var errReader = new Thread(() => ReadErr(p)) { IsBackground = true, Name = "edge-err" };
                outReader.Start();
                errReader.Start();
                Log.Info("edge collector started (pid " + p.Id + ")");
                lastError = null;
                if (configLine != null && !wanted) Send(configLine);
                return true;
            }
            catch (Exception e)
            {
                lastError = "cannot start the edge collector: " + e.Message;
                Log.Error(lastError);
                restarts++;
                nextStart = DateTime.UtcNow.AddSeconds(Math.Min(300, 5 * restarts));
                return false;
            }
        }

        private static string Q(string s) => "\"" + s + "\"";

        private void Send(string line)
        {
            try
            {
                process.StandardInput.WriteLine(line);
            }
            catch (Exception e)
            {
                lastError = "edge collector input: " + e.Message;
            }
        }

        private void ReadOut(Process p)
        {
            try
            {
                string line;
                while ((line = p.StandardOutput.ReadLine()) != null)
                {
                    if (line.StartsWith(ReadingPrefix, StringComparison.Ordinal) && line.EndsWith("}", StringComparison.Ordinal))
                    {
                        onReading(line.Substring(ReadingPrefix.Length, line.Length - ReadingPrefix.Length - 1));
                        continue;
                    }
                    var msg = Json.Parse(line);
                    switch (Json.Str(msg, "type"))
                    {
                        case "result":
                            TaskCompletionSource<IDictionary<string, object>> tcs;
                            lock (gate)
                            {
                                string id = Json.Str(msg, "id") ?? "";
                                if (pending.TryGetValue(id, out tcs)) pending.Remove(id);
                            }
                            tcs?.TrySetResult(msg);
                            break;
                        case "status":
                            lastStatus = msg;
                            break;
                        case "hello":
                            hello = msg;
                            break;
                    }
                }
            }
            catch (Exception e)
            {
                Log.Warn("edge collector output: " + e.Message);
            }
        }

        private static void ReadErr(Process p)
        {
            try
            {
                string line;
                int n = 0;
                while ((line = p.StandardError.ReadLine()) != null)
                {
                    if (line.Trim().Length == 0) continue;
                    // Java stack traces are long: the first lines are enough for the log
                    if (line.StartsWith("\tat ") || line.StartsWith("    at ")) { if (++n > 3) continue; } else n = 0;
                    Log.Info("[edge] " + (line.Length > 1000 ? line.Substring(0, 1000) + "…" : line));
                }
            }
            catch
            {
                // process gone
            }
        }

        /// <summary>every few seconds: restart a process that died while there is something to collect</summary>
        public void Watch()
        {
            lock (gate)
            {
                if (disposed || process == null || !process.HasExited) return;
                int code = SafeExitCode(process);
                process.Dispose();
                process = null;
                foreach (var t in pending.Values) t.TrySetResult(new Dictionary<string, object> { ["ok"] = false, ["error"] = "the edge collector stopped" });
                pending.Clear();
                if (!wanted) return;
                restarts++;
                lastError = "the edge collector stopped (exit code " + code + ")";
                nextStart = DateTime.UtcNow.AddSeconds(Math.Min(300, 5 * restarts));
                Log.Warn(lastError + "; restarting in " + (int)(nextStart - DateTime.UtcNow).TotalSeconds + " s");
            }
            ThreadPool.QueueUserWorkItem(_ =>
            {
                Thread.Sleep(Math.Max(0, (int)(nextStart - DateTime.UtcNow).TotalMilliseconds) + 100);
                lock (gate)
                {
                    if (wanted && EnsureRunning() && configLine != null) Send(configLine);
                }
            });
        }

        private static int SafeExitCode(Process p)
        {
            try { return p.ExitCode; } catch { return -1; }
        }

        public Dictionary<string, object> Status()
        {
            bool running;
            lock (gate) running = Running;
            return new Dictionary<string, object>
            {
                ["state"] = running ? "running" : !Installed ? "missing" : wanted ? "stopped" : "idle",
                ["version"] = Json.Str(hello, "version"),
                ["java"] = Json.Str(hello, "java"),
                ["opcuaCertificate"] = Json.Str(hello, "opcuaCertificate"),
                ["opcuaApplicationUri"] = Json.Str(hello, "opcuaApplicationUri"),
                ["devices"] = lastStatus != null && lastStatus.TryGetValue("devices", out var d) ? d : null,
                ["restarts"] = restarts,
                ["error"] = lastError,
            };
        }

        public void Dispose()
        {
            Process p;
            lock (gate)
            {
                disposed = true;
                p = process;
                process = null;
            }
            if (p == null) return;
            try
            {
                if (!p.HasExited)
                {
                    try { p.StandardInput.Close(); } catch { /* already closed */ }   // the edge process exits when stdin closes
                    if (!p.WaitForExit(5000)) p.Kill();
                }
            }
            catch (Exception e)
            {
                Log.Warn("stopping the edge collector: " + e.Message);
            }
            finally
            {
                p.Dispose();
            }
        }

        /// <summary>installer: ends edge processes still running from the install folder (before replacing the files)</summary>
        public static void KillStray()
        {
            foreach (var p in Process.GetProcessesByName("java").Concat(Process.GetProcessesByName("bimba-opcda")))
            {
                try
                {
                    string path = p.MainModule?.FileName ?? "";
                    if (path.StartsWith(Paths.EdgeDir, StringComparison.OrdinalIgnoreCase))
                    {
                        p.Kill();
                        p.WaitForExit(5000);
                    }
                }
                catch
                {
                    // not ours or already gone
                }
                finally
                {
                    p.Dispose();
                }
            }
        }
    }

    /// <summary>
    /// Disk buffer for readings: one JSON message per line, files of up to 1000 messages or MaxFileChars (written at
    /// least every second), oldest uploaded first. A file is one upload, so it must stay under Setu's request limit
    /// (256 KB); a file that is still too big (older agents wrote up to 1000 messages) is split, not discarded.
    /// Capped at 1 GB: beyond that the oldest files are dropped (and logged).
    /// </summary>
    internal sealed class Spool
    {
        private const int MaxPerFile = 1000;
        private const int MaxFileChars = 200 * 1024;      // well under 256 KB, room for the {"messages":[…]} wrapper
        private const int MaxRejectedFiles = 200;         // refused files kept in rejected\ (oldest removed beyond this)
        private const long MaxBytes = 1L << 30;
        private readonly string dir;
        private readonly object gate = new object();
        private StringBuilder current = new StringBuilder();
        private int count;
        private DateTime firstAt;
        private long seq;
        private long dropped;

        public Spool(string dir)
        {
            this.dir = dir;
            Directory.CreateDirectory(dir);
            // a crash may leave a temporary file: it is complete up to its last line, so keep it
            foreach (var tmp in Directory.GetFiles(dir, "*.tmp"))
            {
                try { File.Move(tmp, Path.ChangeExtension(tmp, ".jsonl")); } catch { /* ignore */ }
            }
        }

        public void Add(string message)
        {
            lock (gate)
            {
                if (count > 0 && current.Length + message.Length + 1 > MaxFileChars) FlushLocked();
                if (count == 0) firstAt = DateTime.UtcNow;
                current.Append(message).Append('\n');
                if (++count >= MaxPerFile || current.Length >= MaxFileChars) FlushLocked();
            }
        }

        public void FlushIfDue()
        {
            lock (gate)
            {
                if (count > 0 && DateTime.UtcNow - firstAt >= TimeSpan.FromSeconds(1)) FlushLocked();
            }
        }

        public void Flush()
        {
            lock (gate)
            {
                if (count > 0) FlushLocked();
            }
        }

        private void FlushLocked()
        {
            string name = DateTime.UtcNow.Ticks.ToString("D19") + "-" + (++seq).ToString("D6");
            string tmp = Path.Combine(dir, name + ".tmp");
            try
            {
                File.WriteAllText(tmp, current.ToString(), new UTF8Encoding(false));
                File.Move(tmp, Path.Combine(dir, name + ".jsonl"));
            }
            catch (Exception e)
            {
                Log.Error("disk buffer: cannot write " + tmp + ": " + e.Message + " (" + count + " readings lost)");
            }
            current = new StringBuilder();
            count = 0;
            Trim();
        }

        private void Trim()
        {
            try
            {
                var files = new DirectoryInfo(dir).GetFiles("*.jsonl").OrderBy(f => f.Name).ToList();
                long total = files.Sum(f => f.Length);
                int i = 0;
                while (total > MaxBytes && i < files.Count - 1)
                {
                    total -= files[i].Length;
                    files[i].Delete();
                    dropped++;
                    i++;
                }
                if (i > 0) Log.Warn($"disk buffer over {MaxBytes >> 20} MB: dropped the {i} oldest file(s) of readings");
            }
            catch (Exception e)
            {
                Log.Warn("disk buffer: " + e.Message);
            }
        }

        public string Oldest() => Directory.GetFiles(dir, "*.jsonl").OrderBy(f => f, StringComparer.Ordinal).FirstOrDefault();

        public void Done(string file)
        {
            try { File.Delete(file); } catch (Exception e) { Log.Warn("disk buffer: " + e.Message); }
        }

        /// <summary>
        /// Replaces the file by two files with its first and second half, sorted at its place (name + "a" / "b"), so they
        /// are uploaded next and in order. False when it has a single message (it cannot get smaller).
        /// </summary>
        public bool Split(string file, List<string> lines)
        {
            if (lines.Count < 2) return false;
            string stem = Path.Combine(dir, Path.GetFileNameWithoutExtension(file));
            int half = lines.Count / 2;
            try
            {
                WriteAtomic(stem + "a", lines.GetRange(0, half));
                WriteAtomic(stem + "b", lines.GetRange(half, lines.Count - half));
                File.Delete(file);
                return true;
            }
            catch (Exception e)
            {
                Log.Warn("disk buffer: cannot split " + Path.GetFileName(file) + ": " + e.Message);
                return false;
            }
        }

        private static void WriteAtomic(string stem, List<string> lines)
        {
            File.WriteAllText(stem + ".tmp", string.Join("\n", lines) + "\n", new UTF8Encoding(false));
            if (File.Exists(stem + ".jsonl")) File.Delete(stem + ".jsonl");
            File.Move(stem + ".tmp", stem + ".jsonl");
        }

        public void Reject(string file)
        {
            try
            {
                var rejectedDir = Path.Combine(dir, "rejected");
                Directory.CreateDirectory(rejectedDir);
                File.Move(file, Path.Combine(rejectedDir, Path.GetFileName(file)));
                var kept = Directory.GetFiles(rejectedDir).OrderBy(f => f, StringComparer.Ordinal).ToList();
                for (int i = 0; i < kept.Count - MaxRejectedFiles; i++) File.Delete(kept[i]);
            }
            catch (Exception e)
            {
                Log.Warn("disk buffer: " + e.Message);
                Done(file);
            }
        }

        public Dictionary<string, object> Status()
        {
            try
            {
                var files = new DirectoryInfo(dir).GetFiles("*.jsonl");
                int inMemory;
                lock (gate) inMemory = count;
                return new Dictionary<string, object>
                {
                    ["files"] = files.Length,
                    ["bytes"] = files.Sum(f => f.Length),
                    ["inMemory"] = inMemory,
                    ["droppedFiles"] = dropped,
                };
            }
            catch
            {
                return new Dictionary<string, object>();
            }
        }
    }
}
