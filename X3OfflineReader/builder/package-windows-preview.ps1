[CmdletBinding()]
param(
    [switch]$WithoutMelo,
    [switch]$WithoutAdditionalVoices,
    [string]$JpackagePath
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repository = (Resolve-Path (Join-Path $project '..')).Path
$buildRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
$outputRoot = [IO.Path]::GetFullPath((Join-Path $buildRoot 'windows-preview'))
$stagingRoot = [IO.Path]::GetFullPath((Join-Path $buildRoot 'windows-preview-next'))
$backupRoot = [IO.Path]::GetFullPath((Join-Path $buildRoot 'windows-preview-previous'))
$archive = [IO.Path]::GetFullPath(
    (Join-Path $buildRoot 'X3AudiobookBuilder-0.7.0-UNSIGNED-PREVIEW.zip'))
$digestPath = $archive + '.sha256'
$archiveNext = [IO.Path]::GetFullPath(
    (Join-Path $buildRoot 'X3AudiobookBuilder-0.7.0-UNSIGNED-PREVIEW.next.zip'))
$digestNext = $digestPath + '.next'
$archiveBackup = $archive + '.previous'
$digestBackup = $digestPath + '.previous'
$allowed = $buildRoot + [IO.Path]::DirectorySeparatorChar

foreach ($candidate in @($outputRoot, $stagingRoot, $backupRoot, $archive, $archiveNext,
        $digestPath, $digestNext, $archiveBackup, $digestBackup)) {
    if (-not $candidate.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unsafe preview output path'
    }
}

$dirty = & git.exe -C $repository status --porcelain -- X3OfflineReader
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to determine the X3OfflineReader source revision.'
}
if ($dirty) {
    throw 'GitHub 预览包必须来自已审核的干净 X3OfflineReader 工作树。'
}
$sourceCommit = (& git.exe -C $repository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to determine the source commit.'
}

