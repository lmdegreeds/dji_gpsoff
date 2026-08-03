#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Попытка восстановить АЛГОРИТМ хеша имени параметра для 0x03/0xF8-0xF9.

Опорные пары (байты на проводе, из профилей FreeFCC / SkylabFCCfree):
  forearm_led_ctrl -> a2 59 ce ed   (очень надёжно: одинаков в led_on и led_off)
  max_height_0     -> 8a 23 71 03   (из fcc.json, note автора)
  <GPS param>      -> 82 95 42 c5   (чтение 0xF8, имя неизвестно — как якорь не годится)

Две стратегии:
  1) СЛОВАРНЫЙ перебор — прогнать ~30 известных хеш-функций × варианты строки по
     ВСЕМ именам параметров из ../../.. /params/ и искать совпадение с целями.
  2) АЛГЕБРАИЧЕСКИЙ взлом Horner-семейства (h=h*M+c / h=(h+c)*M): значение аффинно
     по init, поэтому две пары дают систему — перебираем множитель M, init решаем.

Результат прогона (эта сессия): совпадений НЕТ ни у одной из двух стратегий.
Вывод: хеш нелинейный/кастомный (djb2-xor / fnv1a / murmur / битмиксер) с
неизвестным seed — алгебраически по двум точкам не достаётся, в словаре стандартных
нет. Следующий шаг — дизассемблировать applet write_param_by_hash в
libdrone_hacks_lib.so (строки-якоря найдены, см. FINDINGS.md).

