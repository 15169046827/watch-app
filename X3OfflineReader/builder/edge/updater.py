"""Update only the Edge client from PyPI; never run pip or change its dependencies."""
import argparse
import hashlib
import importlib.metadata
import json
import os
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
from contextlib import contextmanager
from email.parser import BytesParser
from pathlib import Path

from component_store import VERSION, selected

try:
    from packaging.requirements import Requirement
    from packaging.specifiers import SpecifierSet
except ImportError:
    from pip._vendor.packaging.requirements import Requirement
    from pip._vendor.packaging.specifiers import SpecifierSet

MINIMUM = (7, 3, 1)
DAY = 24 * 60 * 60
NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0


def version_tuple(value):
    if not isinstance(value, str) or not VERSION.fullmatch(value):
        raise ValueError("仅支持通过兼容性验证的 7.x 正式版；跨主版本需更新工坊")
    return tuple(map(int, value.split(".")))


def atomic_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    os.replace(temporary, path)


@contextmanager
def update_lock(root):
    """OS-owned lock is released even when the updater is terminated."""
    with (root / "update.lock").open("a+b") as handle:
        handle.seek(0)
        handle.write(b"0")
        handle.flush()
        handle.seek(0)
        if sys.platform == "win32":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            handle.seek(0)
            if sys.platform == "win32":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def fetch(url, host, limit):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != host or parsed.port not in (None, 443):
        raise ValueError("更新来源不符合安全要求")

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("更新源重定向已拒绝")

    context = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE"))
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))
    request = urllib.request.Request(url, headers={"User-Agent": "Tingdu-OnlineClient-Updater/1"})
    with opener.open(request, timeout=20) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("更新下载超过大小限制")
    return data


def metadata_compatible(data, version):
    metadata = BytesParser().parsebytes(data)
    if metadata["Name"] != "edge-tts" or metadata["Version"] != version:
        raise ValueError("组件名称或版本不一致")
    python_version = ".".join(map(str, sys.version_info[:3]))
    if python_version not in SpecifierSet(metadata.get("Requires-Python", "")):
        raise ValueError("新组件需要不同的 Python 运行时，请更新工坊")
    for line in metadata.get_all("Requires-Dist", []):
        requirement = Requirement(line)
        if requirement.marker and not requirement.marker.evaluate({"extra": ""}):
            continue
        if requirement.url:
            raise ValueError("新组件含外部依赖地址")
        try:
            installed = importlib.metadata.version(requirement.name)
        except importlib.metadata.PackageNotFoundError as failure:
            raise ValueError("新组件需要额外依赖，请更新工坊") from failure
        if installed not in requirement.specifier:
            raise ValueError("新组件依赖不兼容，请更新工坊")


def extract(wheel, directory, version):
    """Allow code and metadata only: no entrypoints, .pth, links or traversal."""
    with zipfile.ZipFile(wheel) as archive:
        members = archive.infolist()
        if len(members) > 300 or sum(item.file_size for item in members) > 8 * 1024 * 1024:
            raise ValueError("组件解压超过大小限制")
        seen = set()
        for item in members:
            name = item.filename
            parts = name.split("/")
            allowed = parts[0] in ("edge_tts", "edge_playback", "edge_tts-" + version + ".dist-info")
            mode = item.external_attr >> 16
            if (not allowed or "\\" in item.orig_filename or ":" in name
                    or "\x00" in item.orig_filename or any(unsafe_segment(part) for part in parts if part)
                    or name.lower() in seen or (mode & 0o170000) == 0o120000
                    or name.lower().endswith(".pth")):
                raise ValueError("组件压缩包包含不安全路径")
            seen.add(name.lower())
        metadata_path = "edge_tts-" + version + ".dist-info/METADATA"
        metadata_compatible(archive.read(metadata_path), version)
        if "edge_tts/__init__.py" not in seen:
            raise ValueError("组件缺少入口")
        for item in members:
            if not item.filename.startswith("edge_playback/"):
                archive.extract(item, directory)


def unsafe_segment(part):
    reserved = {"CON", "PRN", "AUX", "NUL"}
    reserved.update("COM" + str(index) for index in range(1, 10))
    reserved.update("LPT" + str(index) for index in range(1, 10))
    return (part in (".", "..") or part != part.rstrip(". ")
            or part.split(".")[0].upper() in reserved)


