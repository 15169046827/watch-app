[CmdletBinding()]
param([string]$Provider,[switch]$Overwrite,[string]$JavaPath)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradle=Join-Path $project 'gradlew.bat'
& $gradle -p $project ':builder:installDist' --offline
if($LASTEXITCODE -ne 0){throw 'Builder compilation failed'}
if(-not $JavaPath -and $env:JAVA_HOME){$JavaPath=Join-Path $env:JAVA_HOME 'bin\java.exe'}
if(-not $JavaPath){$command=Get-Command java.exe -ErrorAction SilentlyContinue;if($command){$JavaPath=$command.Source}}
if(-not $JavaPath -or -not(Test-Path -LiteralPath $JavaPath -PathType Leaf)){throw 'JDK 17 java.exe not found. Pass -JavaPath, set JAVA_HOME, or add Java to PATH.'}
$arguments=@('-Dfile.encoding=UTF-8','-cp',(Join-Path $PSScriptRoot 'build\install\builder\lib\*'),'com.canace.x3offlinereader.builder.VoicePreviewGenerator')
if(-not[string]::IsNullOrWhiteSpace($Provider)){$arguments+=@('--provider',$Provider)}
if($Overwrite){$arguments+='--overwrite'}
& $JavaPath $arguments
if($LASTEXITCODE -ne 0){throw 'Voice preview generation failed'}
