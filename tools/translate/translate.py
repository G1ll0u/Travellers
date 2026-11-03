#!/usr/bin/env python3
"""Fill missing Minecraft 1.12 lang keys with a local llama.cpp server (see README.md)."""
import argparse
import fnmatch
import json
import re
import shutil
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MOD_LANG_DIR = REPO / "src/main/resources/assets/travellers/lang"
# Jars whose own item/block names are reused as glossary (official names stay consistent).
MOD_REFERENCES = [REPO / "libs/millenaire-8.1.2.jar"]

LANGUAGE_NAMES = {
    "fr_fr": "French", "de_de": "German", "es_es": "Spanish (Spain)", "it_it": "Italian",
    "pt_br": "Brazilian Portuguese", "ru_ru": "Russian", "pl_pl": "Polish", "tr_tr": "Turkish",
    "zh_cn": "Simplified Chinese", "ja_jp": "Japanese", "ko_kr": "Korean", "vi_vn": "Vietnamese",
    "id_id": "Indonesian", "th_th": "Thai", "ar_sa": "Arabic", "hi_in": "Hindi",
    "bn_bd": "Bengali", "ur_pk": "Urdu", "sw_ke": "Swahili",
}

BATCH_KEYS = 25
BATCH_CHARS = 3000
EXAMPLES = 10
# A format specifier (%s, %d, %1$s, %.1f, %%) or a Minecraft colour code.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]|§[0-9a-fk-or]")


# ---------- lang files ----------

def read_lang(path):
    """Returns (lines, values): raw lines in order, and key -> value (last one wins, like Minecraft)."""
    return parse_lang(path.read_text(encoding="utf-8")) if path.exists() else ([], {})


def parse_lang(text):
    lines, values = [], {}
    for raw in text.lstrip("\ufeff").splitlines():
        lines.append(raw)
        if raw and not raw.startswith("#") and "=" in raw:
            key, value = raw.split("=", 1)
            values[key] = value
    return lines, values


def write_lang(path, en_lines, values, extra):
    """Rebuilds the file in en_us order (its comments and blank lines), then keys en_us no longer has."""
    out, written = [], set()
    for raw in en_lines:
        if raw and not raw.startswith("#") and "=" in raw:
            key = raw.split("=", 1)[0]
            if key in values and key not in written:
                out.append(f"{key}={values[key]}")
                written.add(key)
        else:
            out.append(raw)
    if extra:
        out += ["", "# Keys no longer in en_us"] + [f"{k}={v}" for k, v in extra.items()]
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


# ---------- memory and glossary ----------

