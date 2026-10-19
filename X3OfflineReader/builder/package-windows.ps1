[CmdletBinding()]
param(
    [switch]$WithoutMelo,
    [switch]$WithoutAdditionalVoices,
    [string]$MeloEnvironmentPath,
    [string]$EdgePackagesPath,
    [string]$JpackagePath,
    [string]$WorkRoot,
    [string]$SignToolPath = $env:X3_WINDOWS_SIGNTOOL_PATH,
    [string]$TimestampUrl = $env:X3_WINDOWS_TIMESTAMP_URL,
    [string]$ExpectedCertificateSha256 = $env:X3_WINDOWS_SIGNING_CERT_SHA256
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outputRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build\windows-package'))
$stagingRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build\windows-package-next'))
$backupRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build\windows-package-previous'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))+[IO.Path]::DirectorySeparatorChar
foreach($candidate in @($outputRoot,$stagingRoot,$backupRoot)){
    if(-not $candidate.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe package output path'}
}
$expectedCertificate=($ExpectedCertificateSha256 -replace '[^0-9A-Fa-f]','').ToUpperInvariant()
if($expectedCertificate.Length -ne 64){throw '正式打包需要 64 位 X3_WINDOWS_SIGNING_CERT_SHA256。'}
if(-not $TimestampUrl -or -not [Uri]::IsWellFormedUriString($TimestampUrl,[UriKind]::Absolute) -or -not $TimestampUrl.StartsWith('https://',[StringComparison]::OrdinalIgnoreCase)){throw '正式打包需要 HTTPS X3_WINDOWS_TIMESTAMP_URL。'}
if(-not $SignToolPath){$signToolCommand=Get-Command signtool.exe -ErrorAction SilentlyContinue;if($signToolCommand){$SignToolPath=$signToolCommand.Source}}
if(-not $SignToolPath -or -not(Test-Path -LiteralPath $SignToolPath -PathType Leaf)){throw '正式打包需要 signtool.exe；请传入 -SignToolPath 或设置 X3_WINDOWS_SIGNTOOL_PATH。'}
$signingCertificate=Get-ChildItem -Path Cert:\CurrentUser\My | Where-Object {
    $_.HasPrivateKey -and $_.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256).ToUpperInvariant() -eq $expectedCertificate
} | Select-Object -First 1
if(-not $signingCertificate){throw '当前用户证书库中未找到指定 SHA-256 且含私钥的 Authenticode 证书。'}
if(-not $MeloEnvironmentPath){$MeloEnvironmentPath=Join-Path $project '.tools\melo'}
if(-not $EdgePackagesPath){$EdgePackagesPath=Join-Path $project '.tools\edge-tts'}
if(-not $WithoutMelo){
    & (Join-Path $MeloEnvironmentPath 'python.exe') -B (Join-Path $PSScriptRoot 'melo\runtime_security.py')
    if($LASTEXITCODE -ne 0){throw 'Source Melo runtime security check failed; select a separately validated environment.'}
}
function Copy-Tree([string]$Source,[string]$Destination){
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & robocopy.exe $Source $Destination /E /COPY:DAT /DCOPY:DAT /R:2 /W:1 /MT:16 /XD __pycache__ .pytest_cache .git .work conda-meta /XF *.pyc *.pyo *.log *.tmp *.bak /NFL /NDL /NJH /NJS /NP | Out-Null
    if($LASTEXITCODE -ge 8){throw "Copy failed ($LASTEXITCODE): $Source"}
}
if(-not $WithoutAdditionalVoices){
    & (Join-Path $MeloEnvironmentPath 'python.exe') -B (Join-Path $PSScriptRoot 'edge\check_runtime.py') --packages $EdgePackagesPath
    if($LASTEXITCODE -ne 0){throw 'Source Edge dependency/ABI check failed; select a fresh validated dependency root.'}
}
$gradle=Join-Path $project 'gradlew.bat'
$icon=Join-Path $PSScriptRoot 'assets\tingdu-workshop-icon.ico'
if(-not(Test-Path -LiteralPath $icon -PathType Leaf)){& (Join-Path $PSScriptRoot 'tools\generate-icons.ps1')}
if(-not(Test-Path -LiteralPath $icon -PathType Leaf)){throw '听读工坊图标生成失败'}
& $gradle -p $project ':builder:installDist' --offline
if($LASTEXITCODE -ne 0){throw 'Builder compilation failed'}
$uiRoot=Join-Path $PSScriptRoot 'ui'
Push-Location $uiRoot
try { & npm.cmd run desktop:build } finally { Pop-Location }
if($LASTEXITCODE -ne 0){throw 'Vue/Tauri compilation failed'}
$tauriExecutable=Join-Path $uiRoot 'src-tauri\target\release\tingdu-workshop.exe'
if(-not(Test-Path -LiteralPath $tauriExecutable -PathType Leaf)){throw 'Tauri executable not found'}
$running=@(Get-Process -Name 'X3AudiobookBuilder','X3BuilderCLI','tingdu-workshop' -ErrorAction SilentlyContinue)
if($running.Count -gt 0){throw '请先关闭听读工坊及 X3BuilderCLI，再重新打包便携程序'}
$persistentWork=if($WorkRoot){[IO.Path]::GetFullPath($WorkRoot)}elseif($env:X3_BUILDER_WORK_ROOT){[IO.Path]::GetFullPath($env:X3_BUILDER_WORK_ROOT)}elseif($env:LOCALAPPDATA){Join-Path $env:LOCALAPPDATA 'Canace\X3AudiobookBuilder\work'}else{throw '无法定位 Windows 用户数据目录；请传入 -WorkRoot'}
$projectBoundary=[IO.Path]::GetFullPath($project)+[IO.Path]::DirectorySeparatorChar
if(([IO.Path]::GetFullPath($persistentWork)+[IO.Path]::DirectorySeparatorChar).StartsWith($projectBoundary,[StringComparison]::OrdinalIgnoreCase)){throw '听读工坊数据目录必须位于项目和正式程序目录之外'}
$builderLauncher=Join-Path $PSScriptRoot 'build\install\builder\bin\builder.bat'
function Move-LegacyWork([string]$Source){
    if(-not(Test-Path -LiteralPath $Source -PathType Container)){return}
    $previousHome=$env:X3_BUILDER_HOME
    $previousWork=$env:X3_BUILDER_WORK_ROOT
    try{
        $env:X3_BUILDER_HOME=$project
        $env:X3_BUILDER_WORK_ROOT=$persistentWork
        & $builderLauncher migrate-work --from $Source
        if($LASTEXITCODE -ne 0){throw "Builder work migration failed: $Source"}
        if(Test-Path -LiteralPath $Source){throw "Builder work migration did not remove the verified legacy copy: $Source"}
    }finally{
        $env:X3_BUILDER_HOME=$previousHome
        $env:X3_BUILDER_WORK_ROOT=$previousWork
    }
}
$oldWork=Join-Path $outputRoot 'X3AudiobookBuilder\builder\.work'
Move-LegacyWork (Join-Path $project 'builder\.work')
Move-LegacyWork $oldWork
if(Test-Path -LiteralPath $stagingRoot){Remove-Item -LiteralPath $stagingRoot -Recurse -Force}
New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null
$launcher=Join-Path $stagingRoot 'cli.properties'
@('main-jar=builder-0.7.0.jar','main-class=com.canace.x3offlinereader.builder.BuilderMain','arguments=','win-console=true') | Set-Content -LiteralPath $launcher -Encoding ASCII
if(-not $JpackagePath -and $env:JAVA_HOME){$JpackagePath=Join-Path $env:JAVA_HOME 'bin\jpackage.exe'}
if(-not $JpackagePath){$command=Get-Command jpackage.exe -ErrorAction SilentlyContinue;if($command){$JpackagePath=$command.Source}}
if(-not $JpackagePath -or -not(Test-Path -LiteralPath $JpackagePath -PathType Leaf)){throw 'JDK 17 jpackage.exe not found. Pass -JpackagePath, set JAVA_HOME, or add jpackage to PATH.'}
& $JpackagePath --type app-image --name X3AudiobookBuilder --app-version '0.7.0' --dest $stagingRoot --input (Join-Path $PSScriptRoot 'build\install\builder\lib') --main-jar 'builder-0.7.0.jar' --main-class 'com.canace.x3offlinereader.builder.BuilderMain' --arguments gui --java-options '-Dfile.encoding=UTF-8' --icon $icon --add-launcher "X3BuilderCLI=$launcher"
if($LASTEXITCODE -ne 0){throw 'jpackage failed'}
$app=Join-Path $stagingRoot 'X3AudiobookBuilder'
Copy-Item -LiteralPath $tauriExecutable -Destination (Join-Path $app 'X3AudiobookBuilder.exe') -Force
Copy-Tree (Join-Path $project 'specification') (Join-Path $app 'specification')
New-Item -ItemType Directory -Force -Path (Join-Path $app 'builder') | Out-Null
Copy-Tree (Join-Path $project 'builder\melo') (Join-Path $app 'builder\melo')
Copy-Tree (Join-Path $project 'builder\edge') (Join-Path $app 'builder\edge')
Copy-Tree (Join-Path $project 'builder\sherpa') (Join-Path $app 'builder\sherpa')
Copy-Tree (Join-Path $project 'builder\windows-speech') (Join-Path $app 'builder\windows-speech')
Copy-Tree (Join-Path $project 'builder\assets') (Join-Path $app 'builder\assets')
Copy-Tree (Join-Path $project 'builder\voice-previews') (Join-Path $app 'builder\voice-previews')
Copy-Item -LiteralPath (Join-Path $project 'builder\THIRD_PARTY_TTS.md') -Destination (Join-Path $app 'builder\THIRD_PARTY_TTS.md')
New-Item -ItemType Directory -Force -Path (Join-Path $app '.tools') | Out-Null
Copy-Tree (Join-Path $project '.tools\ffmpeg') (Join-Path $app '.tools\ffmpeg')
if(-not $WithoutMelo){Copy-Tree $MeloEnvironmentPath (Join-Path $app '.tools\melo')}
if(-not $WithoutAdditionalVoices){
    Copy-Tree $EdgePackagesPath (Join-Path $app '.tools\edge-tts')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\runtime') (Join-Path $app '.tools\sherpa-onnx\runtime')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\models') (Join-Path $app '.tools\sherpa-onnx\models')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\python') (Join-Path $app '.tools\sherpa-onnx\python')
}
Copy-Tree (Join-Path $project 'samples\builder-inputs') (Join-Path $app 'builder-inputs')
if(-not $WithoutMelo){
    . (Join-Path $PSScriptRoot 'tools\prepare-melo-package.ps1')
    $pruned=Remove-RedundantPackagedBertWeights -App $app
    Write-Host "Redundant packaged BERT legacy weights removed: $pruned"
    & (Join-Path $app '.tools\melo\python.exe') -B (Join-Path $app 'builder\melo\runtime_security.py')
    if($LASTEXITCODE -ne 0){throw 'Packaged Melo runtime security check failed'}
    & (Join-Path $app '.tools\melo\python.exe') -B (Join-Path $app 'builder\melo\worker.py') --self-test
    if($LASTEXITCODE -ne 0){throw 'Packaged Melo sanitizer self-test failed'}
    & (Join-Path $app '.tools\melo\python.exe') -B (Join-Path $app 'builder\melo\verify_runtime.py') --python (Join-Path $app '.tools\melo\python.exe')
    if($LASTEXITCODE -ne 0){throw 'Packaged offline Melo synthesis failed after cache pruning'}
}
if(-not $WithoutAdditionalVoices -and -not $WithoutMelo){
    & (Join-Path $app '.tools\melo\python.exe') -B (Join-Path $app 'builder\edge\check_runtime.py') --packages (Join-Path $app '.tools\edge-tts')
    if($LASTEXITCODE -ne 0){throw 'Packaged Edge dependency/ABI check failed'}
}
if(-not $WithoutMelo){
    $emptyCaches=Remove-EmptyPackagedPythonCaches -App $app
    Write-Host "Empty generated Python cache directories removed: $emptyCaches"
    Assert-PackagedContentBoundary -App $app
}
$signedLaunchers=@(
    (Join-Path $app 'X3AudiobookBuilder.exe'),
    (Join-Path $app 'X3BuilderCLI.exe')
)
foreach($artifact in $signedLaunchers){
    if(-not(Test-Path -LiteralPath $artifact -PathType Leaf)){throw "待签名启动程序不存在：$artifact"}
    & $SignToolPath sign /sha1 $signingCertificate.Thumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $artifact
    if($LASTEXITCODE -ne 0){throw "Authenticode 签名失败：$artifact"}
}
& (Join-Path $project '..\scripts\verify-authenticode.ps1') -Artifact $signedLaunchers -ExpectedCertificateSha256 $expectedCertificate
if($LASTEXITCODE -ne 0){throw 'Authenticode 独立验签失败'}
foreach($artifact in $signedLaunchers){
    if(-not (Get-AuthenticodeSignature -LiteralPath $artifact).TimeStamperCertificate){throw "Authenticode 时间戳缺失：$artifact"}
}
Remove-Item -LiteralPath $launcher -Force
if(Test-Path -LiteralPath (Join-Path $app 'builder\.work')){throw 'Persistent work must not be stored inside the replaceable app image'}
if(Test-Path -LiteralPath $backupRoot){Remove-Item -LiteralPath $backupRoot -Recurse -Force}
$hadPrevious=Test-Path -LiteralPath $outputRoot -PathType Container
$previousMoved=$false
try{
    if($hadPrevious){
        Move-Item -LiteralPath $outputRoot -Destination $backupRoot
        $previousMoved=$true
    }
    Move-Item -LiteralPath $stagingRoot -Destination $outputRoot
    $app=Join-Path $outputRoot 'X3AudiobookBuilder'
    $finalLaunchers=@((Join-Path $app 'X3AudiobookBuilder.exe'),(Join-Path $app 'X3BuilderCLI.exe'))
    & (Join-Path $project '..\scripts\verify-authenticode.ps1') -Artifact $finalLaunchers -ExpectedCertificateSha256 $expectedCertificate
    if($LASTEXITCODE -ne 0){throw '正式目录 Authenticode 复验失败'}
}catch{
    if($previousMoved){
        if(Test-Path -LiteralPath $outputRoot){Remove-Item -LiteralPath $outputRoot -Recurse -Force}
        if(Test-Path -LiteralPath $backupRoot){Move-Item -LiteralPath $backupRoot -Destination $outputRoot}
    }elseif(-not $hadPrevious -and (Test-Path -LiteralPath $outputRoot)){
        Remove-Item -LiteralPath $outputRoot -Recurse -Force
    }
    throw
}
if(Test-Path -LiteralPath $backupRoot){Remove-Item -LiteralPath $backupRoot -Recurse -Force}
Write-Host "Windows app image ready: $app"
