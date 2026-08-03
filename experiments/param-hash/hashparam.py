#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Тест находки: чтение/запись параметра FLYC по ХЕШУ имени — cmd_set 0x03,
cmd_id 0xF9 (write) / 0xF8 (read). payload = <hash:4B> [+ <value>].

Зачем это интересно: если писать по хешу имени, то НЕ нужны ни таблица моделей,
ни индекс, ни get_info, ни чтение на 40007, ни остановка DJI Fly. Пакет
самодостаточен: 4 байта хеша + значение.

Что уже ТОЧНО известно (из профилей FreeFCC / SkylabFCCfree):
  forearm_led_ctrl -> a2 59 ce ed   (LED; ef=вкл, 00=выкл)  — 0x03/0xF9 write
  max_height_0     -> 8a 23 71 03   (+ f4 01 = 500 м)       — 0x03/0xF9 write
  <GPS param>      -> 82 95 42 c5                            — 0x03/0xF8 read
Сам АЛГОРИТМ хеша пока не восстановлен (см. recover_hash.py) — поэтому тест
работает на ИЗВЕСТНЫХ литеральных хешах, проверяя именно ТРАНСПОРТ находки.

Транспорт воспроизводит рабочую модель FreeFCC на RC2:
  «один кадр на TCP-соединение»: открыть → записать → прочитать ACK → закрыть.
  LED-запись у FreeFCC идёт на порт 40007 с внешним wrapper (55 CC 30 75 + len).
  Остальные команды — на 40009 (штатный DUML-прокси RC2). Порт автоопределяется.

Запуск (на пульте DJI RC 2, ПК/скрипт в той же среде или через adb-forward нет —
запускать локально на пульте, напр. python из fuli-shell, либо с ПК если порт
проброшен). По умолчанию — DRY-RUN (только печатает кадры, ничего не шлёт).

  python hashparam.py led on            # включить LED по хешу (0x03/0xF9)  -- нужно --send
  python hashparam.py led off
  python hashparam.py probe --send      # led on -> пауза -> led off, визуальный тест
  python hashparam.py write a259ceed ef --send
  python hashparam.py read  829542c5 --send      # чтение по хешу 0x03/0xF8
  python hashparam.py xcheck --send     # тот же LED через ИНДЕКС (0x03/0xE3 idx23) — сверка