$running = @(Get-Process -Name 'X3AudiobookBuilder', 'X3BuilderCLI', 'tingdu-workshop' `
        -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    throw '请先关闭听读工坊及 X3BuilderCLI，再生成预览包。'
}

function Copy-Tree([string]$Source, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & robocopy.exe $Source $Destination /E /COPY:DAT /DCOPY:DAT /R:2 /W:1 /MT:16 `
        /XD __pycache__ .pytest_cache .git .work conda-meta `
        /XF *.pyc *.pyo *.log *.tmp *.bak /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "Copy failed ($LASTEXITCODE): $Source"
    }
}

$gradle = Join-Path $project 'gradlew.bat'
$icon = Join-Path $PSScriptRoot 'assets\tingdu-workshop-icon.ico'
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) {
    & (Join-Path $PSScriptRoot 'tools\generate-icons.ps1')
}
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) {
    throw '听读工坊图标生成失败'
}

& $gradle -p $project ':builder:installDist' --offline
if ($LASTEXITCODE -ne 0) {
    throw 'Builder compilation failed'
}
$uiRoot = Join-Path $PSScriptRoot 'ui'
Push-Location $uiRoot
try {
    & npm.cmd run desktop:build
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    throw 'Vue/Tauri compilation failed'
}
$tauriExecutable = Join-Path $uiRoot 'src-tauri\target\release\tingdu-workshop.exe'
if (-not (Test-Path -LiteralPath $tauriExecutable -PathType Leaf)) {
    throw 'Tauri executable not found'
}

if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null
$launcher = Join-Path $stagingRoot 'cli.properties'
@(
    'main-jar=builder-0.7.0.jar',
    'main-class=com.canace.x3offlinereader.builder.BuilderMain',
    'arguments=',
    'win-console=true'
) | Set-Content -LiteralPath $launcher -Encoding ASCII

if (-not $JpackagePath -and $env:JAVA_HOME) {
    $JpackagePath = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
}
if (-not $JpackagePath) {
    $command = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($command) {
        $JpackagePath = $command.Source
    }
}
if (-not $JpackagePath -or -not (Test-Path -LiteralPath $JpackagePath -PathType Leaf)) {
    throw 'JDK 17 jpackage.exe not found. Pass -JpackagePath, set JAVA_HOME, or add it to PATH.'
}

& $JpackagePath --type app-image --name X3AudiobookBuilder --app-version '0.7.0' `
    --dest $stagingRoot --input (Join-Path $PSScriptRoot 'build\install\builder\lib') `
    --main-jar 'builder-0.7.0.jar' `
    --main-class 'com.canace.x3offlinereader.builder.BuilderMain' --arguments gui `
    --java-options '-Dfile.encoding=UTF-8' --icon $icon `
    --add-launcher "X3BuilderCLI=$launcher"
if ($LASTEXITCODE -ne 0) {
    throw 'jpackage failed'
}

$app = Join-Path $stagingRoot 'X3AudiobookBuilder'
Copy-Item -LiteralPath $tauriExecutable `
    -Destination (Join-Path $app 'X3AudiobookBuilder.exe') -Force
Copy-Tree (Join-Path $project 'specification') (Join-Path $app 'specification')
New-Item -ItemType Directory -Force -Path (Join-Path $app 'builder') | Out-Null
Copy-Tree (Join-Path $project 'builder\melo') (Join-Path $app 'builder\melo')
Copy-Tree (Join-Path $project 'builder\edge') (Join-Path $app 'builder\edge')
Copy-Tree (Join-Path $project 'builder\sherpa') (Join-Path $app 'builder\sherpa')
Copy-Tree (Join-Path $project 'builder\windows-speech') `
    (Join-Path $app 'builder\windows-speech')
Copy-Tree (Join-Path $project 'builder\assets') (Join-Path $app 'builder\assets')
Copy-Tree (Join-Path $project 'builder\voice-previews') `
    (Join-Path $app 'builder\voice-previews')
Copy-Item -LiteralPath (Join-Path $project 'builder\THIRD_PARTY_TTS.md') `
    -Destination (Join-Path $app 'builder\THIRD_PARTY_TTS.md')
New-Item -ItemType Directory -Force -Path (Join-Path $app '.tools') | Out-Null
Copy-Tree (Join-Path $project '.tools\ffmpeg') (Join-Path $app '.tools\ffmpeg')
if (-not $WithoutMelo) {
    Copy-Tree (Join-Path $project '.tools\melo') (Join-Path $app '.tools\melo')
}
if (-not $WithoutAdditionalVoices) {
    Copy-Tree (Join-Path $project '.tools\edge-tts') (Join-Path $app '.tools\edge-tts')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\runtime') `
        (Join-Path $app '.tools\sherpa-onnx\runtime')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\models') `
        (Join-Path $app '.tools\sherpa-onnx\models')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\python') `
        (Join-Path $app '.tools\sherpa-onnx\python')
}
Copy-Tree (Join-Path $project 'samples\builder-inputs') `
    (Join-Path $app 'builder-inputs')
if (-not $WithoutMelo) {
    & (Join-Path $app '.tools\melo\python.exe') `
        -B (Join-Path $app 'builder\melo\worker.py') --self-test
    if ($LASTEXITCODE -ne 0) {
        throw 'Packaged Melo sanitizer self-test failed'
    }
}
Remove-Item -LiteralPath $launcher -Force
if (Test-Path -LiteralPath (Join-Path $app 'builder\.work')) {
    throw 'Persistent work must not be stored inside the preview app image.'
}

$launchers = @(
    (Join-Path $app 'X3AudiobookBuilder.exe'),
    (Join-Path $app 'X3BuilderCLI.exe')
)
foreach ($artifact in $launchers) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
        throw "预览启动程序不存在：$artifact"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $artifact
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::NotSigned) {
        throw "未签名预览目录拒绝已签名或签名异常的启动程序：$artifact"
    }
}

$notice = @"
听读工坊 0.7.0 — UNSIGNED PREVIEW / 未签名个人预览版

此便携 ZIP 仅用于 GitHub 个人预览，不是正式安装包。
Windows 可能显示“未知发布者”或安全警告；请只从项目的官方 GitHub 发布页下载，
并在运行前核对随包发布的 SHA-256。此版本不创建安装器、不绕过系统安全提示。

