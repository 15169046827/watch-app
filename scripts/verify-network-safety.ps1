param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
$executableExtensions = @(
    '.ps1', '.sh', '.cmd', '.bat', '.py', '.js', '.mjs', '.cjs',
    '.ts', '.tsx', '.vue', '.java', '.kt', '.gradle', '.xml', '.yml', '.yaml', '.rs'
)
$excludedDirectoryNames = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
foreach ($name in @(
        '.git', '.gradle', '.gradle-user-home', '.tooling', '.signing',
        'build', '.build', 'node_modules', 'target', 'dist', 'out')) {
    $excludedDirectoryNames.Add($name) | Out-Null
}$forbiddenPatterns = @(
    ('settings\s+' + '(?:put|delete)\s+(?:global|secure|system)\s+(?:http_proxy|global_http_proxy_host|global_http_proxy_port|global_http_proxy_exclusion_list|global_proxy_pac_url)'),
    ('settings\s+' + '(?:put|delete)\s+(?:global|secure|system)\s+(?:airplane_mode_on|airplane_mode_radios|airplane_mode_toggleable_radios|wifi_on|mobile_data|bluetooth_on)'),
    ('cmd\s+' + 'connectivity\s+airplane-mode'),
    ('svc\s+' + '(?:wifi|data|bluetooth)\s+(?:enable|disable)'),
    ('(?:service\s+call|cmd)\s+' + 'bluetooth_manager')
)

$files = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
$gitDirectory = Join-Path $resolvedRoot '.git'
if (Test-Path -LiteralPath $gitDirectory) {
    $relativePaths = @(& git -C $resolvedRoot ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to enumerate tracked and non-ignored source files.'
    }
    foreach ($relativePath in $relativePaths) {
        $candidate = Join-Path $resolvedRoot $relativePath
        if ((Test-Path -LiteralPath $candidate -PathType Leaf) -and
                $executableExtensions -contains [System.IO.Path]::GetExtension($candidate)) {
            $files.Add((Get-Item -LiteralPath $candidate))
        }
    }
} else {
    $pendingDirectories = [System.Collections.Generic.Stack[string]]::new()
    $pendingDirectories.Push($resolvedRoot)
    while ($pendingDirectories.Count -gt 0) {
        $directory = $pendingDirectories.Pop()
        foreach ($item in Get-ChildItem -LiteralPath $directory -Force) {
            if ($item.PSIsContainer) {
                if (-not $excludedDirectoryNames.Contains($item.Name) -and
                        -not ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
                    $pendingDirectories.Push($item.FullName)
                }
            } elseif ($executableExtensions -contains $item.Extension) {
                $files.Add($item)
            }
        }
    }
}

$violations = @(    foreach ($file in $files) {
        Select-String -LiteralPath $file.FullName -Pattern $forbiddenPatterns |
            ForEach-Object {
                '{0}:{1}' -f $_.Path.Substring($resolvedRoot.Length + 1), $_.LineNumber
            }
    }
)

if ($violations.Count -gt 0) {
    throw "Forbidden device-global network mutation found:`n$($violations -join "`n")"
}

Write-Host "NETWORK_SAFETY_OK files=$($files.Count)"
