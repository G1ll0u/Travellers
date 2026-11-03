# translate

Fills missing lang keys with a local llama.cpp server. Python 3, no dependencies.

```fish
python3 tools/translate/translate.py mod --dry-run          # count what is missing
python3 tools/translate/translate.py mod --langs fr_fr      # translate (default: fr de es it pt_br ru)
python3 tools/translate/translate.py --server http://host:8080 mod
```

- Source is `en_us.lang`. Hand-written translations are never changed: a value differing from what the
  tool produced counts as hand-written, so fixing a bad machine line by hand is final.
- Keys whose English changed since the tool translated them are translated again.
- The lang file is rewritten in `en_us` order; keys `en_us` no longer has go to a section at the end.
- Lines that fail the checks (placeholders, `§` codes, length) are left out: Minecraft shows the English.

Files:
- `context.md`: what the mod is, given to the model with every batch.
- `glossary/<lang>.tsv`: `english<TAB>translation<TAB>note`; lines starting with `!` are style rules.
  Item names already translated by Millénaire are added automatically.
- `memory/<lang>.json`: what the tool produced and from which English (commit it). An interrupted run
  resumes from it; delete an entry there to have that key translated again.
