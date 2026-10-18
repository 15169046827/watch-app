param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
$trackedFiles = @(& git -C $resolvedRoot ls-files)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to enumerate Git tracked files.'
}

$violations = [System.Collections.Generic.List[string]]::new()
$forbiddenPath = '(?i)(^|/)(AGENTS\.md|\.agents|\.signing|\.android-sdk|\.gradle|\.gradle-user-home|\.tmp|\.tooling)(/|$)|\.(keystore|jks|p12|pfx|pem|key|apk|aab|msi|pdb)$'
foreach ($relativePath in $trackedFiles) {
    $normalized = $relativePath -replace '\\', '/'
    if ($normalized -match $forbiddenPath) {
        $violations.Add("forbidden_path`t$relativePath")
    }
}

$textExtensions = @(
    '.bat', '.cmd', '.gradle', '.java', '.js', '.json', '.kt', '.kts',
    '.md', '.properties', '.ps1', '.py', '.rs', '.sh', '.toml', '.ts',
    '.tsx', '.txt', '.vue', '.xml', '.yaml', '.yml'
)
$rules = [ordered]@{
    local_absolute_path = '(?i)\b(?:C:\\Users\\|E:\\(?:Project|Meterial)\\)'
    private_wolai_page = 'https://www\.wolai\.com/[A-Za-z0-9]+'
    private_key = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    github_token = '\bgh[pousr]_[A-Za-z0-9_]{20,}\b'
    aws_access_key = '\bAKIA[0-9A-Z]{16}\b'
}
foreach ($relativePath in $trackedFiles) {
    $candidate = Join-Path $resolvedRoot $relativePath
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf) -or
            [System.IO.Path]::GetExtension($candidate) -notin $textExtensions) {
        continue
    }
    $content = Get-Content -LiteralPath $candidate -Raw
    foreach ($name in $rules.Keys) {
        if ($content -match $rules[$name]) {
            $violations.Add("$name`t$relativePath")
        }
    }
}

if ($violations.Count -gt 0) {
    throw "Public source boundary violations found (values suppressed):`n$($violations -join "`n")"
}

Write-Host "PUBLIC_SOURCE_BOUNDARY_OK tracked=$($trackedFiles.Count) rules=$($rules.Count)"
