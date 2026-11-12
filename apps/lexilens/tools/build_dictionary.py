#!/usr/bin/env python3
"""Build the offline English -> Persian dictionary used by LexiLens.

Sources (all freely licensed, see NOTICE.md):
  * koreader-dicts en-fa (CC-BY-SA-4.0, Wiktionary + WordNet via kaikki.org)
  * VahidN/EnglishToPersianDictionaries (Apache-2.0)
  * shirin-manzari/english-persian-dictionary-dataset (mixed upstream licences)

Output: a compact SQLite database with an exact-lookup index and an
inflection/form table.  The app only reads it, never writes to it.
"""
from __future__ import annotations

import argparse
import csv
import glob
import html
import json
import os
import re
import sqlite3
import struct
import sys
import unicodedata
from collections import OrderedDict

ARABIC_FIX = str.maketrans({
    "ي": "ی", "ى": "ی", "ك": "ک", "ۀ": "ه", "أ": "ا", "إ": "ا", "ؤ": "و",
    "٠": "۰", "١": "۱", "٢": "۲", "٣": "۳", "٤": "۴", "٥": "۵", "٦": "۶",
    "٧": "۷", "٨": "۸", "٩": "۹",
})
PUNCT = " \t\r\n.,;:!?\"'“”‘’()[]{}<>،؛…—–-«»/\\"
POS_MAP = {
    "n.": "اسم", "v.": "فعل", "adj.": "صفت", "adv.": "قید", "prep.": "حرف اضافه",
    "conj.": "حرف ربط", "pron.": "ضمیر", "det.": "حرف تعریف", "num.": "عدد",
    "intj.": "شبه‌جمله", "interj.": "شبه‌جمله", "suf.": "پسوند", "pref.": "پیشوند",
    "phrase": "عبارت", "abbr.": "مخفف", "aux.": "فعل کمکی", "part.": "جزء",
}
POS_ORDER = ["اسم", "فعل", "صفت", "قید", "حرف اضافه", "حرف ربط", "ضمیر",
             "حرف تعریف", "عدد", "شبه‌جمله", "پسوند", "پیشوند", "عبارت", "مخفف"]


def norm_word(s: str) -> str:
    s = unicodedata.normalize("NFKC", s or "").translate(ARABIC_FIX).strip()
    s = s.replace("’", "'").replace("‘", "'").replace("`", "'")
    s = re.sub(r"\s+", " ", s)
    s = s.strip(PUNCT)
    return s.lower()


def clean_fa(s: str) -> str:
    s = html.unescape(s or "").translate(ARABIC_FIX)
    s = s.replace("\u200f", "").replace("\u200e", "").replace("\ufeff", "")
    s = re.sub(r"\s+", " ", s)
    s = s.strip(" \u200c\u200f\u200e،,;؛.-")
    return s


def split_meanings(raw: str) -> list[str]:
    raw = clean_fa(raw)
    if not raw:
        return []
    raw = raw.replace("،", ",").replace("؛", ";")
    raw = re.sub(r"\.\s+", ", ", raw)
    parts = re.split(r"[,;]\s*", raw)
    out = []
    for p in parts:
        p = clean_fa(p)
        if not p:
            continue
        if len(p) > 90:
            continue
        if p not in out:
            out.append(p)
    return out


def strip_tags(s: str) -> str:
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    return html.unescape(s)


