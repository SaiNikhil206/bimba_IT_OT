# Creates a TEST code-signing certificate in your personal store (Cert:\CurrentUser\My) so the signing step of
# build-site-agent.ps1 can be tried before the real certificate (CA or Azure Trusted Signing) is available.
#
# Files signed with it are NOT trusted anywhere: Windows shows "unknown publisher" exactly as for unsigned files.
# This script does not add it to any trusted store. To try trusted installs on a disposable TEST machine only, import
# the exported .cer there into "Trusted Root Certification Authorities" and "Trusted Publishers".
#
# Usage:
#   powershell -File scripts/new-test-signing-cert.ps1
#   $env:BIMBA_SIGN_THUMBPRINT = "<printed thumbprint>"; $env:BIMBA_SIGN_TIMESTAMP = "none"
#   powershell -File scripts/build-site-agent.ps1
$ErrorActionPreference = "Stop"
$subject = "CN=BIMBA TEST Code Signing (not trusted), O=BIMBA"
$existing = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert | Where-Object { $_.Subject -eq $subject -and $_.NotAfter -gt (Get-Date) } | Select-Object -First 1
$cert = if ($existing) { $existing } else {
    New-SelfSignedCertificate -Type CodeSigningCert -Subject $subject -CertStoreLocation Cert:\CurrentUser\My `
        -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyExportPolicy NonExportable -NotAfter (Get-Date).AddYears(1)
}
$cer = Join-Path (Split-Path -Parent $PSScriptRoot) "site-agent\bimba-test-signing.cer"
Export-Certificate -Cert $cert -FilePath $cer | Out-Null
Write-Host "Test code-signing certificate: $($cert.Subject)"
Write-Host "Thumbprint: $($cert.Thumbprint)   valid until $($cert.NotAfter)"
Write-Host "Public certificate (for a test machine's trust stores): $cer"
Write-Host ""
Write-Host "  `$env:BIMBA_SIGN_THUMBPRINT = '$($cert.Thumbprint)'"
Write-Host "  `$env:BIMBA_SIGN_TIMESTAMP = 'none'"
Write-Host "  powershell -File scripts/build-site-agent.ps1"
