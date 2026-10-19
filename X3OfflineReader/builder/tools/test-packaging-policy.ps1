$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'packaging-policy.ps1')

Assert-VoicePackagingOptions
Assert-VoicePackagingOptions -WithoutAdditionalVoices
Assert-VoicePackagingOptions -WithoutMelo -WithoutAdditionalVoices

$rejected = $false
try {
    Assert-VoicePackagingOptions -WithoutMelo
} catch {
    $rejected = $_.Exception.Message -like '*requires -WithoutAdditionalVoices*'
}
if (-not $rejected) {
    throw 'Packaging must reject additional voices without their bundled Python runtime.'
}
Write-Output 'Packaging option policy passed: valid combinations accepted and broken runtime combination rejected.'
