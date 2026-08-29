[CmdletBinding()]
param([switch]$SkipKokoro,[switch]$SkipAishell3,[switch]$SkipOnlineClient)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolRoot=Join-Path $project '.tools\sherpa-onnx'
$downloads=Join-Path $toolRoot 'downloads'
$models=Join-Path $toolRoot 'models'
New-Item -ItemType Directory -Force -Path $downloads,$models | Out-Null

function Download([string]$Url,[string]$File){
    if(Test-Path -LiteralPath $File -PathType Leaf){
        & tar.exe -tjf $File 2>$null | Out-Null
        if($LASTEXITCODE -eq 0){Write-Host "Using verified archive: $File";return}
        Write-Host "Resuming incomplete archive: $File"
    }
    & curl.exe -L --fail --retry 3 --continue-at - --output $File $Url
    if($LASTEXITCODE -ne 0){throw "Download failed: $Url"}
    & tar.exe -tjf $File 2>$null | Out-Null
    if($LASTEXITCODE -ne 0){throw "Downloaded archive is invalid: $File"}
}
function Expand-Bzip([string]$Archive,[string]$Destination,[string]$Marker){
    if(Test-Path -LiteralPath (Join-Path $Destination $Marker) -PathType Container){Write-Host "Already installed: $Marker";return}
    & tar.exe -xjf $Archive -C $Destination
    if($LASTEXITCODE -ne 0){throw "Archive extraction failed: $Archive"}
}

if(-not $SkipKokoro){
    if(-not(Test-Path -LiteralPath (Join-Path $models 'kokoro-int8-multi-lang-v1_1') -PathType Container)){
        $archive=Join-Path $downloads 'kokoro-int8-multi-lang-v1_1.tar.bz2'
        Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2' $archive
        Expand-Bzip $archive $models 'kokoro-int8-multi-lang-v1_1'
    }
}
if(-not $SkipAishell3){
    if(-not(Test-Path -LiteralPath (Join-Path $models 'vits-icefall-zh-aishell3') -PathType Container)){
        $archive=Join-Path $downloads 'vits-icefall-zh-aishell3.tar.bz2'
        Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2' $archive
        Expand-Bzip $archive $models 'vits-icefall-zh-aishell3'
    }
}
$python=Join-Path $project '.tools\melo\python.exe'
if(-not(Test-Path -LiteralPath $python)){throw 'Portable Python is missing; install Melo first'}
$sherpaSite=Join-Path $toolRoot 'python\site-packages'
if(-not(Test-Path -LiteralPath (Join-Path $sherpaSite 'sherpa_onnx') -PathType Container)){
    New-Item -ItemType Directory -Force -Path $sherpaSite | Out-Null
    & $python -m pip install --upgrade --target $sherpaSite 'sherpa-onnx==1.13.4' 'soundfile==0.13.1'
    if($LASTEXITCODE -ne 0){throw 'sherpa-onnx Python runtime installation failed'}
}
if(-not $SkipOnlineClient){
    $sitePackages=Join-Path $project '.tools\edge-tts\site-packages'
    New-Item -ItemType Directory -Force -Path $sitePackages | Out-Null
    $siteBoundary=[IO.Path]::GetFullPath($sitePackages)+[IO.Path]::DirectorySeparatorChar
    Get-ChildItem -LiteralPath $sitePackages -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'edge_tts' -or $_.Name -like 'edge_tts-*.dist-info' } |
        ForEach-Object {
            if(-not $_.FullName.StartsWith($siteBoundary,[StringComparison]::OrdinalIgnoreCase)){throw 'Edge client cleanup escaped its tool directory'}
            Remove-Item -LiteralPath $_.FullName -Recurse -Force
        }
    & $python -m pip install --upgrade --target $sitePackages 'edge-tts==7.2.8'
    if($LASTEXITCODE -ne 0){throw 'edge-tts installation failed'}
}
Write-Host 'Additional TTS voices are installed. Online Edge voices still require a network connection during preview and conversion.'
