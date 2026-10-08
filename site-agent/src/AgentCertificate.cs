using System;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// The agent's client certificate for mutual TLS with the gateway. The RSA key is created on this machine in the
    /// Windows key store of the account the code runs as (the service: NT AUTHORITY\LocalService), cannot be exported,
    /// and never leaves the machine; only a certificate signing request goes to the gateway, which signs it with its
    /// agent authority. The certificate sits in that account's personal store (CurrentUser\My).
    /// </summary>
    internal static class AgentCertificate
    {
        private const string KeyPrefix = "BIMBA Site Agent ";

        /// <summary>the stored certificate with its private key, or null</summary>
        public static X509Certificate2 Find(string thumbprint)
        {
            if (string.IsNullOrWhiteSpace(thumbprint)) return null;
            using (var store = new X509Store(StoreName.My, StoreLocation.CurrentUser))
            {
                store.Open(OpenFlags.ReadOnly);
                foreach (var c in store.Certificates.Find(X509FindType.FindByThumbprint, thumbprint, false))
                    if (c.HasPrivateKey) return c;
            }
            return null;
        }

        /// <summary>New non-exportable key + PKCS#10 request (DER). The subject is only a hint; the gateway sets its own.</summary>
        public static RSACng NewKey(string agentId, out byte[] csr)
        {
            var p = new CngKeyCreationParameters
            {
                ExportPolicy = CngExportPolicies.None,
                KeyCreationOptions = CngKeyCreationOptions.OverwriteExistingKey,
                KeyUsage = CngKeyUsages.AllUsages,
                Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider,
            };
            p.Parameters.Add(new CngProperty("Length", BitConverter.GetBytes(2048), CngPropertyOptions.None));
            var key = CngKey.Create(CngAlgorithm.Rsa, KeyPrefix + agentId + " " + DateTime.UtcNow.ToString("yyyyMMddHHmmss"), p);
            var rsa = new RSACng(key);
            var req = new CertificateRequest("CN=" + agentId, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            csr = req.CreateSigningRequest();
            return rsa;
        }

        /// <summary>Joins the issued certificate with its key and keeps it in the personal store.</summary>
        public static X509Certificate2 Save(byte[] certificateDer, RSACng key)
        {
            using (var pub = new X509Certificate2(certificateDer))
            using (var withKey = pub.CopyWithPrivateKey(key))
            using (var store = new X509Store(StoreName.My, StoreLocation.CurrentUser))
            {
                store.Open(OpenFlags.ReadWrite);
                store.Add(withKey);
                return Find(withKey.Thumbprint) ?? throw new InvalidOperationException("the certificate could not be stored");
            }
        }

        /// <summary>Removes a certificate and deletes its key (best effort).</summary>
        public static void Delete(string thumbprint)
        {
            if (string.IsNullOrWhiteSpace(thumbprint)) return;
            try
            {
                using (var store = new X509Store(StoreName.My, StoreLocation.CurrentUser))
                {
                    store.Open(OpenFlags.ReadWrite);
                    foreach (var c in store.Certificates.Find(X509FindType.FindByThumbprint, thumbprint, false))
                    {
                        try
                        {
                            if (c.HasPrivateKey && c.GetRSAPrivateKey() is RSACng rsa && rsa.Key.KeyName != null
                                && rsa.Key.KeyName.StartsWith(KeyPrefix, StringComparison.Ordinal))
                                rsa.Key.Delete();
                        }
                        catch (Exception e)
                        {
                            Log.Warn("could not delete the key of certificate " + thumbprint + ": " + e.Message);
                        }
                        store.Remove(c);
                    }
                }
            }
            catch (Exception e)
            {
                Log.Warn("could not remove certificate " + thumbprint + ": " + e.Message);
            }
        }

        /// <summary>Removes certificates from the gateway's agent authority other than <paramref name="keep"/> (left over from re-installs).</summary>
        public static void DeleteOthers(string keep)
        {
            try
            {
                var old = new System.Collections.Generic.List<string>();
                using (var store = new X509Store(StoreName.My, StoreLocation.CurrentUser))
                {
                    store.Open(OpenFlags.ReadOnly);
                    foreach (var c in store.Certificates)
                        if (c.Issuer.Contains("BIMBA Gateway Agent CA") && !string.Equals(c.Thumbprint, keep, StringComparison.OrdinalIgnoreCase))
                            old.Add(c.Thumbprint);
                }
                foreach (var t in old) Delete(t);
            }
            catch (Exception e)
            {
                Log.Warn("could not clean up old certificates: " + e.Message);
            }
        }

        /// <summary>Renew when less than a third of the lifetime is left.</summary>
        public static bool NeedsRenewal(X509Certificate2 c)
        {
            var life = c.NotAfter.ToUniversalTime() - c.NotBefore.ToUniversalTime();
            return c.NotAfter.ToUniversalTime() - DateTime.UtcNow < TimeSpan.FromTicks(life.Ticks / 3);
        }
    }
}
