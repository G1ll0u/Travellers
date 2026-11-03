# translate

Fills lang keys with a local llama.cpp server. Python 3, no dependencies.

```fish
python3 tools/translate/translate.py mod --dry-run                  # what would be translated, change nothing
python3 tools/translate/translate.py mod                            # every language in glossary/
python3 tools/translate/translate.py mod --langs fr_fr              # one language
python3 tools/translate/translate.py mod --keys 'travellers.zone.*' # retranslate these keys
python3 tools/translate/translate.py mod --keys 'a.b,c.d' --force   # also overwrite hand-written lines
python3 tools/translate/translate.py --server http://host:8080 mod
```

- Source is `en_us.lang`. By default only missing keys, and keys whose English changed under a line the tool
  wrote, are translated.
- Hand-written lines are never changed without `--force`: a value differing from what the tool produced counts
  as hand-written, so fixing a machine line by hand is final. When the English under a hand-written line
  changes, the line is listed, not touched.
- The lang file is rewritten in `en_us` order; keys `en_us` no longer has go to a section at the end.
- Lines that fail the checks (placeholders, `§` codes, length) are left as before: Minecraft shows the English.
- An interrupted run resumes where it stopped.

Files:
- `context.md`: what the mod is, given to the model with every batch.
- `glossary/<lang>.tsv`: `english<TAB>translation<TAB>note`; lines starting with `!` are style rules.
  The list of languages is this folder. Item names already translated by Millénaire are added automatically.
- `memory/<lang>.json`: the English behind every line, and what the tool wrote (commit it).
