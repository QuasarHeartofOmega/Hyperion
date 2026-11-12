#!/usr/bin/env python3
"""Скачивает JDK 21 (Temurin) в /tmp — нужен только для проверки сборки в песочнице."""
import json
import pathlib
import subprocess
import sys
import urllib.request

DEST = pathlib.Path("/tmp/hyperion-toolchain")
DEST.mkdir(parents=True, exist_ok=True)


def get(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "hyperion-build"})
    return urllib.request.urlopen(req, timeout=timeout)


def find_asset():
    try:
        data = json.load(get("https://api.github.com/repos/adoptium/temurin21-binaries/releases/latest"))
        for a in data.get("assets", []):
            n = a["name"]
            if n.startswith("OpenJDK21U-jdk_x64_linux_hotspot_") and n.endswith(".tar.gz"):
                return a["browser_download_url"], n
    except Exception as e:
        print("api.github.com недоступен:", e, flush=True)
    tag = "jdk-21.0.5+11"
    name = "OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz"
    return (f"https://github.com/adoptium/temurin21-binaries/releases/download/"
            f"{tag.replace('+', '%2B')}/{name}"), name


url, name = find_asset()
tar = DEST / name
if not tar.exists():
    print("скачиваю", url, flush=True)
    with get(url, timeout=300) as r, open(tar, "wb") as f:
        total = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            total += len(chunk)
    print("скачано", total // (1 << 20), "МБ", flush=True)

print("распаковываю", flush=True)
subprocess.run(["tar", "xzf", str(tar), "-C", str(DEST)], check=True)
jdk = next(p for p in DEST.iterdir() if p.is_dir() and p.name.startswith("jdk-21"))
print("JAVA_HOME=" + str(jdk), flush=True)
subprocess.run([str(jdk / "bin" / "java"), "-version"], check=True)
