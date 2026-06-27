#!/bin/bash

# --- CONFIGURATION ---
TARGET_DIR="/home/user/temop/travellers/"
TARGET_LOUDNESS="-16"  # Volume perçu cible (LUFS)
TRUE_PEAK="-2.0"       # Crête max autorisée
THRESHOLD="-60dB"
FADE_TIME="0.05"

if ! command -v ffmpeg &> /dev/null; then
    echo "[-] Erreur : 'ffmpeg' n'est pas installé. Lance : sudo pacman -S ffmpeg"
    exit 1
fi

# Détection automatique du nombre de cœurs CPU
CORES=$(nproc)

echo "=========================================================="
echo "    ÉGALISATION MULTI-CŒURS VIA FFMPEG (TURBO)"
echo "=========================================================="
echo "[+] Dossier cible : $TARGET_DIR"
echo "[+] Puissance     : Traitement en parallèle sur $CORES cœurs"
echo "----------------------------------------------------------"

# On exporte les variables pour qu'elles soient accessibles par les sous-processus xargs
export TARGET_LOUDNESS TRUE_PEAK THRESHOLD FADE_TIME

# Définition de la fonction de traitement unitaire
core_process() {
    local FILE="$1"
    local FILENAME=$(basename "$FILE")

    # Création d'un fichier temporaire unique pour ce processus précis
    local TEMP_FFMPEG=$(mktemp /tmp/rectify_XXXXXX.ogg)

    ffmpeg -y -i "$FILE" -af \
"silenceremove=start_periods=1:start_duration=0.01:start_threshold=${THRESHOLD},\
    afade=t=in:d=${FADE_TIME},\
    areverse,\
    silenceremove=start_periods=1:start_duration=0.01:start_threshold=${THRESHOLD},\
    afade=t=in:d=${FADE_TIME},\
    areverse,\
    loudnorm=I=${TARGET_LOUDNESS}:TP=${TRUE_PEAK}:linear=true" \
    -c:a libvorbis -ac 1 "$TEMP_FFMPEG" &>/dev/null

    if [ $? -eq 0 ] && [ -s "$TEMP_FFMPEG" ]; then
        mv "$TEMP_FFMPEG" "$FILE"
        echo "[✔] Égalisé : $FILENAME"
    else
        echo "[X] Échec/Ignoré : $FILENAME"
        rm -f "$TEMP_FFMPEG" 2>/dev/null
    fi
}

# On exporte la fonction pour que xargs puisse l'appeler
export -f core_process

# LE MOTEUR : 'find' liste, 'xargs' distribue sur tous les cœurs en simultané
find "$TARGET_DIR" -type f -name "*.ogg" -print0 | xargs -0 -n 1 -P "$CORES" -I {} bash -c 'core_process "$@"' _ {}

echo "----------------------------------------------------------"
echo "[+] Terminé ! Tous les fichiers ont été injectés dans la boucle parallèle."
