$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$executableExtensions = @('.ps1', '.sh', '.cmd', '.bat', '.java', '.kt', '.gradle', '.xml')
$forbiddenPatterns = @(
    ('settings\s+' + '(?:put|delete)\s+(?:global|secure|system)\s+(?:http_proxy|global_http_proxy_host|global_http_proxy_port|global_http_proxy_exclusion_list|global_proxy_pac_url)'),
    ('settings\s+' + '(?:put|delete)\s+(?:global|secure|system)\s+(?:airplane_mode_on|airplane_mode_radios|airplane_mode_toggleable_radios|wifi_on|mobile_data|bluetooth_on)'),
    ('cmd\s+' + 'connectivity\s+airplane-mode'),
    ('svc\s+' + '(?:wifi|data|bluetooth)\s+(?:enable|disable)'),
    ('(?:service\s+call|cmd)\s+' + 'bluetooth_manager')
)

$files = Get-ChildItem -LiteralPath $projectRoot -Recurse -File |
    Where-Object {
        $executableExtensions -contains $_.Extension -and
        $_.FullName -notmatch '[\\/](?:build|\.build|\.gradle)[\\/]'
    }

$violations = @(
    foreach ($file in $files) {
        Select-String -LiteralPath $file.FullName -Pattern $forbiddenPatterns |
            ForEach-Object {
                '{0}:{1}' -f $_.Path.Substring($projectRoot.Length + 1), $_.LineNumber
            }
    }
)

if ($violations.Count -gt 0) {
    $details = $violations -join [Environment]::NewLine
    throw "System network mutation is forbidden in X3WolaiNotes tests/scripts:$([Environment]::NewLine)$details"
}

'NETWORK_TEST_POLICY_OK'
