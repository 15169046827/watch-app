"""Synthesize public fixtures with the real worker and all socket connects blocked."""

import argparse
import array
import base64
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import wave


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--python", required=True, type=Path)
    args = parser.parse_args()
    python = args.python.resolve()
    worker = Path(__file__).with_name("worker.py")
    env = os.environ.copy()
    env.update(
        {
            "HF_HOME": str(python.parent / "huggingface"),
            "NLTK_DATA": str(python.parent / "nltk_data"),
            "HF_HUB_OFFLINE": "1",
            "TRANSFORMERS_OFFLINE": "1",
            "HF_DATASETS_OFFLINE": "1",
            "HF_HUB_DISABLE_TELEMETRY": "1",
            "PYTHONUTF8": "1",
            "PYTHONIOENCODING": "utf-8",
            "OMP_NUM_THREADS": "8",
            "MKL_NUM_THREADS": "8",
        }
    )
    bootstrap = (
        "import runpy,socket,sys; "
        "deny=lambda *a,**k: (_ for _ in ()).throw(RuntimeError('Network forbidden in acceptance')); "
        "socket.socket.connect=deny; socket.socket.connect_ex=deny; "
        "sys.path.insert(0,sys.argv[1]); worker=sys.argv[2]; probe=sys.argv[3]; sys.argv=[worker]; "
        "runpy.run_path(worker,run_name='__main__'); "
        "from melo.utils import load_wav_to_torch_new; import torch; "
        "samples,rate=load_wav_to_torch_new(probe); "
        "assert rate==22050 and torch.allclose(samples,torch.tensor([0.25,-0.25])); "
        "print('AUDIO_HELPER_OK')"
    )
    texts = [
        "春风吹过山谷，清泉流向远方。",
        "今天是2026年，Hello world。",
        "“听读测试！”他说。ＡＢＣ_中文🙂—测试。",
    ]
    with tempfile.TemporaryDirectory(prefix="x3-melo-security-") as temporary:
        probe = Path(temporary) / "stereo-probe.wav"
        with wave.open(str(probe), "wb") as audio:
            audio.setparams((2, 2, 22050, 0, "NONE", "not compressed"))
            audio.writeframes(array.array("h", [4096, 12288, -4096, -12288]).tobytes())
        outputs = [Path(temporary) / (str(i) + ".wav") for i in range(len(texts))]
        requests = [
            str(path) + "\tZH\t1.0\t" + base64.b64encode(text.encode()).decode()
            for path, text in zip(outputs, texts)
        ]
        result = subprocess.run(
            [
                str(python),
                "-B",
                "-u",
                "-c",
                bootstrap,
                str(worker.parent),
                str(worker),
                str(probe),
            ],
            input="\n".join(requests + ["QUIT", ""]),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=env,
            timeout=300,
            check=False,
        )
        replies = result.stdout.splitlines()
        if result.returncode or replies != [
            "READY",
            "OK",
            "OK",
            "OK NORMALIZED 2",
            "AUDIO_HELPER_OK",
        ]:
            raise RuntimeError(
                "Worker acceptance failed: "
                + result.stdout
                + "\n"
                + result.stderr[-5000:]
            )
        evidence = []
        for output in outputs:
            with wave.open(str(output), "rb") as audio:
                if audio.getsampwidth() != 2 or audio.getnchannels() != 1:
                    raise ValueError("Expected mono PCM16 audio")
                duration = audio.getnframes() / audio.getframerate()
                samples = array.array("h", audio.readframes(audio.getnframes()))
                rms = math.sqrt(
                    sum(sample * sample for sample in samples) / len(samples)
                )
                if not 0.5 < duration < 60 or rms < 10:
                    raise ValueError("Empty, silent or implausible synthesized audio")
                evidence.append(
                    {
                        "seconds": round(duration, 3),
                        "rate": audio.getframerate(),
                        "rms": round(rms, 1),
                    }
                )
        print(json.dumps({"network": "blocked", "publicSamples": evidence}))


if __name__ == "__main__":
    main()