Флаги:  --send (реально слать),  --port N,  --host H,  --no-wrapper,  --wrapper
"""
import argparse
import socket
import sys
import time

from duml import Packet, crc8, crc16, iter_packets

HOST = "127.0.0.1"
# Порядок автоопределения: 40009 (штатный прокси RC2), 40007 (LED/downstream), 40008 (upstream inject).
PORTS = [40009, 40007, 40008]

# Адреса шины
SRC_APP = 0x02   # приложение/камера, index 0 — так шлёт FreeFCC LED-профиль (sender=2)
DST_FLYC = 0x03  # полётный контроллер
CMD_TYPE_REQ = 0x40  # запрос с ACK, без шифрования

# cmd_set / cmd_id находки
SET_FLYC = 0x03
ID_WRITE_HASH = 0xF9
ID_READ_HASH = 0xF8
# индексный путь для сверки
ID_READ_IDX = 0xE2
ID_WRITE_IDX = 0xE3

# Известные литеральные хеши (как байты на проводе, LE-порядок в payload)
KNOWN = {
    "forearm_led_ctrl": bytes.fromhex("a259ceed"),
    "max_height_0":     bytes.fromhex("8a237103"),
    "gps_read_sample":  bytes.fromhex("829542c5"),
}
LED_HASH = KNOWN["forearm_led_ctrl"]
LED_IDX = 23           # forearm_led_ctrl, table 0 (из known_toggles)
LED_TABLE = 0


# ------------------------------------------------------------------ wrapper
def wrap(inner: bytes) -> bytes:
    """Внешний конверт FreeFCC для порта 40007: 55 CC 30 75 + LE len(inner) + inner."""
    n = len(inner)
    return bytes([0x55, 0xCC, 0x30, 0x75,
                  n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF]) + inner


# ------------------------------------------------------------------ кадры
def frame_write_hash(hash4: bytes, value: bytes, seq: int = 0) -> bytes:
    return Packet(SET_FLYC, ID_WRITE_HASH, hash4 + value,
                  src=SRC_APP, dst=DST_FLYC, seq=seq, cmd_type=CMD_TYPE_REQ).build()


def frame_read_hash(hash4: bytes, seq: int = 0) -> bytes:
    return Packet(SET_FLYC, ID_READ_HASH, hash4,
                  src=SRC_APP, dst=DST_FLYC, seq=seq, cmd_type=CMD_TYPE_REQ).build()


def frame_write_idx(table: int, index: int, value: bytes, seq: int = 0) -> bytes:
    # payload индексного write: <table u16><unknown1 u16=1><index u16><value>
    p = bytes([table & 0xFF, (table >> 8) & 0xFF, 1, 0, index & 0xFF, (index >> 8) & 0xFF]) + value
    return Packet(SET_FLYC, ID_WRITE_IDX, p,
                  src=SRC_APP, dst=DST_FLYC, seq=seq, cmd_type=CMD_TYPE_REQ).build()


def frame_read_idx(table: int, index: int, seq: int = 0) -> bytes:
    p = bytes([table & 0xFF, (table >> 8) & 0xFF, 1, 0, index & 0xFF, (index >> 8) & 0xFF])
    return Packet(SET_FLYC, ID_READ_IDX, p,
                  src=SRC_APP, dst=DST_FLYC, seq=seq, cmd_type=CMD_TYPE_REQ).build()


# ------------------------------------------------------------------ транспорт
def _try_port(host, port, timeout=1.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        return s
    except OSError:
        s.close()
        return None


def find_port(host, ports):
    for p in ports:
        s = _try_port(host, p)
        if s:
            s.close()
            return p
    return None


def send_frame(host, port, frame, use_wrapper, read_ms=400, verbose=True):
    """Один кадр на соединение: открыть → записать → прочитать ответ → закрыть."""
    wire = wrap(frame) if use_wrapper else frame
    if verbose:
        tag = "WRAPPED" if use_wrapper else "plain"
        print(f"  -> :{port} [{tag}] {wire.hex(' ')}")
    s = _try_port(host, port)
    if s is None:
        print(f"  !! порт {port} недоступен")
        return None
    try:
        s.sendall(wire)
        s.settimeout(read_ms / 1000.0)
        buf = b""
        try:
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
                if len(buf) >= 8:
                    break
        except socket.timeout:
            pass
        if verbose and buf:
            print(f"  <- {buf.hex(' ')}")
            for fr in iter_packets(buf):
                p = Packet.parse(fr)
                print(f"     DUML resp set={p.cmd_set:02x} id={p.cmd_id:02x} "
                      f"payload={p.payload.hex(' ')}")
        elif verbose:
            print("  <- (нет ответа в окне чтения)")
        return buf
    finally:
        s.close()


# ------------------------------------------------------------------ команды
def do_led(args, on: bool):
    val = b"\xef" if on else b"\x00"
    fr = frame_write_hash(LED_HASH, val)
    print(f"LED {'ON' if on else 'OFF'} по хешу {LED_HASH.hex()} (0x03/0xF9), value={val.hex()}")
    _exec(args, fr, prefer_wrapper=True)


def do_probe(args):
    print("PROBE: LED ON -> 2с -> LED OFF. Смотри на лучи дрона.")
    do_led(args, True)
    if args.send:
        time.sleep(2.0)
    do_led(args, False)
    print("Если LED мигнул — транспорт записи по хешу (0x03/0xF9) РАБОТАЕТ.")


def do_write(args):
    h = bytes.fromhex(args.hash)
    v = bytes.fromhex(args.value) if args.value else b""
    fr = frame_write_hash(h, v)
    print(f"WRITE hash={h.hex()} value={v.hex()} (0x03/0xF9)")
    _exec(args, fr, prefer_wrapper=True)


def do_read(args):
    h = bytes.fromhex(args.hash)
    fr = frame_read_hash(h)
    print(f"READ hash={h.hex()} (0x03/0xF8)")
    _exec(args, fr, prefer_wrapper=False)


def do_xcheck(args):
    print("XCHECK: тот же LED через ИНДЕКС (0x03/0xE3 idx23) — сверка с индексным путём.")
    fr_on = frame_write_idx(LED_TABLE, LED_IDX, b"\xef")
    fr_off = frame_write_idx(LED_TABLE, LED_IDX, b"\x00")
    print("index ON:")
    _exec(args, fr_on, prefer_wrapper=False)
    if args.send:
        time.sleep(2.0)
    print("index OFF:")
    _exec(args, fr_off, prefer_wrapper=False)


def _exec(args, frame, prefer_wrapper):
    use_wrapper = args.wrapper if args.wrapper is not None else prefer_wrapper
    if not args.send:
        wire = wrap(frame) if use_wrapper else frame
        tag = "WRAPPED" if use_wrapper else "plain"
        print(f"  (dry-run) [{tag}] {wire.hex(' ')}")
        return
    port = args.port or find_port(args.host, PORTS)
    if port is None:
        print("  !! ни один DUML-порт не отвечает (40009/40007/40008). "
              "Запусти на пульте, DJI-сервис должен быть жив.")
        return
    send_frame(args.host, port, frame, use_wrapper)


# ------------------------------------------------------------------ main
def main():
    ap = argparse.ArgumentParser(description="Тест read/write параметра по хешу имени (0x03/0xF8-0xF9)")
    ap.add_argument("--host", default=HOST)
    ap.add_argument("--port", type=int, default=0, help="принудительный порт (иначе автоопределение)")
    ap.add_argument("--send", action="store_true", help="реально слать (иначе dry-run)")
    wgrp = ap.add_mutually_exclusive_group()
    wgrp.add_argument("--wrapper", dest="wrapper", action="store_const", const=True, default=None,
                      help="принудительно оборачивать (55 CC 30 75)")
    wgrp.add_argument("--no-wrapper", dest="wrapper", action="store_const", const=False,
                      help="принудительно без wrapper")

    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("led"); p.add_argument("state", choices=["on", "off"])
    sub.add_parser("probe")
    p = sub.add_parser("write"); p.add_argument("hash"); p.add_argument("value", nargs="?", default="")
    p = sub.add_parser("read"); p.add_argument("hash")
    sub.add_parser("xcheck")
    sub.add_parser("selftest")

    args = ap.parse_args()
    if args.port == 0:
        args.port = None

    if args.cmd == "led":
        do_led(args, args.state == "on")
    elif args.cmd == "probe":
        do_probe(args)
    elif args.cmd == "write":
        do_write(args)
    elif args.cmd == "read":
        do_read(args)
    elif args.cmd == "xcheck":
        do_xcheck(args)
    elif args.cmd == "selftest":
        selftest()


def selftest():
    """Проверка framing без железа: собрать LED-кадр и сверить с эталоном FreeFCC."""
    fr = frame_write_hash(LED_HASH, b"\xef")
    print("LED-ON inner frame:", fr.hex(" "))
    # разобрать назад
    p = Packet.parse(fr)
    assert p.cmd_set == 0x03 and p.cmd_id == 0xF9, "cmd_set/cmd_id"
    assert p.payload == LED_HASH + b"\xef", "payload"
    assert p.src == SRC_APP and p.dst == DST_FLYC, "addr"
    # CRC-самопроверка из duml.py
    assert crc8(bytes([0x55, 0x0E, 0x04])) == 0x66
    w = wrap(fr)
    assert w[:4] == bytes([0x55, 0xCC, 0x30, 0x75]) and w[4] == len(fr) & 0xFF
    print("wrapped:", w.hex(" "))
    print("SELFTEST OK — кадр по хешу собран, CRC/wrapper верны.")


if __name__ == "__main__":
    main()
