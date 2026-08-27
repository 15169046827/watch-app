[CmdletBinding()]
param([string]$Language = 'ZH')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tools = Join-Path $projectRoot '.tools'
$mambaRoot = Join-Path $tools 'micromamba-root'
$mamba = Join-Path $tools 'micromamba.exe'
$environment = Join-Path $tools 'melo'
$env:HF_HOME = Join-Path $environment 'huggingface'
$env:NLTK_DATA = Join-Path $environment 'nltk_data'
$env:HF_HUB_DISABLE_SYMLINKS_WARNING = '1'
New-Item -ItemType Directory -Force -Path $tools | Out-Null
if (-not (Test-Path -LiteralPath $mamba -PathType Leaf)) {
    $archive = Join-Path $tools 'micromamba.tar.bz2'
    Invoke-WebRequest 'https://micro.mamba.pm/api/micromamba/win-64/latest' -OutFile $archive
    tar -xf $archive -C $tools 'Library/bin/micromamba.exe'
    Move-Item -LiteralPath (Join-Path $tools 'Library\bin\micromamba.exe') -Destination $mamba
}
if (-not (Test-Path -LiteralPath (Join-Path $environment 'python.exe'))) {
    & $mamba create --yes --root-prefix $mambaRoot --prefix $environment 'python=3.9' pip
    if ($LASTEXITCODE -ne 0 -and -not (Test-Path -LiteralPath (Join-Path $environment 'python.exe'))) { throw 'Python environment creation failed' }
}
$python = Join-Path $environment 'python.exe'
& $python -m pip --version
if ($LASTEXITCODE -ne 0) { & $python -m ensurepip --upgrade }
& $python -m pip install --upgrade pip 'setuptools==80.9.0' wheel
if ($LASTEXITCODE -ne 0) { throw 'Python packaging tool upgrade failed' }
$source = Join-Path $tools 'MeloTTS-main'
if (-not (Test-Path -LiteralPath (Join-Path $source 'setup.py'))) {
    $sourceArchive = Join-Path $tools 'MeloTTS-main.zip'
    Invoke-WebRequest 'https://codeload.github.com/myshell-ai/MeloTTS/zip/refs/heads/main' -OutFile $sourceArchive
    Expand-Archive -LiteralPath $sourceArchive -DestinationPath $tools -Force
}
& $python -m pip install --no-deps $source
if ($LASTEXITCODE -ne 0) { throw 'MeloTTS package installation failed' }
& $python -m pip install -r (Join-Path $PSScriptRoot 'requirements-runtime.txt')
if ($LASTEXITCODE -ne 0) { throw 'MeloTTS runtime dependency installation failed' }
& $python -m pip uninstall --yes unidic
$nltkRoot = Join-Path $environment 'nltk_data'
foreach ($asset in @(
    @{ Kind='corpora'; Name='cmudict' },
    @{ Kind='taggers'; Name='averaged_perceptron_tagger' },
    @{ Kind='taggers'; Name='averaged_perceptron_tagger_eng' }
)) {
    $destination = Join-Path (Join-Path $nltkRoot $asset.Kind) $asset.Name
    $zip = Join-Path $tools ($asset.Name + '.zip')
    $nltkZip = Join-Path (Join-Path $nltkRoot $asset.Kind) ($asset.Name + '.zip')
    if (-not (Test-Path -LiteralPath $destination -PathType Container) -or -not (Test-Path -LiteralPath $nltkZip -PathType Leaf)) {
        Invoke-WebRequest ("https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/{0}/{1}.zip" -f $asset.Kind,$asset.Name) -OutFile $zip
        New-Item -ItemType Directory -Force -Path (Join-Path $nltkRoot $asset.Kind) | Out-Null
        Copy-Item -LiteralPath $zip -Destination $nltkZip -Force
        Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $nltkRoot $asset.Kind) -Force
    }
}
& $python (Join-Path $PSScriptRoot 'worker.py') --prefetch $Language
if ($LASTEXITCODE -ne 0) { throw 'MeloTTS model prefetch failed' }
Write-Host "MeloTTS is ready at $environment"
