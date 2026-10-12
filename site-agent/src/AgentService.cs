using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography.X509Certificates;
using System.ServiceProcess;
using System.Threading;

namespace Bimba.SiteAgent
{
    /// <summary>Windows service "BIMBASiteAgent": heartbeat loop + edge collector (data sources -> gateway).</summary>
    internal sealed class AgentService : ServiceBase
    {
        /// <summary>sent by the uninstaller: tell Setu, delete the certificate, stop reporting</summary>
        public const int UninstallCommand = 200;

        private readonly ManualResetEvent stopping = new ManualResetEvent(false);
        private Thread worker;

        public AgentService()
        {
            ServiceName = Paths.ServiceName;
            CanStop = true;
            CanShutdown = true;
        }

        protected override void OnStart(string[] args)
        {
            worker = new Thread(() => HeartbeatLoop.Run(stopping, () =>
            {
                // revoked by the organization: stop cleanly (a clean stop is not restarted by the recovery actions)
                ThreadPool.QueueUserWorkItem(_ => Stop());
            })) { IsBackground = true, Name = "heartbeat" };
            worker.Start();
        }

        protected override void OnStop()
        {
            stopping.Set();
            worker?.Join(TimeSpan.FromSeconds(10));
            Log.Info("service stopped");
        }

        protected override void OnShutdown() => OnStop();

        /// <summary>
        /// Only the service can use its certificate (the key is in its own store), so the uninstaller asks the service
        /// to send the "uninstalled" notice and to delete its certificate and key.
        /// </summary>
        protected override void OnCustomCommand(int command)
        {
            if (command != UninstallCommand) return;
            stopping.Set();
            worker?.Join(TimeSpan.FromSeconds(10));
            var cfg = AgentConfig.TryLoad();
            if (cfg != null)
            {
                try
                {
                    using (var s = new Session(cfg, persist: false))
                        s.Client.Heartbeat(cfg, null, "uninstalled");
                    Log.Info("told Setu that this machine is being removed");
                }
                catch (Exception e)
                {
                    Log.Warn("could not notify Setu: " + e.Message);
                }
                AgentCertificate.Delete(cfg.CertThumbprint);
            }
            AgentCertificate.DeleteOthers(null);
            try
            {
                File.WriteAllText(Paths.UninstallMarker, DateTime.UtcNow.ToString("o"));
            }
            catch (Exception e)
            {
                Log.Warn("could not write " + Paths.UninstallMarker + ": " + e.Message);
            }
        }
    }

    /// <summary>
    /// The agent's connection to Setu and its credential: the client certificate once it has one, before that
    /// the secret from enrollment. Gets the first certificate when Setu asks for it and renews it in time.
    /// </summary>
    internal sealed class Session : IDisposable
    {
        private readonly bool persist;

        public AgentConfig Cfg { get; }
        public GatewayClient Client { get; private set; }
        public X509Certificate2 Certificate { get; private set; }

        /// <param name="persist">false = test run: nothing is written to the installed configuration</param>
        public Session(AgentConfig cfg, bool persist)
        {
            Cfg = cfg;
            this.persist = persist;
            Certificate = AgentCertificate.Find(cfg.CertThumbprint);
            if (cfg.CertThumbprint != null && Certificate == null)
                Log.Warn("the client certificate " + cfg.CertThumbprint + " is missing from the service account's store. Reinstall the agent if Setu refuses it.");
            Client = new GatewayClient(cfg.Server, cfg.PinnedCertSha256, Certificate);
        }

        public IDictionary<string, object> Heartbeat(IDictionary<string, object> machine)
        {
            var resp = Client.Heartbeat(Cfg, machine);
            if (persist && Client.UsesCertificate && Cfg.SecretProtected != null)
            {
                // the certificate works: the secret is no longer accepted by Setu, so it is not kept either
                Cfg.Secret = null;
                Cfg.Save();
                Log.Info("signed in with the client certificate; the agent secret was removed from this machine");
            }
            return resp;
        }

        /// <summary>no certificate yet and Setu offers one, or the current one is due for renewal</summary>
        public bool CertificateDue(IDictionary<string, object> heartbeatAnswer) =>
            Certificate == null
                ? heartbeatAnswer != null && heartbeatAnswer.TryGetValue("certificateWanted", out var v) && v is bool b && b
                : AgentCertificate.NeedsRenewal(Certificate);