def parse_koreader(dirname: str) -> dict[str, dict]:
    idx_path = glob.glob(os.path.join(dirname, "*.idx"))
    dict_path = glob.glob(os.path.join(dirname, "*.dict"))
    if not idx_path or not dict_path:
        return {}
    idx = open(idx_path[0], "rb").read()
    dic = open(dict_path[0], "rb").read()

    words: list[str] = []
    payloads: list[str] = []
    o = 0
    while o < len(idx):
        try:
            e = idx.index(b"\0", o)
            word = idx[o:e].decode("utf-8", "replace")
            off, size = struct.unpack(">II", idx[e + 1:e + 9])
            payloads.append(dic[off:off + size].decode("utf-8", "replace"))
            words.append(word)
            o = e + 9
        except (ValueError, struct.error):
            break

    out: dict[str, dict] = {}
    for word, payload in zip(words, payloads):
        key = norm_word(word)
        if not key:
            continue
        ipa = ""
        m = re.search(r'<div style="color:#666666">\s*([^<]+?)\s*</div>', payload)
        if m and ("/" in m.group(1) or "[" in m.group(1)):
            ipa = clean_fa(m.group(1))

        senses: list[tuple[str, list[str]]] = []
        pos = ""
        for chunk in re.split(r"<div><i>", payload):
            pm = re.match(r"([^<]+)</i>", chunk)
            if not pm:
                continue
            raw_pos = pm.group(1).strip()
            pos = POS_MAP.get(raw_pos, raw_pos)
            for sm in re.finditer(r"<b>(.*?)</b>", chunk, re.S):
                trans = [clean_fa(x) for x in re.split(r"[,،;؛]", strip_tags(sm.group(1)))]
                trans = [x for x in trans if x and len(x) < 80 and not re.fullmatch(r"[\d\W]+", x)]
                if trans:
                    senses.append((pos, trans))

        if not senses:
            continue
        glosses = []
        for gm in re.finditer(r"&mdash;\s*(.*?)</span>", payload, re.S):
            g = clean_fa(strip_tags(gm.group(1)))
            if g and g not in glosses:
                glosses.append(g)
            if len(glosses) >= 3:
                break

        out.setdefault(key, {
            "display": word.strip(),
            "ipa": ipa,
            "senses": [],
            "glosses": glosses,
            "source": "wiktionary",
        })
        entry = out[key]
        if not entry["ipa"] and ipa:
            entry["ipa"] = ipa
        for p, ts in senses:
            entry["senses"].append((p, ts))
        for g in glosses:
            if g not in entry["glosses"] and len(entry["glosses"]) < 3:
                entry["glosses"].append(g)
    return out


def load_vahidn_sets(root: str, set_names: list[str]) -> dict[str, dict[str, list[str]]]:
    out: dict[str, dict[str, list[str]]] = {}
    for name in set_names:
        bucket_map: dict[str, list[str]] = {}
        for f in sorted(glob.glob(os.path.join(root, name, "*.json"))):
            try:
                data = json.load(open(f, encoding="utf-8-sig"))
            except Exception:
                continue
            for w in data.get("Words", []):
                key = norm_word(w.get("EnglishWord", ""))
                if not key:
                    continue
                bucket = bucket_map.setdefault(key, [])
                for meaning in w.get("Meanings", []):
                    for part in split_meanings(meaning):
                        if part not in bucket:
                            bucket.append(part)
        out[name] = bucket_map
    return out


