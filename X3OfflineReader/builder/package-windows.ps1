[CmdletBinding()]
param([switch]$WithoutMelo,[switch]$WithoutAdditionalVoices,[string]$JpackagePath)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outputRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build\windows-package'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))+[IO.Path]::DirectorySeparatorChar
if(-not $outputRoot.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe package output path'}
function Copy-Tree([string]$Source,[string]$Destination){
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & robocopy.exe $Source $Destination /E /COPY:DAT /DCOPY:DAT /R:2 /W:1 /MT:16 /NFL /NDL /NJH /NJS /NP | Out-Null
    if($LASTEXITCODE -ge 8){throw "Copy failed ($LASTEXITCODE): $Source"}
}
$gradle=Join-Path $project 'gradlew.bat'
$icon=Join-Path $PSScriptRoot 'assets\tingdu-workshop-icon.ico'
if(-not(Test-Path -LiteralPath $icon -PathType Leaf)){& (Join-Path $PSScriptRoot 'tools\generate-icons.ps1')}
if(-not(Test-Path -LiteralPath $icon -PathType Leaf)){throw '听读工坊图标生成失败'}
& $gradle -p $project ':builder:installDist' --offline
if($LASTEXITCODE -ne 0){throw 'Builder compilation failed'}
$uiRoot=Join-Path $PSScriptRoot 'ui'
Push-Location $uiRoot
try { & npm.cmd run tauri build } finally { Pop-Location }
if($LASTEXITCODE -ne 0){throw 'Vue/Tauri compilation failed'}
$tauriExecutable=Join-Path $uiRoot 'src-tauri\target\release\tingdu-workshop.exe'
if(-not(Test-Path -LiteralPath $tauriExecutable -PathType Leaf)){throw 'Tauri executable not found'}
$running=@(Get-Process -Name 'X3AudiobookBuilder','X3BuilderCLI','tingdu-workshop' -ErrorAction SilentlyContinue)
if($running.Count -gt 0){throw '请先关闭听读工坊及 X3BuilderCLI，再重新打包便携程序'}
$oldWork=Join-Path $outputRoot 'X3AudiobookBuilder\builder\.work'
$workBackup=$null
if(Test-Path -LiteralPath $oldWork -PathType Container){
    $workBackup=Join-Path $PSScriptRoot ('build\windows-work-preserve-'+[Guid]::NewGuid().ToString('N'))
    $resolvedBackup=[IO.Path]::GetFullPath($workBackup)
    if(-not $resolvedBackup.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe work backup path'}
    Copy-Tree $oldWork $workBackup
}
if(Test-Path -LiteralPath $outputRoot){Remove-Item -LiteralPath $outputRoot -Recurse -Force}
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$launcher=Join-Path $outputRoot 'cli.properties'
@('main-jar=builder-0.6.2.jar','main-class=com.canace.x3offlinereader.builder.BuilderMain','arguments=','win-console=true') | Set-Content -LiteralPath $launcher -Encoding ASCII
if(-not $JpackagePath -and $env:JAVA_HOME){$JpackagePath=Join-Path $env:JAVA_HOME 'bin\jpackage.exe'}
if(-not $JpackagePath){$command=Get-Command jpackage.exe -ErrorAction SilentlyContinue;if($command){$JpackagePath=$command.Source}}
if(-not $JpackagePath -or -not(Test-Path -LiteralPath $JpackagePath -PathType Leaf)){throw 'JDK 17 jpackage.exe not found. Pass -JpackagePath, set JAVA_HOME, or add jpackage to PATH.'}
& $JpackagePath --type app-image --name X3AudiobookBuilder --app-version '0.6.2' --dest $outputRoot --input (Join-Path $PSScriptRoot 'build\install\builder\lib') --main-jar 'builder-0.6.2.jar' --main-class 'com.canace.x3offlinereader.builder.BuilderMain' --arguments gui --java-options '-Dfile.encoding=UTF-8' --icon $icon --add-launcher "X3BuilderCLI=$launcher"
if($LASTEXITCODE -ne 0){throw 'jpackage failed'}
$app=Join-Path $outputRoot 'X3AudiobookBuilder'
Copy-Item -LiteralPath $tauriExecutable -Destination (Join-Path $app 'X3AudiobookBuilder.exe') -Force
Copy-Tree (Join-Path $project 'specification') (Join-Path $app 'specification')
New-Item -ItemType Directory -Force -Path (Join-Path $app 'builder') | Out-Null
Copy-Tree (Join-Path $project 'builder\melo') (Join-Path $app 'builder\melo')
Copy-Tree (Join-Path $project 'builder\edge') (Join-Path $app 'builder\edge')
Copy-Tree (Join-Path $project 'builder\sherpa') (Join-Path $app 'builder\sherpa')
Copy-Tree (Join-Path $project 'builder\assets') (Join-Path $app 'builder\assets')
Copy-Tree (Join-Path $project 'builder\voice-previews') (Join-Path $app 'builder\voice-previews')
Copy-Item -LiteralPath (Join-Path $project 'builder\THIRD_PARTY_TTS.md') -Destination (Join-Path $app 'builder\THIRD_PARTY_TTS.md')
New-Item -ItemType Directory -Force -Path (Join-Path $app '.tools') | Out-Null
Copy-Tree (Join-Path $project '.tools\ffmpeg') (Join-Path $app '.tools\ffmpeg')
if(-not $WithoutMelo){Copy-Tree (Join-Path $project '.tools\melo') (Join-Path $app '.tools\melo')}
if(-not $WithoutAdditionalVoices){
    Copy-Tree (Join-Path $project '.tools\edge-tts') (Join-Path $app '.tools\edge-tts')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\runtime') (Join-Path $app '.tools\sherpa-onnx\runtime')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\models') (Join-Path $app '.tools\sherpa-onnx\models')
    Copy-Tree (Join-Path $project '.tools\sherpa-onnx\python') (Join-Path $app '.tools\sherpa-onnx\python')
}
Copy-Tree (Join-Path $project 'samples\builder-inputs') (Join-Path $app 'builder-inputs')
if($workBackup){
    Copy-Tree $workBackup (Join-Path $app 'builder\.work')
    Remove-Item -LiteralPath $workBackup -Recurse -Force
}
if(-not $WithoutMelo){
    & (Join-Path $app '.tools\melo\python.exe') (Join-Path $app 'builder\melo\worker.py') --self-test
    if($LASTEXITCODE -ne 0){throw 'Packaged Melo sanitizer self-test failed'}
}
Remove-Item -LiteralPath $launcher -Force
Write-Host "Windows app image ready: $app"
