"""Long-lived offline sherpa-onnx TTS worker. Private text travels as base64 over stdin."""
import argparse
import base64
import os
import sys
import traceback
from pathlib import Path

sys.stdin.reconfigure(encoding="utf-8")
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

import sherpa_onnx  # noqa: E402
import soundfile as sf  # noqa: E402


def find(root, name):
    for path in root.rglob(name):
        if ".cache" not in path.parts:
            return path
    raise FileNotFoundError(name)


def onnx(root):
    candidates = [p for p in root.rglob("*.onnx") if ".cache" not in p.parts]
    preferred = [p for p in candidates if "int8" in p.name.lower()]
    if not candidates:
        raise FileNotFoundError("*.onnx")
    return (preferred or candidates)[0]


def create_tts(kind, root, rate):
    config = sherpa_onnx.OfflineTtsConfig()
    requested_threads = int(os.environ.get("SHERPA_TTS_THREADS", "0"))
    config.model.num_threads = requested_threads if requested_threads > 0 else max(1, min(8, (os.cpu_count() or 2) // 2))
    config.model.debug = False
    config.max_num_sentences = 1
    if kind == "kokoro":
        config.model.kokoro.model = str(onnx(root))
        config.model.kokoro.voices = str(find(root, "voices.bin"))
        config.model.kokoro.tokens = str(find(root, "tokens.txt"))
        config.model.kokoro.data_dir = str(find(root, "espeak-ng-data"))
        config.model.kokoro.lexicon = f"{find(root, 'lexicon-us-en.txt')},{find(root, 'lexicon-zh.txt')}"
        config.model.kokoro.length_scale = 1.0 / rate
        config.rule_fsts = ",".join(str(find(root, name)) for name in ("date-zh.fst", "phone-zh.fst", "number-zh.fst"))
    else:
        config.model.vits.model = str(find(root, "vits-aishell3.onnx"))
        config.model.vits.lexicon = str(find(root, "lexicon.txt"))
        config.model.vits.tokens = str(find(root, "tokens.txt"))
        config.model.vits.length_scale = 1.0 / rate
        config.rule_fsts = ",".join(str(find(root, name)) for name in ("phone.fst", "date.fst", "number.fst", "new_heteronym.fst"))
        config.rule_fars = str(find(root, "rule.far"))
    if not config.validate():
        raise RuntimeError("invalid sherpa-onnx configuration")
    return sherpa_onnx.OfflineTts(config)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=("kokoro", "aishell3"), required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--rate", type=float, default=1.0)
    args = parser.parse_args()
    if not 0.25 <= args.rate <= 5.0:
        raise ValueError("rate must be between 0.25 and 5.0")
    tts = create_tts(args.model, args.model_dir.resolve(), args.rate)
    print("READY", flush=True)
    for line in sys.stdin:
        line = line.rstrip("\r\n")
        if line == "QUIT":
            return
        try:
            output, sid, encoded = line.split("\t", 2)
            text = base64.b64decode(encoded).decode("utf-8")
            audio = tts.generate(text=text, sid=int(sid))
            if audio is None or len(audio.samples) == 0:
                raise RuntimeError("model returned no samples")
            sf.write(output, audio.samples, audio.sample_rate, subtype="PCM_16")
            print("OK", flush=True)
        except Exception as error:
            traceback.print_exc(file=sys.stderr)
            detail = str(error).replace("\n", " ").strip()
            print("ERROR " + type(error).__name__ + (": " + detail if detail else ""), flush=True)


if __name__ == "__main__":
    main()
