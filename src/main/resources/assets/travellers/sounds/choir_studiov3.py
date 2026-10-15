#!/usr/bin/env python3
"""
Choir Studio — multitrack studio for choirs and Travellers dialogues.

Every track (stem) is exported as a mono 48 kHz .ogg. All stems share the same
length and the same t=0, in:  <base>/<lang>/<personality>/choir/<choir>/

Dependencies (Arch):
    sudo pacman -S python-pyqt6 python-numpy python-scipy python-soundfile
    python-sounddevice: AUR (yay -S python-sounddevice) or  pip install sounddevice
    ffmpeg (optional): imports formats libsndfile cannot read
"""

import sys
import os
import re
import json
import html
import math
import time
import uuid
import shutil
import difflib
import tempfile
import subprocess
import collections

try:
    import numpy as np
    import soundfile as sf
    from scipy import signal as sps
    from scipy import ndimage
    from PyQt6.QtWidgets import (
        QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
        QPushButton, QLabel, QLineEdit, QComboBox, QCheckBox, QSlider, QDoubleSpinBox,
        QSpinBox, QGroupBox, QFrame, QScrollArea, QSplitter, QMessageBox, QFileDialog,
        QProgressBar, QAbstractButton, QAbstractSpinBox, QToolButton, QMenu, QInputDialog,
        QListWidget, QListWidgetItem, QPlainTextEdit, QTextEdit, QStackedWidget, QColorDialog,
        QAbstractItemView)
    from PyQt6.QtCore import (Qt, QTimer, QThread, pyqtSignal, QSettings, QRectF, QLineF, QPointF,
                              QPoint, QEvent, QMimeData, QSize, QByteArray)
    from PyQt6.QtGui import (QPainter, QColor, QPen, QKeySequence, QShortcut, QPixmap, QFont,
                             QFontMetrics, QPalette, QPolygonF, QIcon)
except ImportError as e:
    print(f"Missing dependency: {e.name}\n"
          "  sudo pacman -S python-pyqt6 python-numpy python-scipy python-soundfile\n"
          "  + python-sounddevice (AUR) or: pip install sounddevice")
    sys.exit(1)

SR = 48000
APP_ORG, APP_NAME = "Travellers", "ChoirStudio"
DEFAULT_WORK_DIR = os.path.join(os.path.expanduser("~"), ".local", "share", "ChoirStudio", "projects")
PROJECT_VERSION = 4
PEAK_BIN = 64
COLORS = ["#e8a33d", "#4fa3e0", "#6cc070", "#d9667a", "#a77fdc", "#4cc9c0",
          "#e07a3f", "#c9c34c", "#7f9cdc", "#dc7fc4"]
NOTE_COLORS = [("Red", "#e53935"), ("Orange", "#fb8c00"), ("Yellow", "#fdd835"), ("Green", "#43a047"),
               ("Teal", "#00acc1"), ("Blue", "#1e88e5"), ("Purple", "#8e24aa"), ("Pink", "#d81b60")]
TRASH_MIME = "application/x-choirstudio-trash"

# PipeWire latency: must be set before PortAudio is initialised
_boot = QSettings(APP_ORG, APP_NAME)
os.environ.setdefault("PIPEWIRE_LATENCY", f"{int(_boot.value('blocksize', 128))}/{SR}")
try:
    import sounddevice as sd
except (ImportError, OSError) as e:
    print(f"sounddevice unavailable: {e}\n  yay -S python-sounddevice   or   pip install sounddevice")
    sys.exit(1)


# ═══════════════════════════════════════════════════════════════ utilities

def fmt_time(samples):
    sign = "-" if samples < 0 else ""
    s = abs(samples) / SR
    return f"{sign}{int(s // 60)}:{s % 60:06.3f}"


def mc_name(text):
    """Valid name for a Minecraft ResourceLocation (lowercase, a-z0-9_.-)."""
    t = text.strip().lower().replace(" ", "_")
    return re.sub(r"[^a-z0-9_.\-]", "", t)


