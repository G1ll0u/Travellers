#!/usr/bin/env bash
set -euo pipefail

# Normalisation/neutralisation de voix en .ogg (Minecraft-friendly)
# - Coupe-bas + EQ légère (réduit les basses / boominess)
# - (Optionnel) gate léger pour réduire le bruit/room entre les phrases
# - Compression douce pour réduire variations de volume
# - Limiter anti-pics
# - Loudness normalisation EBU R128

TARGET_I="-18"     # LUFS intégrée (Minecraft: -18 est souvent OK)
TARGET_TP="-2"     # True peak max
TARGET_LRA="11"    # Loudness Range cible

# Réglages "voix" (à ajuster selon tes fichiers)
HP="85"            # High-pass (coupe très basses) 70-110 Hz typique voix
LP="16000"         # Low-pass (coupe un peu l'air/hiss) 14k-18k typique

# EQ: petit creux dans le bas-médium si ça sonne "boîte/room"
# et réduction du grave (boominess)
# Tu peux commenter l'une des lignes si besoin.
EQ1="equalizer=f=120:t=q:w=1.0:g=-5"     # réduit basses/boom
EQ2="equalizer=f=250:t=q:w=1.2:g=-3"     # réduit bas-médium "roomy"

# Gate léger (optionnel) : réduit l'ambiance entre les mots
# Attention: trop fort => coupe des fins de phrases.
GATE="agate=threshold=0.010:ratio=6:attack=10:release=350"

# Compression douce : réduit l'écart entre sons forts/faibles
COMP="acompressor=threshold=-20dB:ratio=3:attack=10:release=120:makeup=6"

# Limiter pour éviter les clips avant loudnorm
LIMIT="alimiter=limit=0.97:level=disabled"

# Normalisation loudness (linéaire = plus simple/constant)
LOUDNORM="loudnorm=I=${TARGET_I}:TP=${TARGET_TP}:LRA=${TARGET_LRA}:linear=true"

# Chaîne finale (gate -> EQ -> comp -> limiter -> loudnorm)
AFILTER="highpass=f=${HP},lowpass=f=${LP},${EQ1},${EQ2},${COMP},${LIMIT},${LOUDNORM}"

find . -type f -iname '*.ogg' -print0 | while IFS= read -r -d '' f; do
  tmp="${f}.tmp.ogg"

  ffmpeg -hide_banner -loglevel error -y \
    -i "$f" \
    -af "$AFILTER" \
    -c:a libvorbis -q:a 6 -ac 1 \
    "$tmp" && mv -f "$tmp" "$f"

  echo "OK: $f"
done
