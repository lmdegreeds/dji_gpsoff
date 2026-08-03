#!/usr/bin/env python3
"""Собрать assets/models.tsv из дампов параметров и телеметрии.

Входы (локальные, НЕ в git и НЕ в APK — в ids.json серийные номера и IP пользователей):
  data/paramtables/paramtable_<code>.txt   — дампы таблиц параметров по моделям
  data/ids.json                            — выгрузка телеметрии подключений

Без папки data/ генератор не запустится — это нормально: в репозитории лежит уже
собранный assets/models.tsv, и для сборки APK генератор не нужен.

Выход:
  assets/models.tsv                        — ~2 КБ вместо 533 КБ дампов

Зачем: приложению из всей таблицы нужны три индекса на модель плюс способ опознать
модель. Дампы дают индексы, телеметрия — реальные crc/count/кодовые имена бортов,
встреченные в поле (в дампах часть crc нулевые, а count дрейфует между прошивками).

  python tools/gen_models.py            # перезаписать assets/models.tsv
  python tools/gen_models.py --check    # ничего не писать, упасть при расхождении

Все несоответствия — ошибка, а не предупреждение: молча разъехавшийся индекс означает
запись в чужой параметр на живом дроне.
"""
import json
import os
import sys

PARAMS = ["forearm_led_ctrl", "gps_enable", "fswitch_selection"]
FP_LEN = 12          # длина префикса имени в отпечатке
FP_COUNT = 6         # сколько индексов-проб выбираем
MIN_SEP = 3          # любая пара моделей должна различаться минимум на столько проб

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMPS = os.path.join(ROOT, "data", "paramtables")
IDS = os.path.join(ROOT, "data", "ids.json")
OUT = os.path.join(ROOT, "assets", "models.tsv")

# Человекочитаемые названия моделей для UI. Ключ — код дампа.
LABELS = {
    "wa020": "Neo 2",       "wa140": "Mini 4 Pro",  "wa141": "Flip",
    "wa150": "Mini 5 Pro",  "wa151": "Lito X1",     "wa234": "Air 3S",
    "wa341": "Mavic 4 Pro", "wa521": "Neo",         "wa530": "Avata 360",
    "wm162": "Mini 3 Pro",  "wm232": "Air 2S",      "wm233": "Air 3",
    "wm260": "Mavic 3",     "wm261": "Mavic 3 Pro",
}

# Варианты прошивок, для которых дампа нет: индексы пришли из телеметрии, а она отдаёт
# ТОЛЬКО проверенные живьём по имени номера (Detector коммитит индекс лишь после get_info).
# Каждый вариант — отдельная строка models.tsv с тем же кодом модели; отпечатка у него нет,
# поэтому в опознании по содержимому он опирается на строку дампа своей модели, а между
# вариантами разбирается живая сверка имени.
#
# Вписывай сюда только то, что противоречит дампу или в дампе отсутствует — совпадающие
# с дампом строки телеметрии подхватываются merge_telemetry сами.
VARIANTS = [
    # Neo 2 с тем же паспортом (crc+count), что и дамп, но с переехавшей таблицей:
    # led совпал, gps/fsw уехали (в дампе 377 = dead_zone_for_m, 130 = compass_fdi_open_stuck).
    {"code": "wa020", "crc": 0x2ae1a5ad, "count": 1571,
     "forearm_led_ctrl": 4, "gps_enable": 377, "fswitch_selection": 130},
    # Lito X1, прошивка новее дампа (1593 → 1594) и с другим crc.
    {"code": "wa151", "crc": 0x2ae1a5ad, "count": 1594,
     "forearm_led_ctrl": 23, "gps_enable": 382, "fswitch_selection": 133},
]


class Fail(Exception):
    pass


