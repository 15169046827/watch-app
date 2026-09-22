param(
    [Parameter(Mandatory = $true)]
    [string[]]$Artifact,
    [string]$ExpectedCertificateSha256 = $env:X3_WINDOWS_SIGNING_CERT_SHA256
)

$ErrorActionPreference = 'Stop'
if (-not $ExpectedCertificateSha256) {
    throw 'Expected Authenticode certificate SHA-256 is required.'
}
$expected = ($ExpectedCertificateSha256 -replace '[^0-9A-Fa-f]', '').ToLowerInvariant()
if ($expected.Length -ne 64) {
    throw 'Expected Authenticode certificate SHA-256 must contain 64 hexadecimal characters.'
}

foreach ($item in $Artifact) {
    $resolved = (Resolve-Path -LiteralPath $item).Path
    if ([System.IO.Path]::GetExtension($resolved) -notin @('.exe', '.msi')) {
        throw "Authenticode verification only accepts EXE or MSI artifacts: $resolved"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $resolved
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid -or
            $null -eq $signature.SignerCertificate) {
        throw "Authenticode signature is not valid for ${resolved}: $($signature.Status)"
    }
    $bytes = $signature.SignerCertificate.GetCertHash(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    $actual = [System.BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Unexpected Authenticode signer for $resolved. Expected $expected, got $actual"
    }
    Write-Host "AUTHENTICODE_OK artifact=$resolved sha256=$actual"
}
