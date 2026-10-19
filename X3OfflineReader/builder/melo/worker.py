"""Local stdin/stdout bridge for MeloTTS. Protocol contains paths and base64 text only."""
import argparse
import base64
import contextlib
import os
import sys
import tempfile
import traceback
import unicodedata


ZH_PUNCTUATION = {
    "：": ",", "；": ",", "，": ",", "。": ".", "！": "!", "？": "?",
    "\n": ".", "·": ",", "、": ",", "$": ".", "“": "'", "”": "'",
    "‘": "'", "’": "'", "（": "'", "）": "'", "(": "'", ")": "'",
    "《": "'", "》": "'", "【": "'", "】": "'", "[": "'", "]": "'",
    "—": "-", "–": "-", "～": "-", "~": "-", "「": "'", "」": "'",
}
ZH_ALLOWED_ASCII_PUNCTUATION = set("!?…,.'-")


def normalize_zh_mixed(text):
    """Return Melo ZH_MIX_EN-safe text and a count; never include source text in diagnostics."""
    text = unicodedata.normalize("NFKC", text)
    output = []
    replaced = 0
    for char in text:
        mapped = ZH_PUNCTUATION.get(char)
        if mapped is not None:
            output.append(mapped)
        elif "\u4e00" <= char <= "\u9fa5" or char.isascii() and (char.isalnum() or char.isspace() or char in ZH_ALLOWED_ASCII_PUNCTUATION):
            output.append(char)
        else:
            output.append(",")
            replaced += 1
    normalized = "".join(output)
    while ",," in normalized:
        normalized = normalized.replace(",,", ",")
    return normalized, replaced


def replace_unpronounceable_chinese(text):
    from melo.text.chinese import _g2p as chinese_g2p
    output = []
    replaced = 0
    supported = {}
    for char in text:
        if "\u4e00" <= char <= "\u9fa5":
            if char not in supported:
                try:
                    chinese_g2p([char])
                    supported[char] = True
                except (AssertionError, IndexError, KeyError, ValueError):
                    supported[char] = False
            if not supported[char]:
                output.append(",")
                replaced += 1
                continue
        output.append(char)
    normalized = "".join(output)
    while ",," in normalized:
        normalized = normalized.replace(",,", ",")
    return normalized, replaced


def model_for(language):
    with contextlib.redirect_stdout(sys.stderr):
        from runtime_security import validate_runtime
        validate_runtime()
        # Preserve PyTorch's restricted checkpoint loader, including inherited environments.
        os.environ.pop("TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD", None)
        os.environ["TORCH_FORCE_WEIGHTS_ONLY_LOAD"] = "1"
        from melo.api import TTS
        return TTS(language=language, device="cpu")


def main():
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefetch", metavar="LANGUAGE")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        normalized, changed = normalize_zh_mixed("ＡＢＣ_中文🙂—测试")
        assert normalized == "ABC,中文,-测试"
        assert changed == 2
        print("Melo sanitizer OK")
        return
    if args.prefetch:
        model = model_for(args.prefetch)
        with tempfile.TemporaryDirectory(prefix="melo-public-prefetch-") as temporary:
            with contextlib.redirect_stdout(sys.stderr):
                model.tts_to_file(
                    "春风吹过山谷，清泉流向远方。",
                    next(iter(model.hps.data.spk2id.values())),
                    os.path.join(temporary, "public.wav"),
                    quiet=True,
                )
        print("Model ready")
        return
    models = {}
    print("READY", flush=True)
    for line in sys.stdin:
        line = line.rstrip("\r\n")
        if line == "QUIT":
            return
        try:
            output, voice, speed, encoded = line.split("\t", 3)
            language = voice.split("-", 1)[0].upper()
            if language != "ZH":
                raise ValueError("This Melo runtime supports only the packaged ZH model")
            if language not in models:
                models[language] = model_for(language)
            model = models[language]
            speakers = model.hps.data.spk2id
            if voice not in speakers:
                voice = next(iter(speakers))
            text = base64.b64decode(encoded).decode("utf-8")
            normalized_count = 0
            if language == "ZH":
                text, normalized_count = normalize_zh_mixed(text)
            try:
                with contextlib.redirect_stdout(sys.stderr):
                    model.tts_to_file(text, speakers[voice], output, speed=float(speed), quiet=True)
            except AssertionError:
                if language != "ZH":
                    raise
                text, retry_count = replace_unpronounceable_chinese(text)
                normalized_count += retry_count
                if os.path.exists(output):
                    os.remove(output)
                with contextlib.redirect_stdout(sys.stderr):
                    model.tts_to_file(text, speakers[voice], output, speed=float(speed), quiet=True)
            if normalized_count:
                print("Melo normalized unsupported characters: " + str(normalized_count), file=sys.stderr, flush=True)
            print("OK" + (" NORMALIZED " + str(normalized_count) if normalized_count else ""), flush=True)
        except Exception as error:
            traceback.print_exc(file=sys.stderr)
            sys.stderr.flush()
            detail = str(error).replace("\n", " ").strip()
            print("ERROR " + type(error).__name__ + (": " + detail if detail else ""), flush=True)


if __name__ == "__main__":
    main()