def load_audio_file(path):
    """Loads any audio file as mono float32 at 48 kHz."""
    try:
        data, sr = sf.read(path, dtype="float32", always_2d=True)
    except Exception:
        ff = shutil.which("ffmpeg")
        if not ff:
            raise
        with tempfile.TemporaryDirectory() as td:
            tmp = os.path.join(td, "conv.wav")
            subprocess.run([ff, "-v", "error", "-y", "-i", path, "-ac", "1",
                            "-ar", str(SR), "-c:a", "pcm_f32le", tmp], check=True)
            data, sr = sf.read(tmp, dtype="float32", always_2d=True)
    x = data.mean(axis=1)
    if int(sr) != SR:
        g = math.gcd(int(sr), SR)
        x = sps.resample_poly(x, SR // g, int(sr) // g)
    return np.ascontiguousarray(x, dtype=np.float32)


def compute_peaks(x):
    n = max(1, int(math.ceil(len(x) / PEAK_BIN)))
    pad = np.zeros(n * PEAK_BIN, np.float32)
    pad[:len(x)] = x
    r = pad.reshape(n, PEAK_BIN)
    return r.min(axis=1), r.max(axis=1)


def make_click(freq=1500.0, ms=5.0, amp=0.6):
    n = int(SR * ms / 1000)
    t = np.arange(n) / SR
    return (amp * np.sin(2 * np.pi * freq * t) * np.hanning(n)).astype(np.float32)


# ═══════════════════════════════════════════════════════════════ theme

def blend(c1, c2, t):
    """Mix of two QColors: t = weight of c1 (1.0 = c1 only)."""
    return QColor(int(c1.red() * t + c2.red() * (1 - t)),
                  int(c1.green() * t + c2.green() * (1 - t)),
                  int(c1.blue() * t + c2.blue() * (1 - t)))


def track_qcolor(idx, dark):
    """Track colour, darkened on light themes so waveforms keep their contrast."""
    c = QColor(COLORS[idx % len(COLORS)])
    return c if dark else c.darker(140)


def note_qcolor(n, default):
    """Colour chosen for a note, or the theme highlight."""
    return QColor(n["color"]) if n.get("color") else QColor(default)


def color_icon(hex_color, size=14):
    pm = QPixmap(size, size)
    pm.fill(QColor(hex_color))
    return QIcon(pm)


# ═══════════════════════════════════════════════════════════════ processing

FRAME_S = 0.010


def _frame_db(x):
    win = int(SR * FRAME_S)
    n = max(1, int(math.ceil(len(x) / win)))
    pad = np.zeros(n * win, np.float32)
    pad[:len(x)] = x
    rms = np.sqrt(np.mean(pad.reshape(n, win) ** 2, axis=1) + 1e-12)
    return 20 * np.log10(rms), win


def gate_curve(x, sens_db):
    """Offline noise gate: true silence between phrases, early opening,
    250 ms hold, smoothed transitions (word endings are never chopped)."""
    db, win = _frame_db(x)
    if len(db) < 5:
        return np.ones(len(x), np.float32), np.ones(len(db), bool)
    noise = np.percentile(db, 10)
    loud = np.percentile(db, 99.5)
    # threshold above the noise floor, but never high enough to cut soft singing
    thr = max(noise + 3.0, min(noise + sens_db, loud - 15.0))
    opened = db > thr
    opened = ndimage.binary_opening(opened, structure=np.ones(3, bool))  # ignores spikes < 30 ms
    active = opened.copy()
    hold, look = int(0.25 / FRAME_S), int(0.03 / FRAME_S)
    o = opened.copy()
    for i in range(1, hold + 1):
        o[i:] |= opened[:-i]
    for i in range(1, look + 1):
        o[:-i] |= opened[i:]
    target = np.where(o, 1.0, 10 ** (-80 / 20))
    k = np.hanning(7)
    k /= k.sum()
    target = np.convolve(target, k, mode="same")
    centers = (np.arange(len(target)) + 0.5) * win
    g = np.interp(np.arange(len(x)), centers, target).astype(np.float32)
    return g, active


MIN_TRIM = int(0.05 * SR)


def detect_voice_range(x, sens_db=10.0, min_ms=80, pre_ms=60, post_ms=200):
    """Start/end of the voice in a take. Ignores short noises (key press, click, bump)
    thanks to a minimum activity duration. Returns (a, b) in samples."""
    n = len(x)
    db, win = _frame_db(x)
    if len(db) < 5:
        return 0, n
    noise = np.percentile(db, 10)
    loud = np.percentile(db, 99.5)
    thr = max(noise + 3.0, min(noise + sens_db, loud - 15.0))
    act = db > thr
    k = max(1, int(round(min_ms / 1000 / FRAME_S)))
    act = ndimage.binary_opening(act, structure=np.ones(k, bool))
    idx = np.flatnonzero(act)
    if not len(idx):
        return 0, n
    a = max(0, idx[0] * win - int(pre_ms * SR / 1000))
    b = min(n, (idx[-1] + 1) * win + int(post_ms * SR / 1000))
    if b - a < MIN_TRIM:
        return 0, n
    return int(a), int(b)


def trim_mask(n, trim):
    """0/1 mask with 5 ms fades at the cut points."""
    a, b = trim
    m = np.zeros(n, np.float32)
    m[a:b] = 1.0
    f = min(int(0.005 * SR), (b - a) // 2)
    if f > 0:
        r = np.linspace(0, 1, f, dtype=np.float32)
        if a > 0:
            m[a:a + f] = r
        if b < n:
            m[b - f:b] = r[::-1]
    return m


def is_full(trim, n):
    return trim is None or (trim[0] <= 0 and trim[1] >= n)


def process_take(x, p, gain_db=0.0, trim=None):
    y = x.astype(np.float32)
    if len(y) == 0:
        return y
    if p["hp"] > 0:
        sos = sps.butter(2, p["hp"], "highpass", fs=SR, output="sos")
        y = sps.sosfilt(sos, y).astype(np.float32)
    # the gate measures the noise floor on the whole take (trimmed zones included)
    g, active = (gate_curve(y, p["sens"]) if p["gate"] else (None, None))
    db, win = _frame_db(y)
    inside = np.ones(len(db), bool)
    if not is_full(trim, len(y)):
        m = trim_mask(len(y), trim)
        g = m if g is None else g * m
        inside[:trim[0] // win] = False
        inside[int(math.ceil(trim[1] / win)):] = False
        if active is not None:
            active = active & inside
    ceil = 10 ** (p["ceiling"] / 20)
    if p["mode"] == "rms":
        if active is not None and active.any():
            sel = db[active]
        else:
            d_in = db[inside] if inside.any() else db
            sel = d_in[d_in > d_in.max() - 40]
        level = 10 * np.log10(np.mean(10 ** (sel / 10)) + 1e-12)
        gain = 10 ** ((p["target"] - level) / 20)
    else:
        ref = y * g if g is not None else y
        gain = ceil / (np.max(np.abs(ref)) + 1e-9)
    out = y * (gain * 10 ** (gain_db / 20))
    if g is not None:
        out *= g
    peak = float(np.max(np.abs(out)))
    if peak > ceil:
        out *= ceil / peak
    return out.astype(np.float32)


def render_export(items, p, song_range=None):
    """items: [(name, audio, offset, gain_db, trim)] → [(name, final_audio)], same length, same t=0.
    song_range: (start, end) on the timeline, or None = silences trimmed automatically."""
    proc = [(n, process_take(x, p, g, tr), off) for n, x, off, g, tr in items]
    start = min(off for _, _, off in proc)
    end = max(off + len(y) for _, y, off in proc)
    L = end - start
    tl = []
    for n, y, off in proc:
        a = np.zeros(L, np.float32)
        s = off - start
        a[s:s + len(y)] = y
        tl.append((n, a))
    # trim SHARED by all stems (keeps them aligned)
    thr = 10 ** (-60 / 20)
    firsts, lasts = [], []
    for _, a in tl:
        idx = np.flatnonzero(np.abs(a) > thr)
        if len(idx):
            firsts.append(idx[0])
            lasts.append(idx[-1])
    if song_range is not None:
        s0 = max(0, int(song_range[0]) - start)
        e0 = min(L, int(song_range[1]) - start)
        if e0 - s0 < MIN_TRIM:
            s0, e0 = 0, L
    elif firsts:
        s0 = max(0, min(firsts) - int(0.05 * SR))
        e0 = min(L, max(lasts) + int(0.15 * SR))
    else:
        s0, e0 = 0, L
    fade = int(0.005 * SR)
    ramp = np.linspace(0, 1, fade, dtype=np.float32)
    res = []
    for n, a in tl:
        b = a[s0:e0].copy()
        if len(b) > 2 * fade:
            b[:fade] *= ramp
            b[-fade:] *= ramp[::-1]
        res.append((n, b))
    return res


# ═══════════════════════════════════════════════════════════════ takes & punch-ins
#
# A take is a stack of "passes" (one WAV file each, never modified):
#   take = {"offset": timeline sample of the take's origin,
#           "trim": [a, b] relative to the origin, or None,
#           "passes": [{"file", "rel", "in", "out"}, ...]}
#   rel  = where the pass audio starts, relative to the origin (may be < 0: pre-roll)
#   in   = punch-in seam (None = the pass is audible from its first sample)
#   out  = punch-out seam (None = audible until its last sample)
# The most recent pass wins between its seams; outside them the older passes show
# through. Seams are equal-power crossfades. Nothing is ever shifted in time.

SEAM_HOP = int(0.005 * SR)      # energy analysis step when looking for a quiet seam
SNAP_IN = int(0.2 * SR)         # punch-in may move ±200 ms to a quiet spot
SNAP_OUT = int(1.0 * SR)        # punch-out may move up to 1 s back to a quiet spot
MIN_PASS = int(0.1 * SR)        # a pass must bring at least this much audio past its punch-in
MIN_TAKE = int(0.3 * SR)
LAP_TAIL = int(0.3 * SR)        # loop recording: each lap runs a bit past the loop end


def migrate_take(tk):
    """project.json v2 → v3: a take with a single file becomes a take with a single pass.
    v3 → v4: every take gets an id (so a punch in the trash knows where it came from)."""
    if "passes" not in tk:
        tk["passes"] = [{"file": tk.pop("file"), "rel": 0, "in": None, "out": None}]
    tk.setdefault("offset", 0)
    tk.setdefault("id", uuid.uuid4().hex[:8])
    for p in tk["passes"]:
        p.setdefault("rel", 0)
        p.setdefault("in", None)
        p.setdefault("out", None)
    return tk


def take_key(tk, xfade):
    """Cache key of a take's composite audio (trim and gain are not part of it)."""
    return (int(xfade),) + tuple((p["file"], int(p["rel"]), p.get("in"), p.get("out"))
                                 for p in tk["passes"])


def pass_edges(p, n, h):
    """(ext_start, ext_end, seam_in, seam_out) of a pass, take-relative.
    Seams are clamped so the crossfade ramps always fit inside the pass audio."""
    rel = int(p["rel"])
    ext_s, ext_e = max(0, rel), rel + n
    if ext_e - ext_s < 2 * h + 2:           # too short for a crossfade: hard edges
        return ext_s, ext_e, None, None
    ci = co = None
    if p.get("in") is not None:
        ci = int(min(max(int(p["in"]), ext_s + h), ext_e - h))
    if p.get("out") is not None:
        co = int(min(max(int(p["out"]), ext_s + h), ext_e - h))
        if ci is not None and co < ci:
            co = ci
    return ext_s, ext_e, ci, co


def pass_span(p, n, h):
    """[start, end) where the pass is audible (crossfade ramps included), take-relative."""
    ext_s, ext_e, ci, co = pass_edges(p, n, h)
    s = ext_s if ci is None else ci - h
    e = ext_e if co is None else co + h
    return max(0, s), max(0, e)


def compose_take(tk, audio_of, xfade):
    """Renders the audible result of a take's passes (take-relative, starts at the origin)."""
    passes = tk["passes"]
    h = max(0, int(xfade) // 2)
    if len(passes) == 1 and int(passes[0]["rel"]) == 0 and passes[0].get("in") is None \
            and passes[0].get("out") is None:
        return audio_of(passes[0]["file"])
    spans, end = [], 0
    for p in passes:
        x = audio_of(p["file"])
        s, e = pass_span(p, len(x), h)
        spans.append((x, s, e))
        end = max(end, e)
    y = np.zeros(end, np.float32)
    cov_end = 0                              # how far the older passes already reach
    for p, (x, s, e) in zip(passes, spans):
        if e <= s:
            continue
        rel = int(p["rel"])
        _, ext_e, ci, co = pass_edges(p, len(x), h)
        if co is None and h > 0 and ext_e < cov_end and e - s >= 2 * h:
            co = ext_e - h                   # ends over older audio: never a hard cut
        w = np.ones(e - s, np.float32)
        if h > 0 and ci is not None:
            w[:2 * h] = np.linspace(0.0, 1.0, 2 * h, dtype=np.float32)
        if h > 0 and co is not None:
            w[-2 * h:] = np.minimum(w[-2 * h:], np.linspace(1.0, 0.0, 2 * h, dtype=np.float32))
        th = w * np.float32(np.pi / 2)
        seg = x[s - rel:e - rel]
        out = seg.copy()
        ramp = w < 1.0                       # blend only inside the crossfades: exact elsewhere
        if ramp.any():
            out[ramp] = y[s:e][ramp] * np.cos(th[ramp]) + seg[ramp] * np.sin(th[ramp])   # equal power
        y[s:e] = out
        cov_end = max(cov_end, e)
    return y


def seg_at(x, x_start, lo, hi):
    """Samples [lo, hi) of a signal starting at x_start, zero-padded outside of it."""
    out = np.zeros(max(0, hi - lo), np.float32)
    a, b = max(lo, x_start), min(hi, x_start + len(x))
    if b > a:
        out[a - lo:b - lo] = x[a - x_start:b - x_start]
    return out


def find_seam(old, new, new_rel, lo, hi, target):
    """Quietest spot in [lo, hi) for a seam between `old` (starts at 0) and `new`
    (starts at new_rel). Ties go to the spot closest to `target`."""
    lo, hi = int(lo), int(hi)
    if hi - lo < 3 * SEAM_HOP:
        return int(min(max(target, lo), hi))
    e = seg_at(old, 0, lo, hi) ** 2 + seg_at(new, new_rel, lo, hi) ** 2
    n = len(e) // SEAM_HOP
    fr = e[:n * SEAM_HOP].reshape(n, SEAM_HOP).mean(axis=1)
    fr = np.convolve(fr, np.ones(3) / 3, mode="same")        # quiet over ~15 ms, not one sample
    centers = lo + (np.arange(n) + 0.5) * SEAM_HOP
    dist = np.abs(centers - target) / max(1, hi - lo)
    cost = fr / (fr.max() + 1e-12) + 0.15 * dist
    return int(centers[int(np.argmin(cost))])


def add_pass(tk, fname, x, offset_abs, punch_abs, old, xfade, snap):
    """Stacks a punch-in pass on a take. offset_abs: timeline position of x[0];
    punch_abs: where the new audio takes over; old: current composite of the take.
    Returns the new pass, or None if it brings nothing past its punch-in."""
    h = max(0, int(xfade) // 2)
    origin = int(tk["offset"])
    rel = int(offset_abs) - origin
    n = len(x)
    want = max(0, int(punch_abs) - origin)
    if rel + n - want < MIN_PASS:
        return None
    lo_lim, hi_lim = max(0, rel) + h, rel + n - h
    if hi_lim - lo_lim < 2:
        return None
    pin = min(max(want, lo_lim), hi_lim)
    if snap:
        lo, hi = max(lo_lim, pin - SNAP_IN), min(hi_lim, pin + SNAP_IN)
        pin = find_seam(old, x, rel, lo, hi, pin)
    pin = int(min(max(pin, lo_lim), hi_lim))
    p = {"file": fname, "rel": rel, "in": pin, "out": None}
    tk["passes"].append(p)
    return p


def close_pass(tk, p, audio_of, xfade, snap):
    """When recording stops in the middle of older audio, sets the punch-out of pass p
    so the older audio comes back (crossfaded, snapped to a quiet spot). Returns True if set."""
    h = max(0, int(xfade) // 2)
    idx = next((i for i, q in enumerate(tk["passes"]) if q is p), None)
    if not idx:                       # unknown, or first pass: nothing older underneath
        return False
    older = tk["passes"][:idx]
    x = audio_of(p["file"])
    rel = int(p["rel"])
    end = rel + len(x)
    later = max(pass_span(q, len(audio_of(q["file"])), h)[1] for q in older)
    if later <= end - h:              # no older audio after this pass: keep its tail
        return False
    start = int(p["in"]) if p.get("in") is not None else max(0, rel)
    lo = max(start + 2 * h + SEAM_HOP, end - SNAP_OUT)
    hi = end - h
    if hi <= lo:
        return False
    out = hi
    if snap:
        old = compose_take({"passes": older}, audio_of, xfade)
        out = find_seam(old, x, rel, lo, hi, hi)
    p["out"] = int(min(max(out, lo), hi))
    return True


def extend_trim(tk, span, detect=None):
    """After a punch: widens the take's trim so the new audio isn't hidden.
    span: take-relative region of the new pass; detect: voice range of the new composite."""
    tr = tk.get("trim")
    if not tr:
        return
    a, b = int(tr[0]), int(tr[1])
    s, e = span
    na, nb = a, b
    if s < a:
        na = s if detect is None else min(a, max(s, detect[0]))
    if e > b:
        nb = e if detect is None else max(b, min(e, detect[1]))
    tk["trim"] = [int(na), int(nb)]


# ═══════════════════════════════════════════════════════════════ notes

def note_at(notes, pos):
    """(current, next) note at a timeline position. A zone is current while inside it;
    a point note stays current until the next note starts."""
    cur = nxt = None
    for n in sorted(notes, key=lambda n: n["t"]):
        if n["t"] <= pos:
            cur = n if (n.get("end") is None or pos < n["end"]) else None
        else:
            nxt = n
            break
    return cur, nxt


# ═══════════════════════════════════════════════════════════════ lyrics
#
# lyrics = [{"text": str, "t": timeline sample where the line starts, or None (not synced yet)}]
# Lines keep the order of the text; times are set by tapping Enter, dragging, or a .lrc file.

_LRC_TAG = re.compile(r"\[(\d+):(\d{1,2}(?:[.:]\d{1,3})?)\]")


def read_text_file(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    for enc in ("utf-8-sig", "cp1252", "latin-1"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def text_to_lines(text):
    """Plain text → lyric lines (empty lines ignored)."""
    return [ln.strip() for ln in text.splitlines() if ln.strip()]


def parse_lrc(text):
    """.lrc → [(seconds, text)] sorted by time. Handles several timestamps per line,
    [offset:±ms], metadata tags and enhanced <mm:ss.xx> word timings."""
    offset_ms = 0
    out = []
    for raw in text.splitlines():
        m = re.match(r"\s*\[offset:\s*([+-]?\d+)\s*\]", raw, re.I)
        if m:
            offset_ms = int(m.group(1))
            continue
        tags = _LRC_TAG.findall(raw)
        body = re.sub(r"\[[^\]]*\]", "", raw)
        body = re.sub(r"<\d+:\d{1,2}(?:[.:]\d{1,3})?>", "", body).strip()
        if not tags or not body:
            continue
        for mm, ss in tags:
            out.append((int(mm) * 60 + float(ss.replace(":", ".")) - offset_ms / 1000.0, body))
    out.sort(key=lambda x: x[0])
    return out


def to_lrc(lines, origin=0, title=""):
    """Lyric lines → .lrc text. Times are relative to `origin` (timeline sample of the master start)."""
    out = ["[re:Choir Studio]"] + ([f"[ti:{title}]"] if title else [])
    for ln in lines:
        if ln["t"] is None:
            continue
        s = max(0.0, (ln["t"] - origin) / SR)
        cs = int(round(s * 100))
        out.append(f"[{cs // 6000:02d}:{(cs // 100) % 60:02d}.{cs % 100:02d}]{ln['text']}")
    return "\n".join(out) + "\n"


def remap_lyrics(old, new_texts):
    """Edited lyrics keep their timings: unchanged lines, and lines corrected in place
    (same number of lines replaced), keep their time; new lines are unsynced."""
    old_texts = [ln["text"] for ln in old]
    res = [{"text": t, "t": None} for t in new_texts]
    sm = difflib.SequenceMatcher(None, old_texts, new_texts, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal" or (tag == "replace" and i2 - i1 == j2 - j1):
            for k in range(i2 - i1):
                res[j1 + k]["t"] = old[i1 + k]["t"]
    return res


def lyric_view(lines, pos, lead=0, ahead=1):
    """What the prompter shows at a timeline position. A line becomes current `lead` samples
    before its time (reading comfort + tapping reaction time)."""
    p = pos + lead
    cur = -1
    for i, ln in enumerate(lines):
        if ln["t"] is not None and ln["t"] <= p:
            cur = i
    nxt = list(range(cur + 1, min(len(lines), cur + 1 + ahead)))
    frac = until = None
    if nxt and lines[nxt[0]]["t"] is not None:
        t1 = lines[nxt[0]]["t"]
        t0 = lines[cur]["t"] if cur >= 0 else t1 - 5 * SR
        if t0 is not None and t1 > t0:
            frac = min(1.0, max(0.0, (p - t0) / (t1 - t0)))
        until = max(0.0, (t1 - p) / SR)
    return {"prev": cur - 1 if cur >= 1 else None, "cur": cur, "next": nxt, "frac": frac, "until": until}


# ═══════════════════════════════════════════════════════════════ audio engine

class AudioEngine:
    """A single PortAudio duplex stream: playback and capture happen in the same callback,
    so what you hear and what you record are locked to the sample."""

    MON_FADE = int(0.008 * SR)      # headphone fade when a track stops at its punch point

    def __init__(self):
        self.stream = None
        self.ok = False
        self.error = ""
        self.out_ch = 2
        self.default_latency = 0
        self.mix = []      # [(data, offset, gain_l, gain_r, end)]  end: timeline sample where it goes silent
        self.extra = []    # clicks (count-in, calibration), same format
        self.master_gain = 0.8
        self.cmds = collections.deque()
        self.pos = 0
        self.playing = False
        self.recording = False
        self.stop_at = None
        self.loop = None
        self.gen = 0
        self.ended_gen = -1
        self.rec_blocks = []
        self.rec_start = 0
        self.rec_xruns = 0
        self.finished = collections.deque()     # finished captures, in order: (gen, start, blocks, xruns)
        self.in_peak = 0.0

    def start_stream(self, in_dev, out_dev, blocksize):
        self.close()
        try:
            dout = sd.query_devices(out_dev, "output")
            self.out_ch = 2 if dout["max_output_channels"] >= 2 else 1
            self.stream = sd.Stream(samplerate=SR, blocksize=blocksize, dtype="float32",
                                    channels=(1, self.out_ch), device=(in_dev, out_dev),
                                    latency="low", callback=self._cb)
            self.stream.start()
            li, lo = self.stream.latency
            self.default_latency = int(round((li + lo) * SR))
            self.ok, self.error = True, ""
        except Exception as e:
            self.stream, self.ok, self.error = None, False, str(e)

    def close(self):
        if self.stream is not None:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
        self.stream = None
        self.ok = False
        self.playing = self.recording = False

    # --- commands (GUI thread) ---
    def play(self, gen, pos, record=False, stop_at=None, loop=None):
        self.cmds.append(("play", gen, int(pos), record, stop_at, loop))

    def stop(self):
        self.cmds.append(("stop",))

    def set_loop(self, loop):
        self.cmds.append(("loop", loop))

    def take_peak(self):
        p, self.in_peak = self.in_peak, 0.0
        return p

    # --- audio thread ---
    def _apply(self, c):
        if c[0] == "play":
            if self.playing:
                self._finish()          # never lose a capture, even without an explicit stop
            _, gen, pos, rec, stop_at, loop = c
            self.gen, self.pos, self.stop_at, self.loop = gen, pos, stop_at, loop
            self.rec_blocks, self.rec_start, self.rec_xruns = [], pos, 0
            self.recording, self.playing = rec, True
        elif c[0] == "stop":
            self._finish()
        elif c[0] == "loop":
            self.loop = c[1]

    def _finish(self):
        if self.recording:
            self.finished.append((self.gen, self.rec_start, self.rec_blocks, self.rec_xruns))
            self.rec_blocks = []
        self.recording = self.playing = False
        self.stop_at = None
        self.ended_gen = self.gen

    def _cb(self, indata, outdata, frames, time_info, status):
        while self.cmds:
            self._apply(self.cmds.popleft())
        outdata.fill(0.0)
        x = indata[:, 0]
        pk = float(np.max(np.abs(x))) if frames else 0.0
        if pk > self.in_peak:
            self.in_peak = pk
        if not self.playing:
            return
        if self.recording and getattr(status, "input_overflow", False):
            self.rec_xruns += 1         # lost input samples: this pass may be out of sync
        p = self.pos
        L = outdata[:, 0]
        R = outdata[:, 1] if self.out_ch > 1 else None
        for lst in (self.mix, self.extra):
            for data, off, gl, gr, end in lst:
                n = len(data)
                lim = n if end is None else min(n, end - off)
                s = p - off
                if s >= lim or s + frames <= 0:
                    continue
                a, b = max(s, 0), min(s + frames, lim)
                seg = data[a:b]
                if end is not None and b > lim - self.MON_FADE:
                    k = np.arange(a, b)
                    seg = seg * np.clip((lim - k) / self.MON_FADE, 0.0, 1.0).astype(np.float32)
                o = a - s
                if R is None:
                    L[o:o + len(seg)] += seg * ((gl + gr) * 0.5)
                else:
                    L[o:o + len(seg)] += seg * gl
                    R[o:o + len(seg)] += seg * gr
        outdata *= self.master_gain
        np.clip(outdata, -1.0, 1.0, out=outdata)
        if self.recording:
            self.rec_blocks.append(x.copy())
        self.pos = p + frames
        if self.loop is not None and self.pos >= self.loop[1]:
            if self.recording:          # loop recording: every lap becomes its own capture
                self.finished.append((self.gen, self.rec_start, self.rec_blocks, self.rec_xruns))
                self.rec_blocks, self.rec_xruns = [], 0
                self.rec_start = self.loop[0]
            self.pos = self.loop[0]
        if self.stop_at is not None and self.pos >= self.stop_at:
            self._finish()


# ═══════════════════════════════════════════════════════════════ export (thread)

class ExportWorker(QThread):
    done = pyqtSignal(list, float)
    failed = pyqtSignal(str)

    def __init__(self, items, params, out_dir, prefix, old_files, song_range=None):
        super().__init__()
        self.items, self.params, self.out_dir = items, params, out_dir
        self.prefix, self.old_files, self.song_range = prefix, old_files, song_range

    def run(self):
        try:
            rendered = render_export(self.items, self.params, self.song_range)
            files = []
            for i, (name, y) in enumerate(rendered, 1):
                fn = f"{self.prefix}_{i:02d}.ogg"
                tmp = os.path.join(self.out_dir, f".{fn}.tmp")
                with sf.SoundFile(tmp, "w", SR, 1, format="OGG", subtype="VORBIS") as f:
                    for k in range(0, len(y), 65536):
                        f.write(y[k:k + 65536])
                os.replace(tmp, os.path.join(self.out_dir, fn))
                files.append(fn)
            for f in self.old_files:
                if f not in files:
                    p = os.path.join(self.out_dir, f)
                    if os.path.exists(p):
                        os.remove(p)
            dur = len(rendered[0][1]) / SR if rendered else 0.0
            self.done.emit(files, dur)
        except Exception as e:
            self.failed.emit(str(e))


# ═══════════════════════════════════════════════════════════════ widgets

def _paths(md):
    return [u.toLocalFile() for u in md.urls() if u.isLocalFile()]


def _trash_id(md):
    """Trash item id carried by a drag from the trash window, or None."""
    if md.hasFormat(TRASH_MIME):
        return bytes(md.data(TRASH_MIME)).decode()
    return None


def _accepts(md):
    return md.hasUrls() or md.hasFormat(TRASH_MIME)


class DropLabel(QLabel):
    def __init__(self, text, callback):
        super().__init__(text)
        self.cb = callback
        self.setAcceptDrops(True)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setMinimumHeight(34)
        self._style(False)

    def _style(self, hot):
        pal = self.palette()
        fg, bg = pal.color(QPalette.ColorRole.WindowText), pal.color(QPalette.ColorRole.Window)
        c = pal.color(QPalette.ColorRole.Highlight) if hot else blend(fg, bg, 0.72)
        self.setStyleSheet(f"border:2px dashed {c.name()}; border-radius:6px; padding:4px; color:{c.name()};")

    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()
            self._style(True)

    def dragLeaveEvent(self, e):
        self._style(False)

    def dropEvent(self, e):
        self._style(False)
        self.cb(_paths(e.mimeData()))


class DropArea(QWidget):
    def __init__(self, callback, studio=None):
        super().__init__()
        self.cb = callback
        self.studio = studio
        self.setAcceptDrops(True)

    def dragEnterEvent(self, e):
        if _accepts(e.mimeData()):
            e.acceptProposedAction()

    def dropEvent(self, e):
        tid = _trash_id(e.mimeData())
        if tid and self.studio:
            self.studio.restore_trash(tid)
        else:
            self.cb(_paths(e.mimeData()))


class TrackRow(QFrame):
    def __init__(self, studio, t, color):
        super().__init__()
        self.studio, self.tid, self.color = studio, t["id"], color
        self.setObjectName("row")
        self.setAcceptDrops(True)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(6, 3, 6, 3)
        lay.setSpacing(5)
        tid = self.tid

        sw = QLabel()
        sw.setFixedSize(8, 26)
        sw.setStyleSheet(f"background:{color}; border-radius:3px;")
        lay.addWidget(sw)

        inc = QCheckBox()
        inc.setChecked(t["enabled"])
        inc.setToolTip("Include this track (playback + export)")
        inc.toggled.connect(lambda v: studio.set_field(tid, "enabled", v))
        lay.addWidget(inc)

        star = QPushButton("★" if t["master"] else "☆")
        star.setFixedWidth(30)
        star.setToolTip("Master (lead voice)" if t["master"] else "Make this the master")
        if t["master"]:
            star.setStyleSheet(f"color:{color}; font-weight:bold;")
        star.clicked.connect(lambda: studio.set_master(tid))
        lay.addWidget(star)

        name = QLineEdit(t["name"])
        name.setFixedWidth(140)
        name.editingFinished.connect(lambda: studio.rename(tid, name.text()))
        name.returnPressed.connect(name.clearFocus)
        lay.addWidget(name)

        takes = QComboBox()
        for i, tk in enumerate(t["takes"]):
            np_ = len(tk["passes"]) - 1
            takes.addItem(f"Take {i + 1}" + (f"  ({np_} punch{'es' if np_ > 1 else ''})" if np_ else ""))
        takes.setCurrentIndex(t["active"])
        takes.setToolTip("Takes of this track (T = new take, P = punch in)")
        takes.currentIndexChanged.connect(lambda i: studio.set_active_take(tid, i))
        lay.addWidget(takes)

        for key, label, tip in (("mute", "M", "Mute (listening only, still exported)"),
                                ("solo", "S", "Solo")):
            b = QPushButton(label)
            b.setObjectName(key)
            b.setCheckable(True)
            b.setChecked(t[key])
            b.setFixedWidth(28)
            b.setToolTip(tip)
            b.toggled.connect(lambda v, k=key: studio.set_field(tid, k, v))
            lay.addWidget(b)

        lay.addWidget(QLabel("🎧"))
        vol = QSlider(Qt.Orientation.Horizontal)
        vol.setRange(0, 150)
        vol.setValue(int(t["vol"] * 100))
        vol.setFixedWidth(80)
        vol.setToolTip("Listening volume (does not affect the export)")
        vol.valueChanged.connect(lambda v: studio.set_field(tid, "vol", v / 100))
        lay.addWidget(vol)

        gain = QDoubleSpinBox()
        gain.setRange(-24, 12)
        gain.setSingleStep(0.5)
        gain.setSuffix(" dB")
        gain.setValue(t["gain_db"])
        gain.setToolTip("Export gain, applied after normalisation")
        gain.valueChanged.connect(lambda v: studio.set_field(tid, "gain_db", v))
        gain.editingFinished.connect(gain.clearFocus)
        lay.addWidget(gain)

        left = QPushButton("◀")
        left.setFixedWidth(26)
        left.setToolTip("Shift by -5 ms  (←, Shift+← = 1 ms)")
        left.clicked.connect(lambda: studio.nudge(tid, -5))
        self.off_lbl = QLabel()
        self.off_lbl.setFixedWidth(80)
        self.off_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        right = QPushButton("▶")
        right.setFixedWidth(26)
        right.setToolTip("Shift by +5 ms  (→, Shift+→ = 1 ms)")
        right.clicked.connect(lambda: studio.nudge(tid, 5))
        lay.addWidget(left)
        lay.addWidget(self.off_lbl)
        lay.addWidget(right)
        tk = t["takes"][t["active"]]
        self.update_offset(tk["offset"])

        al = QPushButton("⇄")
        al.setFixedWidth(30)
        al.setToolTip("Auto-align to the master (±300 ms, experimental)")
        al.setEnabled(not t["master"])
        al.clicked.connect(lambda: studio.auto_align(tid))
        lay.addWidget(al)

        ct = QPushButton("✂")
        ct.setFixedWidth(30)
        ct.setToolTip("Automatically cut silences and noises at the start and end\n"
                      "(or double-click a trim handle in the timeline)")
        ct.clicked.connect(lambda: studio.auto_trim(tid))
        lay.addWidget(ct)

        lay.addStretch(1)
        dl = QPushButton("✕")
        dl.setFixedWidth(30)
        dl.setToolTip("Delete the track — or, if it has several takes, choose between\n"
                      "the displayed take and the whole track (Ctrl+Z to undo)")
        dl.clicked.connect(lambda: studio.delete_clicked(tid, dl))
        lay.addWidget(dl)

        for w in self.findChildren(QAbstractButton) + self.findChildren(QSlider):
            w.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        if not t["enabled"]:
            self.setStyleSheet(f"QLabel, QLineEdit {{ color:{studio.c_muted}; }}")

    def set_selected(self, sel):
        c = self.color if sel else "transparent"
        self.setStyleSheet(self.styleSheet().split("#row")[0] +
                           f"#row {{ border:2px solid {c}; border-radius:5px; }}")

    def update_offset(self, off):
        self.off_lbl.setText(f"{off * 1000 / SR:+.1f} ms")

    def mousePressEvent(self, e):
        self.studio.select(self.tid)

    def dragEnterEvent(self, e):
        if _accepts(e.mimeData()):
            e.acceptProposedAction()

    def dropEvent(self, e):
        tid = _trash_id(e.mimeData())
        if tid:
            self.studio.restore_trash(tid, target=self.tid)
        else:
            self.studio.import_files(_paths(e.mimeData()), target=self.tid)


class Timeline(QWidget):
    RULER = 24          # time ruler (loop, [ ] markers)
    LANE = 20           # notes lane, under the ruler
    LYR = 18            # lyrics lane, under the notes lane (only when there are lyrics)

    def __init__(self, studio):
        super().__init__()
        self.studio = studio
        self.setAcceptDrops(True)
        self.setMinimumHeight(190)
        self.setFocusPolicy(Qt.FocusPolicy.ClickFocus)
        self.view_start = -SR // 2
        self.spp = 400.0
        self.drag = None
        self.seam_live = None       # (take, pass index, "in"/"out", value) while a seam is dragged
        self.note_ghost = None      # (start, end) while a zone note is being drawn
        self._pix = None
        self._key = None
        self.setMouseTracking(True)

    # coordinates
    def x_to_s(self, x):
        return int(self.view_start + x * self.spp)

    def s_to_x(self, s):
        return (s - self.view_start) / self.spp

    def lyr_y(self):
        """Top of the lyrics lane."""
        return self.RULER + self.LANE

    def top(self):
        """Tracks start here (below the ruler, the notes lane and, if any, the lyrics lane)."""
        return self.RULER + self.LANE + (self.LYR if self.studio.lyrics else 0)

    def row_h(self):
        n = max(1, len(self.studio.tracks))
        return (self.height() - self.top()) / n

    def row_at(self, y):
        if y < self.top():
            return None
        i = int((y - self.top()) / self.row_h())
        return self.studio.tracks[i] if 0 <= i < len(self.studio.tracks) else None

    def invalidate(self):
        self._key = None
        self.update()

    def zoom_fit(self):
        s, e = self.studio.bounds()
        if e <= s:
            s, e = 0, 10 * SR
        pad = (e - s) * 0.03 + SR * 0.2
        self.view_start = s - pad
        self.spp = max(8.0, (e - s + 2 * pad) / max(100, self.width()))
        self.invalidate()

    def follow(self, pos):
        x = self.s_to_x(pos)
        if x > self.width() * 0.92 or x < 0:
            self.view_start = pos - self.width() * 0.08 * self.spp
            self.invalidate()

    def _colors(self):
        p = self.palette()
        bg = p.color(QPalette.ColorRole.Base)
        fg = p.color(QPalette.ColorRole.Text)
        return {"bg": bg, "fg": fg, "dark": bg.lightness() < 128,
                "ruler": blend(fg, bg, 0.08), "lane": blend(fg, bg, 0.04),
                "grid": blend(fg, bg, 0.12), "tick": blend(fg, bg, 0.72),
                "muted": blend(fg, bg, 0.55), "hl": p.color(QPalette.ColorRole.Highlight)}

    # rendering
    def _render(self):
        w, h = self.width(), self.height()
        dpr = self.devicePixelRatioF()
        c = self._colors()
        pix = QPixmap(int(w * dpr), int(h * dpr))
        pix.setDevicePixelRatio(dpr)
        pix.fill(c["bg"])
        p = QPainter(pix)
        st = self.studio

        # ruler + notes lane
        p.fillRect(QRectF(0, 0, w, self.RULER), c["ruler"])
        p.fillRect(QRectF(0, self.RULER, w, self.LANE), c["lane"])
        f = QFont()
        f.setPointSize(8)
        p.setFont(f)
        step = 120
        for s_ in (0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120):
            if s_ * SR / self.spp >= 80:
                step = s_
                break
        t = math.floor(self.view_start / SR / step) * step
        while self.s_to_x(t * SR) < w:
            x = self.s_to_x(t * SR)
            p.setPen(c["tick"])
            p.drawLine(QLineF(x, self.RULER - 7, x, self.RULER))
            lab = f"{t:.2f}s" if step < 1 else f"{int(t // 60)}:{int(t % 60):02d}" if t >= 0 else f"{t:.0f}s"
            p.drawText(QPointF(x + 3, 13), lab)
            p.setPen(c["grid"])
            p.drawLine(QLineF(x, self.top(), x, h))
            t += step
        if st.lyrics:
            p.fillRect(QRectF(0, self.lyr_y(), w, self.LYR), c["ruler"])

        if not st.tracks:
            self._draw_notes(p, w, h, c)
            self._draw_lyrics(p, w, c)
            p.setPen(c["muted"])
            p.drawText(QRectF(0, self.top(), w, h - self.top()), Qt.AlignmentFlag.AlignCenter,
                       "Drop files here, or press R to record the master")
            p.end()
            return pix

        rh = self.row_h()
        for i, t in enumerate(st.tracks):
            y0 = self.top() + i * rh
            col = track_qcolor(t["color"], c["dark"])
            dim = t["mute"] or not t["enabled"] or (st.any_solo() and not t["solo"])
            if t["id"] == st.selected:
                sel = QColor(c["hl"])
                sel.setAlpha(28)
                p.fillRect(QRectF(0, y0, w, rh), sel)
            p.setPen(c["grid"])
            p.drawLine(QLineF(0, y0 + rh, w, y0 + rh))
            tk = t["takes"][t["active"]]
            data = st.take_data(tk)
            if data is None:
                continue
            x, (mins, maxs) = data
            off = tk["offset"]
            ta, tb = st.trim_of(tk)
            cx0, cx1 = self.s_to_x(off), self.s_to_x(off + len(x))
            bg = QColor(col)
            bg.setAlpha(30 if not dim else 12)
            p.fillRect(QRectF(cx0, y0 + 2, cx1 - cx0, rh - 4), bg)
            px0, px1 = max(0, int(cx0)), min(w, int(cx1))
            if px1 > px0 and len(maxs):
                xs = np.arange(px0, px1)
                samp = self.view_start + xs * self.spp - off
                nb = len(maxs)
                b0 = np.clip((samp // PEAK_BIN).astype(np.int64), 0, nb - 1)
                end = min(nb, int(b0[-1]) + max(1, int(self.spp // PEAK_BIN)))
                mx = np.maximum.reduceat(maxs[:end], b0)
                mn = np.minimum.reduceat(mins[:end], b0)
                mid, amp = y0 + rh / 2, rh * 0.45
                y1 = mid - mx * amp
                y2 = np.maximum(mid - mn * amp, y1 + 1)
                wc = QColor(col)
                if dim:
                    wc.setAlpha(70)
                p.setPen(QPen(wc, 1))
                p.drawLines([QLineF(float(a), float(b), float(a), float(cc))
                             for a, b, cc in zip(xs, y1, y2)])
            # trimmed zones: darkened + hatched
            xa, xb = self.s_to_x(off + ta), self.s_to_x(off + tb)
            shade = QColor(c["bg"])
            shade.setAlpha(200)
            hatch = QColor(c["fg"])
            hatch.setAlpha(18)
            for r0, r1 in ((cx0, xa), (xb, cx1)):
                if r1 - r0 > 0.5:
                    p.fillRect(QRectF(r0, y0 + 2, r1 - r0, rh - 4), shade)
                    p.fillRect(QRectF(r0, y0 + 2, r1 - r0, rh - 4), hatch)
            self._draw_seams(p, tk, y0, rh, c, w)
            # trim handles
            hc = QColor(col)
            hc.setAlpha(255 if t["id"] == st.selected else 170)
            for hx, sgn in ((xa, 1), (xb, -1)):
                if -10 < hx < w + 10:
                    p.fillRect(QRectF(hx - (0 if sgn > 0 else 3), y0 + 2, 3, rh - 4), hc)
                    p.fillRect(QRectF(hx - (0 if sgn > 0 else 8), y0 + 2, 8, 6), hc)
                    p.fillRect(QRectF(hx - (0 if sgn > 0 else 8), y0 + rh - 8, 8, 6), hc)
            p.setPen(c["fg"] if not dim else c["muted"])
            label = ("★ " if t["master"] else "") + t["name"]
            if len(t["takes"]) > 1:
                label += f"  · take {t['active'] + 1}/{len(t['takes'])}"
            if len(tk["passes"]) > 1:
                n_ = len(tk["passes"]) - 1
                label += f"  · {n_} punch{'es' if n_ > 1 else ''}"
            if not t["enabled"]:
                label += "  · excluded"
            p.drawText(QPointF(max(4, max(cx0, xa) + 6), y0 + 14), label)
        self._draw_notes(p, w, h, c)
        self._draw_lyrics(p, w, c)
        p.end()
        return pix

    def _draw_seams(self, p, tk, y0, rh, c, w):
        """Punch seams: dashed line + diamond (draggable, right-click to remove)."""
        st = self.studio
        h = st.xfade() // 2
        live = self.seam_live
        pen = QPen(c["fg"], 1, Qt.PenStyle.DashLine)
        p.setBrush(c["fg"])
        for i, ps in enumerate(tk["passes"]):
            n = len(st.audio.get(ps["file"], ()))
            _, _, ci, co = pass_edges(ps, n, h)
            for which, v in (("in", ci), ("out", co)):
                if live and live[0] is tk and live[1] == i and live[2] == which:
                    v = live[3]
                if v is None:
                    continue
                x = self.s_to_x(tk["offset"] + v)
                if not -10 < x < w + 10:
                    continue
                p.setPen(pen)
                p.drawLine(QLineF(x, y0 + 12, x, y0 + rh - 2))
                p.setPen(Qt.PenStyle.NoPen)
                d = 5
                p.drawPolygon(QPolygonF([QPointF(x, y0 + 2), QPointF(x + d, y0 + 2 + d),
                                         QPointF(x, y0 + 2 + 2 * d), QPointF(x - d, y0 + 2 + d)]))
        p.setBrush(Qt.BrushStyle.NoBrush)

    def _draw_notes(self, p, w, h, c):
        notes = sorted(self.studio.notes, key=lambda n: n["t"])
        fm = QFontMetrics(p.font())
        R, T = self.RULER, self.top()
        NB = R + self.LANE                      # bottom of the notes lane
        for k, n in enumerate(notes):
            hl = note_qcolor(n, c["hl"])
            x = self.s_to_x(n["t"])
            nxt = notes[k + 1]["t"] if k + 1 < len(notes) else None
            text = n["text"] or "…"
            if n.get("end") is not None:
                xe = self.s_to_x(n["end"])
                if xe < 0 or x > w:
                    continue
                band = QColor(hl)
                band.setAlpha(90)
                p.fillRect(QRectF(x, R + 2, xe - x, self.LANE - 4), band)
                over = QColor(hl)
                over.setAlpha(18)
                p.fillRect(QRectF(x, T, xe - x, h - T), over)
                tx = max(x, 0) + 4
                avail = xe - tx - 4
            else:
                if x < -400 or x > w:
                    continue
                line = QColor(hl)
                line.setAlpha(130)
                p.setPen(QPen(line, 1, Qt.PenStyle.DotLine))
                p.drawLine(QLineF(x, T, x, h))
                p.setPen(QPen(hl, 1))
                p.drawLine(QLineF(x, R + 2, x, NB))
                p.setPen(Qt.PenStyle.NoPen)
                p.setBrush(hl)
                p.drawPolygon(QPolygonF([QPointF(x, R + 3), QPointF(x + 9, R + 7.5), QPointF(x, R + 12)]))
                p.setBrush(Qt.BrushStyle.NoBrush)
                tx = x + 12
                avail = (self.s_to_x(nxt) - tx - 6) if nxt is not None else 400
            if avail > 14:
                p.setPen(c["fg"])
                p.drawText(QPointF(tx, R + 14), fm.elidedText(text, Qt.TextElideMode.ElideRight, int(avail)))

    def _draw_lyrics(self, p, w, c):
        st = self.studio
        if not st.lyrics:
            return
        fm = QFontMetrics(p.font())
        y0 = self.lyr_y()
        timed = [(i, ln) for i, ln in enumerate(st.lyrics) if ln["t"] is not None]
        tapping = st.tap_next is not None or len(timed) < len(st.lyrics)
        target = st.tap_target() if tapping else None
        for k, (i, ln) in enumerate(timed):
            x = self.s_to_x(ln["t"])
            nxt = timed[k + 1][1]["t"] if k + 1 < len(timed) else None
            xn = self.s_to_x(nxt) if nxt is not None else x + 400
            if xn < 0 or x > w:
                continue
            col = c["hl"] if i == target else c["tick"]
            p.setPen(QPen(col, 2))
            p.drawLine(QLineF(x, y0 + 2, x, y0 + self.LYR - 2))
            avail = xn - x - 10
            if avail > 14:
                p.setPen(c["fg"])
                p.drawText(QPointF(x + 5, y0 + 13), fm.elidedText(ln["text"], Qt.TextElideMode.ElideRight,
                                                                  int(avail)))
        if len(timed) < len(st.lyrics):
            txt = f"{len(st.lyrics) - len(timed)} line(s) not synced — play and press Enter at each line"
            p.setPen(c["muted"])
            p.drawText(QRectF(0, y0, w - 6, self.LYR), Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter,
                       txt)

    def paintEvent(self, e):
        st = self.studio
        key = (self.width(), self.height(), self.view_start, self.spp, st.model_version, st.selected,
               QApplication.palette().cacheKey(), st.tap_target())
        if key != self._key or self._pix is None:
            self._pix = self._render()
            self._key = key
        c = self._colors()
        p = QPainter(self)
        p.drawPixmap(0, 0, self._pix)
        h = self.height()
        w = self.width()
        rng = st.song_range_eff()
        if rng and st.tracks:
            sa, sb = self.s_to_x(rng[0]), self.s_to_x(rng[1])
            dark = QColor(c["bg"])
            dark.setAlpha(150)
            if sa > 0:
                p.fillRect(QRectF(0, self.top(), min(sa, w), h - self.top()), dark)
            if sb < w:
                p.fillRect(QRectF(max(sb, 0), self.top(), w - max(sb, 0), h - self.top()), dark)
            manual = st.song_range is not None
            for mx, col, txt in ((sa, QColor("#4cd964"), "["), (sb, QColor("#ff6b6b"), "]")):
                p.setPen(QPen(col, 2 if manual else 1, Qt.PenStyle.SolidLine if manual else Qt.PenStyle.DashLine))
                p.drawLine(QLineF(mx, 0, mx, h))
                p.fillRect(QRectF(mx - 6, 0, 12, self.RULER),
                           col if manual else QColor(col.red(), col.green(), col.blue(), 120))
                p.setPen(QColor("#111"))
                p.drawText(QRectF(mx - 6, 0, 12, self.RULER), Qt.AlignmentFlag.AlignCenter, txt)
        if st.loop_region:
            a, b = (self.s_to_x(v) for v in st.loop_region)
            on = st.chk_loop.isChecked()
            lc = QColor(c["hl"])
            lc.setAlpha(40 if on else 15)
            p.fillRect(QRectF(a, 0, b - a, h), lc)
            lc.setAlpha(140 if on else 50)
            p.fillRect(QRectF(a, 0, b - a, self.RULER), lc)
        if self.note_ghost:
            a, b = (self.s_to_x(v) for v in self.note_ghost)
            gc = QColor(c["hl"])
            gc.setAlpha(90)
            p.fillRect(QRectF(a, self.RULER + 2, b - a, self.LANE - 4), gc)
        if st.state == "rec":
            a = self.s_to_x(st.play_origin)
            b = self.s_to_x(st.playhead)
            p.fillRect(QRectF(a, self.top(), max(0, b - a), h - self.top()), QColor(220, 40, 40, 35))
        x = self.s_to_x(st.playhead)
        pc = QColor("#ff5252") if st.state == "rec" else QColor("#ef8a00") if st.state == "rec_pause" else c["fg"]
        p.setPen(QPen(pc, 1.5))
        p.drawLine(QLineF(x, 0, x, h))
        p.end()

    # hit tests
    def marker_hit(self, x):
        rng = self.studio.song_range_eff()
        if not rng:
            return None
        da, db_ = abs(x - self.s_to_x(rng[0])), abs(x - self.s_to_x(rng[1]))
        if min(da, db_) > 7:
            return None
        return "s" if da <= db_ else "e"

    def edge_hit(self, x, y):
        t = self.row_at(y)
        if not t:
            return None
        tk = t["takes"][t["active"]]
        if not self.studio.take_ok(tk):
            return None
        a, b = self.studio.trim_of(tk)
        xa, xb = self.s_to_x(tk["offset"] + a), self.s_to_x(tk["offset"] + b)
        da, db_ = abs(x - xa), abs(x - xb)
        if min(da, db_) > 7:
            return None
        return (t, "a" if da < db_ or (da == db_ and x >= xa) else "b")

    def seam_hit(self, x, y):
        t = self.row_at(y)
        if not t:
            return None
        tk = t["takes"][t["active"]]
        st = self.studio
        if not st.take_ok(tk):
            return None
        h = st.xfade() // 2
        best = None
        for i, ps in enumerate(tk["passes"]):
            _, _, ci, co = pass_edges(ps, len(st.audio[ps["file"]]), h)
            for which, v in (("in", ci), ("out", co)):
                if v is None:
                    continue
                d = abs(x - self.s_to_x(tk["offset"] + v))
                if d <= 6 and (best is None or d < best[0]):
                    best = (d, i, which, v)
        return (t, best[1], best[2], best[3]) if best else None

    def note_hit(self, x):
        """(note, "move"/"end") under x in the notes lane; point notes win over zones."""
        notes = self.studio.notes
        for n in notes:
            if n.get("end") is None:
                xs = self.s_to_x(n["t"])
                if xs - 4 <= x <= xs + 11:
                    return n, "move"
        for n in notes:
            if n.get("end") is not None:
                xs, xe = self.s_to_x(n["t"]), self.s_to_x(n["end"])
                if abs(x - xe) <= 5:
                    return n, "end"
                if xs <= x <= xe:
                    return n, "move"
        return None, None

    def lyric_hit(self, x):
        """Index of the synced lyric line whose marker (or label start) is under x."""
        best = None
        for i, ln in enumerate(self.studio.lyrics):
            if ln["t"] is None:
                continue
            xs = self.s_to_x(ln["t"])
            if xs - 5 <= x <= xs + 40:
                d = abs(x - xs)
                if best is None or d < best[0]:
                    best = (d, i)
        return None if best is None else best[1]

    # mouse
    def mousePressEvent(self, e):
        self.setFocus()
        st = self.studio
        x, y = e.position().x(), e.position().y()
        s = self.x_to_s(x)
        btn = e.button()
        gpos = e.globalPosition().toPoint()
        if y < self.RULER:
            if btn == Qt.MouseButton.RightButton:
                st.set_loop_region(None)
                return
            if btn != Qt.MouseButton.LeftButton or st.busy():
                return
            which = self.marker_hit(x)
            if which:
                st.push_undo()
                st.make_song_range_manual()
                self.drag = {"mode": "marker", "which": which}
                return
            self.drag = {"mode": "loop", "anchor": s, "x0": x, "moved": False}
            return
        if y < self.RULER + self.LANE:                        # notes lane
            n, part = self.note_hit(x)
            if btn == Qt.MouseButton.RightButton:
                st.note_menu(n, s, gpos)
            elif btn == Qt.MouseButton.LeftButton:
                if n:
                    st.push_undo()
                    self.drag = {"mode": "note", "n": n, "part": part, "x0": x,
                                 "t0": n["t"], "e0": n.get("end")}
                else:
                    self.drag = {"mode": "note_new", "anchor": s, "x0": x, "moved": False}
            return
        if y < self.top():                                    # lyrics lane
            i = self.lyric_hit(x)
            if btn == Qt.MouseButton.RightButton:
                st.lyric_menu(i, gpos)
            elif btn == Qt.MouseButton.LeftButton:
                if i is not None:
                    st.push_undo()
                    shift = bool(e.modifiers() & Qt.KeyboardModifier.ShiftModifier)
                    lines = st.lyrics
                    moving = {j: lines[j]["t"] for j in range(i, len(lines) if shift else i + 1)
                              if lines[j]["t"] is not None}
                    self.drag = {"mode": "lyric", "i": i, "x0": x, "t0s": moving}
                else:
                    self.drag = {"mode": "seek"}
                    st.seek(s)
            return
        t = self.row_at(y)
        if btn == Qt.MouseButton.RightButton:
            if t:
                st.select(t["id"])
                st.clip_menu(t, self.seam_hit(x, y), s, gpos)
            return
        if btn != Qt.MouseButton.LeftButton:
            return
        if st.busy():
            if st.state == "rec_pause":           # paused: clicking chooses where to resume
                self.drag = {"mode": "seek"}
                st.seek(s)
            return
        if t:
            st.select(t["id"])
        ctrl = bool(e.modifiers() & Qt.KeyboardModifier.ControlModifier)
        edge = None if ctrl else self.edge_hit(x, y)
        if edge:
            st.push_undo()
            self.drag = {"mode": "trim", "t": edge[0], "which": edge[1]}
            return
        seam = None if ctrl else self.seam_hit(x, y)
        if seam:
            tk = seam[0]["takes"][seam[0]["active"]]
            self.drag = {"mode": "seam", "t": seam[0], "i": seam[1], "which": seam[2]}
            self.seam_live = (tk, seam[1], seam[2], seam[3])
            return
        if t and ctrl:
            st.push_undo()
            self.drag = {"mode": "move", "t": t, "x0": x, "off0": t["takes"][t["active"]]["offset"]}
        else:
            self.drag = {"mode": "seek"}
            st.seek(s)

    def mouseDoubleClickEvent(self, e):
        st = self.studio
        x, y = e.position().x(), e.position().y()
        if y < self.RULER:
            if self.marker_hit(x) and not st.busy():
                st.reset_song_range()
            return
        if y < self.RULER + self.LANE:
            self.drag = None
            self.note_ghost = None
            n, _ = self.note_hit(x)
            if n:
                st.edit_note(n)
            else:
                st.add_note(self.x_to_s(x))
            return
        if y < self.top():
            self.drag = None
            st.open_prompter(edit=True)
            return
        if st.busy():
            return
        edge = self.edge_hit(x, y)
        if edge:
            self.drag = None
            st.auto_trim(edge[0]["id"])

    def mouseMoveEvent(self, e):
        st = self.studio
        x = e.position().x()
        if not self.drag:
            y = e.position().y()
            if y < self.RULER:
                hot = self.marker_hit(x)
            elif y < self.RULER + self.LANE:
                hot = self.note_hit(x)[1] == "end"
            elif y < self.top():
                hot = self.lyric_hit(x) is not None
            else:
                hot = self.edge_hit(x, y) or self.seam_hit(x, y)
            self.setCursor(Qt.CursorShape.SizeHorCursor if hot else Qt.CursorShape.ArrowCursor)
            return
        d = self.drag
        if d["mode"] == "loop":
            if abs(x - d["x0"]) > 3:
                d["moved"] = True
                st.set_loop_region(tuple(sorted((d["anchor"], self.x_to_s(x)))))
        elif d["mode"] == "move":
            st.set_offset_live(d["t"], d["off0"] + (x - d["x0"]) * self.spp)
        elif d["mode"] == "trim":
            st.set_trim_live(d["t"], d["which"], self.x_to_s(x))
        elif d["mode"] == "marker":
            st.set_song_marker(d["which"], self.x_to_s(x))
        elif d["mode"] == "seam":
            tk = d["t"]["takes"][d["t"]["active"]]
            v = st.clamp_seam(tk, d["i"], d["which"], self.x_to_s(x) - tk["offset"])
            self.seam_live = (tk, d["i"], d["which"], v)
            st.touch()
        elif d["mode"] == "note":
            if not d.get("moved") and abs(x - d["x0"]) <= 3:
                return
            d["moved"] = True
            n = d["n"]
            if d["part"] == "end":
                n["end"] = max(n["t"] + int(0.1 * SR), self.x_to_s(x))
            else:
                dt = int((x - d["x0"]) * self.spp)
                n["t"] = d["t0"] + dt
                if d["e0"] is not None:
                    n["end"] = d["e0"] + dt
            st.touch()
        elif d["mode"] == "lyric":
            if not d.get("moved") and abs(x - d["x0"]) <= 3:
                return
            d["moved"] = True
            dt = int((x - d["x0"]) * self.spp)
            for j, t0 in d["t0s"].items():
                st.lyrics[j]["t"] = t0 + dt
            st.touch()
        elif d["mode"] == "note_new":
            if abs(x - d["x0"]) > 3:
                d["moved"] = True
                self.note_ghost = tuple(sorted((d["anchor"], self.x_to_s(x))))
                self.update()
        elif d["mode"] == "seek" and st.state in ("idle", "rec_pause"):
            st.seek(self.x_to_s(x))

    def mouseReleaseEvent(self, e):
        d, self.drag = self.drag, None
        st = self.studio
        if not d:
            return
        if d["mode"] == "loop" and not d["moved"]:
            st.seek(d["anchor"])
        elif d["mode"] == "move":
            st.commit_offset()
        elif d["mode"] in ("trim", "marker"):
            st.commit_trim()
        elif d["mode"] == "seam":
            live, self.seam_live = self.seam_live, None
            if live:
                st.set_seam(d["t"], d["i"], d["which"], live[3])
        elif d["mode"] == "note":
            if d.get("moved"):
                st.notes_changed()
            else:                               # simple click: go to the note
                if st.undo_stack:
                    st.undo_stack.pop()
                st.seek(d["n"]["t"])
        elif d["mode"] == "lyric":
            if d.get("moved"):
                st.lyrics_changed()
            else:                               # simple click: go to the line
                if st.undo_stack:
                    st.undo_stack.pop()
                st.seek(st.lyrics[d["i"]]["t"])
        elif d["mode"] == "note_new":
            ghost, self.note_ghost = self.note_ghost, None
            if d["moved"] and ghost and ghost[1] - ghost[0] >= int(0.1 * SR):
                st.add_note(ghost[0], ghost[1])
            else:
                st.seek(d["anchor"])
            self.update()

    def wheelEvent(self, e):
        steps = e.angleDelta().y() / 120 or e.angleDelta().x() / 120
        if e.modifiers() & Qt.KeyboardModifier.ControlModifier:
            anchor = self.x_to_s(e.position().x())
            self.spp = min(max(self.spp / (1.25 ** steps), 4.0), 50000.0)
            self.view_start = anchor - e.position().x() * self.spp
        else:
            self.view_start -= steps * 80 * self.spp
        self.invalidate()

    def resizeEvent(self, e):
        self.invalidate()

    def dragEnterEvent(self, e):
        if _accepts(e.mimeData()):
            e.acceptProposedAction()

    def dropEvent(self, e):
        tid = _trash_id(e.mimeData())
        if tid:
            t = self.row_at(e.position().y())
            self.studio.restore_trash(tid, target=t["id"] if t else None)
        else:
            self.studio.import_files(_paths(e.mimeData()))


# ═══════════════════════════════════════════════════════════════ prompter

def _no_focus(w):
    for b in w.findChildren(QAbstractButton):
        b.setFocusPolicy(Qt.FocusPolicy.NoFocus)


class LyricsView(QWidget):
    """Teleprompter: previous line (faded), current line (big), time left, next line(s), current note."""

    def __init__(self, studio):
        super().__init__()
        self.studio = studio
        self.setMinimumSize(360, 180)

    def _block(self, p, text, size, bold, color, y, w):
        f = QFont()
        f.setPointSize(max(6, int(size)))
        f.setBold(bold)
        p.setFont(f)
        flags = Qt.AlignmentFlag.AlignHCenter.value | Qt.TextFlag.TextWordWrap.value   # PyQt6: mixed enums → int
        r = p.fontMetrics().boundingRect(0, 0, int(w), 10000, flags, text)
        if color is not None:
            p.setPen(color)
            p.drawText(QRectF(20, y, w, r.height()), flags, text)
        return r.height()

    def _layout(self, p, blocks, w, draw_y=None):
        """blocks: (kind, text, size, bold, color). Returns the total height; draws if draw_y given."""
        y = draw_y or 0
        for kind, text, size, bold, color in blocks:
            if kind == "gap":
                y += size
            elif kind == "bar":
                if draw_y is not None and text is not None:
                    bw = w * 0.6
                    x0 = 20 + (w - bw) / 2
                    p.fillRect(QRectF(x0, y, bw, 6), color[0])
                    p.fillRect(QRectF(x0, y, bw * text, 6), color[1])
                y += 6
            else:
                y += self._block(p, text, size, bold, color if draw_y is not None else None, y, w)
        return y - (draw_y or 0)

    def paintEvent(self, e):
        st = self.studio
        pal = self.palette()
        bg, fg = pal.color(QPalette.ColorRole.Window), pal.color(QPalette.ColorRole.WindowText)
        hl = pal.color(QPalette.ColorRole.Highlight)
        muted = blend(fg, bg, 0.5)
        p = QPainter(self)
        p.fillRect(self.rect(), bg)
        w, h = self.width() - 40, self.height()
        size = st.prompter_size()
        lines = st.lyrics
        if not lines:
            self._block(p, "No lyrics yet.\nImport a .txt or .lrc file, or click “Edit” to paste them.",
                        max(10, size * 0.45), False, muted, h / 2 - size, w)
            p.end()
            return
        v = lyric_view(lines, st.playhead, st.prompter_lead(), st.prompter_ahead())
        blocks = []
        if v["prev"] is not None:
            blocks.append(("text", lines[v["prev"]]["text"], size * 0.55, False, muted))
            blocks.append(("gap", None, size * 0.35, False, None))
        cur = lines[v["cur"]]["text"] if v["cur"] >= 0 else "♪"
        blocks.append(("text", cur, size, True, fg))
        blocks.append(("gap", None, size * 0.3, False, None))
        target = st.tap_target()
        if st.state in ("play", "rec") and target is not None and target in v["next"][:1] \
                and lines[target]["t"] is None:
            blocks.append(("text", "⏎ Enter when the next line starts", size * 0.4, False, hl))
        elif v["cur"] < 0 and v["until"] is not None:
            blocks.append(("text", f"starts in {v['until']:.1f} s", size * 0.4, False, muted))
        else:
            blocks.append(("bar", v["frac"], 0, False, (blend(fg, bg, 0.15), hl)))
        blocks.append(("gap", None, size * 0.45, False, None))
        for k in v["next"]:
            blocks.append(("text", lines[k]["text"], size * 0.7, False, blend(fg, bg, 0.72)))
            blocks.append(("gap", None, size * 0.15, False, None))
        total = self._layout(p, blocks, w)
        self._layout(p, blocks, w, draw_y=max(8.0, (h - total) / 2 - size * 0.4))
        cur_note, _ = note_at(st.notes, st.playhead)
        if cur_note and cur_note["text"]:
            f = QFont()
            f.setPointSize(max(8, int(size * 0.4)))
            p.setFont(f)
            p.setPen(note_qcolor(cur_note, hl))
            p.drawText(QRectF(20, h - size * 0.9 - 8, w, size * 0.9), Qt.AlignmentFlag.AlignHCenter
                       | Qt.AlignmentFlag.AlignBottom, "📝 " + cur_note["text"])
        p.end()


class PrompterWindow(QWidget):
    def __init__(self, studio):
        super().__init__(studio, Qt.WindowType.Window)
        self.studio = studio
        s = studio.settings
        self.setWindowTitle("Prompter")
        self.resize(760, 360)
        geom = s.value("prompter_geom")
        if geom:
            self.restoreGeometry(geom)
        v = QVBoxLayout(self)
        v.setContentsMargins(6, 6, 6, 6)
        row = QHBoxLayout()
        b_imp = QPushButton("📄 Import…")
        b_imp.setToolTip("Import lyrics from a .txt (one line per sung phrase) or a .lrc file")
        b_imp.clicked.connect(studio.import_lyrics)
        self.b_edit = QPushButton("✎ Edit")
        self.b_edit.setToolTip("Paste or correct the lyrics (the timing of unchanged lines is kept)")
        self.b_edit.clicked.connect(lambda: self.edit_mode(True))
        b_exp = QPushButton("💾 Export .lrc…")
        b_exp.setToolTip("Save the synced lyrics as a .lrc file (timed on your master)")
        b_exp.clicked.connect(studio.export_lyrics)
        tog = QToolButton(text="⚙", checkable=True)
        tog.setToolTip("Display settings")
        for w in (b_imp, self.b_edit, b_exp):
            row.addWidget(w)
        row.addStretch(1)
        row.addWidget(tog)
        v.addLayout(row)

        self.opts = QWidget()
        ol = QHBoxLayout(self.opts)
        ol.setContentsMargins(0, 0, 0, 0)
        self.size_spin = QSpinBox(minimum=12, maximum=96, suffix=" pt")
        self.size_spin.setValue(int(s.value("prompter_size", 30)))
        self.ahead_spin = QSpinBox(minimum=1, maximum=3)
        self.ahead_spin.setValue(int(s.value("prompter_ahead", 1)))
        self.lead_spin = QDoubleSpinBox(minimum=0.0, maximum=1.5, singleStep=0.1, suffix=" s")
        self.lead_spin.setValue(float(s.value("prompter_lead", 0.3)))
        self.lead_spin.setToolTip("Lines appear this much before their time:\n"
                                  "time to read, and it makes up for your reaction time when tapping")
        self.chk_top = QCheckBox("Always on top")
        self.chk_top.setChecked(s.value("prompter_top", "false") == "true")
        for lbl, w in (("Size:", self.size_spin), ("Lines ahead:", self.ahead_spin),
                       ("Show lines early:", self.lead_spin)):
            ol.addWidget(QLabel(lbl))
            ol.addWidget(w)
            w.valueChanged.connect(self._opts_changed)
            w.editingFinished.connect(w.clearFocus)
        ol.addWidget(self.chk_top)
        ol.addStretch(1)
        self.chk_top.toggled.connect(self._on_top)
        self.opts.setVisible(False)
        tog.toggled.connect(self.opts.setVisible)
        v.addWidget(self.opts)

        self.stack = QStackedWidget()
        self.view = LyricsView(studio)
        self.stack.addWidget(self.view)
        ed = QWidget()
        el = QVBoxLayout(ed)
        el.setContentsMargins(0, 0, 0, 0)
        hint = QLabel("One line per sung phrase. Empty lines are ignored. "
                      "Lines you don't change keep their timing.")
        hint.setWordWrap(True)
        self.editor = QPlainTextEdit()
        er = QHBoxLayout()
        b_ok = QPushButton("✔ Apply")
        b_ok.clicked.connect(self._apply)
        b_cancel = QPushButton("Cancel")
        b_cancel.clicked.connect(lambda: self.edit_mode(False))
        er.addStretch(1)
        er.addWidget(b_cancel)
        er.addWidget(b_ok)
        el.addWidget(hint)
        el.addWidget(self.editor, 1)
        el.addLayout(er)
        self.stack.addWidget(ed)
        v.addWidget(self.stack, 1)
        _no_focus(self)
        if self.chk_top.isChecked():
            self.setWindowFlag(Qt.WindowType.WindowStaysOnTopHint, True)

    def edit_mode(self, on):
        if on:
            self.editor.setPlainText("\n".join(ln["text"] for ln in self.studio.lyrics))
            self.stack.setCurrentIndex(1)
            self.editor.setFocus()
        else:
            self.stack.setCurrentIndex(0)
            self.editor.clearFocus()

    def _apply(self):
        self.studio.set_lyrics_text(self.editor.toPlainText())
        self.edit_mode(False)

    def _opts_changed(self, *_):
        s = self.studio.settings
        s.setValue("prompter_size", self.size_spin.value())
        s.setValue("prompter_ahead", self.ahead_spin.value())
        s.setValue("prompter_lead", self.lead_spin.value())
        self.view.update()

    def _on_top(self, on):
        self.studio.settings.setValue("prompter_top", "true" if on else "false")
        self.setWindowFlag(Qt.WindowType.WindowStaysOnTopHint, on)
        self.show()

    def hideEvent(self, e):
        self.studio.settings.setValue("prompter_geom", self.saveGeometry())
        super().hideEvent(e)


# ═══════════════════════════════════════════════════════════════ trash

class TrashList(QListWidget):
    def __init__(self, studio):
        super().__init__()
        self.studio = studio
        self.setDragEnabled(True)
        self.setDragDropMode(QAbstractItemView.DragDropMode.DragOnly)
        self.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.setIconSize(QSize(150, 38))
        self.setSpacing(2)

    def mimeTypes(self):
        return [TRASH_MIME]

    def mimeData(self, items):
        md = QMimeData()
        items = list(items)
        if items:
            md.setData(TRASH_MIME, QByteArray(items[0].data(Qt.ItemDataRole.UserRole).encode()))
        return md


class TrashWindow(QWidget):
    def __init__(self, studio):
        super().__init__(studio, Qt.WindowType.Window)
        self.studio = studio
        self.setWindowTitle("Trash")
        self.resize(600, 440)
        v = QVBoxLayout(self)
        self.hint = QLabel("Double-click to restore · drag onto a track to add it there as a new take")
        self.hint.setWordWrap(True)
        v.addWidget(self.hint)
        self.list = TrashList(studio)
        self.list.itemDoubleClicked.connect(lambda it: studio.restore_trash(it.data(Qt.ItemDataRole.UserRole)))
        self.list.currentRowChanged.connect(lambda *_: self._buttons())
        v.addWidget(self.list, 1)
        row = QHBoxLayout()
        self.b_listen = QPushButton("▶ Listen")
        self.b_listen.clicked.connect(lambda: self._with_current(studio.preview_trash))
        self.b_restore = QPushButton("↩ Restore")
        self.b_restore.clicked.connect(lambda: self._with_current(studio.restore_trash))
        self.b_delete = QPushButton("Delete forever")
        self.b_delete.clicked.connect(lambda: self._with_current(lambda i: studio.purge_trash([i])))
        self.b_empty = QPushButton("Empty trash")
        self.b_empty.clicked.connect(lambda: studio.purge_trash(None))
        for w in (self.b_listen, self.b_restore, self.b_delete):
            row.addWidget(w)
        row.addStretch(1)
        row.addWidget(self.b_empty)
        v.addLayout(row)
        sc = QShortcut(QKeySequence("Delete"), self)
        sc.activated.connect(lambda: self._with_current(lambda i: studio.purge_trash([i])))
        _no_focus(self)
        self.refresh()

    def _with_current(self, fn):
        it = self.list.currentItem()
        if it:
            fn(it.data(Qt.ItemDataRole.UserRole))

    def _buttons(self):
        has = self.list.currentItem() is not None
        for b in (self.b_listen, self.b_restore, self.b_delete):
            b.setEnabled(has)
        self.b_empty.setEnabled(self.list.count() > 0)

    def refresh(self):
        cur = self.list.currentItem()
        cur_id = cur.data(Qt.ItemDataRole.UserRole) if cur else None
        self.list.clear()
        self.hint.setStyleSheet(f"color:{self.studio.c_muted};")
        for item in reversed(self.studio.trash):
            it = QListWidgetItem(QIcon(self.studio.trash_thumb(item)), self.studio.trash_label(item))
            it.setData(Qt.ItemDataRole.UserRole, item["id"])
            self.list.addItem(it)
            if item["id"] == cur_id:
                self.list.setCurrentItem(it)
        if self.list.currentItem() is None and self.list.count():
            self.list.setCurrentRow(0)
        self._buttons()


# ═══════════════════════════════════════════════════════════════ loop takes

class LapPicker(QWidget):
    """After a loop recording: pick the best lap, heard in context."""

    def __init__(self, studio, n):
        super().__init__(studio, Qt.WindowType.Window)
        self.studio = studio
        self.done = False
        self.setWindowTitle("Loop takes")
        self.resize(360, 320)
        v = QVBoxLayout(self)
        v.addWidget(QLabel("Pick the best lap: it is inserted and played in context."))
        self.list = QListWidget()
        for i in range(n):
            self.list.addItem(f"Lap {i + 1}")
        self.list.currentRowChanged.connect(self._pick)
        v.addWidget(self.list, 1)
        row = QHBoxLayout()
        b_play = QPushButton("▶ Listen again")
        b_play.clicked.connect(studio.play_lap_region)
        b_keep = QPushButton("✔ Keep this lap")
        b_keep.clicked.connect(self._keep)
        b_drop = QPushButton("✕ Discard all")
        b_drop.clicked.connect(self._discard)
        row.addWidget(b_play)
        row.addStretch(1)
        row.addWidget(b_drop)
        row.addWidget(b_keep)
        v.addLayout(row)
        _no_focus(self)

    def _pick(self, i):
        if i >= 0:
            self.studio.apply_lap(i)

    def _keep(self):
        self.done = True
        self.studio.finish_laps(keep=True)
        self.close()

    def _discard(self):
        self.done = True
        self.studio.finish_laps(keep=False)
        self.close()

    def closeEvent(self, e):
        if not self.done:                 # closing the window keeps the selected lap
            self.done = True
            self.studio.finish_laps(keep=True)
        super().closeEvent(e)


# ═══════════════════════════════════════════════════════════════ main window

class ChoirStudio(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Choir Studio")
        self.resize(1280, 860)
        self.settings = QSettings(APP_ORG, APP_NAME)
        self.engine = AudioEngine()
        self.tracks, self.notes, self.exported = [], [], []
        self.audio = {}                 # raw pass files: name -> samples
        self.comp_cache, self.proc_cache, self.raw_cache = {}, {}, {}
        self.project_dir = None
        self.work_project_dir = None
        self.selected = None
        self.playhead = 0
        self.play_origin = 0
        self.loop_region = None
        self.song_range = None
        self.state = "idle"             # idle | play | rec | rec_pause | calib
        self.stop_mode = "stop"
        self.gen = 0
        self.handled_gen = -1
        self.gen_info = {}              # gen -> what that playback/capture was for
        self.session = None             # current recording session (several passes, one take)
        self.rec_ctx = None             # headphone mix while recording
        self.undo_stack, self.redo_stack = [], []
        self.model_version = 0
        self.worker = None
        self.export_pending = False
        self.export_deferred = False
        self.clip_hold = 0
        self._clip_on = None
        self.rows = {}
        self.calib_sig = None
        self.c_muted = "#888"
        self.dark = True
        self._audio_status = ("", True)
        self._banner_text = None
        self._pal_key = None
        self.lyrics = []                # [{"text", "t"}] — see lyric_view()
        self.trash = []                 # deleted tracks / takes / punches / laps / recovered files
        self.thumb_cache = {}
        self.prompter = None
        self.trash_win = None
        self.lap_state = None           # laps waiting for a choice after a loop recording
        self.lap_picker = None
        self.tap_run_start = 0          # where the current playback started (lyrics tapping)
        self.tap_next = None            # index of the next line Enter will stamp in this run
        self.previewing = False         # playing a trash item instead of the song

        self._build_ui()
        self.apply_theme(force=True)
        self._populate_devices()
        self._restart_audio()

        self.export_timer = QTimer(self, singleShot=True, interval=1500)
        self.export_timer.timeout.connect(lambda: self.export_now(silent=True))
        self.timer = QTimer(self, interval=30)
        self.timer.timeout.connect(self._tick)
        self.timer.start()
        QApplication.instance().focusChanged.connect(self._on_focus)

        if self.settings.value("lang") and self.settings.value("pers") and self.settings.value("choir"):
            self.lang_combo.setCurrentText(self.settings.value("lang"))
            self.pers_combo.setCurrentText(self.settings.value("pers"))
            self.choir_combo.setCurrentText(self.settings.value("choir"))
            QTimer.singleShot(0, self.open_project)

    # ─────────────────────────────────────────────── UI construction
    def _build_ui(self):
        s = self.settings
        root = QWidget()
        self.setCentralWidget(root)
        v = QVBoxLayout(root)
        v.setSpacing(6)

        # Project
        pg = QGroupBox("Project")
        pl = QGridLayout(pg)
        self.base_edit = QLineEdit(s.value("base_dir", ""))
        self.base_edit.setPlaceholderText("…/src/main/resources/assets/travellers/sounds/travellers  (or the mod root)")
        self.base_edit.editingFinished.connect(self._refresh_langs)
        self.base_edit.returnPressed.connect(self.base_edit.clearFocus)
        bb = QPushButton("…")
        bb.setFixedWidth(30)
        bb.clicked.connect(self._browse_base)
        self.work_edit = QLineEdit(s.value("work_dir", DEFAULT_WORK_DIR))
        self.work_edit.setToolTip("Raw takes and project state. Kept outside the mod resources\n"
                                  "so the jar doesn't grow.")
        self.work_edit.editingFinished.connect(lambda: s.setValue("work_dir", self.work_edit.text().strip()))
        self.work_edit.returnPressed.connect(self.work_edit.clearFocus)
        wb = QPushButton("…")
        wb.setFixedWidth(30)
        wb.clicked.connect(self._browse_work)
        self.lang_combo = QComboBox(editable=True)
        self.lang_combo.setMinimumWidth(70)
        self.lang_combo.currentTextChanged.connect(self._refresh_pers)
        self.pers_combo = QComboBox(editable=True)
        self.pers_combo.setMinimumWidth(150)
        self.pers_combo.currentTextChanged.connect(self._refresh_choirs)
        self.choir_combo = QComboBox(editable=True)
        self.choir_combo.setMinimumWidth(150)
        ob = QPushButton("📂 Open / Create")
        ob.clicked.connect(self.open_project)
        fb = QPushButton("Folder")
        fb.setToolTip("Open the choir folder in the file manager")
        fb.clicked.connect(self._open_folder)
        pl.addWidget(QLabel("Sounds:"), 0, 0)
        pl.addWidget(self.base_edit, 0, 1, 1, 7)
        pl.addWidget(bb, 0, 8)
        pl.addWidget(QLabel("Work:"), 1, 0)
        pl.addWidget(self.work_edit, 1, 1, 1, 7)
        pl.addWidget(wb, 1, 8)
        pl.addWidget(QLabel("Language:"), 2, 0)
        pl.addWidget(self.lang_combo, 2, 1)
        pl.addWidget(QLabel("Personality:"), 2, 2)
        pl.addWidget(self.pers_combo, 2, 3)
        pl.addWidget(QLabel("Choir:"), 2, 4)
        pl.addWidget(self.choir_combo, 2, 5)
        pl.addWidget(ob, 2, 6)
        pl.addWidget(fb, 2, 7)
        self.proj_lbl = QLabel("No project open")
        pl.addWidget(self.proj_lbl, 3, 0, 1, 9)
        pl.setColumnStretch(3, 1)
        pl.setColumnStretch(5, 1)
        v.addWidget(pg)

        # Settings (collapsible)
        tog = QToolButton(text="⚙ Audio settings", checkable=True)
        tog.setChecked(False)                    # always collapsed when the app opens
        v.addWidget(tog)
        self.settings_box = QWidget()
        sl = QHBoxLayout(self.settings_box)
        sl.setContentsMargins(0, 0, 0, 0)
        tog.toggled.connect(self.settings_box.setVisible)
        self.settings_box.setVisible(False)

        ag = QGroupBox("Audio")
        al = QGridLayout(ag)
        self.in_combo, self.out_combo = QComboBox(), QComboBox()
        self.bs_combo = QComboBox()
        self.bs_combo.addItems(["32", "64", "128", "256", "512", "1024"])
        self.bs_combo.setCurrentText(str(s.value("blocksize", 128)))
        self.bs_combo.setToolTip("Block size / PipeWire quantum. Smaller = less latency.\n"
                                 "Raise it if you hear crackles or get dropout warnings.")
        for c in (self.in_combo, self.out_combo, self.bs_combo):
            c.activated.connect(self._restart_audio)
        self.lat_spin = QDoubleSpinBox()
        self.lat_spin.setRange(0, 1000)
        self.lat_spin.setDecimals(1)
        self.lat_spin.setSuffix(" ms")
        self.lat_spin.setValue(float(s.value("latency_ms", 0)))
        self.lat_spin.setToolTip("Round-trip latency compensation.\nApplies to the next takes.")
        self.lat_spin.valueChanged.connect(lambda val: s.setValue("latency_ms", val))
        self.lat_spin.editingFinished.connect(self.lat_spin.clearFocus)
        cb = QPushButton("🎯 Calibrate")
        cb.clicked.connect(self.calibrate)
        self.audio_lbl = QLabel()
        self.audio_lbl.setWordWrap(True)
        al.addWidget(QLabel("Input:"), 0, 0)
        al.addWidget(self.in_combo, 0, 1, 1, 3)
        al.addWidget(QLabel("Output:"), 1, 0)
        al.addWidget(self.out_combo, 1, 1, 1, 3)
        al.addWidget(QLabel("Block:"), 2, 0)
        al.addWidget(self.bs_combo, 2, 1)
        al.addWidget(QLabel("Latency:"), 2, 2)
        lh = QHBoxLayout()
        lh.addWidget(self.lat_spin)
        lh.addWidget(cb)
        al.addLayout(lh, 2, 3)
        al.addWidget(self.audio_lbl, 3, 0, 1, 4)
        sl.addWidget(ag, 3)

        tg = QGroupBox("Processing (export + processed listening)")
        tl = QGridLayout(tg)
        self.hp_spin = QSpinBox(minimum=0, maximum=300, suffix=" Hz")
        self.hp_spin.setValue(int(s.value("hp", 80)))
        self.hp_spin.setToolTip("High-pass filter against rumble and bumps (0 = off)")
        self.chk_gate = QCheckBox("Clean silence (gate)")
        self.chk_gate.setChecked(s.value("gate", "true") == "true")
        self.chk_gate.setToolTip("Replaces the background noise between phrases with true silence")
        self.sens_spin = QDoubleSpinBox(minimum=3, maximum=30, suffix=" dB")
        self.sens_spin.setValue(float(s.value("sens", 10)))
        self.sens_spin.setToolTip("Threshold above the noise floor.\n"
                                  "Higher = cuts harder (raise it if hiss still gets through).")
        self.mode_combo = QComboBox()
        self.mode_combo.addItems(["Perceived loudness (active RMS)", "Peak"])
        self.mode_combo.setCurrentIndex(int(s.value("mode", 0)))
        self.mode_combo.setToolTip("Perceived loudness: every voice sounds equally loud, silences ignored.\n"
                                   "Peak: like the old script.")
        self.target_spin = QDoubleSpinBox(minimum=-40, maximum=-6, suffix=" dBFS")
        self.target_spin.setValue(float(s.value("target", -20)))
        self.target_spin.setToolTip("Target average level while singing (perceived loudness mode)")
        self.ceil_spin = QDoubleSpinBox(minimum=-12, maximum=0, singleStep=0.5, suffix=" dBFS")
        self.ceil_spin.setValue(float(s.value("ceiling", -1)))
        self.ceil_spin.setToolTip("Maximum peak allowed")
        tl.addWidget(QLabel("High-pass:"), 0, 0)
        tl.addWidget(self.hp_spin, 0, 1)
        tl.addWidget(self.chk_gate, 0, 2)
        tl.addWidget(QLabel("Sensitivity:"), 0, 3)
        tl.addWidget(self.sens_spin, 0, 4)
        tl.addWidget(QLabel("Normalise:"), 1, 0)
        tl.addWidget(self.mode_combo, 1, 1, 1, 2)
        tl.addWidget(QLabel("Target:"), 1, 3)
        tl.addWidget(self.target_spin, 1, 4)
        tl.addWidget(QLabel("Ceiling:"), 2, 3)
        tl.addWidget(self.ceil_spin, 2, 4)
        for w in (self.hp_spin, self.sens_spin, self.target_spin, self.ceil_spin):
            w.valueChanged.connect(self._proc_changed)
            w.editingFinished.connect(w.clearFocus)
        self.chk_gate.toggled.connect(self._proc_changed)
        self.mode_combo.currentIndexChanged.connect(self._proc_changed)
        sl.addWidget(tg, 3)

        rg = QGroupBox("Recording & punch-in")
        rl = QGridLayout(rg)
        self.back_spin = QDoubleSpinBox(minimum=0.5, maximum=15, singleStep=0.5, suffix=" s")
        self.back_spin.setValue(float(s.value("back_s", 3.0)))
        self.back_spin.setToolTip("How far ⌫ jumps back while recording.\n"
                                  "Press ⌫ again within 1 s to jump back further.")
        self.back_spin.valueChanged.connect(lambda val: s.setValue("back_s", val))
        self.xfade_spin = QSpinBox(minimum=4, maximum=200, suffix=" ms")
        self.xfade_spin.setValue(int(s.value("xfade_ms", 30)))
        self.xfade_spin.setToolTip("Crossfade length at each punch seam")
        self.xfade_spin.valueChanged.connect(self._xfade_changed)
        self.chk_snap = QCheckBox("Snap seams to silence")
        self.chk_snap.setChecked(s.value("snap", "true") == "true")
        self.chk_snap.setToolTip("Moves each seam to the quietest spot nearby (±200 ms at the punch-in,\n"
                                 "up to 1 s back at the punch-out) so it can't be heard")
        self.chk_snap.toggled.connect(lambda val: s.setValue("snap", "true" if val else "false"))
        self.chk_hear_prev = QCheckBox("Hear my previous take in the pre-roll")
        self.chk_hear_prev.setChecked(s.value("hear_prev", "true") == "true")
        self.chk_hear_prev.setToolTip("While recording, the track you are re-recording plays up to the\n"
                                      "punch point, then goes silent so you only hear the master and yourself")
        self.chk_hear_prev.toggled.connect(self._rec_opts_changed)
        self.chk_light = QCheckBox("Light monitor while recording")
        self.chk_light.setChecked(s.value("light_mon", "false") == "true")
        self.chk_light.setToolTip("While recording, only play the master and the track being recorded")
        self.chk_light.toggled.connect(self._rec_opts_changed)
        rl.addWidget(QLabel("⌫ jumps back:"), 0, 0)
        rl.addWidget(self.back_spin, 0, 1)
        rl.addWidget(QLabel("Crossfade:"), 1, 0)
        rl.addWidget(self.xfade_spin, 1, 1)
        rl.addWidget(self.chk_snap, 2, 0, 1, 2)
        rl.addWidget(self.chk_hear_prev, 3, 0, 1, 2)
        rl.addWidget(self.chk_light, 4, 0, 1, 2)
        self.chk_line_back = QCheckBox("⌫ goes back to the start of the lyric line")
        self.chk_line_back.setChecked(s.value("line_back", "false") == "true")
        self.chk_line_back.setToolTip("When the lyrics are synced, ⌫ jumps to the start of the line you are\n"
                                      "singing (press again for the previous line) instead of a fixed time")
        self.chk_line_back.toggled.connect(lambda val: s.setValue("line_back", "true" if val else "false"))
        rl.addWidget(self.chk_line_back, 5, 0, 1, 2)
        for w in (self.back_spin, self.xfade_spin):
            w.editingFinished.connect(w.clearFocus)
        sl.addWidget(rg, 2)
        v.addWidget(self.settings_box)

        # Transport
        tr = QHBoxLayout()
        self.btn_home = QPushButton("⏮")
        self.btn_home.setToolTip("Go to start (Home)")
        self.btn_home.clicked.connect(self.go_home)
        self.btn_play = QPushButton("▶")
        self.btn_play.setToolTip("Play / pause (Space)")
        self.btn_play.clicked.connect(self.toggle_play)
        self.btn_stop = QPushButton("⏹")
        self.btn_stop.setToolTip("Stop (Esc)")
        self.btn_stop.clicked.connect(self.stop_transport)
        self.btn_rec = QPushButton()
        self.btn_rec.setToolTip("Record a new track (stem)")
        self.btn_rec.clicked.connect(lambda: self.start_session("track"))
        self.btn_take = QPushButton()
        self.btn_take.setToolTip("Re-record the selected track from scratch (old takes are kept)")
        self.btn_take.clicked.connect(lambda: self.start_session("take"))
        self.btn_punch = QPushButton()
        self.btn_punch.setToolTip("Re-record only a part of the selected track, from the playhead.\n"
                                  "When you stop, the old audio comes back after a seamless crossfade.")
        self.btn_punch.clicked.connect(lambda: self.start_session("punch"))
        for b in (self.btn_home, self.btn_play, self.btn_stop):
            b.setFixedSize(42, 34)
        for b in (self.btn_rec, self.btn_take, self.btn_punch):
            b.setFixedHeight(34)
        self.time_lbl = QLabel("0:00.000")
        f = QFont("monospace")
        f.setPointSize(13)
        self.time_lbl.setFont(f)
        self.time_lbl.setMinimumWidth(110)
        self.state_lbl = QLabel("")
        for w in (self.btn_home, self.btn_play, self.btn_stop, self.btn_rec, self.btn_take, self.btn_punch,
                  self.time_lbl, self.state_lbl):
            tr.addWidget(w)
        tr.addSpacing(10)
        tr.addWidget(QLabel("Pre-roll:"))
        self.preroll_spin = QDoubleSpinBox(minimum=0, maximum=10, singleStep=0.5, suffix=" s")
        self.preroll_spin.setValue(float(s.value("preroll", 2.0)))
        self.preroll_spin.setToolTip("How much you hear before the recording point\n"
                                     "(at least 1 s after ⌫, a resume or a punch-in)")
        self.preroll_spin.editingFinished.connect(self.preroll_spin.clearFocus)
        tr.addWidget(self.preroll_spin)
        self.chk_countdown = QCheckBox("Count-in")
        self.chk_countdown.setToolTip("4 clicks before the start point (not recorded)")
        self.chk_countdown.setChecked(s.value("countdown", "false") == "true")
        self.chk_autostop = QCheckBox("Auto stop")
        self.chk_autostop.setToolTip("Stops recording 1 s after the end of the existing tracks")
        self.chk_autostop.setChecked(s.value("autostop", "true") == "true")
        self.chk_autotrim = QCheckBox("Auto trim")
        self.chk_autotrim.setToolTip("Automatically cuts silences and noises (R key, clicks…)\n"
                                     "at the start and end of each new take or import.\n"
                                     "Non-destructive: the handles stay adjustable.")
        self.chk_autotrim.setChecked(s.value("autotrim", "true") == "true")
        self.chk_autotrim.toggled.connect(lambda val: s.setValue("autotrim", "true" if val else "false"))
        self.chk_loop = QCheckBox("Loop (L)")
        self.chk_loop.setToolTip("Drag in the ruler = set the loop · right-click = clear it")
        self.chk_loop.toggled.connect(self._loop_toggled)
        self.chk_processed = QCheckBox("Hear processed")
        self.chk_processed.setToolTip("Listen to the final render (high-pass, gate, normalisation)")
        self.chk_processed.toggled.connect(self.rebuild_mix)
        self.chk_spatial = QCheckBox("Spatial preview")
        self.chk_spatial.setToolTip("Spreads the voices left/right like a group around the player")
        self.chk_spatial.setChecked(s.value("spatial", "false") == "true")
        self.chk_spatial.toggled.connect(self.rebuild_mix)
        for c in (self.chk_countdown, self.chk_autostop, self.chk_autotrim, self.chk_loop,
                  self.chk_processed, self.chk_spatial):
            tr.addWidget(c)
        tr.addStretch(1)
        tr.addWidget(QLabel("🔊"))
        self.vol_slider = QSlider(Qt.Orientation.Horizontal)
        self.vol_slider.setRange(0, 150)
        self.vol_slider.setValue(int(s.value("master_vol", 80)))
        self.vol_slider.setFixedWidth(90)
        self.vol_slider.valueChanged.connect(self._master_vol)
        tr.addWidget(self.vol_slider)
        tr.addWidget(QLabel("🎙"))
        self.meter = QProgressBar()
        self.meter.setRange(0, 600)
        self.meter.setTextVisible(False)
        self.meter.setFixedSize(110, 14)
        tr.addWidget(self.meter)
        self.clip_lbl = QLabel("CLIP")
        tr.addWidget(self.clip_lbl)
        v.addLayout(tr)

        # Track toolbar
        tb = QHBoxLayout()
        self.master_drop = DropLabel("🎤 Drop a file here to set the MASTER",
                                     lambda p: self.import_files(p, as_master=True))
        tb.addWidget(self.master_drop, 2)
        pb = QPushButton("🎤 Prompter (Ctrl+L)")
        pb.setToolTip("Lyrics window: current line in big, next line below, synced with the song")
        pb.clicked.connect(lambda: self.open_prompter())
        self.btn_trash = QPushButton("🗑 Trash")
        self.btn_trash.setToolTip("Deleted tracks, takes and punches of this song — restore them from here")
        self.btn_trash.clicked.connect(self.open_trash)
        ib = QPushButton("＋ Import stems…")
        ib.clicked.connect(self._import_dialog)
        tb_trim = QPushButton("✂ Trim all (auto)")
        tb_trim.setToolTip("Re-trims every track automatically and puts the [ ] markers back in auto mode")
        tb_trim.clicked.connect(self.auto_trim_all)
        tb_mark = QPushButton("[ ] Auto")
        tb_mark.setToolTip("Places the Start/End markers automatically (or double-click a marker)")
        tb_mark.clicked.connect(lambda: self.reset_song_range())
        self.dur_lbl = QLabel("")
        self.dur_lbl.setToolTip("Final exported length (between the [ ] markers)")
        zb = QPushButton("⤢ Fit (F)")
        zb.clicked.connect(lambda: self.timeline.zoom_fit())
        self.chk_autoexport = QCheckBox("Auto export")
        self.chk_autoexport.setChecked(s.value("autoexport", "true") == "true")
        self.chk_autoexport.setToolTip("Re-exports the .ogg files automatically after each change\n"
                                       "(postponed while recording)")
        eb = QPushButton("💾 Export (Ctrl+E)")
        eb.clicked.connect(lambda: self.export_now(False))
        for w in (pb, self.btn_trash, ib, tb_trim, tb_mark, self.dur_lbl, zb, self.chk_autoexport, eb):
            tb.addWidget(w)
        v.addLayout(tb)

        # Current note banner
        self.note_banner = QLabel()
        self.note_banner.setTextFormat(Qt.TextFormat.RichText)
        self.note_banner.setVisible(False)
        v.addWidget(self.note_banner)

        # Timeline + tracks
        split = QSplitter(Qt.Orientation.Vertical)
        self.timeline = Timeline(self)
        split.addWidget(self.timeline)
        self.rows_host = DropArea(lambda p: self.import_files(p), studio=self)
        self.rows_layout = QVBoxLayout(self.rows_host)
        self.rows_layout.setContentsMargins(0, 0, 0, 0)
        self.rows_layout.setSpacing(3)
        self.rows_layout.addStretch(1)
        sa = QScrollArea()
        sa.setWidgetResizable(True)
        sa.setWidget(self.rows_host)
        split.addWidget(sa)
        split.setSizes([450, 240])
        v.addWidget(split, 1)

        self.hint = QLabel(
            "Space play/pause (while recording: pause/resume) · R new stem · T new take · P punch in · "
            "⌫ jump back & retake · Esc stop/finish · N note (double-click or drag in the notes lane) · "
            "Ctrl+L prompter · Enter / Shift+Enter sync the next / previous lyric line while playing · "
            "PgUp/PgDn previous/next note · click = move the playhead · Ctrl+drag = move a track · "
            "drag in the ruler = loop · clip edges = trim (double-click = auto) · seams ◆ = drag / right-click · "
            "C auto trim · Shift+C clear trim · I/O start/end markers · Ctrl+wheel zoom · ←/→ nudge · "
            "M/S mute/solo · Del delete track · Shift+Del delete take · Ctrl+Z / Ctrl+Shift+Z undo/redo")
        self.hint.setWordWrap(True)
        v.addWidget(self.hint)

        for w in root.findChildren(QAbstractButton) + root.findChildren(QSlider):
            w.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.setStyleSheet("QPushButton#mute:checked { background:#c62828; color:white; }"
                           "QPushButton#solo:checked { background:#f9a825; color:black; }")

        self.shortcuts = []
        for key, fn in (("Space", self.toggle_play), ("R", lambda: self.start_session("track")),
                        ("T", lambda: self.start_session("take")), ("P", lambda: self.start_session("punch")),
                        ("Backspace", self.back_step), ("Escape", self.stop_transport),
                        ("Home", self.go_home), ("Left", lambda: self._nudge_sel(-5)),
                        ("Right", lambda: self._nudge_sel(5)), ("Shift+Left", lambda: self._nudge_sel(-1)),
                        ("Shift+Right", lambda: self._nudge_sel(1)), ("Delete", self._delete_sel),
                        ("Shift+Delete", self._delete_sel_take),
                        ("M", lambda: self._toggle_sel("mute")), ("S", lambda: self._toggle_sel("solo")),
                        ("L", lambda: self.chk_loop.toggle()), ("F", lambda: self.timeline.zoom_fit()),
                        ("Ctrl+Z", self.undo), ("Ctrl+Shift+Z", self.redo), ("Ctrl+Y", self.redo),
                        ("Ctrl+E", lambda: self.export_now(False)),
                        ("Up", lambda: self._select_rel(-1)), ("Down", lambda: self._select_rel(1)),
                        ("I", lambda: self.marker_at_playhead("s")), ("O", lambda: self.marker_at_playhead("e")),
                        ("C", lambda: self.selected and self.auto_trim(self.selected)),
                        ("Shift+C", self.untrim_selected), ("N", self.note_at_playhead),
                        ("PgUp", lambda: self.jump_note(-1)), ("PgDown", lambda: self.jump_note(1)),
                        ("Return", self.lyric_tap), ("Enter", self.lyric_tap),
                        ("Shift+Return", lambda: self.lyric_tap(back=True)),
                        ("Shift+Enter", lambda: self.lyric_tap(back=True)),
                        ("Ctrl+L", lambda: self.open_prompter())):
            sc = QShortcut(QKeySequence(key), self)
            if key in self.GLOBAL_KEYS:
                sc.setContext(Qt.ShortcutContext.ApplicationShortcut)
            sc.activated.connect(fn)
            self.shortcuts.append(sc)
        self._update_transport_ui()
        self._refresh_langs()

    GLOBAL_KEYS = {"Space", "R", "T", "P", "Backspace", "Escape", "Home", "N", "PgUp", "PgDown",
                   "Return", "Enter", "Shift+Return", "Shift+Enter", "Ctrl+L", "Ctrl+Z", "Ctrl+Shift+Z", "Ctrl+Y"}

    def _on_focus(self, old, new):
        # typing in a field, or a dialog open: keys go to the field / the dialog
        typing = isinstance(new, (QLineEdit, QAbstractSpinBox, QPlainTextEdit, QTextEdit))
        blocked = typing or QApplication.activeModalWidget() is not None
        for sc in self.shortcuts:
            sc.setEnabled(not blocked)

    # ─────────────────────────────────────────────── theme
    def apply_theme(self, force=False):
        """Uses the desktop (KDE) palette everywhere instead of hard-coded greys."""
        key = QApplication.palette().cacheKey()
        if key == self._pal_key and not force:
            return
        self._pal_key = key
        p = self.palette()
        fg, bg = p.color(QPalette.ColorRole.WindowText), p.color(QPalette.ColorRole.Window)
        hl = p.color(QPalette.ColorRole.Highlight)
        self.dark = bg.lightness() < 128
        self.c_muted = blend(fg, bg, 0.72).name()
        self.proj_lbl.setStyleSheet(f"color:{self.c_muted};")
        self.hint.setStyleSheet(f"color:{self.c_muted}; font-size:9pt;")
        self.dur_lbl.setStyleSheet(f"color:{fg.name()}; padding:0 6px;")
        self.note_banner.setStyleSheet(f"background:{blend(hl, bg, 0.18).name()}; color:{fg.name()};"
                                       "border-radius:5px; padding:4px 8px; font-size:11pt;")
        self.master_drop._style(False)
        self._set_audio_status(*self._audio_status)
        self._clip_on = None
        self._banner_text = None
        self._update_transport_ui()
        self._rebuild_rows()
        self.timeline.invalidate()
        self.thumb_cache = {}
        if self.trash_win and self.trash_win.isVisible():
            self.trash_win.refresh()

    def changeEvent(self, e):
        super().changeEvent(e)
        if e.type() in (QEvent.Type.PaletteChange, QEvent.Type.ApplicationPaletteChange):
            QTimer.singleShot(0, self.apply_theme)

    def _set_audio_status(self, text, ok):
        self._audio_status = (text, ok)
        col = ("#6cc070" if self.dark else "#2e7d32") if ok else ("#e57373" if self.dark else "#c62828")
        self.audio_lbl.setText(text)
        self.audio_lbl.setStyleSheet(f"color:{col};")

    # ─────────────────────────────────────────────── audio
    def _populate_devices(self):
        try:
            devs = sd.query_devices()
            apis = sd.query_hostapis()
        except Exception:
            devs, apis = [], []
        for combo, kind, key in ((self.in_combo, "max_input_channels", "in_dev"),
                                 (self.out_combo, "max_output_channels", "out_dev")):
            combo.blockSignals(True)
            combo.clear()
            combo.addItem("System default (PipeWire)", None)
            for i, d in enumerate(devs):
                if d[kind] > 0:
                    combo.addItem(f"{d['name']}  [{apis[d['hostapi']]['name']}]", i)
            saved = self.settings.value(key, "")
            idx = combo.findText(saved) if saved else 0
            combo.setCurrentIndex(max(0, idx))
            combo.blockSignals(False)

    def _restart_audio(self, *_):
        self._flush_session()
        self._hard_stop()
        bs = int(self.bs_combo.currentText())
        os.environ["PIPEWIRE_LATENCY"] = f"{bs}/{SR}"
        self.settings.setValue("blocksize", bs)
        self.settings.setValue("in_dev", self.in_combo.currentText())
        self.settings.setValue("out_dev", self.out_combo.currentText())
        self.engine.start_stream(self.in_combo.currentData(), self.out_combo.currentData(), bs)
        self.engine.master_gain = self.vol_slider.value() / 100
        if self.engine.ok:
            est = self.engine.default_latency * 1000 / SR
            calibrated = self.settings.value("calibrated", "false") == "true"
            if not calibrated:
                self.lat_spin.setValue(round(est, 1))
            cal = "calibrated" if calibrated else "estimated (calibrate for better accuracy)"
            self._set_audio_status(f"✔ 48 kHz · block {bs} · PortAudio latency {est:.1f} ms · "
                                   f"compensation {cal}", True)
            self.rebuild_mix()
        else:
            self._set_audio_status(f"✖ Audio unavailable: {self.engine.error}", False)

    def _master_vol(self, v):
        self.engine.master_gain = v / 100
        self.settings.setValue("master_vol", v)

    def _lat(self):
        return int(round(self.lat_spin.value() * SR / 1000))

    def xfade(self):
        return int(self.xfade_spin.value() * SR / 1000)

    def _xfade_changed(self, v):
        self.settings.setValue("xfade_ms", v)
        self.touch()
        self.rebuild_mix()
        self.schedule_export()

    def _rec_opts_changed(self, *_):
        self.settings.setValue("hear_prev", "true" if self.chk_hear_prev.isChecked() else "false")
        self.settings.setValue("light_mon", "true" if self.chk_light.isChecked() else "false")
        self.rebuild_mix()

    def proc_params(self):
        return {"hp": float(self.hp_spin.value()), "gate": self.chk_gate.isChecked(),
                "sens": float(self.sens_spin.value()),
                "mode": "rms" if self.mode_combo.currentIndex() == 0 else "peak",
                "target": float(self.target_spin.value()), "ceiling": float(self.ceil_spin.value())}

    def _proc_changed(self, *_):
        s = self.settings
        s.setValue("hp", self.hp_spin.value())
        s.setValue("gate", "true" if self.chk_gate.isChecked() else "false")
        s.setValue("sens", self.sens_spin.value())
        s.setValue("mode", self.mode_combo.currentIndex())
        s.setValue("target", self.target_spin.value())
        s.setValue("ceiling", self.ceil_spin.value())
        self.target_spin.setEnabled(self.mode_combo.currentIndex() == 0)
        self.sens_spin.setEnabled(self.chk_gate.isChecked())
        if self.chk_processed.isChecked():
            self.rebuild_mix()
        self.schedule_export()

    # take audio: composite of the passes, cached
    def take_ok(self, tk):
        return bool(tk["passes"]) and all(p["file"] in self.audio for p in tk["passes"])

    def take_data(self, tk):
        """(composite samples, peaks) of a take, or None if a file is missing."""
        if not self.take_ok(tk):
            return None
        key = take_key(tk, self.xfade())
        c = self.comp_cache.get(key)
        if c is None:
            if len(self.comp_cache) > 24:
                self.comp_cache.clear()
            x = compose_take(tk, self.audio.__getitem__, self.xfade())
            c = (x, compute_peaks(x))
            self.comp_cache[key] = c
        return c

    def take_audio(self, tk):
        d = self.take_data(tk)
        return None if d is None else d[0]

    def processed(self, t):
        tk = t["takes"][t["active"]]
        p = self.proc_params()
        trim = self.trim_of(tk)
        key = (take_key(tk, self.xfade()), t["gain_db"], trim, tuple(sorted(p.items())))
        if key not in self.proc_cache:
            if len(self.proc_cache) > 40:
                self.proc_cache.clear()
            QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
            try:
                self.proc_cache[key] = process_take(self.take_audio(tk), p, t["gain_db"], trim)
            finally:
                QApplication.restoreOverrideCursor()
        return self.proc_cache[key]

    def any_solo(self):
        return any(t["solo"] and t["enabled"] for t in self.tracks)

    def rebuild_mix(self, *_):
        solo = self.any_solo()
        n = len(self.tracks)
        ctx = self.rec_ctx
        target = ctx["session"]["tid"] if ctx else None
        light = bool(ctx) and self.chk_light.isChecked()
        mix = []
        for i, t in enumerate(self.tracks):
            if not t["enabled"] or t["mute"] or (solo and not t["solo"]):
                continue
            end = None
            if ctx:
                is_target = t["id"] == target
                if light and not (t["master"] or is_target):
                    continue
                if is_target:
                    if not self.chk_hear_prev.isChecked():
                        continue
                    end = ctx["cut"]          # your old voice stops where the new recording starts
            tk = t["takes"][t["active"]]
            if not self.take_ok(tk):
                continue
            x = self.processed(t) if self.chk_processed.isChecked() else self.raw_trimmed(tk)
            pan = (-0.8 + 1.6 * i / (n - 1)) if (self.chk_spatial.isChecked() and n > 1) else 0.0
            mix.append((x, tk["offset"], t["vol"] * (1 - max(0.0, pan)), t["vol"] * (1 + min(0.0, pan)), end))
        self.engine.mix = mix
        self.settings.setValue("spatial", "true" if self.chk_spatial.isChecked() else "false")

    # ─────────────────────────────────────────────── project
    SOUNDS_SUBPATH = os.path.join("src", "main", "resources", "assets", "travellers", "sounds", "travellers")

    def _resolve_base(self, path):
        """Accepts the Gradle project root, the sounds/ folder or sounds/travellers/."""
        path = os.path.abspath(os.path.expanduser(path.strip())) if path.strip() else ""
        if not path:
            return path
        full = os.path.join(path, self.SOUNDS_SUBPATH)
        if os.path.isdir(full):                          # mod root
            return full
        sub = os.path.join(path, "travellers")
        if os.path.basename(path) == "sounds" and os.path.isdir(sub):
            return sub
        return path

    def _browse_base(self):
        d = QFileDialog.getExistingDirectory(
            self, "Mod root, or …/assets/travellers/sounds/travellers folder", self.base_edit.text())
        if d:
            self.base_edit.setText(self._resolve_base(d))
            self._refresh_langs()

    def _browse_work(self):
        d = QFileDialog.getExistingDirectory(self, "Studio workspace (raw takes)", self.work_edit.text())
        if d:
            self.work_edit.setText(d)
            self.settings.setValue("work_dir", d)

    @staticmethod
    def _subdirs(d, exclude=()):
        if not os.path.isdir(d):
            return []
        return sorted(x for x in os.listdir(d) if os.path.isdir(os.path.join(d, x))
                      and not x.startswith(".") and x.lower() not in exclude)

    def _set_items(self, combo, items):
        cur = combo.currentText()
        combo.blockSignals(True)
        combo.clear()
        combo.addItems(items)
        combo.setCurrentText(cur)
        combo.blockSignals(False)

    def _refresh_langs(self, *_):
        base = self._resolve_base(self.base_edit.text())
        if base != self.base_edit.text().strip():
            self.base_edit.setText(base)
        self.settings.setValue("base_dir", base)
        # "raiders" is a sibling of the language folders: ignored (like the gradle task does)
        self._set_items(self.lang_combo, self._subdirs(base, exclude=("raiders",)))
        self._refresh_pers()

    def _refresh_pers(self, *_):
        d = os.path.join(self.base_edit.text().strip(), mc_name(self.lang_combo.currentText()))
        self._set_items(self.pers_combo, self._subdirs(d))
        self._refresh_choirs()

    def _refresh_choirs(self, *_):
        d = os.path.join(self.base_edit.text().strip(), mc_name(self.lang_combo.currentText()),
                         mc_name(self.pers_combo.currentText()), "choir")
        self._set_items(self.choir_combo, self._subdirs(d))

    def _open_folder(self):
        if self.project_dir:
            subprocess.Popen(["xdg-open", self.project_dir])

    @property
    def studio_dir(self):
        return self.work_project_dir

    def open_project(self):
        lang = mc_name(self.lang_combo.currentText())
        pers = mc_name(self.pers_combo.currentText())
        choir = mc_name(self.choir_combo.currentText())
        if not lang or not pers or not choir:
            QMessageBox.warning(self, "Project", "Enter a language, a personality and a choir name.")
            return
        base = self.base_edit.text().strip()
        if not os.path.isdir(base):
            QMessageBox.warning(self, "Project", f"Sounds folder not found:\n{base}")
            return
        pers_dir = os.path.join(base, lang, pers)
        if not os.path.isdir(pers_dir) and QMessageBox.question(
                self, "New personality",
                f"The personality “{lang}/{pers}” doesn't exist yet.\nCreate it?") != QMessageBox.StandardButton.Yes:
            return
        self._flush_session()                  # never lose a take that is being recorded
        self._hard_stop()
        if self.lap_picker:
            self.lap_picker.close()
        if self.worker and self.worker.isRunning():
            self.worker.wait()
        if self.export_timer.isActive() and self.project_dir:
            self.export_timer.stop()
            self.export_now(silent=True)       # pending changes of the project being left
            if self.worker:
                self.worker.wait()
        self.lang_combo.setCurrentText(lang)
        self.pers_combo.setCurrentText(pers)
        self.choir_combo.setCurrentText(choir)
        d = os.path.join(pers_dir, "choir", choir)
        work_root = os.path.expanduser(self.work_edit.text().strip() or DEFAULT_WORK_DIR)
        self.settings.setValue("work_dir", work_root)
        wd = os.path.join(work_root, lang, pers, choir)
        os.makedirs(d, exist_ok=True)
        # Migration: older versions stored .studio/ INSIDE the resources (hence inside the jar)
        legacy = os.path.join(d, ".studio")
        if os.path.isdir(legacy) and not os.path.exists(os.path.join(wd, "project.json")):
            os.makedirs(os.path.dirname(wd), exist_ok=True)
            if os.path.exists(wd):
                shutil.rmtree(wd)
            shutil.move(legacy, wd)
        elif os.path.isdir(legacy):
            shutil.rmtree(legacy)
        stale = os.path.join(d, "choir.json")
        if os.path.exists(stale):
            os.remove(stale)
        os.makedirs(os.path.join(wd, "takes"), exist_ok=True)
        self.work_project_dir = wd
        self.project_dir, self.lang, self.pers, self.choir = d, lang, pers, choir
        self.tracks, self.notes, self.exported = [], [], []
        self.lyrics, self.trash, self.thumb_cache = [], [], {}
        self.tap_next = None
        self.audio, self.comp_cache, self.proc_cache, self.raw_cache = {}, {}, {}, {}
        self.undo_stack, self.redo_stack, self.selected = [], [], None
        self.playhead, self.loop_region = 0, None
        self.song_range = None
        self.gen_info.clear()
        pj = os.path.join(self.studio_dir, "project.json")
        if os.path.exists(pj):
            with open(pj) as fh:
                data = json.load(fh)
            self.tracks = data.get("tracks", [])
            self.exported = data.get("exported", [])
            self.song_range = data.get("song_range")
            self.notes = data.get("notes", [])
            for n in self.notes:
                n.setdefault("id", uuid.uuid4().hex[:8])
                n.setdefault("end", None)
                n.setdefault("text", "")
                n.setdefault("color", None)
            self.lyrics = [{"text": ln.get("text", ""), "t": ln.get("t")} for ln in data.get("lyrics", [])]
            self.trash = data.get("trash", [])
            for item in self.trash:
                for tk in self._item_takes(item):
                    migrate_take(tk)
            missing = 0
            for t in self.tracks:
                for k in ("enabled", "mute", "solo"):
                    t.setdefault(k, k == "enabled")
                t.setdefault("vol", 1.0)
                t.setdefault("gain_db", 0.0)
                t.setdefault("color", 0)
                ok = []
                for tk in t["takes"]:
                    needs_trim = "trim" not in tk
                    migrate_take(tk)
                    if not all(self._load_file(p["file"]) for p in tk["passes"]):
                        missing += 1
                        continue
                    tk.setdefault("trim", None)
                    # old projects: auto trim for takes that never had one
                    if needs_trim and self.chk_autotrim.isChecked():
                        tk["trim"] = list(detect_voice_range(self.take_audio(tk), self.sens_spin.value()))
                    ok.append(tk)
                t["takes"] = ok
                t["active"] = min(t.get("active", 0), max(0, len(ok) - 1))
            self.tracks = [t for t in self.tracks if t["takes"]]
            if missing:
                QMessageBox.warning(self, "Project", f"{missing} take(s) with missing audio files were skipped.")
        else:
            oggs = sorted(f for f in os.listdir(d) if f.lower().endswith(".ogg"))
            if oggs and QMessageBox.question(
                    self, "Existing sounds",
                    f"{len(oggs)} .ogg file(s) already exist in this choir.\n"
                    "Import them as tracks (aligned on t=0, the first one as master)?\n\n"
                    "They will be replaced by the studio export.") == QMessageBox.StandardButton.Yes:
                self.exported = list(oggs)
                self.import_files([os.path.join(d, f) for f in oggs], undo=False)
        self.settings.setValue("lang", lang)
        self.settings.setValue("pers", pers)
        self.settings.setValue("choir", choir)
        self._scan_orphans()
        self.setWindowTitle(f"Choir Studio — {lang} / {pers} / {choir}")
        self.proj_lbl.setText(f"🎵 Export: {d}\n💾 Takes: {wd}")
        self._refresh_pers()
        self.refresh_all(export=False)
        self.trash_changed()
        if self.prompter:
            self.prompter.setWindowTitle(f"Prompter — {choir}")
            self.prompter.edit_mode(False)
        self.timeline.zoom_fit()
        self.statusBar().showMessage(f"Project opened: {lang}/{pers}/choir/{choir}", 4000)

    def _load_file(self, fname):
        if fname in self.audio:
            return True
        path = os.path.join(self.studio_dir, fname)
        if not os.path.exists(path):
            return False
        x, _ = sf.read(path, dtype="float32", always_2d=True)
        self.audio[fname] = np.ascontiguousarray(x.mean(axis=1))
        return True

    def _store_take(self, x):
        fname = f"takes/{uuid.uuid4().hex[:12]}.wav"
        sf.write(os.path.join(self.studio_dir, fname), x, SR, subtype="FLOAT")
        self.audio[fname] = x
        return fname

    def save_project(self):
        if not self.project_dir:
            return
        pj = os.path.join(self.studio_dir, "project.json")
        tmp = pj + ".tmp"
        with open(tmp, "w") as fh:
            json.dump({"version": PROJECT_VERSION, "tracks": self.tracks, "exported": self.exported,
                       "song_range": self.song_range, "notes": self.notes, "lyrics": self.lyrics,
                       "trash": self.trash}, fh, indent=1)
        os.replace(tmp, pj)

    # ─────────────────────────────────────────────── model
    def busy(self):
        """True while recording (or paused inside a recording) or calibrating."""
        return self.state in ("rec", "rec_pause", "calib")

    def track(self, tid):
        return next((t for t in self.tracks if t["id"] == tid), None)

    def master(self):
        return next((t for t in self.tracks if t["master"]), None)

    def bounds(self):
        s = e = None
        for t in self.tracks:
            tk = t["takes"][t["active"]]
            x = self.take_audio(tk)
            if x is None:
                continue
            s = tk["offset"] if s is None else min(s, tk["offset"])
            e = tk["offset"] + len(x) if e is None else max(e, tk["offset"] + len(x))
        return (s or 0, e or 0)

    def _new_take(self, fname, offset):
        tk = {"id": uuid.uuid4().hex[:8], "offset": int(offset), "trim": None,
              "passes": [{"file": fname, "rel": 0, "in": None, "out": None}]}
        if self.chk_autotrim.isChecked():
            tk["trim"] = list(detect_voice_range(self.audio[fname], self.sens_spin.value()))
        return tk

    def _new_track(self, name, take, master=False):
        used = {t["color"] for t in self.tracks}
        color = next((c for c in range(len(COLORS)) if c not in used), len(self.tracks))
        return {"id": uuid.uuid4().hex[:8], "name": name, "master": master,
                "takes": [take], "active": 0,
                "enabled": True, "mute": False, "solo": False, "vol": 1.0, "gain_db": 0.0,
                "color": color}

    def touch(self):
        self.model_version += 1
        self.timeline.invalidate()

    def _snapshot(self):
        return json.loads(json.dumps({"tracks": self.tracks, "song_range": self.song_range,
                                      "notes": self.notes, "lyrics": self.lyrics, "trash": self.trash}))

    def push_undo(self):
        self.undo_stack.append(self._snapshot())
        del self.undo_stack[:-100]
        self.redo_stack.clear()

    def _restore(self, st):
        self.tracks, self.song_range, self.notes = st["tracks"], st["song_range"], st.get("notes", [])
        self.lyrics, self.trash = st.get("lyrics", []), st.get("trash", [])
        self.tap_next = None
        self.trash_changed()
        for t in self.tracks:
            for tk in t["takes"]:
                for p in tk["passes"]:
                    self._load_file(p["file"])
        if self.selected and not self.track(self.selected):
            self.selected = None
        self.refresh_all()

    def undo(self):
        if not self.undo_stack or self.busy():
            return
        self.redo_stack.append(self._snapshot())
        self._restore(self.undo_stack.pop())
        self.statusBar().showMessage("Undone (Ctrl+Shift+Z to redo)", 2000)

    def redo(self):
        if not self.redo_stack or self.busy():
            return
        self.undo_stack.append(self._snapshot())
        self._restore(self.redo_stack.pop())
        self.statusBar().showMessage("Redone", 2000)

    def _rebuild_rows(self):
        while self.rows_layout.count() > 1:
            w = self.rows_layout.takeAt(0).widget()
            if w:
                w.deleteLater()
        self.rows = {}
        for i, t in enumerate(self.tracks):
            r = TrackRow(self, t, COLORS[t["color"] % len(COLORS)])
            r.set_selected(t["id"] == self.selected)
            self.rows_layout.insertWidget(i, r)
            self.rows[t["id"]] = r

    def refresh_all(self, export=True):
        if self.tracks and not self.master():
            self.tracks[0]["master"] = True
        self.tracks.sort(key=lambda t: not t["master"])
        self._rebuild_rows()
        self.touch()
        self.rebuild_mix()
        self.save_project()
        self._update_duration()
        self._banner_text = None
        if export:
            self.schedule_export()

    def select(self, tid):
        self.selected = tid
        for i, r in self.rows.items():
            r.set_selected(i == tid)
        self.timeline.update()

    def _select_rel(self, d):
        if not self.tracks:
            return
        ids = [t["id"] for t in self.tracks]
        i = ids.index(self.selected) + d if self.selected in ids else 0
        self.select(ids[max(0, min(len(ids) - 1, i))])

    def set_master(self, tid):
        t = self.track(tid)
        if not t or t["master"] or self.busy():
            return
        self.push_undo()
        for x in self.tracks:
            x["master"] = x is t
        self.refresh_all()

    def rename(self, tid, name):
        t = self.track(tid)
        name = name.strip()
        if t and name and name != t["name"]:
            self.push_undo()
            t["name"] = name
            self.save_project()
            self.touch()

    def set_active_take(self, tid, i):
        t = self.track(tid)
        if self.busy():
            self._rebuild_rows()          # puts the combo back on the take being recorded
            return
        if t and 0 <= i < len(t["takes"]) and i != t["active"]:
            self.push_undo()
            t["active"] = i
            self.refresh_all()

    def delete_clicked(self, tid, button):
        """✕ button: deletes the track, or asks take/track when there are several takes."""
        t = self.track(tid)
        if not t or self.busy():
            return
        if len(t["takes"]) < 2:
            self.delete_track(tid)
            return
        menu = QMenu(self)
        a_take = menu.addAction(f"Delete take {t['active'] + 1}")
        a_track = menu.addAction(f"Delete the whole track ({len(t['takes'])} takes)")
        act = menu.exec(button.mapToGlobal(QPoint(0, button.height())))
        if act is a_take:
            self.delete_take(tid)
        elif act is a_track:
            self.delete_track(tid)

    def delete_take(self, tid):
        t = self.track(tid)
        if not t or self.busy():
            return
        self.push_undo()
        n = t["active"] + 1
        tk = t["takes"].pop(t["active"])
        self._to_trash("take", f"Take {n} of “{t['name']}”", take=tk, track_id=t["id"], track_name=t["name"])
        if not t["takes"]:
            self.tracks.remove(t)
            self.selected = None if self.selected == tid else self.selected
        else:
            t["active"] = min(t["active"], len(t["takes"]) - 1)
        self.refresh_all()
        self.statusBar().showMessage(f"Take {n} moved to the trash — Ctrl+Z to undo", 4000)

    def delete_track(self, tid):
        t = self.track(tid)
        if not t or self.busy():
            return
        self.push_undo()
        self.tracks.remove(t)
        self._to_trash("track", f"Track “{t['name']}”", track=t)
        if self.selected == tid:
            self.selected = None
        self.refresh_all()
        self.statusBar().showMessage(f"“{t['name']}” moved to the trash — Ctrl+Z to undo", 5000)

    def set_field(self, tid, key, value):
        t = self.track(tid)
        if not t or t[key] == value:
            return
        if key == "enabled":
            self.push_undo()
            t[key] = value
            self.refresh_all()
            return
        t[key] = value
        self.touch()
        self.rebuild_mix()
        self.save_project()
        if key == "gain_db":
            self.schedule_export()

    def _toggle_sel(self, key):
        t = self.track(self.selected)
        if t:
            self.set_field(t["id"], key, not t[key])
            self.refresh_all(export=False)

    def _delete_sel(self):
        if self.selected:
            self.delete_track(self.selected)

    def _delete_sel_take(self):
        if self.selected:
            self.delete_take(self.selected)

    def nudge(self, tid, ms):
        t = self.track(tid)
        if not t or self.busy():
            return
        self.push_undo()
        tk = t["takes"][t["active"]]
        self.set_offset_live(t, tk["offset"] + ms * SR / 1000)
        self.commit_offset()

    def _nudge_sel(self, ms):
        if self.selected:
            self.nudge(self.selected, ms)

    def set_offset_live(self, t, off):
        t["takes"][t["active"]]["offset"] = int(round(off))
        if t["id"] in self.rows:
            self.rows[t["id"]].update_offset(t["takes"][t["active"]]["offset"])
        self.touch()
        self.rebuild_mix()

    def commit_offset(self):
        self.save_project()
        self.schedule_export()

    # ─────────────────────────────────────────────── trims
    def trim_of(self, tk):
        x = self.take_audio(tk)
        n = 0 if x is None else len(x)
        tr = tk.get("trim")
        if not tr or n == 0:
            return (0, n)
        a = max(0, min(int(tr[0]), n))
        b = max(a, min(int(tr[1]), n))
        return (a, b) if b - a >= MIN_TRIM else (0, n)

    def raw_trimmed(self, tk):
        x = self.take_audio(tk)
        tr = self.trim_of(tk)
        if is_full(tr, len(x)):
            return x
        key = (take_key(tk, self.xfade()), tr)
        if key not in self.raw_cache:
            if len(self.raw_cache) > 40:
                self.raw_cache.clear()
            self.raw_cache[key] = x * trim_mask(len(x), tr)
        return self.raw_cache[key]

    def set_trim_live(self, t, which, pos):
        tk = t["takes"][t["active"]]
        n = len(self.take_audio(tk))
        a, b = self.trim_of(tk)
        v = int(pos) - tk["offset"]
        if which == "a":
            a = max(0, min(v, b - MIN_TRIM))
        else:
            b = min(n, max(v, a + MIN_TRIM))
        tk["trim"] = [a, b]
        self.touch()

    def commit_trim(self):
        self.rebuild_mix()
        self.save_project()
        self.schedule_export()
        self._update_duration()

    def auto_trim(self, tid, undo=True):
        t = self.track(tid)
        if not t or self.busy():
            return
        tk = t["takes"][t["active"]]
        x = self.take_audio(tk)
        if x is None:
            return
        if undo:
            self.push_undo()
        tk["trim"] = list(detect_voice_range(x, self.sens_spin.value()))
        self.touch()
        self.commit_trim()
        a, b = tk["trim"]
        self.statusBar().showMessage(f"“{t['name']}”: {a / SR:.2f} s cut at the start, "
                                     f"{(len(x) - b) / SR:.2f} s at the end (Ctrl+Z to undo)", 5000)

    def auto_trim_all(self):
        if not self.tracks or self.busy():
            return
        self.push_undo()
        for t in self.tracks:
            self.auto_trim(t["id"], undo=False)
        self.reset_song_range(undo=False)
        self.statusBar().showMessage("All tracks trimmed automatically (Ctrl+Z to undo)", 5000)

    def untrim_selected(self):
        t = self.track(self.selected)
        if not t or self.busy():
            return
        self.push_undo()
        t["takes"][t["active"]]["trim"] = None
        self.touch()
        self.commit_trim()

    def auto_song_range(self):
        s = e = None
        for t in self.tracks:
            if not t["enabled"]:
                continue
            tk = t["takes"][t["active"]]
            if not self.take_ok(tk):
                continue
            a, b = self.trim_of(tk)
            s = tk["offset"] + a if s is None else min(s, tk["offset"] + a)
            e = tk["offset"] + b if e is None else max(e, tk["offset"] + b)
        return None if s is None else (s, e)

    def song_range_eff(self):
        return tuple(self.song_range) if self.song_range else self.auto_song_range()

    def make_song_range_manual(self):
        if not self.song_range:
            r = self.auto_song_range()
            if r:
                self.song_range = list(r)

    def set_song_marker(self, which, pos):
        self.make_song_range_manual()
        if not self.song_range:
            return
        s, e = self.song_range
        if which == "s":
            s = min(int(pos), e - MIN_TRIM)
        else:
            e = max(int(pos), s + MIN_TRIM)
        self.song_range = [s, e]
        self._update_duration()
        self.timeline.update()

    def marker_at_playhead(self, which):
        if not self.tracks or self.busy():
            return
        self.push_undo()
        self.set_song_marker(which, self.playhead)
        self.commit_trim()

    def reset_song_range(self, undo=True):
        if undo and self.song_range is not None:
            self.push_undo()
        self.song_range = None
        self.timeline.update()
        self.commit_trim()

    def _update_duration(self):
        r = self.song_range_eff()
        if not r:
            self.dur_lbl.setText("")
            return
        sec = (r[1] - r[0]) / SR
        mode = "manual" if self.song_range else "auto"
        self.dur_lbl.setText(f"⏱ {sec:.2f} s · {int(math.ceil(sec * 20))} ticks ({mode})")

    # ─────────────────────────────────────────────── punch seams
    def clamp_seam(self, tk, i, which, v):
        p = tk["passes"][i]
        n = len(self.audio[p["file"]])
        rel, h = int(p["rel"]), self.xfade() // 2
        lo, hi = max(0, rel) + h, rel + n - h
        gap = 2 * h + SEAM_HOP
        if which == "in" and p.get("out") is not None:
            hi = min(hi, int(p["out"]) - gap)
        if which == "out" and p.get("in") is not None:
            lo = max(lo, int(p["in"]) + gap)
        return int(min(max(v, lo), max(lo, hi)))

    def set_seam(self, t, i, which, v):
        tk = t["takes"][t["active"]]
        if i >= len(tk["passes"]):
            return
        v = self.clamp_seam(tk, i, which, v)
        if tk["passes"][i].get(which) == v:
            return
        self.push_undo()
        tk["passes"][i][which] = v
        self.refresh_all()

    def remove_pass(self, t, i):
        tk = t["takes"][t["active"]]
        if self.busy() or not 0 < i < len(tk["passes"]):
            return
        self.push_undo()
        p = tk["passes"].pop(i)
        self._to_trash("punch", f"Punch on “{t['name']}” at {fmt_time(tk['offset'] + (p['in'] or p['rel']))}",
                       pas=p, take_id=tk.get("id"), take_offset=tk["offset"], track_id=t["id"], track_name=t["name"])
        self.refresh_all()
        self.statusBar().showMessage("Punch removed (it is in the trash), previous audio restored", 4000)

    def clip_menu(self, t, seam, pos, gpos):
        """Right-click on a track in the timeline."""
        menu = QMenu(self)
        acts = {}
        busy = self.busy()
        if seam and seam[1] > 0 and not busy:
            acts[menu.addAction("Remove this punch (restore the previous audio)")] = \
                lambda: self.remove_pass(t, seam[1])
            menu.addSeparator()
        if not busy:
            acts[menu.addAction("Punch in from here  (P)")] = lambda: (self.seek(pos), self.start_session("punch"))
        acts[menu.addAction("Add a note here  (N)")] = lambda: self.add_note(pos)
        if not busy:
            menu.addSeparator()
            if len(t["takes"]) > 1:
                acts[menu.addAction(f"Delete take {t['active'] + 1}  (Shift+Del)")] = lambda: self.delete_take(t["id"])
            acts[menu.addAction("Delete track  (Del)")] = lambda: self.delete_track(t["id"])
        act = menu.exec(gpos)
        if act in acts:
            acts[act]()

    # ─────────────────────────────────────────────── notes
    def notes_changed(self):
        self.notes.sort(key=lambda n: n["t"])
        self.save_project()
        self.touch()
        self._banner_text = None

    def add_note(self, t, end=None):
        if not self.project_dir:
            return
        self.push_undo()
        n = {"id": uuid.uuid4().hex[:8], "t": int(t), "end": None if end is None else int(end), "text": ""}
        self.notes.append(n)
        self.notes_changed()
        if self.state in ("rec", "play"):
            self.statusBar().showMessage("Empty note added — double-click it later to write it", 3000)
            return
        text, ok = QInputDialog.getText(self, "New note", "Note:", QLineEdit.EchoMode.Normal, "")
        if not ok:                          # cancelled: as if nothing happened
            self.notes.remove(n)
            if self.undo_stack:
                self.undo_stack.pop()
            self.notes_changed()
            return
        n["text"] = text.strip()
        self.notes_changed()

    def edit_note(self, n):
        text, ok = QInputDialog.getText(self, "Edit note", "Note:", QLineEdit.EchoMode.Normal, n["text"])
        if ok and text.strip() != n["text"]:
            self.push_undo()
            n["text"] = text.strip()
            self.notes_changed()

    def delete_note(self, n):
        self.push_undo()
        self.notes = [x for x in self.notes if x is not n]
        self.notes_changed()

    def note_at_playhead(self):
        self.add_note(self.playhead)

    def note_menu(self, n, pos, gpos):
        menu = QMenu(self)
        acts = {}
        if n:
            acts[menu.addAction("Edit note…")] = lambda: self.edit_note(n)
            cm = menu.addMenu("Color")
            cur = n.get("color")
            a = cm.addAction("Default (theme)")
            a.setCheckable(True)
            a.setChecked(not cur)
            acts[a] = lambda: self.set_note_color(n, None)
            for name, hexc in NOTE_COLORS:
                a = cm.addAction(color_icon(hexc), name)
                a.setCheckable(True)
                a.setChecked(cur == hexc)
                acts[a] = (lambda hc: lambda: self.set_note_color(n, hc))(hexc)
            cm.addSeparator()
            acts[cm.addAction("Custom…")] = lambda: self._custom_note_color(n)
            if n.get("end") is not None:
                acts[menu.addAction("Turn into a point note")] = lambda: self._set_note_end(n, None)
            acts[menu.addAction("Delete note")] = lambda: self.delete_note(n)
        else:
            acts[menu.addAction("Add a note here")] = lambda: self.add_note(pos)
            acts[menu.addAction("Add a note at the playhead  (N)")] = self.note_at_playhead
        act = menu.exec(gpos)
        if act in acts:
            acts[act]()

    def set_note_color(self, n, color):
        if n.get("color") == color:
            return
        self.push_undo()
        n["color"] = color
        self.notes_changed()

    def _custom_note_color(self, n):
        start = QColor(n.get("color") or self.palette().color(QPalette.ColorRole.Highlight))
        c = QColorDialog.getColor(start, self, "Note color")
        if c.isValid():
            self.set_note_color(n, c.name())

    def _set_note_end(self, n, end):
        self.push_undo()
        n["end"] = end
        self.notes_changed()

    def jump_note(self, d):
        if not self.notes or self.state == "rec":
            return
        starts = sorted(n["t"] for n in self.notes)
        if d > 0:
            nxt = [t for t in starts if t > self.playhead + SR // 20]
            target = nxt[0] if nxt else None
        else:
            prv = [t for t in starts if t < self.playhead - SR // 4]   # tap twice to go further back
            target = prv[-1] if prv else None
        if target is not None:
            self.seek(target)
            self.timeline.follow(self.playhead)

    def _update_banner(self):
        if not self.notes:
            if self.note_banner.isVisible():
                self.note_banner.setVisible(False)
            return
        cur, nxt = note_at(self.notes, self.playhead)
        parts = []
        hl = self.palette().color(QPalette.ColorRole.Highlight)
        if cur:
            parts.append(f"<span style='color:{note_qcolor(cur, hl).name()}'>●</span> "
                         f"<b>{html.escape(cur['text'] or '…')}</b>")
        if nxt:
            dt = (nxt["t"] - self.playhead) / SR
            parts.append(f"<span style='color:{note_qcolor(nxt, hl).name()}'>●</span> "
                         f"<span style='color:{self.c_muted}'>next: {html.escape(nxt['text'] or '…')}"
                         f" (in {dt:.1f} s)</span>")
        text = "&nbsp;&nbsp;→&nbsp;&nbsp;".join(parts) or f"<span style='color:{self.c_muted}'>📝 —</span>"
        if text != self._banner_text:
            self._banner_text = text
            self.note_banner.setText(text)
        if not self.note_banner.isVisible():
            self.note_banner.setVisible(True)

    # ─────────────────────────────────────────────── lyrics & prompter
    def prompter_size(self):
        return int(self.settings.value("prompter_size", 30))

    def prompter_ahead(self):
        return int(self.settings.value("prompter_ahead", 1))

    def prompter_lead(self):
        return int(float(self.settings.value("prompter_lead", 0.3)) * SR)

    def open_prompter(self, edit=False):
        if self.prompter is None:
            self.prompter = PrompterWindow(self)
        self.prompter.setWindowTitle(f"Prompter — {self.choir}" if self.project_dir else "Prompter")
        self.prompter.show()
        self.prompter.raise_()
        self.prompter.activateWindow()
        if edit:
            self.prompter.edit_mode(True)

    def _lrc_origin(self):
        """.lrc times count from the start of the master."""
        m = self.master()
        return int(m["takes"][m["active"]]["offset"]) if m else 0

    def import_lyrics(self):
        if not self.project_dir:
            QMessageBox.information(self, "Lyrics", "Open or create a project first.")
            return
        parent = self.prompter if self.prompter and self.prompter.isVisible() else self
        path, _ = QFileDialog.getOpenFileName(parent, "Import lyrics", self.work_project_dir,
                                              "Lyrics (*.txt *.lrc);;All files (*)")
        if not path:
            return
        text = read_text_file(path)
        if path.lower().endswith(".lrc") or _LRC_TAG.search(text):
            o = self._lrc_origin()
            lines = [{"text": b, "t": o + int(round(sec * SR))} for sec, b in parse_lrc(text)]
        else:
            lines = [{"text": t, "t": None} for t in text_to_lines(text)]
        if not lines:
            QMessageBox.warning(parent, "Lyrics", "No lyric lines found in this file.")
            return
        if self.lyrics and QMessageBox.question(
                parent, "Lyrics", "Replace the current lyrics?") != QMessageBox.StandardButton.Yes:
            return
        self.push_undo()
        self.lyrics = lines
        self.tap_next = None
        self.lyrics_changed()
        synced = all(ln["t"] is not None for ln in lines)
        self.statusBar().showMessage(
            f"{len(lines)} lines imported" + (" (synced — Shift+drag a line in the lyrics lane to shift them all)"
                                             if synced else " — play the song and press Enter at the start of each line"),
            7000)

    def export_lyrics(self):
        if not self.project_dir:
            return
        parent = self.prompter if self.prompter and self.prompter.isVisible() else self
        timed = sum(ln["t"] is not None for ln in self.lyrics)
        if not timed:
            QMessageBox.information(parent, "Export .lrc", "No synced line yet: play the song and press Enter "
                                                           "at the start of each line first.")
            return
        default = os.path.join(self.work_project_dir, f"{self.choir}.lrc")
        path, _ = QFileDialog.getSaveFileName(parent, "Export .lrc", default, "LRC lyrics (*.lrc)")
        if not path:
            return
        if not path.lower().endswith(".lrc"):
            path += ".lrc"
        res = os.path.abspath(self.base_edit.text().strip() or "/nonexistent")
        if os.path.abspath(path).startswith(res + os.sep) and QMessageBox.question(
                parent, "Export .lrc", "This folder is inside the mod resources: the .lrc would end up in the jar.\n"
                                       "Save it there anyway?") != QMessageBox.StandardButton.Yes:
            return
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(to_lrc(self.lyrics, self._lrc_origin(), self.choir))
        missing = len(self.lyrics) - timed
        self.statusBar().showMessage(f"✔ {path}" + (f" ({missing} unsynced line(s) left out)" if missing else ""), 6000)

    def set_lyrics_text(self, text):
        res = remap_lyrics(self.lyrics, text_to_lines(text))
        if res == self.lyrics:
            return
        self.push_undo()
        self.lyrics = res
        self.tap_next = None
        self.lyrics_changed()

    def lyrics_changed(self):
        self.save_project()
        self.touch()
        if self.prompter:
            self.prompter.view.update()

    def _reset_tap(self, start):
        """A new playback starts a new tapping run."""
        self.tap_run_start = int(start)
        self.tap_next = None
        self.tap_dirty = False

    def tap_target(self):
        """Line the next Enter will stamp: the first line (from where playback started) that is
        not synced yet or starts after that point; then the following ones."""
        if not self.lyrics or self.state not in ("play", "rec") or self.previewing:
            return None
        if self.tap_next is not None:
            return self.tap_next if self.tap_next < len(self.lyrics) else None
        start = self.tap_run_start - int(0.2 * SR)
        return next((i for i, ln in enumerate(self.lyrics) if ln["t"] is None or ln["t"] >= start), None)

    def lyric_tap(self, back=False):
        """Enter: the next lyric line starts now. Shift+Enter: go back one line."""
        if not self.lyrics:
            return
        if self.state not in ("play", "rec") or self.previewing:
            self.statusBar().showMessage("Start playback (or recording), then press Enter at the start "
                                         "of each lyric line", 4000)
            return
        k = self.tap_target()
        if back:
            k = len(self.lyrics) if k is None else k
            if k > 0:
                self.tap_next = k - 1
                self.statusBar().showMessage(f"Next Enter re-syncs: “{self.lyrics[k - 1]['text']}”", 3000)
                self.touch()
            return
        if k is None:
            self.statusBar().showMessage("All lines are synced — start playback before a line to re-sync it", 4000)
            return
        if not getattr(self, "tap_dirty", False):
            self.push_undo()                # one undo step per tapping run
            self.tap_dirty = True
        pos = int(self.engine.pos)
        old = self.lyrics[k]["t"]
        self.lyrics[k]["t"] = pos
        if old is not None:                 # re-sync: the following lines follow the correction
            d = pos - old
            for ln in self.lyrics[k + 1:]:
                if ln["t"] is not None:
                    ln["t"] += d
        self.tap_next = k + 1
        self.lyrics_changed()

    def lyric_menu(self, i, gpos):
        menu = QMenu(self)
        acts = {}
        if i is not None:
            acts[menu.addAction("Unsync this line")] = lambda: self._unsync(i, False)
            acts[menu.addAction("Unsync this line and all the following")] = lambda: self._unsync(i, True)
            menu.addSeparator()
        acts[menu.addAction("Edit lyrics…")] = lambda: self.open_prompter(edit=True)
        acts[menu.addAction("Import lyrics…")] = self.import_lyrics
        act = menu.exec(gpos)
        if act in acts:
            acts[act]()

    def _unsync(self, i, following):
        self.push_undo()
        for ln in self.lyrics[i:] if following else [self.lyrics[i]]:
            ln["t"] = None
        self.tap_next = None
        self.lyrics_changed()

    def _line_back(self, pos):
        """Start of the lyric line being sung at pos (just before it), or None."""
        ts = sorted(ln["t"] for ln in self.lyrics if ln["t"] is not None)
        c = [t for t in ts if t <= pos - int(0.7 * SR)]      # a line just started: the previous one
        return c[-1] - int(0.2 * SR) if c else None

    # ─────────────────────────────────────────────── trash
    @staticmethod
    def _item_takes(item):
        if item["kind"] == "track":
            return item["track"]["takes"]
        if item["kind"] == "take":
            return [item["take"]]
        return []

    def _item_files(self, item):
        files = [p["file"] for tk in self._item_takes(item) for p in tk["passes"]]
        if item.get("pas"):
            files.append(item["pas"]["file"])
        if item.get("file"):
            files.append(item["file"])
        return files

    def _referenced_files(self):
        refs = {p["file"] for t in self.tracks for tk in t["takes"] for p in tk["passes"]}
        for it in self.trash:
            refs.update(self._item_files(it))
        return refs

    def _to_trash(self, kind, name, **data):
        item = {"id": uuid.uuid4().hex[:8], "kind": kind, "name": name, "when": time.time()}
        item.update(data)
        self.trash.append(item)
        self.trash_changed()
        return item

    def trash_changed(self):
        n = len(self.trash)
        self.btn_trash.setText(f"🗑 Trash ({n})" if n else "🗑 Trash")
        if self.trash_win and self.trash_win.isVisible():
            self.trash_win.refresh()

    def open_trash(self):
        if not self.project_dir:
            QMessageBox.information(self, "Trash", "Open or create a project first.")
            return
        if self.trash_win is None:
            self.trash_win = TrashWindow(self)
        self.trash_win.setWindowTitle(f"Trash — {self.choir}")
        self.trash_win.refresh()
        self.trash_win.show()
        self.trash_win.raise_()
        self.trash_win.activateWindow()

    def _scan_orphans(self):
        """Raw takes on disk that nothing uses anymore (deleted before the trash existed,
        undone recordings…) are listed in the trash so they can be recovered or cleaned up."""
        d = os.path.join(self.studio_dir, "takes")
        if not os.path.isdir(d):
            return 0
        refs = self._referenced_files()
        found = []
        for f in sorted(os.listdir(d)):
            rel = f"takes/{f}"
            if not f.lower().endswith(".wav") or rel in refs:
                continue
            mtime = os.path.getmtime(os.path.join(d, f))
            found.append({"id": uuid.uuid4().hex[:8], "kind": "file", "when": mtime, "file": rel,
                          "name": f"Recording found on disk ({time.strftime('%d/%m %H:%M', time.localtime(mtime))})"})
        if found:
            self.trash = sorted(self.trash + found, key=lambda it: it.get("when", 0))
            self.save_project()
        return len(found)

    def _trash_audio(self, item):
        try:
            k = item["kind"]
            if k in ("track", "take"):
                tk = item["track"]["takes"][item["track"]["active"]] if k == "track" else item["take"]
                if not all(self._load_file(p["file"]) for p in tk["passes"]):
                    return None
                return compose_take(tk, self.audio.__getitem__, self.xfade())
            f = item["pas"]["file"] if k in ("punch", "lap") else item["file"]
            return self.audio[f] if self._load_file(f) else None
        except Exception:
            return None

    def trash_label(self, item):
        x = self._trash_audio(item)
        dur = f"{len(x) / SR:.1f} s" if x is not None else "⚠ audio file missing"
        when = time.strftime("%d/%m %H:%M", time.localtime(item.get("when", 0)))
        verb = "found" if item["kind"] == "file" else "deleted"
        return f"{item['name']}\n{dur} · {verb} {when}"

    def trash_thumb(self, item):
        pm = self.thumb_cache.get(item["id"])
        if pm is not None:
            return pm
        W, H = 150, 38
        pm = QPixmap(W, H)
        pm.fill(Qt.GlobalColor.transparent)
        x = self._trash_audio(item)
        if x is not None and len(x):
            n = max(1, int(math.ceil(len(x) / W)))
            pad = np.zeros(n * W, np.float32)
            pad[:len(x)] = np.abs(x)
            env = pad.reshape(W, n).max(axis=1)
            env = env / (env.max() + 1e-9)
            p = QPainter(pm)
            p.setPen(QPen(self.palette().color(QPalette.ColorRole.Highlight), 1))
            p.drawLines([QLineF(i, H / 2 - v * H * 0.45, i, H / 2 + v * H * 0.45 + 1) for i, v in enumerate(env)])
            p.end()
        self.thumb_cache[item["id"]] = pm
        return pm

    def _find_take(self, take_id):
        for t in self.tracks:
            for i, tk in enumerate(t["takes"]):
                if take_id and tk.get("id") == take_id:
                    return t, i
        return None

    def restore_trash(self, item_id, target=None):
        """Brings an item back. target: id of the track it was dropped on (becomes a new take there)."""
        item = next((it for it in self.trash if it["id"] == item_id), None)
        if not item or self.busy():
            return
        if not all(self._load_file(f) for f in self._item_files(item)):
            QMessageBox.warning(self, "Trash", "The audio file of this item is missing from the disk.")
            return
        tgt = self.track(target) if target else None
        k = item["kind"]
        self.push_undo()
        self.trash = [it for it in self.trash if it is not item]

        def add_take(tk, name):
            dest = tgt or self.track(item.get("track_id"))
            if dest:
                dest["takes"].append(tk)
                dest["active"] = len(dest["takes"]) - 1
                self.selected = dest["id"]
                return f"restored as take {dest['active'] + 1} of “{dest['name']}”"
            nt = self._new_track(name, tk, master=not self.tracks)
            self.tracks.append(nt)
            self.selected = nt["id"]
            return f"restored as track “{name}”"

        if k == "track":
            t = item["track"]
            if tgt:
                tgt["takes"].extend(t["takes"])
                tgt["active"] = len(tgt["takes"]) - 1
                msg = f"takes of “{t['name']}” added to “{tgt['name']}”"
            else:
                t["master"] = t["master"] and not self.master()
                self.tracks.append(t)
                self.selected = t["id"]
                msg = f"track “{t['name']}” restored"
        elif k == "take":
            msg = add_take(item["take"], item.get("track_name") or "Restored")
        elif k in ("punch", "lap"):
            p = item["pas"]
            home = self._find_take(item.get("take_id"))
            if home and (tgt is None or tgt is home[0]):
                t, i = home
                t["takes"][i]["passes"].append(p)        # back on top of its take, at its place
                t["active"] = i
                self.selected = t["id"]
                msg = f"punch put back on “{t['name']}”"
            else:                                        # alone: a take of its own, same place in time
                tk = self._new_take(p["file"], int(item.get("take_offset", 0)) + int(p["rel"]))
                msg = add_take(tk, item.get("track_name") or "Restored")
        else:
            tk = self._new_take(item["file"], 0)
            msg = add_take(tk, "Recovered")
        self.thumb_cache.pop(item["id"], None)
        self.refresh_all()
        self.trash_changed()
        self.statusBar().showMessage(f"↩ {item['name']}: {msg} (Ctrl+Z to undo)", 6000)

    def purge_trash(self, ids):
        """Deletes trash items for good (their audio files too). ids=None: everything."""
        items = list(self.trash) if ids is None else [it for it in self.trash if it["id"] in ids]
        if not items or self.busy():
            return
        parent = self.trash_win if self.trash_win and self.trash_win.isVisible() else self
        what = "Empty the trash" if ids is None else f"Delete “{items[0]['name']}” forever"
        if QMessageBox.question(parent, "Trash", f"{what}?\n\nThe audio files are deleted from the disk and "
                                                 "the undo history is cleared.") != QMessageBox.StandardButton.Yes:
            return
        doomed = {f for it in items for f in self._item_files(it)}
        self.trash = [it for it in self.trash if not any(it is x for x in items)]
        keep = self._referenced_files()
        for f in doomed - keep:
            try:
                os.remove(os.path.join(self.studio_dir, f))
            except OSError:
                pass
            self.audio.pop(f, None)
        for it in items:
            self.thumb_cache.pop(it["id"], None)
        self.undo_stack.clear()
        self.redo_stack.clear()
        self.save_project()
        self.trash_changed()
        self.statusBar().showMessage(f"{len(items)} item(s) deleted forever", 4000)

    def preview_trash(self, item_id):
        """▶ in the trash: plays the item on its own (again = stop)."""
        if self.previewing:
            self._hard_stop()
            return
        item = next((it for it in self.trash if it["id"] == item_id), None)
        x = self._trash_audio(item) if item else None
        if x is None or not self.engine.ok or self.busy():
            return
        self._hard_stop()
        self.previewing = True
        self.gen += 1
        self.gen_info[self.gen] = {"kind": "preview"}
        self.engine.extra = []
        self.engine.mix = [(x, 0, 1.0, 1.0, None)]
        self.engine.play(self.gen, 0, False, len(x) + int(0.1 * SR), None)
        self.state, self.stop_mode = "play", "pause"
        self._update_transport_ui()

    # ─────────────────────────────────────────────── loop takes
    def _start_lap_choice(self, sess):
        t, tk = self.track(sess["tid"]), sess["take"]
        laps = sess["laps"]
        a, b = sess["region"]
        if not t or not any(q is tk for q in t["takes"]):
            return
        self.lap_state = {"track": t, "take": tk, "laps": laps, "region": (a, b),
                          "applied": None, "choice": None, "undo": False}
        self.lap_picker = LapPicker(self, len(laps))
        self.lap_picker.show()
        self.lap_picker.raise_()
        self.lap_picker.list.setCurrentRow(len(laps) - 1)    # the last lap is usually the best one

    def apply_lap(self, i):
        st = self.lap_state
        if not st or self.busy():
            return
        t, tk = st["track"], st["take"]
        if not any(q is tk for q in t["takes"]):
            return
        if not st["undo"]:
            self.push_undo()
            st["undo"] = True
        if st["applied"] is not None:
            tk["passes"] = [q for q in tk["passes"] if q is not st["applied"]]
        lap = st["laps"][i]
        x = self.audio[lap["file"]]
        a, b = st["region"]
        snap = self.chk_snap.isChecked()
        p = add_pass(tk, lap["file"], x, lap["offset"], a, self.take_audio(tk), self.xfade(), snap)
        if p is not None:
            close_pass(tk, p, self.audio.__getitem__, self.xfade(), snap)
            extend_trim(tk, pass_span(p, len(x), self.xfade() // 2),
                        detect_voice_range(self.take_audio(tk), self.sens_spin.value())
                        if self.chk_autotrim.isChecked() else None)
        st["applied"], st["choice"] = p, i
        t["active"] = next(j for j, q in enumerate(t["takes"]) if q is tk)
        self.refresh_all()
        self.play_lap_region()

    def play_lap_region(self):
        st = self.lap_state
        if not st or self.busy() or not self.engine.ok:
            return
        a, b = st["region"]
        self._start_play(a - self._reprise_preroll(), stop=b + SR, use_loop=False)

    def finish_laps(self, keep):
        st, self.lap_state, self.lap_picker = self.lap_state, None, None
        if not st:
            return
        if self.state == "play":
            self._hard_stop()
        t, tk = st["track"], st["take"]
        if not st["undo"]:
            self.push_undo()
        if not keep and st["applied"] is not None:
            tk["passes"] = [q for q in tk["passes"] if q is not st["applied"]]
        a, _ = st["region"]
        for i, lap in enumerate(st["laps"]):
            if keep and i == st["choice"] and st["applied"] is not None:
                continue
            self._to_trash("lap", f"Loop lap {i + 1} on “{t['name']}”",
                           pas={"file": lap["file"], "rel": lap["offset"] - tk["offset"],
                                "in": a - tk["offset"], "out": None},
                           take_id=tk.get("id"), take_offset=tk["offset"], track_id=t["id"], track_name=t["name"])
        self.refresh_all()
        kept = f"lap {st['choice'] + 1} kept" if keep and st["applied"] is not None else "no lap kept"
        self.statusBar().showMessage(f"Loop recording: {kept}, the other laps are in the trash", 5000)

    def auto_align(self, tid):
        t, m = self.track(tid), self.master()
        if not t or not m or t is m or self.busy():
            return
        hop = 48

        def onset_env(a):
            n = len(a) // hop
            if n < 4:
                return np.zeros(4)
            e = np.sqrt(np.mean(a[:n * hop].reshape(n, hop) ** 2, axis=1) + 1e-9)
            return np.maximum(0, np.diff(np.log(e), prepend=np.log(e[0])))

        mt, tt = m["takes"][m["active"]], t["takes"][t["active"]]
        em = onset_env(self.processed(m))
        ex = onset_env(self.processed(t))
        c = sps.correlate(em, ex, mode="full", method="fft")
        d = int(round((tt["offset"] - mt["offset"]) / hop))
        lo = max(0, d - 300 + len(ex) - 1)
        hi = min(len(c), d + 301 + len(ex) - 1)
        if hi <= lo:
            self.statusBar().showMessage("Auto-align impossible: no overlap with the master", 4000)
            return
        best = lo + int(np.argmax(c[lo:hi]))
        shift = (best - (len(ex) - 1)) - d
        self.push_undo()
        self.set_offset_live(t, tt["offset"] + shift * hop)
        self.commit_offset()
        self.statusBar().showMessage(f"“{t['name']}” shifted by {shift:+d} ms (Ctrl+Z to undo)", 5000)

    # ─────────────────────────────────────────────── import
    def _import_dialog(self):
        paths, _ = QFileDialog.getOpenFileNames(self, "Import stems", "",
                                                "Audio (*.wav *.ogg *.flac *.mp3 *.opus *.m4a *.aiff);;All (*)")
        if paths:
            self.import_files(paths)

    def import_files(self, paths, target=None, as_master=False, undo=True):
        if not self.project_dir:
            QMessageBox.information(self, "Project", "Open or create a project first (personality + choir).")
            return
        if not paths or self.busy():
            return
        if undo:
            self.push_undo()
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        errors = []
        try:
            for i, p in enumerate(paths):
                try:
                    x = load_audio_file(p)
                except Exception as e:
                    errors.append(f"{os.path.basename(p)}: {e}")
                    continue
                f = self._store_take(x)
                t = self.track(target) if target else None
                if t:
                    # a file dropped on a row becomes a new take, where the current take sits
                    off = t["takes"][t["active"]]["offset"] if t["takes"] else 0
                    t["takes"].append(self._new_take(f, off))
                    t["active"] = len(t["takes"]) - 1
                else:
                    is_master = (as_master and i == 0) or not self.tracks
                    if is_master:
                        for x_ in self.tracks:
                            x_["master"] = False
                    nt = self._new_track(os.path.splitext(os.path.basename(p))[0], self._new_take(f, 0), is_master)
                    self.tracks.append(nt)
                    self.selected = nt["id"]
        finally:
            QApplication.restoreOverrideCursor()
        self.refresh_all()
        if errors:
            QMessageBox.warning(self, "Import", "Some files could not be read:\n\n" + "\n".join(errors))

    # ─────────────────────────────────────────────── transport
    def seek(self, pos):
        if self.state == "rec":
            return
        self.playhead = int(pos)
        if self.state == "play":
            self._start_play(self.playhead)
        self.timeline.update()

    def go_home(self):
        if self.state == "rec":
            return
        rng = self.song_range_eff()
        self.seek(rng[0] if rng else self.bounds()[0])
        self.timeline.follow(self.playhead)

    def _loop_toggled(self, on):
        if self.state == "play" and not self.previewing:
            self.engine.set_loop(self.loop_region if on else None)
        self.timeline.update()
        self._update_transport_ui()

    def loop_active(self):
        return bool(self.chk_loop.isChecked() and self.loop_region)

    def set_loop_region(self, r):
        self.loop_region = r if r and r[1] - r[0] > SR * 0.1 else None
        if self.loop_region and not self.chk_loop.isChecked():
            self.chk_loop.setChecked(True)
        elif self.state == "play" and not self.previewing:
            self.engine.set_loop(self.loop_region if self.chk_loop.isChecked() else None)
        self.timeline.update()
        self._update_transport_ui()

    def _start_play(self, start, stop=None, use_loop=True):
        if self.previewing:
            self.previewing = False
            self.rebuild_mix()
        s, e = self.bounds()
        loop = self.loop_region if (use_loop and self.loop_active()) else None
        if loop and not (loop[0] <= start < loop[1]):
            start = loop[0]
        if not loop and start >= e:
            start = s
        self.gen += 1
        self.gen_info[self.gen] = {"kind": "play"}
        self.engine.extra = []
        self._reset_tap(start)
        end = stop if stop is not None else e + int(0.2 * SR)
        self.engine.play(self.gen, start, False, None if loop else end, loop)
        self.state, self.stop_mode = "play", "stop"
        self.play_origin = self.playhead = start
        self._update_transport_ui()

    def toggle_play(self):
        if not self.engine.ok:
            return
        if self.state == "rec":
            self.pause_rec()
        elif self.state == "rec_pause":
            self.resume_rec()
        elif self.state == "play":
            self.stop_mode = "pause"
            self.engine.stop()
        elif self.state == "idle" and self.tracks:
            self._start_play(self.playhead)

    def stop_transport(self):
        if self.state in ("rec", "rec_pause"):
            self.end_session()
        elif self.state == "play":
            self.stop_mode = "stop"
            self.engine.stop()
        elif self.state == "idle":
            self.playhead = self.play_origin
            self.timeline.update()

    def _hard_stop(self):
        """Stops playback/calibration right away (recordings go through _flush_session)."""
        if self.state in ("play", "calib"):
            self.engine.stop()
            self.state = "idle"
            self.engine.extra = []
            self.handled_gen = self.gen
            if self.previewing:
                self.previewing = False
                self.rebuild_mix()
            self._update_transport_ui()

    def _wait_engine(self, timeout=0.6):
        """Waits (briefly) until the audio thread has processed the last stop."""
        t0 = time.monotonic()
        while self.engine.ok and self.engine.ended_gen != self.gen and time.monotonic() - t0 < timeout:
            time.sleep(0.005)

    def _drain(self):
        """Commits the captures the audio thread has finished, in order."""
        eng = self.engine
        while eng.finished:
            gen, start, blocks, xruns = eng.finished.popleft()
            info = self.gen_info.get(gen)
            if info and not info.get("loop"):
                del self.gen_info[gen]
            if info:
                self._on_capture(info, start, blocks, xruns)

    # --- recording sessions: one take, as many passes (⌫, pause/resume) as needed
    def _reprise_preroll(self):
        return max(int(self.preroll_spin.value() * SR), SR)

    def start_session(self, mode):
        """mode: "track" (R, new stem), "take" (T, new take), "punch" (P, part of the active take)."""
        if self.state in ("rec", "rec_pause"):
            self.end_session()
            return
        if self.state == "calib":
            return
        if not self.engine.ok:
            QMessageBox.warning(self, "Audio", "The audio stream is not available.")
            return
        if not self.project_dir:
            QMessageBox.information(self, "Project", "Open or create a project first (personality + choir).")
            return
        t = None
        if mode in ("take", "punch"):
            t = self.track(self.selected)
            if not t:
                self.statusBar().showMessage("Select the track to re-record first", 3000)
                return
            if mode == "punch" and not self.take_ok(t["takes"][t["active"]]):
                return
        self._hard_stop()
        if self.tracks and self.settings.value("headphones_ok", "false") != "true":
            QMessageBox.information(
                self, "Headphones", "Use headphones: otherwise the microphone picks up the other voices "
                "and they end up in your stem.\n\n(This message won't be shown again.)")
            self.settings.setValue("headphones_ok", "true")
        s = self.settings
        s.setValue("preroll", self.preroll_spin.value())
        s.setValue("countdown", "true" if self.chk_countdown.isChecked() else "false")
        s.setValue("autostop", "true" if self.chk_autostop.isChecked() else "false")

        point = int(self.playhead)
        pre = int(self.preroll_spin.value() * SR) if self.tracks else 0
        sess = {"mode": mode, "tid": t["id"] if t else None, "take": None, "origin": None,
                "home": point, "last_pass": None, "last_bs": None, "started": False}
        if mode == "punch":
            tk = t["takes"][t["active"]]
            point = max(point, int(tk["offset"]))
            sess.update(take=tk, origin=int(tk["offset"]), home=point, started=True)
            pre = self._reprise_preroll()
            if self.loop_active():           # P + loop: one candidate per lap
                a, b = self.loop_region
                a = max(int(a), int(tk["offset"]))
                if b - a < SR // 2:
                    self.statusBar().showMessage("The loop is too short (or before the take)", 3000)
                    return
                sess.update(mode="loop", region=(a, int(b)), laps=[], home=a)
                point = a
        self.session = sess
        self._begin_pass(sess, point, pre, countdown=self.chk_countdown.isChecked())

    def _begin_pass(self, sess, point, preroll, countdown=False):
        """Starts capturing one pass: plays from point - preroll, the new audio takes over at point.
        In a loop session every lap (from the pre-roll to the loop end) is captured separately."""
        loop = None
        if sess["mode"] == "loop":
            point = sess["region"][0]
        play_from = point - preroll
        extra = []
        if countdown:
            clicks = np.zeros(2 * SR, np.float32)
            for k in range(4):
                c = make_click(2000 if k == 0 else 1300)
                clicks[k * SR // 2: k * SR // 2 + len(c)] = c
            extra.append((clicks, point - 2 * SR, 1.0, 1.0, None))
            play_from = min(play_from, point - 2 * SR)
        if sess["origin"] is None:
            sess["origin"] = play_from - self._lat()
        stop_at = None
        if sess["mode"] == "loop":
            loop = (play_from, sess["region"][1] + LAP_TAIL)
        elif self.chk_autostop.isChecked() and self.tracks:
            e = self.bounds()[1] + SR
            if e > point + SR:
                stop_at = e
        first = not sess["started"]
        sess["started"] = True
        self.engine.extra = extra
        self.gen += 1
        self.gen_info[self.gen] = {"kind": "rec", "session": sess, "point": int(point),
                                   "punch": None if first else int(point), "end": "stop",
                                   "loop": sess.get("region") if loop else None}
        self.rec_ctx = {"session": sess, "cut": int(point)}
        self.rebuild_mix()
        self._reset_tap(play_from)
        self.engine.play(self.gen, play_from, True, stop_at, loop)
        self.state = "rec"
        self.play_origin = int(point)
        self.playhead = play_from
        self._update_transport_ui()

    def back_step(self):
        """⌫ — while recording: jump back and keep recording from there (the new pass replaces
        what follows). Otherwise: move the playhead back."""
        back = int(self.back_spin.value() * SR)
        by_line = self.chk_line_back.isChecked() and any(ln["t"] is not None for ln in self.lyrics)
        if self.state == "rec" and self.session and self.session["mode"] == "loop":
            info = self.gen_info.get(self.gen)
            if info:
                info["end"] = "chain"
            self.engine.stop()                  # the unfinished lap is dropped
            self._begin_pass(self.session, self.session["region"][0], self._reprise_preroll())
            self.statusBar().showMessage("⌫ Lap restarted", 2000)
            return
        if self.state == "rec" and self.session:
            sess = self.session
            now = time.monotonic()
            lb = sess["last_bs"]
            base = lb[1] if (lb and now - lb[0] < 1.0) else self.engine.pos
            origin = sess["take"]["offset"] if sess["take"] else sess["origin"]
            target = self._line_back(base) if by_line else None
            point = max(int(base - back if target is None else target), int(origin))
            sess["last_bs"] = (now, point)
            info = self.gen_info.get(self.gen)
            if info:
                info["end"] = "chain"
            self.engine.stop()
            self._begin_pass(sess, point, self._reprise_preroll())
            self.statusBar().showMessage(f"⌫ Back to {fmt_time(point)} — sing again from there "
                                         "(⌫ again = further back)", 3000)
        elif self.state in ("idle", "rec_pause", "play") and not self.previewing:
            target = self._line_back(self.playhead) if by_line else None
            self.seek(self.playhead - back if target is None else target)
            self.timeline.follow(self.playhead)

    def pause_rec(self):
        info = self.gen_info.get(self.gen)
        if info:
            info["end"] = "pause"
        self.engine.stop()
        self.state = "rec_pause"
        self.playhead = self.engine.pos
        self._update_transport_ui()

    def resume_rec(self):
        sess = self.session
        if not sess:
            self.state = "idle"
            self._update_transport_ui()
            return
        self._wait_engine()
        self._drain()
        origin = sess["take"]["offset"] if sess["take"] else sess["origin"]
        self._begin_pass(sess, max(int(self.playhead), int(origin)), self._reprise_preroll())

    def end_session(self):
        if self.state == "rec":
            info = self.gen_info.get(self.gen)
            if info:
                info["end"] = "stop"
            self.stop_mode = "stop"
            self.engine.stop()              # the capture closes the take, _on_end closes the session
        elif self.state == "rec_pause":
            self._wait_engine()
            self._drain()
            self._finalize(self.session, undo=True)
            self._close_session()

    def _flush_session(self):
        """Finishes the current recording synchronously (before closing, switching project…)."""
        if self.state == "rec":
            info = self.gen_info.get(self.gen)
            if info:
                info["end"] = "stop"
            self.engine.stop()
            self._wait_engine()
            self._drain()
            self.handled_gen = self.gen
            if self.state == "rec":
                self._close_session()
        elif self.state == "rec_pause":
            self._wait_engine()
            self._drain()
            self.handled_gen = self.gen
            self._finalize(self.session, undo=True)
            self._close_session()

    def _close_session(self):
        sess, self.session = self.session, None
        self.rec_ctx = None
        self.state = "idle"
        self.engine.extra = []
        self.gen_info = {g: i for g, i in self.gen_info.items() if i.get("session") is not sess}
        if sess:
            self.playhead = sess["home"]
        self.rebuild_mix()
        self._update_transport_ui()
        if self.export_deferred:
            self.export_deferred = False
            self.schedule_export()
        if sess and sess["mode"] == "loop":
            if sess["laps"]:
                self._start_lap_choice(sess)
            else:
                self.statusBar().showMessage("Loop recording: no complete lap", 4000)

    def _finalize(self, sess, undo):
        """End of a session: if the last pass stopped in the middle of older audio,
        that older audio comes back after a seamless punch-out."""
        if not sess or not sess["take"] or not sess["last_pass"]:
            return
        tk, p = sess["take"], sess["last_pass"]
        if p.get("out") is not None or not any(q is p for q in tk["passes"][1:]):
            return
        if undo:
            self.push_undo()
        if close_pass(tk, p, self.audio.__getitem__, self.xfade(), self.chk_snap.isChecked()):
            self.refresh_all()
        elif undo and self.undo_stack:
            self.undo_stack.pop()

    def _on_capture(self, info, start, blocks, xruns):
        if info["kind"] == "calib":
            self._finish_calibration(blocks)
            return
        if info["kind"] != "rec":
            return
        sess = info["session"]
        x = np.concatenate(blocks).astype(np.float32) if blocks else np.zeros(0, np.float32)
        offset = int(start) - self._lat()
        if info.get("loop"):
            a, b = info["loop"]
            if offset <= a and offset + len(x) >= b:      # a complete lap
                sess["laps"].append({"file": self._store_take(x), "offset": offset})
                msg = f"Lap {len(sess['laps'])} recorded — sing it again, or Esc to choose the best one"
            else:
                msg = None
            if xruns:
                msg = "⚠ audio dropout during this lap: it may be out of sync, raise the block size"
            if msg:
                self.statusBar().showMessage(msg, 5000)
            self._update_transport_ui()
            return
        msg, committed, new_track = None, False, False
        if sess["take"] is None:
            # first usable pass: creates the take (and the track for R)
            if offset + len(x) - info["point"] < MIN_TAKE:
                msg = "Take too short, ignored"
            else:
                f = self._store_take(x)
                self.push_undo()
                tk = self._new_take(f, offset)
                t = self.track(sess["tid"]) if sess["tid"] else None
                if sess["mode"] != "track" and t:
                    t["takes"].append(tk)
                    t["active"] = len(t["takes"]) - 1
                    msg = f"New take for “{t['name']}”"
                else:
                    name = "Master" if not self.tracks else f"Voice {len(self.tracks) + 1}"
                    nt = self._new_track(name, tk, master=not self.tracks)
                    self.tracks.append(nt)
                    sess["tid"] = nt["id"]
                    self.selected = nt["id"]
                    new_track = True
                    msg = f"“{name}” recorded"
                sess["take"], sess["last_pass"] = tk, tk["passes"][0]
                committed = True
        else:
            tk = sess["take"]
            punch = info["punch"] if info["punch"] is not None else info["point"]
            if offset + len(x) - punch >= MIN_PASS:        # otherwise only the pre-roll was captured
                f = self._store_take(x)
                self.push_undo()
                p = add_pass(tk, f, x, offset, punch, self.take_audio(tk), self.xfade(),
                             self.chk_snap.isChecked())
                if p is None:
                    self.undo_stack.pop()
                    self.audio.pop(f, None)
                    try:
                        os.remove(os.path.join(self.studio_dir, f))
                    except OSError:
                        pass
                else:
                    span = pass_span(p, len(x), self.xfade() // 2)
                    det = None
                    if self.chk_autotrim.isChecked():
                        det = detect_voice_range(self.take_audio(tk), self.sens_spin.value())
                    extend_trim(tk, span, det)
                    sess["last_pass"] = p
                    committed = True
                    msg = f"Punch from {fmt_time(tk['offset'] + p['in'])}"
        if info["end"] == "stop":
            self._finalize(sess, undo=not committed)
        if committed:
            self.refresh_all()
            if new_track and len(self.tracks) == 1:
                self.timeline.zoom_fit()
            if len(x) and np.max(np.abs(x)) >= 0.99:
                msg += " — ⚠ clipping detected, lower the mic gain"
        if xruns:
            msg = (msg + " — " if msg else "") + ("⚠ audio dropout during this pass: it may be out of sync, "
                                                  "raise the block size and check it")
        if msg:
            self.statusBar().showMessage(msg, 6000 if xruns else 4000)

    def _update_transport_ui(self):
        st = self.state
        rec, pause = st == "rec", st == "rec_pause"
        mode = self.session["mode"] if self.session else None
        self.btn_play.setText("⏸" if st in ("play", "rec") else "▶")
        self.btn_play.setToolTip("Pause the recording (Space)" if rec else
                                 "Resume recording from the playhead (Space)" if pause else "Play / pause (Space)")
        red = "background:#c62828; color:white; font-weight:bold;"
        punch_idle = "⏺ Loop punch  (P)" if self.loop_active() else "⏺ Punch in  (P)"
        for b, m, key, idle in ((self.btn_rec, "track", "R", "⏺ New stem  (R)"),
                                (self.btn_take, "take", "T", "⏺ New take  (T)"),
                                (self.btn_punch, ("punch", "loop"), "P", punch_idle)):
            on = (rec or pause) and mode in (m if isinstance(m, tuple) else (m,))
            b.setStyleSheet(red if on else "")
            b.setText(f"⏹ Finish  ({key})" if on else idle)
        if rec and mode == "loop":
            n = len(self.session["laps"])
            self.state_lbl.setText(f"● REC LOOP — {n} lap{'s' if n != 1 else ''} · ⌫ restart lap · Esc choose")
            self.state_lbl.setStyleSheet("color:#e53935; font-weight:bold;")
        elif rec:
            self.state_lbl.setText("● REC   ⌫ = back & retake")
            self.state_lbl.setStyleSheet("color:#e53935; font-weight:bold;")
        elif pause:
            self.state_lbl.setText("❚❚ PAUSED — Space resume · click/⌫ move · Esc finish")
            self.state_lbl.setStyleSheet(f"color:{'#ffb74d' if self.dark else '#e65100'}; font-weight:bold;")
        else:
            self.state_lbl.setText("")

    def _tick(self):
        eng = self.engine
        self._drain()
        if self.state in ("play", "rec", "calib") and eng.gen == self.gen and eng.playing \
                and not self.previewing:
            self.playhead = eng.pos
            self.timeline.follow(self.playhead)
        if eng.ended_gen == self.gen and self.handled_gen != self.gen and self.state != "idle":
            self.handled_gen = self.gen
            self._on_end()
        self.time_lbl.setText(fmt_time(self.playhead))
        self.timeline.update()
        self._update_banner()
        if self.prompter and self.prompter.isVisible():
            self.prompter.view.update()
        pk = eng.take_peak()
        db = 20 * math.log10(pk + 1e-9)
        cur = self.meter.value()
        self.meter.setValue(max(int((max(db, -60) + 60) * 10), cur - 25))
        if pk >= 0.99:
            self.clip_hold = 50
        self.clip_hold = max(0, self.clip_hold - 1)
        on = self.clip_hold > 0
        if on != self._clip_on:
            self._clip_on = on
            self.clip_lbl.setStyleSheet(f"color:{'#ff3b3b' if on else self.c_muted}; font-weight:bold;")

    def _on_end(self):
        st = self.state
        self.engine.extra = []
        if st == "rec":
            self._close_session()
        elif st == "rec_pause":
            self.playhead = self.engine.pos
        elif st == "calib":
            self.state = "idle"
            self.rebuild_mix()
        elif st == "play":
            self.state = "idle"
            if self.previewing:
                self.previewing = False
                self.rebuild_mix()
            elif self.stop_mode != "pause":
                self.playhead = self.play_origin
        self._update_transport_ui()

    # ─────────────────────────────────────────────── calibration
    def calibrate(self):
        if not self.engine.ok or self.state != "idle":
            return
        if QMessageBox.information(
                self, "Latency calibration",
                "Put the headphones against the microphone (or use speakers at medium volume).\n"
                "Clicks will be played for about 5 seconds.\n\nStay silent during the measurement.",
                QMessageBox.StandardButton.Ok | QMessageBox.StandardButton.Cancel) != QMessageBox.StandardButton.Ok:
            return
        times = [0.5, 0.9, 1.6, 2.0, 2.7, 3.3, 3.6, 4.4]       # irregular spacing: no ambiguity
        sig = np.zeros(int(4.6 * SR), np.float32)
        c = make_click(1000, 5, 0.8)
        for t in times:
            i = int(t * SR)
            sig[i:i + len(c)] = c
        self.calib_sig = sig
        self.engine.mix = []
        self.engine.extra = [(sig, 0, 1.0, 1.0, None)]
        self.gen += 1
        self.gen_info[self.gen] = {"kind": "calib"}
        self.engine.play(self.gen, 0, True, len(sig) + int(0.6 * SR), None)
        self.state = "calib"
        self.statusBar().showMessage("Calibrating…")

    def _finish_calibration(self, blocks):
        sig = self.calib_sig
        if not blocks or sig is None:
            return
        x = np.concatenate(blocks)
        if np.max(np.abs(x)) < 0.01:
            QMessageBox.warning(self, "Calibration", "The microphone picked up almost nothing. "
                                                     "Bring the headphones closer to the microphone.")
            return
        c = sps.correlate(x, sig, mode="full", method="fft")
        z = len(sig) - 1
        seg = np.abs(c[z:z + int(0.5 * SR)])
        k = int(np.argmax(seg))
        score = seg[k] / (np.median(seg) + 1e-12)
        if score < 8:
            QMessageBox.warning(self, "Calibration", "Unreliable measurement (clicks poorly captured or "
                                                     "too much noise). Try again.")
            return
        ms = k * 1000 / SR
        self.lat_spin.setValue(round(ms, 1))
        self.settings.setValue("calibrated", "true")
        self._set_audio_status(self._audio_status[0].split(" · compensation")[0] + " · compensation calibrated",
                               True)
        QMessageBox.information(self, "Calibration", f"Measured round-trip latency: {ms:.1f} ms\n"
                                                     "It will be compensated on the next takes.")

    # ─────────────────────────────────────────────── export
    def schedule_export(self):
        self.settings.setValue("autoexport", "true" if self.chk_autoexport.isChecked() else "false")
        if self.busy():
            self.export_deferred = True     # never compete with the audio thread while recording
            return
        if self.chk_autoexport.isChecked() and self.project_dir:
            self.export_timer.start()

    def export_now(self, silent=False):
        if not self.project_dir:
            return
        if self.busy():
            self.export_deferred = True
            return
        if self.worker and self.worker.isRunning():
            self.export_pending = True
            return
        items = []
        for t in self.tracks:
            if not t["enabled"]:
                continue
            tk = t["takes"][t["active"]]
            x = self.take_audio(tk)
            if x is not None:
                items.append((t["name"], x, tk["offset"], t["gain_db"], self.trim_of(tk)))
        if not items:
            if not silent:
                self.statusBar().showMessage("Nothing to export", 3000)
            return
        self.worker = ExportWorker(items, self.proc_params(), self.project_dir,
                                   self.choir, list(self.exported),
                                   tuple(self.song_range) if self.song_range else None)
        self.worker.done.connect(self._export_done)
        self.worker.failed.connect(lambda e: QMessageBox.critical(self, "Export", e))
        self.worker.finished.connect(self._export_finished)
        self.statusBar().showMessage("Exporting…")
        self.worker.start()

    def _export_done(self, files, dur):
        self.exported = files
        self.save_project()
        self.statusBar().showMessage(f"✔ {len(files)} stem(s) exported · {dur:.2f} s · "
                                     f"{self.lang}/{self.pers}/choir/{self.choir}/", 5000)

    def _export_finished(self):
        if self.export_pending:
            self.export_pending = False
            self.export_now(silent=True)

    def closeEvent(self, e):
        if self.lap_picker:
            self.lap_picker.close()
        self._flush_session()
        self._hard_stop()
        for w in (self.prompter, self.trash_win):
            if w:
                w.close()
        if (self.export_timer.isActive() or self.export_deferred) and self.project_dir:
            self.export_timer.stop()
            self.export_deferred = False
            self.export_now(silent=True)
        if self.worker and self.worker.isRunning():
            self.worker.wait()
        self.save_project()
        self.engine.close()
        e.accept()


if __name__ == "__main__":
    app = QApplication(sys.argv)
    app.setApplicationName("Choir Studio")
    w = ChoirStudio()
    w.show()
    sys.exit(app.exec())
