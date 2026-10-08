using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace Bimba.SiteAgent
{
    internal static class Paths
    {
        public const string ServiceName = "BIMBASiteAgent";
        public const string DisplayName = "BIMBA Site Agent";
        public const string ExeName = "bimba-site-agent.exe";

        public static string InstallDir =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), DisplayName);
        public static string InstalledExe => Path.Combine(InstallDir, ExeName);
        public static string DataDir =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), DisplayName);
        public static string ConfigFile => Path.Combine(DataDir, "agent.json");
        public static string LogDir => Path.Combine(DataDir, "logs");
        /// <summary>edge collector: Java runtime + jars, extracted by the installer</summary>
        public static string EdgeDir => Path.Combine(InstallDir, "edge");
        /// <summary>readings waiting for the gateway (disk buffer)</summary>
        public static string SpoolDir => Path.Combine(DataDir, "spool");
        /// <summary>the edge collector's data sources (DPAPI-protected: may contain OPC UA passwords)</summary>
        public static string SourcesFile => Path.Combine(DataDir, "sources.dat");
        /// <summary>the edge collector's OPC UA client certificate</summary>
        public static string EdgePkiDir => Path.Combine(DataDir, "edge-pki");
        /// <summary>short temp folder for the Java runtime (its Windows socket paths must stay short)</summary>
        public static string EdgeTempDir => Path.Combine(DataDir, "tmp");
        /// <summary>written by the service when it has told the gateway about an uninstall</summary>
        public static string UninstallMarker => Path.Combine(DataDir, "uninstall-notified");
    }

    /// <summary>
    /// What the installer stores on the machine: gateway address, agent id and the agent's own secret.
    /// The secret is DPAPI-encrypted (machine scope) and the folder is readable only by SYSTEM,
    /// Administrators and the service account. Enrollment tokens and passwords are never stored.
    /// Once the service has its client certificate (see AgentCertificate) and has used it, the secret is removed:
    /// only the certificate's thumbprint is kept here, the certificate and key live in the service account's store.
    /// </summary>
    internal sealed class AgentConfig
    {
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("BIMBA Site Agent v1");

        public string Server { get; set; }
        public string AgentId { get; set; }
        public string SecretProtected { get; set; }
        public string OrgSlug { get; set; }
        public string OrgName { get; set; }
        public string DisplayName { get; set; }
        public string Site { get; set; }
        public int HeartbeatSeconds { get; set; } = 60;
        /// <summary>SHA-256 of a self-signed gateway certificate the installer was told to trust (hex), or null.</summary>
        public string PinnedCertSha256 { get; set; }
        public string EnrolledAt { get; set; }
        /// <summary>thumbprint of the client certificate in the service account's personal store, or null</summary>
        public string CertThumbprint { get; set; }
        public string CertNotAfter { get; set; }

        /// <summary>null once the agent signs in with its certificate</summary>
        public string Secret
        {
            get => string.IsNullOrEmpty(SecretProtected) ? null : Encoding.UTF8.GetString(ProtectedData.Unprotect(
                Convert.FromBase64String(SecretProtected), Entropy, DataProtectionScope.LocalMachine));
            set => SecretProtected = value == null ? null : Convert.ToBase64String(ProtectedData.Protect(
                Encoding.UTF8.GetBytes(value), Entropy, DataProtectionScope.LocalMachine));
        }

        public static bool Exists => File.Exists(Paths.ConfigFile);

        public static AgentConfig Load()
        {
            var json = File.ReadAllText(Paths.ConfigFile, Encoding.UTF8);
            return new JavaScriptSerializer().Deserialize<AgentConfig>(json);
        }

        public static AgentConfig TryLoad()
        {
            try
            {
                return Exists ? Load() : null;
            }
            catch
            {
                return null;
            }
        }

        public void Save()
        {
            Directory.CreateDirectory(Paths.DataDir);
            var map = new Dictionary<string, object>
            {
                ["Server"] = Server, ["AgentId"] = AgentId, ["SecretProtected"] = SecretProtected,
                ["OrgSlug"] = OrgSlug, ["OrgName"] = OrgName, ["DisplayName"] = DisplayName, ["Site"] = Site,
                ["HeartbeatSeconds"] = HeartbeatSeconds, ["PinnedCertSha256"] = PinnedCertSha256, ["EnrolledAt"] = EnrolledAt,
                ["CertThumbprint"] = CertThumbprint, ["CertNotAfter"] = CertNotAfter,
            };
            string tmp = Paths.ConfigFile + ".tmp";
            File.WriteAllText(tmp, Json.Pretty(map), new UTF8Encoding(false));
            if (File.Exists(Paths.ConfigFile)) File.Delete(Paths.ConfigFile);
            File.Move(tmp, Paths.ConfigFile);
        }
    }
}
