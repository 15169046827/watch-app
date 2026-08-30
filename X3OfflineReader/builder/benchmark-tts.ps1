param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('melo', 'sherpa-kokoro', 'sherpa-aishell3', 'windows', 'edge')]
    [string]$Provider,
    [ValidateRange(1, 12)]
    [int]$Workers = 1,
    [ValidateRange(1, 20)]
    [int]$Cycles = 2
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$cli = Join-Path $projectRoot 'builder\build\install\builder\bin\builder.bat'
if (-not (Test-Path -LiteralPath $cli)) {
    throw '请先运行 .\gradlew.bat :builder:installDist'
}

$publicSentences = @(
    '清晨的风穿过树梢，阳光落在安静的小路上。',
    '我们用公开文字测量语音生成速度，不读取任何私人书籍。',
    '稳定的断点缓存会保留已经完成的句子，避免重复计算。',
    '章节音频按原顺序编码，后台并发不会改变正文与时间轴。',
    '不同语音模型拥有不同音质、内存需求和最佳并行数量。',
    '完成基准后，工坊会把实测转换效率显示在模型选择处。',
    '今天适合整理书架，也适合听一段温和而清晰的朗读。',
    '数字一二三四五和常用标点，也需要保持自然的中文发音。',
    '离线模型不发送正文到网络，在线模型则需要用户明确同意。',
    '最终结果同时包含正文、音频、时间轴、封面与完整校验信息。',
    '每次测试都会使用新的公开编号，确保不会误用旧的句子缓存。',
    '这是一段可重复分发的性能语料，不含版权作品或个人信息。'
)

$benchmarkRoot = Join-Path $projectRoot 'builder\.work\benchmarks'
New-Item -ItemType Directory -Force -Path $benchmarkRoot | Out-Null
$runId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$input = Join-Path $benchmarkRoot "$Provider-$Workers-$runId.txt"
$output = Join-Path $benchmarkRoot "$Provider-$Workers-$runId.opbook"
$lines = New-Object System.Collections.Generic.List[string]
for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    for ($index = 0; $index -lt $publicSentences.Count; $index++) {
        $lines.Add("基准$runId，第$cycle轮第$($index + 1)句。$($publicSentences[$index])")
    }
}
$text = $lines -join [Environment]::NewLine
[IO.File]::WriteAllText($input, $text, [Text.UTF8Encoding]::new($false))
$characters = ($lines | ForEach-Object { $_.Length } | Measure-Object -Sum).Sum

$voice = switch ($Provider) {
    'melo' { 'ZH' }
    'sherpa-kokoro' { '中文女声 zf_001 · sid=3' }
    'sherpa-aishell3' { '说话人 000 · sid=0' }
    'windows' { 'Microsoft Huihui Desktop' }
    'edge' { 'zh-CN-XiaoxiaoNeural' }
}
$workerOption = switch ($Provider) {
    'melo' { '--melo-workers' }
    { $_ -like 'sherpa-*' } { '--sherpa-workers' }
    'windows' { '--windows-workers' }
    'edge' { '--edge-workers' }
}
$arguments = @(
    'build', '--input', $input, '--output', $output,
    '--title', '公开语音性能基准', '--author', 'X3OfflineReader',
    '--tts', $Provider, '--voice', $voice, '--pause-ms', '0',
    $workerOption, $Workers.ToString()
)
if ($Provider -eq 'edge') { $arguments += '--allow-online-tts' }

$watch = [Diagnostics.Stopwatch]::StartNew()
try {
    $log = & $cli @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($log -join [Environment]::NewLine) }
}
finally {
    $watch.Stop()
    Remove-Item -LiteralPath $input -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
}
$seconds = [Math]::Max(0.001, $watch.Elapsed.TotalSeconds)
[pscustomobject]@{
    provider = $Provider
    workers = $Workers
    cycles = $Cycles
    characters = $characters
    elapsedSeconds = [Math]::Round($seconds, 3)
    charactersPerHour = [Math]::Round($characters * 3600 / $seconds)
} | ConvertTo-Json -Compress