def parse_dump(path):
    """-> {code, crc, count, names: {index: short_name}}. Формат строки:
    index<TAB>type<TAB>default<TAB>min<TAB>max<TAB>short|full.path"""
    code = os.path.basename(path)[len("paramtable_"):-len(".txt")]
    crc, count, names = 0, 0, {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            if line[0] == "#":
                for tok in line.lstrip("#").split():
                    k, _, v = tok.partition("=")
                    if k == "crc":
                        crc = int(v, 16)
                    elif k == "count":
                        count = int(v)
                continue
            c = line.split("\t", 5)
            if len(c) < 6:
                continue
            try:
                idx = int(c[0].strip())
            except ValueError:
                continue
            names[idx] = c[5].split("|", 1)[0].strip()
    if not names:
        raise Fail("пустой дамп: %s" % path)
    return {"code": code, "crc": crc, "count": count, "names": names}


def load_dumps():
    files = sorted(f for f in os.listdir(DUMPS)
                   if f.startswith("paramtable_") and f.endswith(".txt"))
    if not files:
        raise Fail("нет дампов в %s" % DUMPS)
    return [parse_dump(os.path.join(DUMPS, f)) for f in files]


def index_of(dump, short):
    for idx, name in dump["names"].items():
        if name == short:
            return idx
    return -1


def variant_of(crc, count, board):
    """Объявленный вручную вариант прошивки для этой строки телеметрии, или None."""
    for v in VARIANTS:
        if v["crc"] == crc and v["count"] == count and (not board or board == v["code"]):
            return v
    return None


def merge_telemetry(models):
    """Добавить в модели данные из ids.json: реальные count, недостающие crc, кодовые
    имена бортов. Строки со всеми null-индексами всё равно полезны — они несут crc/count/model."""
    if not os.path.exists(IDS):
        print("  ids.json нет — собираю только из дампов")
        return
    with open(IDS, encoding="utf-8") as f:
        rows = json.load(f)

    by_crc_count = {}       # (crc, count) -> model
    for m in models:
        if m["crc"]:
            for c in m["counts"]:
                by_crc_count[(m["crc"], c)] = m

    unmatched = []
    for r in rows:
        crc = int(r.get("crc") or "0", 16)
        count = int(r.get("count") or 0)
        board = (r.get("model") or "").strip().lower()
        if not count:
            continue

        # Паспорт объявлен вариантом — модель для него уже задана вручную, дамп трогать
        # нельзя. Сверяем только, что индексы из поля совпали с объявленными.
        v = variant_of(crc, count, board)
        if v is not None:
            for p in PARAMS:
                got = r.get(p)
                if got is not None and int(got) != v[p]:
                    raise Fail("вариант %s (crc=%08x count=%d): телеметрия даёт %s=%d, "
                               "в VARIANTS %d — разберись вручную"
                               % (v["code"], crc, count, p, int(got), v[p]))
            continue

        # Сопоставляем строку телеметрии с моделью: сначала точно (crc,count), затем по
        # уже известному кодовому имени, затем по одинаковому count (это и даёт crc
        # моделям, у которых в дампе crc=0), затем по ближайшему count при том же crc.
        m = by_crc_count.get((crc, count))
        if m is None and board:
            cand = [x for x in models if board in x["boards"]]
            if len(cand) == 1:
                m = cand[0]
        if m is None:
            cand = [x for x in models if count in x["counts"]]
            if len(cand) == 1:
                m = cand[0]
        if m is None and crc:
            cand = [x for x in models if x["crc"] == crc]
            if len(cand) == 1:
                m = cand[0]
            elif cand:
                m = min(cand, key=lambda x: min(abs(count - c) for c in x["counts"]))
        if m is None:
            unmatched.append((r.get("crc"), count, board))
            continue

        if crc and not m["crc"]:
            m["crc"] = crc                      # телеметрия дала crc, которого нет в дампе
        if crc and m["crc"] and crc != m["crc"]:
            raise Fail("crc телеметрии %08x != crc дампа %08x у модели %s"
                       % (crc, m["crc"], m["code"]))
        if count not in m["counts"]:
            m["counts"].append(count)
        if board and board not in m["boards"]:
            m["boards"].append(board)

        # Индексы из телеметрии обязаны совпасть с дампом — иначе прошивка их сдвинула
        # и таблицу нельзя публиковать не разобравшись.
        for p in PARAMS:
            v = r.get(p)
            if v is None:
                continue
            if m[p] >= 0 and int(v) != m[p]:
                raise Fail("модель %s: телеметрия даёт %s=%d, дамп даёт %d "
                           "(crc=%s count=%d) — прошивка сдвинула индекс, разберись вручную"
                           % (m["code"], p, int(v), m[p], r.get("crc"), count))
            if m[p] < 0:
                m[p] = int(v)

    if unmatched:
        print("  строки телеметрии без модели (%d): %s" % (len(unmatched), unmatched[:5]))


def pick_probe_indices(models):
    """Жадно выбрать FP_COUNT индексов так, чтобы любая пара моделей различалась
    минимум MIN_SEP пробами. Кандидаты — индексы, присутствующие почти во всех дампах."""
    n = len(models)
    need = max(2, n - 3)
    counts = {}
    for m in models:
        for idx in m["names"]:
            counts[idx] = counts.get(idx, 0) + 1
    cands = sorted(i for i, c in counts.items() if c >= need)
    if not cands:
        raise Fail("нет индексов, общих для большинства дампов")

    pairs = [(a, b) for a in range(n) for b in range(a + 1, n)]

    def sep(idx, a, b):
        """1, если проба idx РАЗЛИЧАЕТ модели a и b (оба имени известны и разные)."""
        na = models[a]["names"].get(idx)
        nb = models[b]["names"].get(idx)
        if na is None or nb is None:
            return 0
        return 1 if na[:FP_LEN] != nb[:FP_LEN] else 0

    chosen, score = [], {p: 0 for p in pairs}
    while len(chosen) < FP_COUNT and cands:
        # берём индекс, который сильнее всего помогает самым слабо разделённым парам
        best, best_gain = None, None
        for idx in cands:
            gain = sum(sep(idx, a, b) for a, b in pairs if score[(a, b)] < MIN_SEP)
            if best_gain is None or gain > best_gain:
                best, best_gain = idx, gain
        if not best_gain:
            break
        chosen.append(best)
        cands.remove(best)
        for p in pairs:
            score[p] += sep(best, p[0], p[1])

    weak = [(models[a]["code"], models[b]["code"], score[(a, b)])
            for a, b in pairs if score[(a, b)] < MIN_SEP]
    if weak:
        raise Fail("пары моделей различаются менее чем %d пробами: %s" % (MIN_SEP, weak))
    return sorted(chosen)


def build():
    dumps = load_dumps()
    models = []
    for d in dumps:
        m = {
            "code": d["code"], "crc": d["crc"], "counts": [d["count"]],
            "boards": [d["code"]], "names": d["names"],
            "label": LABELS.get(d["code"], d["code"].upper()),
        }
        for p in PARAMS:
            m[p] = index_of(d, p)
            if m[p] < 0:
                print("  %s: нет параметра %s в дампе" % (d["code"], p))
        models.append(m)

    merge_telemetry(models)

    # Варианты прошивок без дампа: имён у них нет, поэтому и отпечатка нет.
    for v in VARIANTS:
        if v["code"] not in {m["code"] for m in models}:
            raise Fail("вариант ссылается на модель %s, дампа которой нет" % v["code"])
        m = {
            "code": v["code"], "crc": v["crc"], "counts": [v["count"]],
            "boards": [v["code"]], "names": {}, "variant": True,
            "label": LABELS.get(v["code"], v["code"].upper()),
        }
        for p in PARAMS:
            m[p] = int(v[p])
        models.append(m)

    # (crc, count) должен однозначно указывать на МОДЕЛЬ — это главное правило выбора в
    # ModelDb. Несколько строк одной модели на один паспорт допустимы: это её варианты,
    # и разбирается между ними живая сверка имени.
    seen = {}
    for m in models:
        if not m["crc"]:
            continue
        for c in m["counts"]:
            other = seen.get((m["crc"], c))
            if other and other["code"] != m["code"]:
                raise Fail("(crc=%08x count=%d) указывает и на %s, и на %s"
                           % (m["crc"], c, other["code"], m["code"]))
            seen[(m["crc"], c)] = m

    probes = pick_probe_indices([m for m in models if not m.get("variant")])

    lines = [
        "#djiquick-models v1  params=%s  type=U8" % ",".join(PARAMS),
        "#P\tиндексы-пробы для опознания модели по содержимому",
        "#M\tcode\tcrc\tcounts\tled\tgps\tfsw\tboards\tfp\tlabel",
        "#M\tстрок с одним code может быть несколько — варианты прошивок одной модели",
        "P\t" + "\t".join(str(i) for i in probes),
    ]
    # Дамп модели идёт перед её вариантами: он проверен по именам, вариант — только по полю.
    for m in sorted(models, key=lambda x: (x["code"], bool(x.get("variant")), min(x["counts"]))):
        fp = "|".join((m["names"].get(i) or "-")[:FP_LEN] for i in probes)
        lines.append("\t".join([
            "M", m["code"], "%08x" % m["crc"],
            ",".join(str(c) for c in sorted(m["counts"])),
            str(m[PARAMS[0]]), str(m[PARAMS[1]]), str(m[PARAMS[2]]),
            ",".join(sorted(m["boards"])), fp, m["label"],
        ]))
    return "\n".join(lines) + "\n", models, probes


def main():
    check = "--check" in sys.argv
    try:
        text, models, probes = build()
    except Fail as e:
        print("ОШИБКА: %s" % e)
        return 1

    if check:
        if not os.path.exists(OUT):
            print("ОШИБКА: %s не существует" % OUT)
            return 1
        with open(OUT, encoding="utf-8") as f:
            cur = f.read()
        if cur != text:
            print("ОШИБКА: %s расходится с генератором — перегенерируй" % OUT)
            return 1
        print("OK: %s актуален (%d строк)" % (OUT, len(models)))
        return 0

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("записано %s: %d строк (%d вариантов), %d байт, пробы %s"
          % (OUT, len(models), sum(1 for m in models if m.get("variant")),
             len(text.encode("utf-8")), probes))
    for m in sorted(models, key=lambda x: (x["code"], bool(x.get("variant")), min(x["counts"]))):
        print("  %-6s crc=%08x counts=%-14s led=%-5d gps=%-5d fsw=%-5d boards=%s%s"
              % (m["code"], m["crc"], ",".join(str(c) for c in sorted(m["counts"])),
                 m[PARAMS[0]], m[PARAMS[1]], m[PARAMS[2]], ",".join(sorted(m["boards"])),
                 "  (вариант)" if m.get("variant") else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
