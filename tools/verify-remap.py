#!/usr/bin/env python3
"""
Проверяет, что в готовом (intermediary) jar не осталось «named»-ссылок на Minecraft.

Зачем: tiny-remapper ремапит имена классов и без classpath, а вот членов
(методов/полей) — нет: без classpath он не может разрешить владельца члена
по иерархии наследования и молча оставляет имена как есть. Результат — jar,
 который прекрасно собирается, но падает в рантайме:
    NoSuchFieldError: Class net.minecraft.class_2248 does not have member
    field 'net.minecraft.class_2361 STATE_IDS'

Проверка авторитетная: каждый Fieldref/Methodref/InterfaceMethodref из constant
pool мода, чей владелец начинается с net/minecraft/, ищется в intermediary-jar
Minecraft с подъёмом по суперклассам и интерфейсам (член может быть унаследован).

    python3 tools/verify-remap.py <mod.jar> <client-intermediary.jar> [server-intermediary.jar]
"""
import struct
import sys
import zipfile

REF_TAGS = (9, 10, 11)  # Fieldref, Methodref, InterfaceMethodref


def parse_cp(data):
    """Возвращает (cp, this_class, super_class, interfaces, members)."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    pos = 8
    count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    cp = {}
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp[i] = (tag, data[pos:pos + ln].decode("utf-8", "replace"))
            pos += ln
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag == 15:
            cp[i] = (tag, None)
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[i] = (tag, struct.unpack_from(">HH", data, pos))
            pos += 4
        elif tag in (3, 4):
            cp[i] = (tag, None)
            pos += 4
        elif tag in (5, 6):
            cp[i] = (tag, None)
            pos += 8
            i += 1
        else:
            raise ValueError(f"неизвестный тег constant pool: {tag}")
        i += 1

    def utf8(idx):
        e = cp.get(idx)
        return e[1] if e and e[0] == 1 else ""

    def cls_name(idx):
        e = cp.get(idx)
        return utf8(e[1]) if e and e[0] == 7 else ""

    access, this_i, super_i = struct.unpack_from(">HHH", data, pos)
    pos += 6
    n_if = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    ifaces = [cls_name(struct.unpack_from(">H", data, pos + 2 * k)[0]) for k in range(n_if)]
    pos += 2 * n_if

    members = set()
    for _section in range(2):  # fields, methods
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _m in range(n):
            pos += 2
            name_i, desc_i = struct.unpack_from(">HH", data, pos)
            pos += 4
            members.add((utf8(name_i), utf8(desc_i)))
            n_attr = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            for _a in range(n_attr):
                pos += 2
                size = struct.unpack_from(">I", data, pos)[0]
                pos += 4 + size

    refs = set()
    for tag, payload in cp.values():
        if tag in REF_TAGS:
            cls_i, nt_i = payload
            owner = cls_name(cls_i)
            nt = cp.get(nt_i)
            if owner and nt and nt[0] == 12:
                refs.add((owner, utf8(nt[1][0]), utf8(nt[1][1])))
    return refs, cls_name(this_i), cls_name(super_i), ifaces, members


class McIndex:
    def __init__(self, jars):
        self.where = {}
        self.zips = []
        self.cache = {}
        for path in jars:
            z = zipfile.ZipFile(path)
            self.zips.append(z)
            for n in z.namelist():
                if n.endswith(".class") and n.startswith("net/minecraft/"):
                    self.where.setdefault(n[:-6], (z, n))

    def info(self, cls):
        if cls in self.cache:
            return self.cache[cls]
        loc = self.where.get(cls)
        res = None
        if loc:
            z, n = loc
            parsed = parse_cp(z.read(n))
            if parsed:
                _refs, _this, sup, ifaces, members = parsed
                res = (sup, ifaces, members)
        self.cache[cls] = res
        return res

    def has(self, cls, name, desc, seen=None):
        """True/False — найден ли член; None — класс вообще не найден."""
        seen = seen or set()
        if cls in seen:
            return False
        seen.add(cls)
        info = self.info(cls)
        if info is None:
            return None
        sup, ifaces, members = info
        if (name, desc) in members:
            return True
        for parent in [sup] + ifaces:
            if parent and parent.startswith("net/minecraft/"):
                if self.has(parent, name, desc, seen):
                    return True
        return False

    def close(self):
        for z in self.zips:
            z.close()


def main():
    if len(sys.argv) < 3:
        raise SystemExit("Использование: verify-remap.py <mod.jar> <mc-intermediary.jar> [...]")
    mod_jar, *mc_jars = sys.argv[1:]
    index = McIndex(mc_jars)
    bad = []
    checked = 0
    with zipfile.ZipFile(mod_jar) as z:
        for n in z.namelist():
            if not n.endswith(".class"):
                continue
            parsed = parse_cp(z.read(n))
            if not parsed:
                continue
            refs = parsed[0]
            for owner, name, desc in sorted(refs):
                if not owner.startswith("net/minecraft/") or owner.startswith("["):
                    continue
                checked += 1
                found = index.has(owner, name, desc)
                if found is None:
                    bad.append((n, owner, name, desc, "класс не найден"))
                elif not found:
                    bad.append((n, owner, name, desc, "член не найден (не смаплен?)"))
    index.close()

    for n, owner, name, desc, why in bad[:40]:
        print(f"  [FAIL] {n}: {owner}.{name}{desc} — {why}")
    if len(bad) > 40:
        print(f"  ... и ещё {len(bad) - 40}")
    print(f"\nПроверка ремапа: {checked} ссылок на Minecraft, {len(bad)} ошибок")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