Когда алгоритм будет найден — впиши его в hash_name() и запусти `python recover_hash.py verify`.
"""
import glob
import json
import os
import sys
import zlib

M32 = 0xFFFFFFFF

# корень репозитория djiparam (…/MINIAPP/experiments/param-hash -> вверх на 3)
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
PARAMS = os.path.join(ROOT, "params")

ANCHORS = {  # name -> on-wire 4 bytes
    "forearm_led_ctrl": "a259ceed",
    "max_height_0": "8a237103",
}


# ---------------------------------------------------------------- ИЩЕМЫЙ алгоритм
def hash_name(name: str) -> int:
    """ЗАГЛУШКА. Впиши сюда восстановленный из .so алгоритм и верни u32.
    Пока не восстановлен — возвращает None, verify() это учтёт."""
    return None


def verify():
    ok = True
    for name, hx in ANCHORS.items():
        want_le = int.from_bytes(bytes.fromhex(hx), "little")
        got = hash_name(name)
        if got is None:
            print(f"  {name}: hash_name() не реализован (заглушка)")
            ok = False
            continue
        mark = "OK" if (got & M32) == want_le else "MISMATCH"
        print(f"  {name}: got={got & M32:08x} want(LE)={want_le:08x}  {mark}")
        ok = ok and (got & M32) == want_le
    print("VERIFY:", "PASS" if ok else "FAIL")
    return ok


# ---------------------------------------------------------------- имена
def collect_names():
    names = set()
    for p in glob.glob(os.path.join(PARAMS, "**", "*.dhp"), recursive=True):
        try:
            for it in json.load(open(p, encoding="utf-8", errors="replace")):
                if it.get("name"):
                    names.add(it["name"])
        except Exception:
            pass
    for p in glob.glob(os.path.join(PARAMS, "**", "*.dhv2params"), recursive=True):
        try:
            for it in json.load(open(p, encoding="utf-8", errors="replace")).get("params", []):
                if it.get("name"):
                    names.add(it["name"])
        except Exception:
            pass
    for p in glob.glob(os.path.join(PARAMS, "**", "paramtable_*.txt"), recursive=True):
        for line in open(p, encoding="utf-8", errors="replace"):
            if line.startswith("#"):
                continue
            f = line.rstrip("\n").split("\t")
            if len(f) >= 6 and f[5]:
                names.add(f[5])
    out = set()
    for n in names:
        out.add(n)
        if "|" in n:
            for part in n.split("|"):
                if part:
                    out.add(part)
    return out


# ---------------------------------------------------------------- хеши-кандидаты
def h_djb2(b, init=5381):
    h = init
    for c in b:
        h = (h * 33 + c) & M32
    return h


def h_djb2x(b, init=5381):
    h = init
    for c in b:
        h = ((h * 33) ^ c) & M32
    return h


def h_sdbm(b):
    h = 0
    for c in b:
        h = (c + (h << 6) + (h << 16) - h) & M32
    return h


def h_java(b):
    h = 0
    for c in b:
        h = (h * 31 + c) & M32
    return h


def h_fnv1(b):
    h = 0x811C9DC5
    for c in b:
        h = (h * 0x01000193) & M32
        h ^= c
    return h


def h_fnv1a(b):
    h = 0x811C9DC5
    for c in b:
        h ^= c
        h = (h * 0x01000193) & M32
    return h


def h_oat(b):  # Jenkins one-at-a-time
    h = 0
    for c in b:
        h = (h + c) & M32
        h = (h + (h << 10)) & M32
        h ^= h >> 6
    h = (h + (h << 3)) & M32
    h ^= h >> 11
    h = (h + (h << 15)) & M32
    return h


def h_elf(b):
    h = 0
    for c in b:
        h = ((h << 4) + c) & M32
        g = h & 0xF0000000
        if g:
            h ^= g >> 24
        h &= ~g & M32
    return h


def h_crc32(b):
    return zlib.crc32(b) & M32


def h_crc32_noxor(b):
    return zlib.crc32(b) ^ M32


def _crc32_generic(b, poly, init, xorout, refin, refout):
    rev8 = lambda x: int("{:08b}".format(x)[::-1], 2)
    rev32 = lambda x: int("{:032b}".format(x)[::-1], 2)
    crc = init
    for c in b:
        if refin:
            c = rev8(c)
        crc = (crc ^ (c << 24)) & M32
        for _ in range(8):
            crc = ((crc << 1) ^ poly) & M32 if crc & 0x80000000 else (crc << 1) & M32
    if refout:
        crc = rev32(crc)
    return crc ^ xorout


def h_crc32_bzip2(b):
    return _crc32_generic(b, 0x04C11DB7, M32, M32, False, False)


def h_crc32_mpeg2(b):
    return _crc32_generic(b, 0x04C11DB7, M32, 0, False, False)


def h_crc32_jam(b):
    return _crc32_generic(b, 0x04C11DB7, M32, 0, True, True)


def h_crc32c(b):
    return _crc32_generic(b, 0x1EDC6F41, M32, M32, True, True)


def h_murmur3(b, seed=0):
    c1, c2 = 0xCC9E2D51, 0x1B873593
    h = seed
    n = len(b) // 4 * 4
    for i in range(0, n, 4):
        k = int.from_bytes(b[i:i + 4], "little")
        k = (k * c1) & M32
        k = (((k << 15) | (k >> 17)) & M32) * c2 & M32
        h ^= k
        h = ((h << 13) | (h >> 19)) & M32
        h = (h * 5 + 0xE6546B64) & M32
    k = 0
    for i, ch in enumerate(b[n:]):
        k |= ch << (8 * i)
    if b[n:]:
        k = (k * c1) & M32
        k = (((k << 15) | (k >> 17)) & M32) * c2 & M32
        h ^= k
    h ^= len(b)
    h ^= h >> 16
    h = (h * 0x85EBCA6B) & M32
    h ^= h >> 13
    h = (h * 0xC2B2AE35) & M32
    h ^= h >> 16
    return h


ALGOS = {
    "djb2": h_djb2, "djb2_init0": lambda b: h_djb2(b, 0), "djb2xor": h_djb2x,
    "sdbm": h_sdbm, "java31": h_java, "fnv1": h_fnv1, "fnv1a": h_fnv1a,
    "jenkins_oat": h_oat, "elf": h_elf,
    "crc32": h_crc32, "crc32_noxor": h_crc32_noxor,
    "crc32_bzip2": h_crc32_bzip2, "crc32_mpeg2": h_crc32_mpeg2,
    "crc32_jamcrc": h_crc32_jam, "crc32c": h_crc32c,
    "murmur3_s0": h_murmur3, "murmur3_bee": lambda b: h_murmur3(b, 0xDEADBEEF),
}


def variants(name):
    yield "raw", name.encode()
    yield "raw0", name.encode() + b"\0"
    yield "lower", name.lower().encode()
    yield "upper", name.upper().encode()
    yield "gcfg", ("g_config." + name).encode()


def dict_brute():
    names = collect_names()
    print(f"[dict] имён: {len(names)}, алгоритмов: {len(ALGOS)}")
    targets = {}
    for hx in ANCHORS.values():
        b = bytes.fromhex(hx)
        targets[int.from_bytes(b, "little")] = (hx, "LE")
        targets[int.from_bytes(b, "big")] = (hx, "BE")
    hits = []
    for an, fn in ALGOS.items():
        for name in names:
            for vn, data in variants(name):
                try:
                    h = fn(data) & M32
                except Exception:
                    continue
                if h in targets:
                    hits.append((an, vn, name, targets[h]))
    if hits:
        for x in hits:
            print("  HIT", x)
    else:
        print("  совпадений НЕТ")
    return hits


# ---------------------------------------------------------------- аффинный взлом
def _inv(a):
    x = 1
    for _ in range(32):
        x = (x * (2 - a * x)) & M32
    return x


def affine_crack():
    print("[affine] Horner-семейство, перебор множителя M, init решается из уравнения")
    LED = ["forearm_led_ctrl", "g_config.misc_cfg.forearm_lamp_ctrl", "forearm_lamp_ctrl"]
    MH = ["max_height_0", "g_config.flight_limit.max_height_0", "max_height"]

    def tf(s):
        b = s.encode()
        yield "raw", b
        yield "raw0", b + b"\0"
        yield "up", s.upper().encode()

    def horner(d, M):
        h = 0
        for c in d:
            h = (h * M + c) & M32
        return h, pow(M, len(d), 1 << 32)

    def preadd(d, M):
        run = lambda i0: __import__("functools").reduce(lambda h, c: ((h + c) * M) & M32, d, i0)
        b = run(0)
        return b, (run(1) - b) & M32

    led_le, led_be = (int.from_bytes(bytes.fromhex("a259ceed"), o) for o in ("little", "big"))
    mh_le, mh_be = (int.from_bytes(bytes.fromhex("8a237103"), o) for o in ("little", "big"))
    mults = [31, 33, 37, 65599, 65587, 5381, 16777619, 0x01000193, 1000003,
             0x9E3779B1, 131, 257] + list(range(3, 60000, 2))
    hits = []
    for order, led_t, mh_t in [("LE", led_le, mh_le), ("BE", led_be, mh_be)]:
        for lf in LED:
            for ltn, ld in tf(lf):
                for mf in MH:
                    for mtn, md in tf(mf):
                        if ltn != mtn:
                            continue
                        for fam, fn in [("horner", horner), ("preadd", preadd)]:
                            for M in mults:
                                if M % 2 == 0:
                                    continue
                                p0, a0 = fn(ld, M)
                                if a0 % 2 == 0:
                                    continue
                                init = ((led_t - p0) & M32) * _inv(a0) & M32
                                p1, a1 = fn(md, M)
                                if ((a1 * init + p1) & M32) == mh_t:
                                    hits.append((order, fam, M, lf, mf, ltn, hex(init)))
    if hits:
        for x in hits[:20]:
            print("  HIT", x)
    else:
        print("  совпадений НЕТ")
    return hits


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd in ("dict", "all"):
        dict_brute()
    if cmd in ("affine", "all"):
        affine_crack()
    if cmd in ("verify", "all"):
        verify()