        /// <summary>
        /// New key + certificate. The configuration points to the new certificate before it is first used. Setu accepts
        /// the new certificate from the moment it issued it, and the previous credential only until the new one is first
        /// used. So the old credential is restored only when Setu refuses the new certificate; when the trial heartbeat
        /// fails without an answer (it may have reached Setu, which then no longer accepts the old one), the new one is kept.
        /// </summary>
        public void UpdateCertificate()
        {
            X509Certificate2 fresh;
            using (var rsa = AgentCertificate.NewKey(Cfg.AgentId, out var csr))
            {
                try
                {
                    var answer = Client.RequestCertificate(Cfg, csr);
                    fresh = AgentCertificate.Save(Convert.FromBase64String(Json.Str(answer, "certificate") ?? ""), rsa);
                }
                catch
                {
                    try { rsa.Key.Delete(); } catch { /* already gone */ }
                    throw;
                }
            }
            string oldThumbprint = Cfg.CertThumbprint, oldNotAfter = Cfg.CertNotAfter;
            Cfg.CertThumbprint = fresh.Thumbprint;
            Cfg.CertNotAfter = fresh.NotAfter.ToUniversalTime().ToString("o");
            var trial = new GatewayClient(Cfg.Server, Cfg.PinnedCertSha256, fresh);
            try
            {
                if (persist) Cfg.Save();
                trial.Heartbeat(Cfg, null);     // first use: Setu now refuses the previous credential
            }
            catch (Exception e) when (!(e is GatewayException g && (g.Status == 401 || g.Status == 403 || g.Status == 410)))
            {
                // no answer: keep the new certificate (always valid); the secret goes after the first confirmed heartbeat
                Log.Warn("the new client certificate was not confirmed yet (" + e.Message + "); it stays in use");
                Client.Dispose();
                Client = trial;
                Certificate = fresh;
                return;
            }
            catch (Exception e) when (!(e is GatewayException g && g.Status == 410))
            {
                trial.Dispose();
                Cfg.CertThumbprint = oldThumbprint;
                Cfg.CertNotAfter = oldNotAfter;
                if (persist) { try { Cfg.Save(); } catch (Exception se) { Log.Warn("could not restore the configuration: " + se.Message); } }
                AgentCertificate.Delete(fresh.Thumbprint);
                throw new InvalidOperationException("the new client certificate could not be used: " + e.Message, e);
            }
            if (persist && Cfg.SecretProtected != null)
            {
                Cfg.Secret = null;
                Cfg.Save();
            }
            Client.Dispose();
            Client = trial;
            Certificate = fresh;
            if (oldThumbprint != null) AgentCertificate.Delete(oldThumbprint);
            if (persist) AgentCertificate.DeleteOthers(fresh.Thumbprint);
        }

        public void Dispose() => Client.Dispose();
    }

    internal static class HeartbeatLoop
    {
        /// <summary>Full inventory this often; heartbeats in between carry no details.</summary>
        private static readonly TimeSpan FullEvery = TimeSpan.FromMinutes(10);

        public static void Run(WaitHandle stop, Action revoked)
        {
            AgentConfig cfg;
            try
            {
                cfg = AgentConfig.Load();
            }
            catch (Exception e)
            {
                Log.Error("cannot read " + Paths.ConfigFile + ": " + e.Message + ". Re-run the installer.");
                revoked();
                return;
            }
            Log.Info($"started {Inventory.Version}: agent {cfg.AgentId}, organization {cfg.OrgSlug}, gateway {cfg.Server}");
            var lastFull = DateTime.MinValue;
            var nextCertificateTry = DateTime.MinValue;
            int failures = 0;
            using (var session = new Session(cfg, persist: true))
            using (var edge = new EdgeCollector(cfg, () => session.Certificate, stop))
            {
                Log.Info(session.Client.UsesCertificate
                    ? $"signing in with client certificate {cfg.CertThumbprint} (valid until {cfg.CertNotAfter})"
                    : "signing in with the agent secret");
                edge.Start();
                while (true)
                {
                    int wait;
                    try
                    {
                        bool full = DateTime.UtcNow - lastFull >= FullEvery;
                        var resp = session.Heartbeat(full ? Inventory.Collect(cfg.Server) : null);
                        if (full) lastFull = DateTime.UtcNow;
                        edge.OnHeartbeat(resp);
                        if (failures > 0) Log.Info($"gateway reachable again after {failures} failed heartbeat(s)");
                        failures = 0;
                        wait = Math.Max(15, Math.Min(3600, Json.Int(resp, "heartbeatSeconds", cfg.HeartbeatSeconds)));
                        if (DateTime.UtcNow >= nextCertificateTry && session.CertificateDue(resp))
                        {
                            try
                            {
                                bool renewal = session.Certificate != null;
                                session.UpdateCertificate();
                                Log.Info($"{(renewal ? "renewed the" : "received a")} client certificate {cfg.CertThumbprint}, valid until {cfg.CertNotAfter}");
                            }
                            catch (GatewayException e) when (e.Status == 410)
                            {
                                throw;
                            }
                            catch (Exception e)
                            {
                                Log.Warn("client certificate: " + e.Message + " (next try in an hour)");
                                nextCertificateTry = DateTime.UtcNow.AddHours(1);
                            }
                        }
                    }
                    catch (GatewayException e) when (e.Status == 410)
                    {
                        Log.Warn("the organization removed this machine: " + e.Message + " Stopping.");
                        revoked();
                        return;
                    }
                    catch (Exception e)
                    {
                        failures++;
                        if (failures == 1 || failures % 10 == 0) Log.Warn($"heartbeat failed ({failures}x): {e.Message}");
                        lastFull = DateTime.MinValue;          // resend the details once Setu is back
                        wait = Math.Min(600, cfg.HeartbeatSeconds * Math.Min(failures, 5));
                    }
                    if (stop.WaitOne(TimeSpan.FromSeconds(wait))) return;
                }
            }
        }
    }
}
