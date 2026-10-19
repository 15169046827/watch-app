function Assert-VoicePackagingOptions {
    [CmdletBinding()]
    param(
        [switch]$WithoutMelo,
        [switch]$WithoutAdditionalVoices
    )
    if ($WithoutMelo -and -not $WithoutAdditionalVoices) {
        throw '-WithoutMelo requires -WithoutAdditionalVoices because Edge and Sherpa use the bundled Python runtime.'
    }
}
