#!/usr/bin/env python3
"""
Готовит автономный toolchain для сборки Hyperion без Gradle.

Скачивает Minecraft, official->intermediary->named (Yarn) маппинги, библиотеки
игры, Fabric Loader, Mixin Annotation Processor и tiny-remapper, после чего
ремапит client.jar и server.jar в Yarn-имена (ими и компилируем мод).

    python3 tools/prepare-toolchain.py --dest /tmp/hyperion-toolchain

Требуется JDK 21 в JAVA_HOME (или --java-home). Память: remap запускается
отдельным процессом с -Xmx700m, поэтому хватает ~1 ГБ RAM.
"""
import argparse
import concurrent.futures
import json
import os
import pathlib
import re
import subprocess
import urllib.request
import zipfile

MC = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC = "https://maven.fabricmc.net"
CENTRAL = "https://repo1.maven.org/maven2"


def fetch(url, dest):
    dest = pathlib.Path(dest)
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(url, dest)
    return dest


def jget(url):
    return json.load(urllib.request.urlopen(url, timeout=60))


def latest_version(group_artifact):
    url = f"{FABRIC}/{group_artifact}/maven-metadata.xml"
    data = urllib.request.urlopen(url, timeout=60).read().decode()
    return re.findall(r"<version>([^<]+)</version>", data)[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", default="/tmp/hyperion-toolchain")
    ap.add_argument("--mc-version", default="1.21.4")
    ap.add_argument("--yarn", default="1.21.4+build.8")
    ap.add_argument("--loader", default="0.20.1")
    ap.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    args = ap.parse_args()

    if not args.java_home:
        raise SystemExit("Нужен JDK 21: передайте --java-home или выставьте JAVA_HOME")
    java = pathlib.Path(args.java_home) / "bin" / "java"

    root = pathlib.Path(args.dest)
    mc_dir, lib_dir = root / "mc", root / "libs"
    mc_dir.mkdir(parents=True, exist_ok=True)
    lib_dir.mkdir(parents=True, exist_ok=True)

    print("==> метаданные версии")
    manifest = jget(MC)
    entry = next(v for v in manifest["versions"] if v["id"] == args.mc_version)
    meta = jget(entry["url"])

    print("==> Minecraft + маппинги + Fabric")
    yarn_url = f"{FABRIC}/net/fabricmc/yarn/{args.yarn.replace('+', '%2B')}/yarn-{args.yarn.replace('+', '%2B')}-v2.jar"
    downloads = {
        mc_dir / "client.jar": meta["downloads"]["client"]["url"],
        mc_dir / "server-bundler.jar": meta["downloads"]["server"]["url"],
        mc_dir / "yarn-v2.jar": yarn_url,
        mc_dir / "intermediary-v2.jar": f"{FABRIC}/net/fabricmc/intermediary/{args.mc_version}/intermediary-{args.mc_version}-v2.jar",
        mc_dir / "fabric-loader.jar": f"{FABRIC}/net/fabricmc/fabric-loader/{args.loader}/fabric-loader-{args.loader}.jar",
        mc_dir / "tiny-remapper.jar": f"{FABRIC}/net/fabricmc/tiny-remapper/0.10.4/tiny-remapper-0.10.4-fat.jar",
    }
    with concurrent.futures.ThreadPoolExecutor(4) as ex:
        list(ex.map(lambda kv: fetch(kv[1], kv[0]), downloads.items()))

    with zipfile.ZipFile(mc_dir / "yarn-v2.jar") as z:
        (mc_dir / "mappings.tiny").write_bytes(z.read("mappings/mappings.tiny"))
    with zipfile.ZipFile(mc_dir / "intermediary-v2.jar") as z:
        (mc_dir / "intermediary.tiny").write_bytes(z.read("mappings/mappings.tiny"))
    with zipfile.ZipFile(mc_dir / "server-bundler.jar") as z:
        name = next(n for n in z.namelist() if re.search(r"versions/.*server-.*\.jar$", n))
        (mc_dir / "server.jar").write_bytes(z.read(name))
        for n in z.namelist():
            if n.startswith("META-INF/libraries/") and n.endswith(".jar"):
                target = lib_dir / pathlib.Path(n).name
                if not target.exists():
                    target.write_bytes(z.read(n))
    with zipfile.ZipFile(mc_dir / "fabric-loader.jar") as z:
        for n in z.namelist():
            if n.endswith(".jar"):
                (lib_dir / pathlib.Path(n).name).write_bytes(z.read(n))

    print("==> библиотеки игры")
    tasks = {}
    for lib in meta["libraries"]:
        if "natives" in lib["name"]:
            continue
        art = lib.get("downloads", {}).get("artifact")
        if art:
            tasks[lib_dir / pathlib.Path(art["path"]).name] = art["url"]
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        list(ex.map(lambda kv: fetch(kv[1], kv[0]), tasks.items()))

    print("==> Mixin Annotation Processor и ASM")
    mixin_ver = latest_version("net/fabricmc/sponge-mixin")
    ext_ver = latest_version("net/fabricmc/fabric-mixin-compile-extensions")
    mappingio_ver = latest_version("net/fabricmc/mapping-io")
    ap_tasks = {
        lib_dir / f"sponge-mixin-{mixin_ver}.jar":
            f"{FABRIC}/net/fabricmc/sponge-mixin/{mixin_ver.replace('+', '%2B')}/sponge-mixin-{mixin_ver.replace('+', '%2B')}.jar",
        lib_dir / f"fabric-mixin-compile-extensions-{ext_ver}.jar":
            f"{FABRIC}/net/fabricmc/fabric-mixin-compile-extensions/{ext_ver}/fabric-mixin-compile-extensions-{ext_ver}.jar",
        lib_dir / f"mapping-io-{mappingio_ver}.jar":
            f"{FABRIC}/net/fabricmc/mapping-io/{mappingio_ver}/mapping-io-{mappingio_ver}.jar",
    }
    for module in ("asm", "asm-tree", "asm-analysis", "asm-commons", "asm-util"):
        ap_tasks[lib_dir / f"{module}-9.8.jar"] = f"{CENTRAL}/org/ow2/asm/{module}/9.8/{module}-9.8.jar"
    with concurrent.futures.ThreadPoolExecutor(6) as ex:
        list(ex.map(lambda kv: fetch(kv[1], kv[0]), ap_tasks.items()))

    print("==> remap Minecraft в Yarn-имена (4 прохода, по одному процессу)")
    for side in ("client", "server"):
        for src, dst, mappings, frm, to in (
            (f"{side}.jar", f"{side}-intermediary.jar", "intermediary.tiny", "official", "intermediary"),
            (f"{side}-intermediary.jar", f"{side}-named.jar", "mappings.tiny", "intermediary", "named"),
        ):
            out = mc_dir / dst
            if out.exists() and out.stat().st_size > 1000:
                print(f"    {dst}: уже готов")
                continue
            cmd = [str(java), "-Xmx700m", "-XX:+UseSerialGC", "-jar", str(mc_dir / "tiny-remapper.jar"),
                   str(mc_dir / src), str(out), str(mc_dir / mappings), frm, to]
            subprocess.run(cmd, check=True, capture_output=True)
            print(f"    {dst}: {out.stat().st_size / 1e6:.1f} МБ")

    print()
    print("Toolchain готов.")
    print(f"  JAVA_HOME={args.java_home} MC_DIR={mc_dir} LIB_DIR={lib_dir} bash tools/build-offline.sh")


if __name__ == "__main__":
    main()
