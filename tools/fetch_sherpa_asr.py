#!/usr/bin/env python3
"""下载 ASR + KWS + TTS 模型，并可一键推送到已连接设备。

引擎 AAR 已改为远程依赖（com.github.k2-fsa.sherpa-onnx:sherpa-onnx:2.0.1），
本脚本只处理模型文件（ASR 约 190MB + KWS 约 5MB + TTS 约 163MB），不入库、不进 APK。

用法：
    python tools/fetch_sherpa_asr.py              # 下载 + 按 manifest 校验（三类模型）
    python tools/fetch_sherpa_asr.py --push       # 再 push 到已连接设备
    python tools/fetch_sherpa_asr.py --package com.blue.sherpa.demo
    python tools/fetch_sherpa_asr.py --verify-only
    python tools/fetch_sherpa_asr.py --tts-only   # 只要 TTS 模型
    python tools/fetch_sherpa_asr.py --no-kws     # 跳过 KWS
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# ---- ASR 主模型（HuggingFace 镜像，逐文件下载）----
ASR_DIR = ROOT / "asr_models" / "models" / "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
ASR_MANIFEST = ROOT / "asr_models" / "manifest.json"
HF_BASE = (
    "https://hf-mirror.com/csukuangfj/"
    "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/"
)
ASR_FILES = (
    "tokens.txt",
    "encoder-epoch-99-avg-1.int8.onnx",
    "decoder-epoch-99-avg-1.onnx",
    "joiner-epoch-99-avg-1.int8.onnx",
)

# ---- KWS 唤醒词模型（GitHub Release，经 ghproxy 镜像下载压缩包）----
KWS_ROOT = ROOT / "kws_models"
KWS_MODEL_NAME = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"
KWS_DIR = KWS_ROOT / KWS_MODEL_NAME
KWS_MANIFEST = KWS_ROOT / "manifest.json"
KWS_ARCHIVE_NAME = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2"
KWS_ARCHIVE = KWS_ROOT / "downloads" / "kws-wenetspeech-mobile.tar.bz2"
KWS_URLS = (
    f"https://ghproxy.net/https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/{KWS_ARCHIVE_NAME}",
    f"https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/{KWS_ARCHIVE_NAME}",
)
KWS_ARCHIVE_SIZE = 15292881
KWS_ARCHIVE_SHA = "b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b"
KWS_FILES = (
    "tokens.txt",
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "keywords.txt",
)

# ---- TTS 模型（GitHub Release，经 ghproxy 镜像下载压缩包）----
TTS_ROOT = ROOT / "tts_models"
TTS_MODEL_NAME = "vits-melo-tts-zh_en"
TTS_DIR = TTS_ROOT / "models" / TTS_MODEL_NAME
TTS_MANIFEST = TTS_ROOT / "manifest.json"
TTS_ARCHIVE_NAME = "vits-melo-tts-zh_en.tar.bz2"
TTS_ARCHIVE = TTS_ROOT / "downloads" / TTS_ARCHIVE_NAME
TTS_URLS = (
    f"https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{TTS_ARCHIVE_NAME}",
    f"https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{TTS_ARCHIVE_NAME}",
)
TTS_ARCHIVE_SIZE = 167006755
TTS_ARCHIVE_SHA = "e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514"
TTS_FILES = (
    "model.onnx",
    "lexicon.txt",
    "tokens.txt",
    "date.fst",
    "number.fst",
    "phone.fst",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def extract_tar_bz2(archive: Path, target: Path) -> None:
    """用 Python 解压，避免 Windows/Git Bash 下 D: 盘符被 tar 当成远程主机。"""
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tf:
        try:
            tf.extractall(target, filter="data")
        except TypeError:  # Python < 3.12 没有 filter 参数
            tf.extractall(target)


def curl_download(url: str, target: Path, max_time: int = 300) -> None:
    if shutil.which("curl"):
        subprocess.run(
            ["curl", "-fsSL", "--http1.1", "-C", "-", "--max-time", str(max_time),
             "--speed-limit", "20480", "--speed-time", "25", url, "-o", str(target)],
            check=True,
        )
    else:  # pragma: no cover
        mode = "ab" if target.exists() else "wb"
        with urllib.request.urlopen(url, timeout=120) as response, target.open(mode) as out:
            shutil.copyfileobj(response, out)


def download_file(url: str, target: Path, expected_size: int, expected_sha: str) -> None:
    """curl 断点续传 + 大小/sha256 校验。"""
    for _ in range(40):
        size = target.stat().st_size if target.exists() else 0
        if size == expected_size and sha256(target) == expected_sha:
            return
        if size > expected_size:
            target.unlink()
            size = 0
        print(f"[download] {url} (已有 {size / 1048576:.1f} MB)")
        try:
            curl_download(url, target)
        except (subprocess.CalledProcessError, OSError) as exc:
            print(f"[retry] {exc}")
    raise RuntimeError(f"下载不完整或校验失败: {target}")


def fetch_group(model_dir: Path, manifest_path: Path, files: tuple[str, ...],
                url_of, verify_only: bool) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    model_dir.mkdir(parents=True, exist_ok=True)
    for name in files:
        meta = manifest["files"][name]
        target = model_dir / name
        if verify_only:
            if (not target.is_file() or target.stat().st_size != meta["size"]
                    or sha256(target) != meta["sha256"]):
                raise RuntimeError(f"校验失败: {target}")
        else:
            download_file(url_of(name), target, meta["size"], meta["sha256"])
        print(f"[verify] {name} OK ({meta['size'] / 1048576:.1f} MB)")


def fetch_asr(verify_only: bool) -> None:
    fetch_group(ASR_DIR, ASR_MANIFEST, ASR_FILES, lambda n: HF_BASE + n, verify_only)


def fetch_kws(verify_only: bool) -> None:
    if verify_only:
        fetch_group(KWS_DIR, KWS_MANIFEST, KWS_FILES, lambda n: "", True)
        return
    # 先确保压缩包完整（优先镜像，失败回退 GitHub）
    KWS_ARCHIVE.parent.mkdir(parents=True, exist_ok=True)
    if not (KWS_ARCHIVE.is_file() and KWS_ARCHIVE.stat().st_size == KWS_ARCHIVE_SIZE
            and sha256(KWS_ARCHIVE) == KWS_ARCHIVE_SHA):
        last = None
        for url in KWS_URLS:
            try:
                for _ in range(30):
                    size = KWS_ARCHIVE.stat().st_size if KWS_ARCHIVE.exists() else 0
                    if size == KWS_ARCHIVE_SIZE and sha256(KWS_ARCHIVE) == KWS_ARCHIVE_SHA:
                        break
                    if size > KWS_ARCHIVE_SIZE:
                        KWS_ARCHIVE.unlink()
                        break
                    print(f"[download] {url} (已有 {size / 1048576:.1f} MB)")
                    curl_download(url, KWS_ARCHIVE)
                if KWS_ARCHIVE.stat().st_size == KWS_ARCHIVE_SIZE and sha256(KWS_ARCHIVE) == KWS_ARCHIVE_SHA:
                    break
            except (subprocess.CalledProcessError, OSError) as exc:
                last = exc
                print(f"[retry] 镜像失败: {exc}")
        else:
            raise RuntimeError(f"KWS 压缩包下载失败: {last}")
    # 解压到 kws_models/，再按 manifest 逐文件校验
    extract_tar_bz2(KWS_ARCHIVE, KWS_ROOT)
    fetch_group(KWS_DIR, KWS_MANIFEST, KWS_FILES, lambda n: "", True)


def fetch_tts(verify_only: bool) -> None:
    if verify_only:
        fetch_group(TTS_DIR, TTS_MANIFEST, TTS_FILES, lambda n: "", True)
        return
    TTS_ARCHIVE.parent.mkdir(parents=True, exist_ok=True)
    if not (TTS_ARCHIVE.is_file() and TTS_ARCHIVE.stat().st_size == TTS_ARCHIVE_SIZE
            and sha256(TTS_ARCHIVE) == TTS_ARCHIVE_SHA):
        last = None
        for url in TTS_URLS:
            try:
                for _ in range(40):
                    size = TTS_ARCHIVE.stat().st_size if TTS_ARCHIVE.exists() else 0
                    if size == TTS_ARCHIVE_SIZE and sha256(TTS_ARCHIVE) == TTS_ARCHIVE_SHA:
                        break
                    if size > TTS_ARCHIVE_SIZE:
                        TTS_ARCHIVE.unlink()
                        break
                    print(f"[download] {url} (已有 {size / 1048576:.1f} MB)")
                    curl_download(url, TTS_ARCHIVE, max_time=600)
                if TTS_ARCHIVE.stat().st_size == TTS_ARCHIVE_SIZE and sha256(TTS_ARCHIVE) == TTS_ARCHIVE_SHA:
                    break
            except (subprocess.CalledProcessError, OSError) as exc:
                last = exc
                print(f"[retry] 镜像失败: {exc}")
        else:
            raise RuntimeError(f"TTS 压缩包下载失败: {last}")
    # 压缩包内是 vits-melo-tts-zh_en/，解压到 tts_models/models/ 下。
    extract_tar_bz2(TTS_ARCHIVE, TTS_DIR.parent)
    fetch_group(TTS_DIR, TTS_MANIFEST, TTS_FILES, lambda n: "", True)


def adb(*args: str) -> str:
    result = subprocess.run(["adb", *args], capture_output=True, text=True, check=True)
    return result.stdout.strip()


def push_group(model_dir: Path, files: tuple[str, ...], remote_name: str,
               packages: list[str] | None) -> None:
    devices = [
        line.split("\t")[0]
        for line in adb("devices").splitlines()[1:]
        if "\tdevice" in line
    ]
    if not devices:
        print(f"[adb] 没有已连接的设备；模型已下载到 {model_dir}")
        return
    for device in devices:
        for pkg in packages or ["com.blue.sherpa.demo"]:
            target = f"/sdcard/Android/data/{pkg}/files/sherpa/{remote_name}"
            print(f"[adb] {device}: {model_dir.name} -> {target}")
            adb("-s", device, "shell", "mkdir", "-p", target)
            for name in files:
                adb("-s", device, "push", str(model_dir / name), f"{target}/{name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--push", action="store_true", help="推送模型到已连接设备")
    parser.add_argument("--package", action="append", help="目标包名，可重复；默认 com.blue.sherpa.demo")
    parser.add_argument("--verify-only", action="store_true", help="只校验现有文件")
    parser.add_argument("--no-asr", action="store_true", help="跳过 ASR 主模型")
    parser.add_argument("--no-kws", action="store_true", help="跳过 KWS 唤醒词模型")
    parser.add_argument("--no-tts", action="store_true", help="跳过 TTS 模型")
    parser.add_argument("--tts-only", action="store_true", help="只处理 TTS 模型")
    args = parser.parse_args()
    if args.push and args.verify_only:
        raise RuntimeError("--verify-only 与 --push 不能同时使用")
    if args.tts_only:
        args.no_asr = True
        args.no_kws = True

    if not args.no_asr:
        fetch_asr(args.verify_only)
    if not args.no_kws:
        fetch_kws(args.verify_only)
    if not args.no_tts:
        fetch_tts(args.verify_only)

    if args.push:
        if not args.no_asr:
            push_group(ASR_DIR, ASR_FILES, ASR_DIR.name, args.package)
        if not args.no_kws:
            push_group(KWS_DIR, KWS_FILES, KWS_DIR.name, args.package)
        if not args.no_tts:
            push_group(TTS_DIR, TTS_FILES, TTS_DIR.name, args.package)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (subprocess.CalledProcessError, RuntimeError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
