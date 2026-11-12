#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Автономная сборка Hyperion без Gradle.
#
# Зачем: Fabric Loom требует ~2 ГБ RAM (Gradle-демон + remap Minecraft).
# В контейнере с 1 ГБ демон убивает OOM-killer. Этот скрипт делает ровно то же,
# что Loom, но по шагам и с контролируемой памятью:
#   1. javac против Minecraft, отмапленного в Yarn (named) + Mixin Annotation
#      Processor, который генерирует refmap named -> intermediary;
#   2. автономные тесты чистых компонентов;
#   3. упаковка jar с ресурсами;
#   4. tiny-remapper: named -> intermediary (то, что грузит Fabric в рантайме);
#   5. проверка, что все цели миксинов реально существуют в intermediary-jar.
#
# Требования: JDK 21, python3, подготовленный toolchain (tools/prepare-toolchain.py).
#
#   JAVA_HOME=/path/to/jdk21 \
#   MC_DIR=/tmp/hyperion-toolchain/mc LIB_DIR=/tmp/hyperion-toolchain/libs \
#   bash tools/build-offline.sh
# ---------------------------------------------------------------------------
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

: "${JAVA_HOME:?Укажите JAVA_HOME на JDK 21}"
MC_DIR="${MC_DIR:-/tmp/hyperion-toolchain/mc}"
LIB_DIR="${LIB_DIR:-/tmp/hyperion-toolchain/libs}"
VERSION="${VERSION:-1.0.0}"
MC_VERSION="${MC_VERSION:-1.21.4}"

JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
JAR="$JAVA_HOME/bin/jar"

BUILD="$PROJECT_DIR/build"
CLASSES="$BUILD/classes"
GENERATED="$BUILD/generated"
LIBS_OUT="$BUILD/libs"
mkdir -p "$CLASSES" "$GENERATED" "$LIBS_OUT"

CP="$MC_DIR/client-named.jar:$MC_DIR/server-named.jar:$MC_DIR/fabric-loader.jar"
for j in "$LIB_DIR"/*.jar; do CP="$CP:$j"; done

AP=""
for j in "$LIB_DIR"/sponge-mixin-*.jar "$LIB_DIR"/fabric-mixin-compile-extensions-*.jar \
         "$LIB_DIR"/mapping-io-*.jar "$LIB_DIR"/asm-*.jar "$LIB_DIR"/gson-*.jar "$LIB_DIR"/guava-*.jar; do
  AP="$AP:$j"
done
AP="${AP#:}"

echo "==> [1/5] javac (+ Mixin AP: refmap named -> intermediary)"
find src/main/java -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -J-Xmx512m --release 21 -encoding UTF-8 -nowarn \
  -cp "$CP" \
  -processorpath "$AP" \
  -AoutRefMapFile="$GENERATED/hyperion-refmap.json" \
  -AdefaultObfuscationEnv=named:intermediary \
  -AinMapFileNamedIntermediary="$MC_DIR/mappings.tiny" \
  -d "$CLASSES" @"$BUILD/sources.txt"

echo "==> [2/5] автономные тесты"
"$JAVA" -Xmx256m -Dstdout.encoding=UTF-8 -cp "$CLASSES:$CP" dev.hyperion.selftest.SelfTest

echo "==> [3/5] упаковка (named / dev jar)"
RES="$BUILD/resources"
rm -rf "$RES"; mkdir -p "$RES"
cp -r src/main/resources/* "$RES/"
cp "$GENERATED/hyperion-refmap.json" "$RES/"
python3 - "$RES/fabric.mod.json" "$VERSION" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
p.write_text(p.read_text(encoding="utf-8").replace("${version}", sys.argv[2]), encoding="utf-8")
PY
DEV_JAR="$BUILD/hyperion-$VERSION-dev-named.jar"
"$JAR" --create --file "$DEV_JAR" -C "$CLASSES" . -C "$RES" .

echo "==> [4/5] remap named -> intermediary"
OUT_JAR="$LIBS_OUT/hyperion-$VERSION+mc$MC_VERSION.jar"
"$JAVA" -Xmx256m -jar "$MC_DIR/tiny-remapper.jar" \
  "$DEV_JAR" "$OUT_JAR" "$MC_DIR/mappings.tiny" named intermediary > /dev/null

echo "==> [5/5] проверка целей миксинов"
python3 tools/verify-refmap.py "$GENERATED/hyperion-refmap.json" \
  "$MC_DIR/client-intermediary.jar" "$MC_DIR/server-intermediary.jar"

echo
echo "Готово: $OUT_JAR"
ls -la "$OUT_JAR"
