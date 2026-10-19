[CmdletBinding()]
param(
    [ValidateSet('ZH')][string]$Language = 'ZH',
    [string]$EnvironmentPath
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tools = Join-Path $projectRoot '.tools'
$mambaRoot = Join-Path $tools 'micromamba-root'
$mamba = Join-Path $tools 'micromamba.exe'
$environment = if ($EnvironmentPath) { [IO.Path]::GetFullPath($EnvironmentPath) } else { Join-Path $tools 'melo' }
if (-not $environment.StartsWith($tools + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The Melo environment must be an isolated child of the project .tools directory.'
}
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
    & $mamba create --yes --root-prefix $mambaRoot --prefix $environment 'python=3.11' pip
    if ($LASTEXITCODE -ne 0 -and -not (Test-Path -LiteralPath (Join-Path $environment 'python.exe'))) { throw 'Python environment creation failed' }
}
$python = Join-Path $environment 'python.exe'
& $python -B -c "import sys; sys.exit(0 if sys.version_info[:2] == (3, 11) else 1)"
if ($LASTEXITCODE -ne 0) {
    throw 'Melo now requires Python 3.11. Keep the old environment; use -EnvironmentPath .tools\melo-next to validate a separate replacement first.'
}
$running = @(Get-Process -Name X3AudiobookBuilder,X3BuilderCLI,tingdu-workshop,python -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -ne 'python' -or ($_.Path -and $_.Path.StartsWith($environment + '\', [StringComparison]::OrdinalIgnoreCase)) })
if ($running.Count -gt 0) { throw 'Close the Builder and processes using this environment before setup; no process was stopped.' }
& $python -m pip --version
if ($LASTEXITCODE -ne 0) { & $python -m ensurepip --upgrade }
& $python -m pip install --no-compile --upgrade pip 'setuptools==83.0.0' wheel
if ($LASTEXITCODE -ne 0) { throw 'Python packaging tool upgrade failed' }
$revision = '209145371cff8fc3bd60d7be902ea69cbdb7965a'
$source = Join-Path $tools ('MeloTTS-' + $revision)
$sourceArchive = Join-Path $tools ('MeloTTS-' + $revision + '.zip')
if (-not (Test-Path -LiteralPath $sourceArchive -PathType Leaf)) {
    Invoke-WebRequest ('https://codeload.github.com/myshell-ai/MeloTTS/zip/' + $revision) -OutFile $sourceArchive
}
if ((Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256).Hash -ne '3EDF1B2AB60E777691AB2EC5FBA837E278F60761C296BC5D753DA5AB8AB5754E') {
    throw 'MeloTTS source archive SHA-256 mismatch'
}
if (Test-Path -LiteralPath $source) {
    $sourceItem = Get-Item -LiteralPath $source -Force
    if (-not $sourceItem.PSIsContainer -or
        $sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Pinned Melo source path must be a regular generated directory.'
    }
    # Expand-Archive overlays existing trees. Remove the generated source copy first so
    # untracked/stale files cannot survive beside the SHA-256-verified archive contents.
    Remove-Item -LiteralPath $source -Recurse -Force
}
Expand-Archive -LiteralPath $sourceArchive -DestinationPath $tools -Force
# torchaudio is unused by inference; its released wheels pin older torch versions.
# Retain the mono loading helper using soundfile instead of an ABI-incompatible wheel.
foreach ($relative in @('melo\api.py', 'melo\split_utils.py', 'melo\utils.py')) {
    $file = Join-Path $source $relative
    $code = [IO.File]::ReadAllText($file)
    if ($code -notmatch '(?m)^import torchaudio\r?$') { throw 'Pinned Melo source import changed' }
    $replacement = if ($relative -eq 'melo\utils.py') { 'import soundfile' } else { '' }
    $code = $code -replace '(?m)^import torchaudio\r?$', $replacement
    if ($relative -eq 'melo\utils.py') {
        $old = 'audio_norm, sampling_rate = torchaudio.load(full_path, frame_offset=0, num_frames=-1, normalize=True, channels_first=True)'
        if (-not $code.Contains($old)) { throw 'Pinned Melo audio helper changed' }
        $code = $code.Replace($old, "audio_array, sampling_rate = soundfile.read(full_path, dtype='float32', always_2d=True)`n    audio_norm = torch.from_numpy(audio_array.T)")
    }
    [IO.File]::WriteAllText($file, $code, [Text.UTF8Encoding]::new($false))
}
# Adapt inference dependency metadata so pip check verifies the real runtime.
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'requirements-runtime.txt') -Destination (Join-Path $source 'requirements.txt') -Force
& $python -m pip install --no-compile --no-deps $source
if ($LASTEXITCODE -ne 0) { throw 'MeloTTS package installation failed' }
& $python -m pip install --no-compile -r (Join-Path $PSScriptRoot 'requirements-runtime.txt')
if ($LASTEXITCODE -ne 0) { throw 'MeloTTS runtime dependency installation failed' }
& $python -m pip check
if ($LASTEXITCODE -ne 0) { throw 'MeloTTS runtime dependency consistency check failed' }
& $python -B (Join-Path $PSScriptRoot 'runtime_security.py')
if ($LASTEXITCODE -ne 0) { throw 'MeloTTS runtime security check failed' }
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