def probe(app, root, version, scratch):
    audio = scratch / "probe.mp3"
    env = dict(os.environ, X3_EDGE_COMPONENT_ROOT=str(root), X3_EDGE_CLIENT=version)
    request = {"action": "synthesize", "voice": "zh-CN-XiaoxiaoNeural", "rate": 1,
               "text": "你好，这是听读工坊的在线语音连接测试。", "output": str(audio)}
    result = subprocess.run([sys.executable, str(app / "builder/edge/worker.py")],
                            input=json.dumps(request), text=True, encoding="utf-8", env=env,
                            capture_output=True, timeout=40, creationflags=NO_WINDOW)
    if result.returncode or not audio.is_file() or audio.stat().st_size < 256:
        raise ValueError("公开短句合成验证失败，原组件保持不变")
    ffmpeg = next((app / ".tools/ffmpeg").rglob("ffmpeg.exe"), None)
    if not ffmpeg:
        raise ValueError("未找到音频验证工具，原组件保持不变")
    decoded = subprocess.run([str(ffmpeg), "-v", "error", "-i", str(audio), "-f", "null", "-"],
                             capture_output=True, timeout=15, creationflags=NO_WINDOW)
    if decoded.returncode:
        raise ValueError("测试音频无法解码，原组件保持不变")


def current_version(root):
    version = selected(root)
    return importlib.metadata.version("edge-tts") if version == "bundled" else version


def status(root):
    result = {"state": "ready", "version": current_version(root), "message": "尚未检查更新"}
    try:
        result.update(json.loads((root / "status.json").read_text(encoding="utf-8")))
    except (OSError, ValueError, TypeError):
        pass
    result["version"] = current_version(root)
    return result


def candidate_release(metadata):
    version = metadata["info"]["version"]
    if version_tuple(version) < MINIMUM:
        raise ValueError("更新源版本低于兼容基线")
    for item in metadata["urls"]:
        if (not item.get("yanked") and item.get("packagetype") == "bdist_wheel"
                and item["filename"] == "edge_tts-" + version + "-py3-none-any.whl"):
            return version, item
    raise ValueError("没有兼容的正式版组件")


def install(app, root, metadata):
    version, item = candidate_release(metadata)
    if version_tuple(version) < version_tuple(current_version(root)):
        raise ValueError("更新源版本较旧，已拒绝降级")
    if selected(root) == version:
        return {"state": "current", "message": "在线语音组件已是最新兼容版本"}
    with tempfile.TemporaryDirectory(prefix="download-", dir=root) as temporary:
        scratch = Path(temporary)
        data = fetch(item["url"], "files.pythonhosted.org", 2 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != item["digests"]["sha256"]:
            raise ValueError("组件 SHA-256 校验失败")
        wheel = scratch / "client.whl"
        wheel.write_bytes(data)
        candidate = scratch / "client"
        extract(wheel, candidate, version)
        target = root / version
        # Existing versions are immutable: never overwrite code a running build has pinned.
        if not target.exists():
            os.replace(candidate, target)
        else:
            for source in candidate.rglob("*"):
                if source.is_file():
                    existing = target / source.relative_to(candidate)
                    if not existing.is_file() or existing.read_bytes() != source.read_bytes():
                        raise ValueError("本地组件与官方校验结果不一致；保留当前版本")
        probe(app, root, version, scratch)
        previous = selected(root)
        atomic_json(root / "active.json", {"version": version, "previous": previous})
    return {"state": "updated", "message": "在线语音组件已更新并通过音频验证；新任务将使用新版本"}


def check(app, root, force):
    root.mkdir(parents=True, exist_ok=True)
    try:
        with update_lock(root):
            previous = status(root)
            if not force and time.time() - previous.get("lastChecked", 0) < DAY:
                return previous
            try:
                metadata = json.loads(fetch("https://pypi.org/pypi/edge-tts/json", "pypi.org", 1024 * 1024))
                result = install(app, root, metadata)
            except Exception as failure:
                # Do not leak service URLs, user paths or book contents into the UI.
                message = str(failure) if isinstance(failure, ValueError) else "网络或组件验证失败"
                result = {"state": "failed", "message": "更新未启用，原组件保持不变：" + message[:160]}
            result.update(version=current_version(root), lastChecked=int(time.time()))
            atomic_json(root / "status.json", result)
            return result
    except OSError:
        return dict(status(root), state="busy", message="另一更新检查正在运行，请稍后再试")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("status", "auto", "force"))
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--store", type=Path, required=True)
    args = parser.parse_args()
    result = status(args.store) if args.action == "status" else check(args.app, args.store, args.action == "force")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