def load_memory(lang):
    path = HERE / "memory" / f"{lang}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def save_memory(lang, memory):
    path = HERE / "memory" / f"{lang}.json"
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(memory, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def load_glossary(lang):
    """Returns (style rules, terms). Lines starting with '!' are style rules for the whole language."""
    path = HERE / "glossary" / f"{lang}.tsv"
    style, terms = [], []
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            if raw.startswith("!"):
                style.append(raw[1:].strip())
            elif raw.strip() and not raw.startswith("#"):
                cols = raw.split("\t")
                terms.append((cols[0], cols[1], cols[2] if len(cols) > 2 else ""))
    return style, terms


def reference_names(jars, lang):
    """Item/block names other mods already translated themselves: (english, translation, note)."""
    names = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            files = {n.lower(): n for n in z.namelist() if "/lang/" in n and n.endswith(".lang")}
            for en_file in [n for n in files if n.endswith("/en_us.lang")]:
                tr_file = en_file[:-len("en_us.lang")] + lang + ".lang"
                if tr_file not in files:
                    continue
                _, en = parse_lang(z.read(files[en_file]).decode("utf-8", "replace"))
                _, tr = parse_lang(z.read(files[tr_file]).decode("utf-8", "replace"))
                for k, v in en.items():
                    if k.endswith(".name") and k in tr and len(v) >= 4 and v != tr[k] and "%" not in v:
                        names.setdefault(v, tr[k])
    return [(en, tr, "official name") for en, tr in names.items()]


def glossary_hits(terms, references, texts):
    joined = "\n".join(texts)
    hits = [t for t in terms if re.search(r"\b" + re.escape(t[0]) + r"\b", joined, re.IGNORECASE)]
    # Reference names are case sensitive: "Back" the item must not match "back" the word.
    hits += [t for t in references if re.search(r"\b" + re.escape(t[0]) + r"\b", joined)]
    return hits


# ---------- prompt ----------

def system_prompt(lang, style, terms, examples):
    name = LANGUAGE_NAMES.get(lang, lang)
    parts = [(HERE / "context.md").read_text(encoding="utf-8").strip(), f"""
# Task
Translate the values of the JSON object from English into {name}. The JSON keys are Minecraft lang keys:
they tell you where the text appears (button, tooltip, notification, screen title). Never translate keys.

# Rules
- Natural, concise {name} as a native game translator would write it. Buttons and titles stay short.
- Keep every placeholder exactly as written and as many times: %s, %d, %1$s, %.1f, %%, §a, §r, \\n.
  You may move a placeholder where {name} grammar needs it.
- Keep proper names (Millénaire, village and culture names) untranslated.
- Answer with the JSON object only, same keys, translated values."""]
    if style:
        parts.append("# Style for this language (mandatory)\n" + "\n".join(f"- {s}" for s in style))
    if terms:
        parts.append("# Glossary (mandatory, adapt plural and case)\n" + "\n".join(
            f"- {en} -> {tr}" + (f" ({note})" if note else "") for en, tr, note in terms))
    if examples:
        parts.append("# Existing translations of this mod (follow their style and words)\n" + "\n".join(
            f"- {en} -> {tr}" for en, tr in examples))
    return "\n\n".join(parts)


def pick_examples(batch_keys, en, human):
    """Human translations whose key shares the longest dotted prefix with the batch."""
    def shared(a, b):
        n = 0
        for x, y in zip(a.split("."), b.split(".")):
            if x != y:
                break
            n += 1
        return n
    scored = [(max(shared(k, b) for b in batch_keys), k) for k in human if k in en and len(en[k]) < 120]
    scored.sort(key=lambda s: -s[0])
    return [(en[k], human[k]) for _, k in scored[:EXAMPLES]]


# ---------- llm ----------

def ask_llm(server, system, batch):
    schema = {"type": "object", "properties": {k: {"type": "string"} for k in batch},
              "required": list(batch), "additionalProperties": False}
    body = {
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": json.dumps(batch, ensure_ascii=False, indent=1)}],
        "temperature": 0.2,
        "response_format": {"type": "json_schema", "json_schema": {"name": "lang", "schema": schema}},
    }
    req = urllib.request.Request(server.rstrip("/") + "/v1/chat/completions",
                                 data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as resp:
        content = json.loads(resp.read())["choices"][0]["message"]["content"]
    return json.loads(content)


def problem(en_text, tr):
    """Why a translation is rejected, or None if it looks sound."""
    if not isinstance(tr, str) or not tr.strip():
        return "empty"
    if "\n" in tr or "\r" in tr:
        return "real line break"
    if sorted(PLACEHOLDER.findall(en_text)) != sorted(PLACEHOLDER.findall(tr)):
        return "placeholders differ"
    if en_text.count("\\n") != tr.count("\\n"):
        return "\\n count differs"
    if len(tr) > max(3 * len(en_text), len(en_text) + 25):
        return "far too long"
    return None


def batches(keys, en):
    batch, size = [], 0
    for k in keys:
        if batch and (len(batch) >= BATCH_KEYS or size + len(en[k]) > BATCH_CHARS):
            yield batch
            batch, size = [], 0
        batch.append(k)
        size += len(en[k])
    if batch:
        yield batch


class Status:
    """One status line rewritten in place (redrawn every second so the clock moves while the model works)."""

    def __init__(self, lang, index, count, total):
        self.lang, self.index, self.count, self.total = lang, index, count, total
        self.done = self.failed = 0
        self.step = ""
        self.start = time.monotonic()
        self.live = sys.stdout.isatty()
        self.stop = threading.Event()
        if self.live:
            threading.Thread(target=self._tick, daemon=True).start()

    def update(self, done=None, failed=None, step=None):
        if done is not None:
            self.done, self.failed = done, failed
        if step is not None:
            self.step = step
        if self.live:
            self._draw()
        else:
            print(self._line(), flush=True)

    def close(self):
        self.stop.set()
        if self.live:
            self._draw()
            print()

    def _tick(self):
        while not self.stop.wait(1):
            self._draw()

    def _draw(self):
        width = shutil.get_terminal_size((100, 20)).columns
        print("\r\033[K" + self._line()[:width - 1], end="", flush=True)

    def _line(self):
        elapsed = time.monotonic() - self.start
        part = self.done / self.total if self.total else 1
        bar = "#" * int(part * 20) + "-" * (20 - int(part * 20))
        eta = f"~{clock(elapsed / part - elapsed)} left" if self.done else "estimating..."
        failed = f" | {self.failed} rejected" if self.failed else ""
        return (f"[{self.index}/{self.count}] {self.lang} [{bar}] {self.done}/{self.total} {part:4.0%}"
                f"{failed} | {clock(elapsed)} | {eta} | {self.step}")


def clock(seconds):
    m, s = divmod(int(seconds), 60)
    return f"{m // 60}h{m % 60:02d}" if m >= 60 else f"{m}:{s:02d}"


def translate(server, lang, keys, en, human, references, status, on_batch):
    """Translates keys (one retry for rejected ones); returns key -> reason for those still rejected."""
    style, terms = load_glossary(lang)
    done, failed, todo = 0, {}, list(keys)
    for attempt in (1, 2):
        failed = {}
        groups = list(batches(todo, en))
        for i, batch in enumerate(groups, 1):
            label = "retry " if attempt == 2 else ""
            status.update(step=f"{label}batch {i}/{len(groups)}: {batch[0]}")
            src = {k: en[k] for k in batch}
            system = system_prompt(lang, style, glossary_hits(terms, references, src.values()),
                                   pick_examples(batch, en, human))
            try:
                answer = ask_llm(server, system, src)
            except Exception as e:  # network, bad JSON: the whole batch goes to the retry
                answer = {}
                status.update(step=f"batch error: {e}")
            good = {}
            for k in batch:
                why = problem(en[k], answer.get(k)) if k in answer else "missing"
                if why:
                    failed[k] = why
                else:
                    good[k] = answer[k]
            on_batch(good)
            done += len(good)
            status.update(done=done, failed=len(failed))
        todo = list(failed)
        if not todo:
            break
    return failed


# ---------- commands ----------

def plan(lang, en, existing, memory, patterns, force):
    """Sorts a language's keys. Returns (human, machine, todo, stale).

    Memory entries are {"en", "tr"} for lines the tool wrote and {"en", "hand"} for hand-written lines,
    so a change of the English under either kind is noticed."""
    human = {k: v for k, v in existing.items() if memory.get(k, {}).get("tr") != v}
    machine = {k: v for k, v in existing.items() if k not in human}
    # Resume an interrupted run: memory entries for the current English are reused as they are.
    machine.update({k: m["tr"] for k, m in memory.items()
                    if "tr" in m and k in en and k not in existing and m["en"] == en[k]})
    stale = [k for k, v in human.items()
             if k in en and memory.get(k, {}).get("hand") == v and memory[k]["en"] != en[k]]
    if patterns:
        wanted = [k for k in en if any(fnmatch.fnmatchcase(k, p) for p in patterns)]
        todo = [k for k in wanted if force or k not in human]
    else:
        todo = [k for k in en if k not in human and (k not in machine or memory[k]["en"] != en[k])]
    return human, machine, todo, stale


def check_server(server):
    try:
        urllib.request.urlopen(server.rstrip("/") + "/health", timeout=5).read()
    except Exception as e:
        sys.exit(f"llama-server is not reachable at {server} ({e}). Start it, then run this again.")


def run_mod(args):
    en_lines, en = read_lang(MOD_LANG_DIR / "en_us.lang")
    patterns = [p for p in args.keys.split(",") if p] if args.keys else []
    langs = args.langs.split(",") if args.langs else sorted(p.stem for p in (HERE / "glossary").glob("*.tsv"))
    if not args.dry_run:
        check_server(args.server)
    for index, lang in enumerate(langs, 1):
        path = MOD_LANG_DIR / f"{lang}.lang"
        _, existing = read_lang(path)
        memory = load_memory(lang)
        human, machine, todo, stale = plan(lang, en, existing, memory, patterns, args.force)
        if args.limit:
            todo = todo[:args.limit]
        print(f"\n{LANGUAGE_NAMES.get(lang, lang)} ({lang}): {len(human)} by hand, "
              f"{len(machine)} by the tool, {len(todo)} to translate")
        if stale:
            print(f"  English changed under {len(stale)} hand-written line(s), left as they are "
                  f"(retranslate with --keys ... --force):")
            for k in stale:
                print(f"  - {k} = {en[k]}")
        if args.dry_run:
            for k in todo[:30]:
                print(f"  + {k} = {en[k]}")
            if len(todo) > 30:
                print(f"  + ... and {len(todo) - 30} more")
            continue

        def keep(good):
            for k, v in good.items():
                memory[k] = {"en": en[k], "tr": v}
                machine[k] = v
                human.pop(k, None)
            save_memory(lang, memory)

        failed = {}
        if todo:
            status = Status(lang, index, len(langs), len(todo))
            try:
                failed = translate(args.server, lang, todo, en, human,
                                   reference_names(MOD_REFERENCES, lang), status, keep)
            finally:
                status.close()
        # Remember the English under each hand-written line, so a later change of it is reported.
        for k, v in human.items():
            if k in en and memory.get(k, {}).get("hand") != v:
                memory[k] = {"en": en[k], "hand": v}
        save_memory(lang, memory)
        if not todo:
            continue
        values = {k: v for k, v in {**machine, **human}.items() if k in en}
        extra = {k: v for k, v in existing.items() if k not in en}
        write_lang(path, en_lines, values, extra)
        print(f"  wrote {path.name}: {len(todo) - len(failed)} translated, {len(failed)} left as before")
        for k, why in failed.items():
            print(f"  - {why}: {k} = {en[k]}")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--server", default="http://localhost:8080")
    sub = p.add_subparsers(dest="cmd", required=True)
    mod = sub.add_parser("mod", help="translate the Travellers lang files")
    mod.add_argument("--langs", help="comma-separated, e.g. fr_fr,de_de (default: every glossary/*.tsv)")
    mod.add_argument("--keys", help="only these keys, comma-separated, * wildcards allowed; "
                                    "retranslates them even if the tool already did")
    mod.add_argument("--force", action="store_true", help="with --keys: also overwrite hand-written lines")
    mod.add_argument("--limit", type=int, default=0, help="translate at most N keys per language (trial run)")
    mod.add_argument("--dry-run", action="store_true", help="show what would be translated, change nothing")
    args = p.parse_args()
    if args.force and not args.keys:
        p.error("--force needs --keys")
    run_mod(args)


if __name__ == "__main__":
    main()
