param([string]$ShortcutName)

$ErrorActionPreference = 'Stop'
$defaultNameCharacters = 0x542C, 0x8BFB, 0x5DE5, 0x574A
if ([string]::IsNullOrWhiteSpace($ShortcutName)) {
    $ShortcutName = -join ($defaultNameCharacters | ForEach-Object { [char]$_ })
}
$appPath = Join-Path $PSScriptRoot 'build\windows-package\X3AudiobookBuilder\X3AudiobookBuilder.exe'
$programsPath = Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs'

if (-not (Test-Path -LiteralPath $appPath -PathType Leaf)) {
    throw "听读工坊程序不存在：$appPath"
}
if (-not (Test-Path -LiteralPath $programsPath -PathType Container)) {
    throw "Windows Start Menu Programs directory is missing: $programsPath"
}

$shortcutPath = Join-Path $programsPath ($ShortcutName + '.lnk')
$shortcutShell = New-Object -ComObject WScript.Shell
$shortcut = $shortcutShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $appPath
$shortcut.WorkingDirectory = Split-Path -Parent $appPath
$shortcut.IconLocation = "$appPath,0"
$shortcut.Description = '听读工坊：将 EPUB/TXT 转换为开放 .opbook 有声书'
$shortcut.Save()

$verified = $shortcutShell.CreateShortcut($shortcutPath)
if ($verified.TargetPath -ne $appPath) {
    throw "Start Menu shortcut target verification failed: $shortcutPath"
}

$legacyNames = @(
    (-join ((0x58,0x33,0x20,0x6709,0x58F0,0x4E66,0x8F6C,0x6362,0x5668) | ForEach-Object { [char]$_ })),
    'X3 Audiobook Builder'
)
foreach ($legacyName in $legacyNames) {
    $legacyPath = Join-Path $programsPath ($legacyName + '.lnk')
    if ($legacyPath -ne $shortcutPath -and (Test-Path -LiteralPath $legacyPath -PathType Leaf)) {
        Remove-Item -LiteralPath $legacyPath -Force
    }
}

Write-Host "Created: $shortcutPath"
Write-Host "Target:  $($verified.TargetPath)"
