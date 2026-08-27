[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('sherpa-kokoro','sherpa-aishell3')][string]$Provider,
    [ValidateRange(1,8)][int]$Workers=4,
    [ValidateRange(1,8)][int]$ThreadsPerWorker=4,
    [string]$JavaPath
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradle=Join-Path $project 'gradlew.bat'
& $gradle -p $project ':builder:installDist' --offline
if($LASTEXITCODE -ne 0){throw 'Builder compilation failed'}
if(-not $JavaPath -and $env:JAVA_HOME){$JavaPath=Join-Path $env:JAVA_HOME 'bin\java.exe'}
if(-not $JavaPath){$command=Get-Command java.exe -ErrorAction SilentlyContinue;if($command){$JavaPath=$command.Source}}
if(-not $JavaPath -or -not(Test-Path -LiteralPath $JavaPath -PathType Leaf)){throw 'JDK 17 java.exe not found. Pass -JavaPath, set JAVA_HOME, or add Java to PATH.'}
$classpath=Join-Path $PSScriptRoot 'build\install\builder\lib\*'
$logRoot=Join-Path $PSScriptRoot ('build\preview-shards-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$previousThreads=$env:SHERPA_TTS_THREADS
$env:SHERPA_TTS_THREADS=$ThreadsPerWorker
try{
    $processes=@()
    for($index=0;$index -lt $Workers;$index++){
        $stdout=Join-Path $logRoot ("worker-$index.out.log")
        $stderr=Join-Path $logRoot ("worker-$index.err.log")
        $arguments=@('-Dfile.encoding=UTF-8','-cp',$classpath,'com.canace.x3offlinereader.builder.VoicePreviewGenerator','--provider',$Provider,'--shard-count',$Workers,'--shard-index',$index)
        $processes+=Start-Process -FilePath $JavaPath -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    }
    do{
        Start-Sleep -Seconds 15
        $remaining=@($processes|Where-Object{-not $_.HasExited}).Count
        $ready=@(Get-ChildItem (Join-Path $PSScriptRoot "voice-previews\$Provider") -File -Filter '*.wav' -ErrorAction SilentlyContinue).Count
        Write-Host "$Provider previews ready: $ready; workers remaining: $remaining"
    }while($remaining -gt 0)
    $failed=@($processes|Where-Object{$_.ExitCode -ne 0})
    if($failed.Count){Get-Content (Join-Path $logRoot '*.err.log') -ErrorAction SilentlyContinue;throw "$($failed.Count) preview worker(s) failed; logs: $logRoot"}
}finally{$env:SHERPA_TTS_THREADS=$previousThreads}
Write-Host "$Provider parallel preview generation completed. Logs: $logRoot"
