"""Private stdin/stdout bridge for the optional Microsoft Edge online TTS service."""
import asyncio
import json
import os
import ssl
import sys

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

_original_context = ssl.create_default_context


def _portable_context(*args, **kwargs):
    if not kwargs.get("cafile") and os.environ.get("SSL_CERT_FILE"):
        kwargs["cafile"] = os.environ["SSL_CERT_FILE"]
    return _original_context(*args, **kwargs)


ssl.create_default_context = _portable_context

import edge_tts  # noqa: E402

sys.stdin.reconfigure(encoding="utf-8")
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


async def main():
    request = json.load(sys.stdin)
    if request["action"] == "voices":
        voices = await edge_tts.list_voices()
        chinese = sorted(v["ShortName"] for v in voices if v.get("Locale", "").lower().startswith("zh-"))
        json.dump({"voices": chinese}, sys.stdout, ensure_ascii=False)
        return
    rate = max(0.5, min(2.0, float(request.get("rate", 1.0))))
    percentage = round((rate - 1.0) * 100)
    communicate = edge_tts.Communicate(request["text"], request["voice"], rate=f"{percentage:+d}%")
    await communicate.save(request["output"])
    json.dump({"ok": True}, sys.stdout)


def run():
    try:
        asyncio.run(main())
    except Exception as failure:
        message = " ".join(str(failure).split())[:500]
        json.dump(
            {"errorType": type(failure).__name__, "error": message or "服务未返回诊断"},
            sys.stderr,
            ensure_ascii=False,
        )
        sys.stderr.write("\n")
        raise SystemExit(1)


if __name__ == "__main__":
    run()