源提交：$sourceCommit
发布轨道：WARS 1.1 LIMITED_PREVIEW
正式公共生产版仍要求 Authenticode 状态 Valid、证书 SHA-256 核验和可信时间戳。
"@
$notice | Set-Content -LiteralPath (Join-Path $app 'UNSIGNED-PREVIEW.txt') -Encoding UTF8

if (Test-Path -LiteralPath $backupRoot) {
    Remove-Item -LiteralPath $backupRoot -Recurse -Force
}
$hadPrevious = Test-Path -LiteralPath $outputRoot -PathType Container
$previousMoved = $false
try {
    if ($hadPrevious) {
        Move-Item -LiteralPath $outputRoot -Destination $backupRoot
        $previousMoved = $true
    }
    Move-Item -LiteralPath $stagingRoot -Destination $outputRoot
    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    if (-not $tar) {
        throw '生成预览 ZIP 需要 Windows tar.exe。'
    }
    if (Test-Path -LiteralPath $archiveNext) {
        Remove-Item -LiteralPath $archiveNext -Force
    }
    & $tar.Source -a -cf $archiveNext -C $outputRoot 'X3AudiobookBuilder'
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $archiveNext -PathType Leaf)) {
        throw 'Unsigned preview ZIP creation failed.'
    }
    $entries = @(& $tar.Source -tf $archiveNext)
    if ($LASTEXITCODE -ne 0 -or
            'X3AudiobookBuilder/UNSIGNED-PREVIEW.txt' -notin $entries -or
            @($entries | Where-Object {
                $_ -match '(?i)\.msi$|/(setup|install)\.exe$|/(__pycache__|\.pytest_cache|\.git|\.work|conda-meta)/|\.(pyc|pyo|log|tmp|bak)$'
            }).Count -gt 0) {
        throw 'Unsigned preview ZIP content verification failed.'
    }
    $hash = (Get-FileHash -LiteralPath $archiveNext -Algorithm SHA256).Hash.ToUpperInvariant()
    "$hash  $([IO.Path]::GetFileName($archive))" |
        Set-Content -LiteralPath $digestNext -Encoding ASCII
    foreach ($oldBackup in @($archiveBackup, $digestBackup)) {
        if (Test-Path -LiteralPath $oldBackup) {
            Remove-Item -LiteralPath $oldBackup -Force
        }
    }
    try {
        if (Test-Path -LiteralPath $archive) {
            Move-Item -LiteralPath $archive -Destination $archiveBackup
        }
        if (Test-Path -LiteralPath $digestPath) {
            Move-Item -LiteralPath $digestPath -Destination $digestBackup
        }
        Move-Item -LiteralPath $archiveNext -Destination $archive
        Move-Item -LiteralPath $digestNext -Destination $digestPath
    } catch {
        if (Test-Path -LiteralPath $archive) {
            Remove-Item -LiteralPath $archive -Force
        }
        if (Test-Path -LiteralPath $digestPath) {
            Remove-Item -LiteralPath $digestPath -Force
        }
        if (Test-Path -LiteralPath $archiveBackup) {
            Move-Item -LiteralPath $archiveBackup -Destination $archive
        }
        if (Test-Path -LiteralPath $digestBackup) {
            Move-Item -LiteralPath $digestBackup -Destination $digestPath
        }
        throw
    }
} catch {
    foreach ($temporary in @($archiveNext, $digestNext)) {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    if ($previousMoved) {
        if (Test-Path -LiteralPath $outputRoot) {
            Remove-Item -LiteralPath $outputRoot -Recurse -Force
        }
        if (Test-Path -LiteralPath $backupRoot) {
            Move-Item -LiteralPath $backupRoot -Destination $outputRoot
        }
    } elseif (-not $hadPrevious -and (Test-Path -LiteralPath $outputRoot)) {
        Remove-Item -LiteralPath $outputRoot -Recurse -Force
    }
    throw
}
foreach ($completedBackup in @($archiveBackup, $digestBackup)) {
    if (Test-Path -LiteralPath $completedBackup) {
        Remove-Item -LiteralPath $completedBackup -Force
    }
}
if (Test-Path -LiteralPath $backupRoot) {
    Remove-Item -LiteralPath $backupRoot -Recurse -Force
}
Write-Host "Unsigned preview ready: $archive"
Write-Host "SHA-256 sidecar: $digestPath"
