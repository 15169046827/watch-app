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

from component_store import activate_import  # noqa: E402

activate_import()

import edge_tts  # noqa: E402
from aiohttp import ClientResponseError  # noqa: E402

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


def failure_details(failure):
    """Keep actionable HTTP status without logging per-request service URLs."""
    message = failure.message if isinstance(failure, ClientResponseError) else str(failure)
    details = {
        "errorType": type(failure).__name__,
        "error": " ".join(message.split())[:500] or "服务未返回诊断",
        "clientVersion": edge_tts.__version__,
    }
    if isinstance(failure, ClientResponseError):
        details["httpStatus"] = failure.status
    return details


def run():
    try:
        asyncio.run(main())
    except Exception as failure:
        json.dump(
            failure_details(failure),
            sys.stderr,
            ensure_ascii=False,
        )
        sys.stderr.write("\n")
        raise SystemExit(1)


if __name__ == "__main__":
    run()
