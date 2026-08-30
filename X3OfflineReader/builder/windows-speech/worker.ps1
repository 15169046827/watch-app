$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -AssemblyName System.Speech
$synthesizer = New-Object System.Speech.Synthesis.SpeechSynthesizer
$currentVoice = ''

try {
    [Console]::Out.WriteLine('READY')
    [Console]::Out.Flush()
    while (($line = [Console]::In.ReadLine()) -ne $null) {
        if ($line -eq 'QUIT') { break }
        try {
            $parts = $line.Split([char]9)
            if ($parts.Length -ne 4) { throw 'invalid worker request' }
            $output = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($parts[0]))
            $voice = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($parts[1]))
            $rate = [Math]::Max(-10, [Math]::Min(10, [int]$parts[2]))
            $text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($parts[3]))
            if ($currentVoice -ne $voice) {
                $match = $synthesizer.GetInstalledVoices() |
                    ForEach-Object { $_.VoiceInfo } |
                    Where-Object { $_.Name -eq $voice -or $_.Description -eq $voice } |
                    Select-Object -First 1
                if ($null -eq $match) { throw 'Windows voice not found' }
                $synthesizer.SelectVoice($match.Name)
                $currentVoice = $voice
            }
            $synthesizer.Rate = $rate
            $synthesizer.SetOutputToWaveFile($output)
            $synthesizer.Speak($text)
            $synthesizer.SetOutputToNull()
            [Console]::Out.WriteLine('OK')
        }
        catch {
            try { $synthesizer.SetOutputToNull() } catch {}
            $message = ('{0}: {1}' -f $_.Exception.GetType().Name, $_.Exception.Message) -replace '[\r\n]+', ' '
            if ($message.Length -gt 400) { $message = $message.Substring(0, 400) }
            [Console]::Out.WriteLine('ERROR ' + $message)
        }
        [Console]::Out.Flush()
    }
}
finally {
    $synthesizer.Dispose()
}
