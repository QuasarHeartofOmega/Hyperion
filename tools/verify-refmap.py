#!/usr/bin/env python3
"""
Проверяет, что каждая цель миксина из refmap действительно существует
в intermediary-jar Minecraft (класс + метод + дескриптор).

Это дешёвая статическая проверка вместо запуска игры: она ловит опечатки
в именах методов и неверные дескрипторы до того, как мод упадёт у пользователя.
"""
import json
import re
import struct
import sys
import zipfile


def read_class_methods(data: bytes):
    """Мини-парсер class-файла: возвращает {(name, desc)} из constant pool + method_info."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return set()
    pos = 8
    cp_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    utf8 = {}
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            utf8[i] = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (15,):
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        else:
            raise ValueError(f"unknown cp tag {tag}")
        i += 1
    pos += 6  # access_flags, this_class, super_class
    ifaces = struct.unpack_from(">H", data, pos)[0]
    pos += 2 + ifaces * 2
    for _ in range(2):  # fields, then methods (нужны только методы)
        count = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        entries = set()
        for _ in range(count):
            pos += 2
            name_i, desc_i = struct.unpack_from(">HH", data, pos)
            pos += 4
            entries.add((utf8.get(name_i, ""), utf8.get(desc_i, "")))
            attrs = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            for _ in range(attrs):
                pos += 2
                size = struct.unpack_from(">I", data, pos)[0]
                pos += 4 + size
        if _ == 1:
            return entries
    return set()


def main():
    refmap_path, *jars = sys.argv[1:]
    refmap = json.load(open(refmap_path, encoding="utf-8"))
    index = {}
    open_jars = []
    for jar in jars:
        z = zipfile.ZipFile(jar)
        open_jars.append(z)
        for name in z.namelist():
            if name.endswith(".class") and name.startswith("net/minecraft/"):
                index.setdefault(name[:-6], (z, name))

    pattern = re.compile(r"^L([^;]+);([^(]+)(\(.*)$")
    ok = bad = 0
    for mixin, entries in refmap.get("mappings", {}).items():
        for named, target in entries.items():
            m = pattern.match(target)
            if not m:
                print(f"  [?] не разобрано: {target}")
                bad += 1
                continue
            cls, method, desc = m.groups()
            if cls not in index:
                print(f"  [FAIL] {mixin}: класс {cls} не найден")
                bad += 1
                continue
            z, entry = index[cls]
            methods = read_class_methods(z.read(entry))
            if (method, desc) in methods:
                print(f"  [OK]   {mixin.split('.')[-1]} -> {cls}.{method}{desc}")
                ok += 1
            else:
                print(f"  [FAIL] {mixin}: {cls}.{method}{desc} отсутствует")
                bad += 1
    for z in open_jars:
        z.close()
    print(f"\nПроверка целей миксинов: {ok} ok, {bad} ошибок")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
