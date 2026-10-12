using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Management;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.ServiceProcess;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Bimba.SiteAgent
{
    /// <summary>
    /// The machine details shared with the organization: identity, OS, hardware, network addresses and
    /// detected SCADA / OT software. No user names, files, process data or credentials are collected.
    /// </summary>
    internal static class Inventory
    {
        public static string Version => typeof(Inventory).Assembly.GetName().Version.ToString(3);

        /// <summary>Known SCADA / HMI / OT packages: (family, match in product or service name).</summary>
        private static readonly (string Family, string[] Match)[] Known =
        {
            ("Inductive Automation Ignition", new[] { "ignition" }),
            ("Siemens WinCC / TIA Portal", new[] { "wincc", "tia portal", "simatic pcs 7", "simatic step 7" }),
            ("AVEVA / Wonderware", new[] { "intouch", "wonderware", "system platform", "aveva edge", "aveva historian", "archestra" }),
            ("AVEVA Plant SCADA / Citect", new[] { "plant scada", "citect" }),
            ("GE Proficy (iFIX, CIMPLICITY)", new[] { "ifix", "cimplicity", "proficy" }),
            ("Rockwell FactoryTalk / RSLinx", new[] { "factorytalk", "rslinx", "rsview", "studio 5000", "rslogix" }),
            ("Schneider Geo SCADA / Vijeo", new[] { "geo scada", "clearscada", "vijeo", "ecostruxure operator", "ecostruxure control" }),
            ("Kepware KEPServerEX", new[] { "kepserver", "kepware" }),
            ("Matrikon OPC", new[] { "matrikon" }),
            ("COPA-DATA zenon", new[] { "zenon" }),
            ("ICONICS GENESIS64", new[] { "genesis64", "iconics" }),
            ("Emerson DeltaV / Ovation", new[] { "deltav", "ovation" }),
            ("Honeywell Experion", new[] { "experion" }),
            ("Yokogawa CENTUM / FAST/TOOLS", new[] { "centum", "fast/tools" }),
            ("ABB 800xA", new[] { "800xa" }),
            ("InduSoft Web Studio", new[] { "indusoft" }),
            ("Mitsubishi MC Works64", new[] { "mc works" }),
        };

        public static Dictionary<string, object> Collect(string serverUrl = null)
        {
            var m = new Dictionary<string, object>
            {
                ["fingerprint"] = Fingerprint(),
                ["hostname"] = Environment.MachineName,
                ["domain"] = Safe(() => IPGlobalProperties.GetIPGlobalProperties().DomainName, ""),
                ["os"] = Os(),
                ["hardware"] = Hardware(),
                ["disks"] = Disks(),
                ["network"] = Network(),
                ["primaryIp"] = PrimaryIp(serverUrl),
                ["timezone"] = TimeZoneInfo.Local.Id,
                ["scada"] = Scada(),
                ["dataSources"] = Safe(() => (object)Discovery.All(), null),   // suggested in the console's "Add data source"
                ["agentVersion"] = Version,
                ["collectedAt"] = DateTime.UtcNow.ToString("o"),
            };
            return m;
        }

        /// <summary>SHA-256 of the Windows MachineGuid: stable per Windows install, not reversible.</summary>
        public static string Fingerprint()
        {
            string guid = Safe(() =>
            {
                using (var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64)
                           .OpenSubKey(@"SOFTWARE\Microsoft\Cryptography"))
                    return k?.GetValue("MachineGuid") as string;
            }, null) ?? Environment.MachineName;
            using (var sha = SHA256.Create())
            {
                var hash = sha.ComputeHash(Encoding.UTF8.GetBytes("bimba-site-agent:" + guid.Trim().ToLowerInvariant()));
                return string.Concat(hash.Select(b => b.ToString("x2")));
            }
        }

        private static Dictionary<string, object> Os()
        {
            var o = Wmi("SELECT Caption, Version, BuildNumber, OSArchitecture, LastBootUpTime FROM Win32_OperatingSystem").FirstOrDefault();
            return new Dictionary<string, object>
            {
                ["name"] = (Get(o, "Caption") ?? "Windows").Trim(),
                ["version"] = Get(o, "Version") ?? Environment.OSVersion.Version.ToString(),
                ["build"] = Get(o, "BuildNumber"),
                ["arch"] = Get(o, "OSArchitecture") ?? (Environment.Is64BitOperatingSystem ? "64-bit" : "32-bit"),
                ["lastBoot"] = Safe(() => ManagementDateTimeConverter.ToDateTime(Get(o, "LastBootUpTime")).ToUniversalTime().ToString("o"), null),
            };
        }

        private static Dictionary<string, object> Hardware()
        {
            var cs = Wmi("SELECT Manufacturer, Model, TotalPhysicalMemory FROM Win32_ComputerSystem").FirstOrDefault();
            var bios = Wmi("SELECT SerialNumber FROM Win32_BIOS").FirstOrDefault();
            var cpu = Wmi("SELECT Name, NumberOfCores, NumberOfLogicalProcessors FROM Win32_Processor").ToList();
            double.TryParse(Get(cs, "TotalPhysicalMemory"), out var ram);
            return new Dictionary<string, object>
            {
                ["manufacturer"] = Get(cs, "Manufacturer"),
                ["model"] = Get(cs, "Model"),
                ["serialNumber"] = Get(bios, "SerialNumber"),
                ["cpu"] = Get(cpu.FirstOrDefault(), "Name")?.Trim(),
                ["cores"] = cpu.Sum(c => int.TryParse(Get(c, "NumberOfCores"), out var n) ? n : 0),
                ["logicalProcessors"] = Environment.ProcessorCount,
                ["memoryGb"] = Math.Round(ram / 1024 / 1024 / 1024, 1),
            };
        }

        private static List<object> Disks() => Safe(() => DriveInfo.GetDrives()
            .Where(d => d.DriveType == DriveType.Fixed && d.IsReady)
            .Select(d => (object)new Dictionary<string, object>
            {
                ["drive"] = d.Name.TrimEnd('\\'),
                ["sizeGb"] = Math.Round(d.TotalSize / 1073741824.0, 1),
                ["freeGb"] = Math.Round(d.AvailableFreeSpace / 1073741824.0, 1),
            }).ToList(), new List<object>());

        private static List<object> Network() => Safe(() => NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                        && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                        && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel)
            .Select(n =>
            {
                var p = n.GetIPProperties();
                return (object)new Dictionary<string, object>
                {
                    ["name"] = n.Name,
                    ["description"] = n.Description,
                    ["mac"] = string.Join(":", n.GetPhysicalAddress().GetAddressBytes().Select(b => b.ToString("X2"))),
                    ["ipv4"] = p.UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                        .Select(a => a.Address.ToString()).ToList(),
                    ["ipv6"] = p.UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetworkV6 && !a.Address.IsIPv6LinkLocal)
                        .Select(a => a.Address.ToString()).ToList(),
                    ["gateway"] = p.GatewayAddresses.Select(g => g.Address.ToString()).FirstOrDefault(g => g != "0.0.0.0"),
                };
            }).ToList(), new List<object>());

        /// <summary>The local address used to reach Setu (falls back to the first interface with a default gateway).</summary>
        private static string PrimaryIp(string serverUrl)
        {
            string viaRoute = Safe(() =>
            {
                if (string.IsNullOrEmpty(serverUrl)) return null;
                var uri = new Uri(serverUrl);
                var target = Dns.GetHostAddresses(uri.Host).FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork);
                if (target == null || IPAddress.IsLoopback(target)) return null;
                using (var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
                {
                    s.Connect(target, uri.Port > 0 ? uri.Port : 443);   // UDP connect sends nothing, it only picks the route
                    return ((IPEndPoint)s.LocalEndPoint).Address.ToString();
                }
            }, null);
            if (viaRoute != null) return viaRoute;
            return Safe(() => NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .Select(n => n.GetIPProperties())
                .Where(p => p.GatewayAddresses.Any(g => g.Address.ToString() != "0.0.0.0"))
                .SelectMany(p => p.UnicastAddresses)
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(a => a.Address.ToString()).FirstOrDefault(), "");
        }

        /// <summary>Installed SCADA / OT products (Programs and Features) plus their Windows services.</summary>
        public static List<object> Scada()
        {
            var found = new Dictionary<string, Dictionary<string, object>>(StringComparer.OrdinalIgnoreCase);
            foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
            {
                Safe(() =>
                {
                    using (var root = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view)
                               .OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"))
                    {
                        if (root == null) return 0;
                        foreach (var name in root.GetSubKeyNames())
                        {
                            using (var k = root.OpenSubKey(name))
                            {
                                var product = k?.GetValue("DisplayName") as string;
                                var family = FamilyOf(product);
                                if (family == null || found.ContainsKey(product)) continue;
                                found[product] = new Dictionary<string, object>
                                {
                                    ["family"] = family, ["product"] = product.Trim(),
                                    ["version"] = k.GetValue("DisplayVersion") as string ?? "",
                                    ["vendor"] = k.GetValue("Publisher") as string ?? "", ["source"] = "installed",
                                };
                            }
                        }
                    }
                    return 0;
                }, 0);
            }
            Safe(() =>
            {
                foreach (var svc in ServiceController.GetServices())
                {
                    using (svc)
                    {
                        var family = FamilyOf(svc.DisplayName) ?? FamilyOf(svc.ServiceName);
                        if (family == null || svc.ServiceName == Paths.ServiceName) continue;
                        var key = "service:" + svc.ServiceName;
                        found[key] = new Dictionary<string, object>
                        {
                            ["family"] = family, ["product"] = svc.DisplayName, ["service"] = svc.ServiceName,
                            ["state"] = svc.Status.ToString(), ["source"] = "service",
                        };
                    }
                }
                return 0;
            }, 0);
            return found.Values.OrderBy(v => (string)v["family"]).ThenBy(v => (string)v["source"]).Cast<object>().ToList();
        }

        private static string FamilyOf(string name)
        {
            if (string.IsNullOrWhiteSpace(name) || name.IndexOf("BIMBA", StringComparison.OrdinalIgnoreCase) >= 0) return null;
            // match at the start of a word only ("Ovation" must not match "Innovation"); the end may run on ("KEPServerEX")
            foreach (var (family, match) in Known)
                if (match.Any(m => Regex.IsMatch(name, @"(?<![a-z0-9])" + Regex.Escape(m), RegexOptions.IgnoreCase))) return family;
            return null;
        }

        // ------------------------------------------------------------------ helpers

        private static IEnumerable<ManagementBaseObject> Wmi(string query)
        {
            try
            {
                using (var s = new ManagementObjectSearcher(query))
                    return s.Get().Cast<ManagementBaseObject>().ToList();
            }
            catch
            {
                return Enumerable.Empty<ManagementBaseObject>();
            }
        }

        private static string Get(ManagementBaseObject o, string prop)
        {
            try
            {
                return o?[prop]?.ToString();
            }
            catch
            {
                return null;
            }
        }

        private static T Safe<T>(Func<T> f, T fallback)
        {
            try
            {
                return f();
            }
            catch
            {
                return fallback;
            }
        }
    }
}
