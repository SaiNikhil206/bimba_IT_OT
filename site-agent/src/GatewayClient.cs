using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading.Tasks;

namespace Bimba.SiteAgent
{
    internal sealed class GatewayException : Exception
    {
        public int Status { get; }
        public IDictionary<string, object> Body { get; }
        /// <summary>SHA-256 of the gateway certificate when TLS validation failed (offer "trust this certificate").</summary>
        public string UntrustedCertSha256 { get; }

        public GatewayException(int status, string message, IDictionary<string, object> body = null, string untrustedCert = null)
            : base(message)
        {
            Status = status;
            Body = body ?? new Dictionary<string, object>();
            UntrustedCertSha256 = untrustedCert;
        }

        /// <summary>The user's organizations when the gateway asks which one to enroll into (HTTP 409).</summary>
        public List<KeyValuePair<string, string>> Orgs =>
            (Body.TryGetValue("orgs", out var v) ? v as object[] : null)?.OfType<IDictionary<string, object>>()
            .Select(o => new KeyValuePair<string, string>(Json.Str(o, "slug"), Json.Str(o, "name"))).ToList()
            ?? new List<KeyValuePair<string, string>>();
    }

    /// <summary>Talks to the BIMBA Gateway (/system/bimba-agent-*).</summary>
    internal sealed class GatewayClient : IDisposable
    {
        private readonly HttpClient http;
        private readonly string pin;
        private string lastCertSha256;
        private bool lastCertRejected;

        public string Server { get; }

        /// <summary>true when requests sign in with the agent's client certificate (mutual TLS) instead of its secret</summary>
        public bool UsesCertificate { get; }

        public GatewayClient(string server, string pinnedCertSha256 = null, X509Certificate2 clientCertificate = null)
        {
            Server = Normalize(server);
            pin = string.IsNullOrWhiteSpace(pinnedCertSha256) ? null : pinnedCertSha256.Trim().ToLowerInvariant();
            var handler = new HttpClientHandler { ServerCertificateCustomValidationCallback = Validate, UseProxy = true };
            if (clientCertificate != null && Server.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                handler.ClientCertificateOptions = ClientCertificateOption.Manual;
                handler.ClientCertificates.Add(clientCertificate);
                UsesCertificate = true;
            }
            http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(20) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("BIMBA-Site-Agent/" + Inventory.Version);
        }

        /// <summary>"gw.example.com:8443" -> "https://gw.example.com:8443" (no trailing slash).</summary>
        public static string Normalize(string server)
        {
            var s = (server ?? "").Trim().TrimEnd('/');
            if (s.Length == 0) throw new ArgumentException("Enter the gateway address.");
            if (!s.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !s.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                s = "https://" + s;
            if (!Uri.TryCreate(s, UriKind.Absolute, out var u) || string.IsNullOrEmpty(u.Host))
                throw new ArgumentException("The gateway address is not a valid URL.");
            return u.GetLeftPart(UriPartial.Authority);
        }

        public static bool IsPlainHttpToRemote(string server)
        {
            try
            {
                var u = new Uri(Normalize(server));
                return u.Scheme == Uri.UriSchemeHttp && !u.IsLoopback;
            }
            catch
            {
                return false;
            }
        }

        private bool Validate(HttpRequestMessage req, X509Certificate2 cert, X509Chain chain, SslPolicyErrors errors)
        {
            using (var sha = SHA256.Create())
                lastCertSha256 = string.Concat(sha.ComputeHash(cert.RawData).Select(b => b.ToString("x2")));
            // a pinned certificate (self-signed gateway the installer was told to trust) must match exactly
            bool ok = pin != null ? lastCertSha256 == pin : errors == SslPolicyErrors.None;
            lastCertRejected = !ok;
            return ok;
        }

        /// <summary>Reachability + "is site agent registration enabled" (GET on the enroll endpoint).</summary>
        public IDictionary<string, object> Check() => Send(HttpMethod.Get, "/system/bimba-agent-enroll", null, null);

        public IDictionary<string, object> Enroll(IDictionary<string, object> auth, string displayName, string site,
                                                  IDictionary<string, object> machine)
        {
            var body = new Dictionary<string, object>
            {
                ["auth"] = auth, ["displayName"] = displayName, ["site"] = site, ["machine"] = machine,
            };
            return Send(HttpMethod.Post, "/system/bimba-agent-enroll", body, null);
        }

        public IDictionary<string, object> Heartbeat(AgentConfig cfg, IDictionary<string, object> machine, string status = "running")
        {
            var body = new Dictionary<string, object> { ["status"] = status, ["agentVersion"] = Inventory.Version };
            if (machine != null) body["machine"] = machine;
            return Send(HttpMethod.Post, "/system/bimba-agent-heartbeat", body, cfg);
        }

        /// <summary>First client certificate (signed in with the secret) or renewal (signed in with the current certificate).</summary>
        public IDictionary<string, object> RequestCertificate(AgentConfig cfg, byte[] csr)
        {
            var body = new Dictionary<string, object> { ["csr"] = Convert.ToBase64String(csr) };
            return Send(HttpMethod.Post, "/system/bimba-agent-certificate", body, cfg);
        }

        private IDictionary<string, object> Send(HttpMethod method, string path, object body, AgentConfig agent)
        {
            var req = new HttpRequestMessage(method, Server + path);
            if (body != null) req.Content = new StringContent(Json.Serialize(body), Encoding.UTF8, "application/json");
            if (agent != null)
            {
                req.Headers.Add("X-BIMBA-Agent", agent.AgentId);
                // with a client certificate the secret is not sent (the gateway no longer accepts it)
                string secret = UsesCertificate ? null : agent.Secret;
                if (secret != null)
                    req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", secret);
            }
            HttpResponseMessage resp;
            lastCertRejected = false;
            try
            {
                resp = http.SendAsync(req).GetAwaiter().GetResult();
            }
            catch (HttpRequestException) when (lastCertRejected)
            {
                throw new GatewayException(0, pin != null
                    ? "The gateway's certificate changed and no longer matches the trusted certificate."
                    : "The gateway's certificate is not trusted (self-signed or wrong host name).", null, lastCertSha256);
            }
            catch (Exception e)
            {
                var inner = e is HttpRequestException && e.InnerException != null ? e.InnerException : e;
                throw new GatewayException(0, "Cannot reach the gateway at " + Server + ": " +
                    (inner is TaskCanceledException ? "timed out" : inner.Message));
            }
            using (resp)
            {
                string text = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                var json = Json.Parse(text);
                int code = (int)resp.StatusCode;
                if (code >= 200 && code < 300) return json;
                string message = Json.Str(json, "error");
                if (message == null)
                    message = code == 404 ? "This address is not a BIMBA Gateway (site agent registration not found)."
                                          : $"The gateway answered HTTP {code}.";
                throw new GatewayException(code, message, json);
            }
        }

        public void Dispose() => http.Dispose();
    }
}
