# TEST ONLY, run in the test VM from an elevated PowerShell:
#   powershell -ExecutionPolicy Bypass -File \\host.lan\Data\opcda-test.ps1
# 1. registers the BIMBA OPC DA test server (32-bit in-process COM DLL, site-agent/opcda/testserver)
# 2. drives the OPC DA bridge (bimba-opcda.exe) directly: status, browse, read, read once
# 3. installs / upgrades the BIMBA Site Agent with the enrollment token in opcda-token.txt
# Everything is written to opcda-results.txt in the Shared folder (readable on the host).
$ErrorActionPreference = "Continue"
# elevated processes do not see the Z: mapping of the signed-in session: use the share itself
$share = if (Test-Path "Z:\opcda-test.ps1") { "Z:" } else { "\\host.lan\Data" }
$out = "$share\opcda-results.txt"
"=== OPC DA test $(Get-Date -Format o) on $env:COMPUTERNAME (share $share)" | Set-Content $out
function Log($text) { $text | Add-Content $out; Write-Host $text }

$dir = "C:\BIMBA-test"
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item "$share\BimbaOpcDaTest.dll", "$share\bimba-opcda.exe", "$share\bimba-opcda.exe.config", "$share\BIMBA-Site-Agent-Setup.exe" $dir -Force
& "$env:WINDIR\SysWOW64\regsvr32.exe" /s "$dir\BimbaOpcDaTest.dll"
Log "regsvr32 exit $LASTEXITCODE"
$prog = Get-ItemProperty "Registry::HKEY_CLASSES_ROOT\WOW6432Node\CLSID\{B1B5A0DA-6F2C-4E3B-9C11-0DA7E5715001}\ProgID" -ErrorAction SilentlyContinue
Log "registered (32-bit view): $($prog.'(default)')"

Log "`n--- bridge"
$requests = @(
    '{"id":"status","op":"status","progId":"BIMBA.OpcDaTest.1"}',
    '{"id":"root","op":"browse","progId":"BIMBA.OpcDaTest.1","session":"request"}',
    '{"id":"branch","op":"browse","progId":"BIMBA.OpcDaTest.1","session":"request","branch":"Simulation"}',
    '{"id":"read1","op":"read","progId":"BIMBA.OpcDaTest.1","session":"collect","rateMs":500,"items":["Simulation.Ramp","Simulation.Switch","Simulation.Text","Simulation.Counter","Faults.CommFailure","Faults.Uncertain","No.Such.Item"]}',
    '{"id":"read2","op":"read","progId":"BIMBA.OpcDaTest.1","session":"collect","rateMs":500,"items":["Simulation.Ramp","Simulation.Counter"]}',
    '{"id":"once","op":"readOnce","progId":"BIMBA.OpcDaTest.1","session":"request","items":["Simulation.Sine"]}',
    '{"id":"missing","op":"status","progId":"Nope.Server.1"}',
    '{"id":"rel","op":"release","progId":"BIMBA.OpcDaTest.1","session":"collect"}'
)
$answers = $requests | & "$dir\bimba-opcda.exe" 2>&1
$answers | ForEach-Object { Log $_ }

Log "`n--- agent"
$token = (Get-Content "$share\opcda-token.txt" -Raw).Trim()
$pin = "4f5ed4883ce4b0576d7f663a5e8a6d72e289a9f4c331b64d821ffccd2af84633"
& "$dir\BIMBA-Site-Agent-Setup.exe" install --server https://172.30.0.254:8443 --token $token --trust-cert $pin --site "OPC DA lab" --name "test-scada-opcda" 2>&1 |
    ForEach-Object { Log $_ }
Log "install exit $LASTEXITCODE"
Start-Sleep -Seconds 20
& "C:\Program Files\BIMBA Site Agent\bimba-site-agent.exe" status 2>&1 | ForEach-Object { Log $_ }
Get-Content "C:\ProgramData\BIMBA Site Agent\logs\agent.log" -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Log $_ }
Log "=== done"