def load_shirin(path: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8-sig", newline="") as fh:
        for row in csv.reader(fh):
            if len(row) < 4:
                continue
            key = norm_word(row[0])
            if not key or key.isdigit():
                continue
            trans = clean_fa(row[3])
            if not trans or len(trans) > 70:
                continue
            bucket = out.setdefault(key, [])
            if trans not in bucket:
                bucket.append(trans)
    return out


def merge(koreader: dict, vahidn: dict[str, dict[str, list[str]]], shirin: dict) -> list[dict]:
    keys = set(koreader) | set(shirin)
    for bucket in vahidn.values():
        keys |= set(bucket)
    rows = []
    primary = vahidn.get("generic-13", {})
    common = vahidn.get("essential-english-words-2", {})
    for key in keys:
        k = koreader.get(key)
        meanings: list[str] = []
        seen: set[str] = set()
        pos_set: list[str] = []
        glosses: list[str] = []
        has_vahidn = any(key in b for b in vahidn.values())

        def add(parts, limit):
            for p in parts:
                p = clean_fa(p)
                if not p or p in seen:
                    continue
                if len(p) > 90:
                    continue
                # Drop grammar/meta noise that is not a translation.
                low = p.lower()
                if ("=" in p or re.search(r"\b(of|p\. of|past|participle|plural)\b", low)
                        or "صورت جمع" in p or "ماضی واسم" in p or "مصدر" in p
                        or (re.search(r"[A-Za-z]", p) and re.search(r"[\u0600-\u06FF]", p))):
                    continue
                if re.fullmatch(r"[A-Za-z .'-]+", p):
                    continue
                # Skip grammatical labels that do not carry a meaning.
                if p in {"اسم", "فعل", "صفت", "قید", "حرف اضافه", "حرف ربط", "ضمیر"}:
                    continue
                seen.add(p)
                meanings.append(p)
                if len(meanings) >= limit:
                    return

        # 1. The general dictionary has the best frequency-ordered equivalents.
        add(primary.get(key, []), 8)
        # 2. Add common-word list, then the remaining general sets.
        add(common.get(key, []), 9)
        for name, bucket in vahidn.items():
            if name in ("generic-13", "essential-english-words-2"):
                continue
            add(bucket.get(key, []), 10)
        # 3. Wiktionary adds IPA/POS and extra senses.
        if k:
            for pos, ts in k["senses"]:
                for t in ts:
                    if pos and pos not in pos_set:
                        pos_set.append(pos)
                    add([t], 12)
            glosses = k["glosses"][:2]
        # 4. Last-resort coverage from the merged community dataset.
        add(shirin.get(key, []), 12)

        if not meanings:
            continue
        meanings = meanings[:10]
        if not any(re.search(r"[\u0600-\u06FFA-Za-z]", m) for m in meanings):
            continue
        if k and has_vahidn:
            source = "wiktionary+vahidn"
        elif k:
            source = "wiktionary"
        elif has_vahidn:
            source = "vahidn"
        else:
            source = "shirin"
        rows.append({
            "norm": key,
            "display": (k["display"] if k else key),
            "ipa": (k["ipa"] if k else ""),
            "pos": "، ".join(sorted(pos_set, key=lambda x: POS_ORDER.index(x) if x in POS_ORDER else 99)),
            "trans": "\n".join(meanings[:10]),
            "gloss": "\n".join(glosses),
            "source": source,
        })
    rows.sort(key=lambda r: r["norm"])
    return rows


def load_forms(koreader_dir: str) -> dict[str, str]:
    idx_path = glob.glob(os.path.join(koreader_dir, "*.idx"))
    syn_path = glob.glob(os.path.join(koreader_dir, "*.syn"))
    if not idx_path or not syn_path:
        return {}
    idx = open(idx_path[0], "rb").read()
    syn = open(syn_path[0], "rb").read()
    words: list[str] = []
    o = 0
    while o < len(idx):
        try:
            e = idx.index(b"\0", o)
            words.append(idx[o:e].decode("utf-8", "replace"))
            o = e + 9
        except ValueError:
            break
    out: dict[str, str] = {}
    o = 0
    while o < len(syn):
        try:
            e = syn.index(b"\0", o)
            form = syn[o:e].decode("utf-8", "replace")
            i = struct.unpack(">I", syn[e + 1:e + 5])[0]
            o = e + 5
        except (ValueError, struct.error):
            break
        if i >= len(words):
            continue
        key = norm_word(form)
        head = norm_word(words[i])
        if not key or not head or key == head:
            continue
        # Ignore junk/very long synonym rows.
        if len(key) > 40 or not re.search(r"[a-z]", key):
            continue
        out.setdefault(key, head)
    return out


EXTRA_FORMS = {
    "was": "be", "were": "be", "been": "be", "am": "be", "is": "be", "are": "be",
    "did": "do", "done": "do", "does": "do", "had": "have", "has": "have",
    "went": "go", "gone": "go", "saw": "see", "seen": "see", "took": "take",
    "taken": "take", "came": "come", "got": "get", "gotten": "get", "made": "make",
    "said": "say", "knew": "know", "known": "know", "thought": "think",
    "felt": "feel", "left": "leave", "kept": "keep", "brought": "bring",
    "began": "begin", "begun": "begin", "held": "hold", "wrote": "write",
    "written": "write", "stood": "stand", "heard": "hear", "let": "let",
    "meant": "mean", "met": "meet", "ran": "run", "paid": "pay", "sat": "sit",
    "spoke": "speak", "spoken": "speak", "lay": "lie", "lain": "lie",
    "led": "lead", "grew": "grow", "grown": "grow", "lost": "lose",
    "fell": "fall", "fallen": "fall", "sent": "send", "built": "build",
    "understood": "understand", "drew": "draw", "drawn": "draw", "broke": "break",
    "broken": "break", "spent": "spend", "cut": "cut", "rose": "rise",
    "risen": "rise", "drove": "drive", "driven": "drive", "bought": "buy",
    "wore": "wear", "worn": "wear", "chose": "choose", "chosen": "choose",
    "ate": "eat", "eaten": "eat", "gave": "give", "given": "give",
    "flew": "fly", "flown": "fly", "threw": "throw", "thrown": "throw",
    "caught": "catch", "taught": "teach", "fought": "fight", "sought": "seek",
    "sold": "sell", "told": "tell", "found": "find", "held": "hold",
    "won": "win", "wore": "wear", "wound": "wind", "hung": "hang",
    "dug": "dig", "stuck": "stick", "struck": "strike", "swept": "sweep",
    "wept": "weep", "slept": "sleep", "crept": "creep", "leapt": "leap",
    "shone": "shine", "shot": "shoot", "lit": "light", "fed": "feed",
    "fled": "flee", "bled": "bleed", "bred": "breed", "dealt": "deal",
    "dreamt": "dream", "learnt": "learn", "burnt": "burn", "spoilt": "spoil",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vahidn", required=True)
    ap.add_argument("--koreader", required=True)
    ap.add_argument("--shirin", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--with-shirin", action="store_true")
    args = ap.parse_args()

    sets = [
        "essential-english-words-2", "essential-english-words", "learn-english",
        "generic-1", "generic-2", "generic-3", "generic-4", "generic-5", "generic-6",
        "generic-7", "generic-8", "generic-9", "generic-10", "generic-11", "generic-12",
        "generic-13", "generic-14", "generic-15", "generic-16", "wikipedia-fa-en",
    ]
    print("Parsing Wiktionary/KOReader ...", flush=True)
    koreader = parse_koreader(args.koreader)
    print(f"  {len(koreader):,} entries")
    print("Parsing VahidN ...", flush=True)
    vahidn = load_vahidn_sets(args.vahidn, sets)
    print(f"  {sum(len(b) for b in vahidn.values()):,} set-entries")
    shirin = load_shirin(args.shirin) if args.with_shirin else {}
    print(f"  shirin {len(shirin):,} headwords")
    print("Merging ...", flush=True)
    rows = merge(koreader, vahidn, shirin)
    forms = load_forms(args.koreader)
    print(f"  {len(rows):,} entries, {len(forms):,} forms")

    if os.path.exists(args.out):
        os.remove(args.out)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    db = sqlite3.connect(args.out)
    db.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA page_size=4096;
        PRAGMA auto_vacuum=NONE;
        CREATE TABLE entries(
            norm TEXT PRIMARY KEY,
            display TEXT NOT NULL,
            ipa TEXT NOT NULL DEFAULT '',
            pos TEXT NOT NULL DEFAULT '',
            trans TEXT NOT NULL,
            gloss TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL DEFAULT ''
        ) WITHOUT ROWID;
        CREATE TABLE forms(
            form TEXT PRIMARY KEY,
            head TEXT NOT NULL
        ) WITHOUT ROWID;
    """)
    db.executemany(
        "INSERT OR REPLACE INTO entries(norm,display,ipa,pos,trans,gloss,source) VALUES(?,?,?,?,?,?,?)",
        [(r["norm"], r["display"], r["ipa"], r["pos"], r["trans"], r["gloss"], r["source"]) for r in rows],
    )
    known = set(forms)
    extra = {k: v for k, v in EXTRA_FORMS.items() if norm_word(v) in {r["norm"] for r in rows}}
    forms.update({k: v for k, v in extra.items() if k not in known})
    db.executemany(
        "INSERT OR REPLACE INTO forms(form,head) VALUES(?,?)",
        [(k, v) for k, v in sorted(forms.items())],
    )
    db.commit()
    db.execute("VACUUM")
    db.close()
    size = os.path.getsize(args.out)
    print(f"Wrote {args.out} ({size / 1048576:.1f} MiB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
