#!/usr/bin/env python3
"""Drives a local copy of the test server for automated tests. One verb per call, a few lines of output each.
Test verbs (forest, scenarios, soak, ab) stop the server when they finish (user rule: never leave it running).

  setup              create the autotest server copy (once)
  deploy [jar]       copy a jar (default: newest production jar in build/libs) into the copy's mods/ (stops the server)
  reset              restore the world from the snapshot (stops the server)
  start              launch the server detached, wait for "Done"
  cmd "<command>"    send one console command through RCON, print the reply
  wait <seconds>     sleep
  forest [seconds]   mounted forest crossing scenario on each FOREST_ROUTES entry (running server, stopped after)
  scenarios [name|all]  scripted behaviour checks (PASS/FAIL/SKIP per scenario; running server, stopped after)
  restart-go         Go order with a save and a server restart mid-walk (running server, stopped after)
  soak [minutes]     zones + movement profile + invariants every minute on a running server (stopped after)
  compare <a> <b>    compare two soak .metrics files (paths or names in .autotest/soaks) against the thresholds
  ab <commitA> <commitB> [minutes]  build both commits, soak each on a fresh world, compare
  stop               save and stop, kill after a timeout
  status             running or not, deployed jar
  logscan [--baseline]  new problems in the last log; --baseline stores the current signatures as known
"""
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
SOURCE = "user@192.168.1.149:/home/user/server_grandevadrouille_2026/"
SERVER = Path("/home/user/Desktop/LGV2026_autotest")
STATE = SERVER / ".autotest"
SNAPSHOT = STATE / "snapshot.zip"
BASELINE = STATE / "baseline.txt"
DEPLOYED = STATE / "deployed.txt"
JAR_NAME = "travellers-autotest.jar"

GAME_PORT = 25566
RCON_PORT = 25576
VOICECHAT_PORT = 24455
GROOVY_LSP_PORT = 25563
HEAP_GB = 6
START_TIMEOUT_S = 300
STOP_TIMEOUT_S = 120
MAX_SCAN_LINES = 30

# Not copied from the source server (rsync patterns, rooted at its folder): other worlds, backups, logs,
# installers, the production Travellers jar (the runner deploys its own), 4 GB of profiler dumps, a render cache.
SETUP_EXCLUDES = ["/ALLEZ_*", "/LaGrandeVadrouille_*", "*.zip", "*.tar", "*.tar.gz", "/jdk-*", "/download?link*",
                  "/wget-log*", "/hs_err*", "/logs", "/crash-reports", "/config/flare", "/travellers-b0.*.jar",
                  "/mods/travellers-latest.jar", "DistantHorizons.sqlite*"]

# Zones a run forces are chosen by the mod itself (`autotest layout`): the busiest few villages, a small radius,
# because this world has far too many villages to load. See AutotestLayout.CHUNK_BUDGET.
SOAK_MINUTES = 10
# Dense forest crossings for mounted riders (x1, z1, x2, z2). None yet for the current world: forest runs
# (and the forest step of `ab`) are skipped until some are chosen.
FOREST_ROUTES = []
FOREST_SECONDS = 300
FORESTS = STATE / "forests"
SOAKS = STATE / "soaks"
SCENARIO_TIMEOUT_S = 1800
SCENARIOS = STATE / "scenarios"

# Comparison thresholds, from two soaks of the same jar (same-jar spread: efficiency within 4 %, incident rates
# up to 3x apart on low counts). Rate keys are per loaded unit-hour.
EFFICIENCY_DROP = 0.10
INCIDENT_RATIO = 2.5
INCIDENT_MIN_COUNT = 20
PATH_COST_RATIO = 1.5
JDK = "/home/user/.jdks/temurin-25.0.4.1"
WORKTREES = STATE / "worktrees"

# Mod settings forced on the copy before every start. No player ever joins it, and the default pauses the
# whole offscreen simulation on an empty server.
TEST_OVERRIDES = {"OFFSCREEN.pauseWhenServerEmpty": "false",
                  # Stalls are captured into logs/travellers-trace on their own, so a run leaves traces to read.
                  "TRACE.autoCapture": "true"}


def die(msg):
    print("ERROR: " + msg)
    sys.exit(1)


def read_props():
    props = {}
    for line in (SERVER / "server.properties").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            props[k] = v
    return props


def write_props(changes):
    path = SERVER / "server.properties"
    lines = path.read_text().splitlines()
    seen = set()
    for i, line in enumerate(lines):
        key = line.split("=", 1)[0]
        if key in changes:
            lines[i] = key + "=" + changes[key]
            seen.add(key)
    lines += [k + "=" + v for k, v in changes.items() if k not in seen]
    path.write_text("\n".join(lines) + "\n")


def replace_in_file(path, pattern, repl):
    if path.exists():
        path.write_text(re.sub(pattern, repl, path.read_text(), flags=re.M))


def server_pid():
    out = subprocess.run(["pgrep", "-f", r"java .*-Dtravellers\.autotest=true"], capture_output=True, text=True)
    pids = out.stdout.split()
    return int(pids[0]) if pids else None


def mem_available_gb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) / 1024 / 1024
    return 0.0


def latest_log():
    return SERVER / "logs" / "latest.log"


# ---------------------------------------------------------------- setup / deploy / reset

def cmd_setup():
    if SERVER.exists():
        die(f"{SERVER} already exists; delete it first to rebuild the copy")
    t0 = time.time()
    cmd = ["rsync", "-a", "--no-owner", "--no-group"] + ["--exclude=" + e for e in SETUP_EXCLUDES] + [SOURCE, str(SERVER) + "/"]
    if subprocess.run(cmd).returncode != 0:
        die("rsync failed; delete " + str(SERVER) + " before retrying")
    STATE.mkdir()
    write_props({
        "server-port": str(GAME_PORT),
        "enable-rcon": "true",
        "rcon.port": str(RCON_PORT),
        "rcon.password": secrets.token_hex(12),
        "broadcast-rcon-to-ops": "false",
        "motd": "Travellers AUTOTEST",
    })
    replace_in_file(SERVER / "settings.cfg", r"^JAVA_PATH=.*", f"JAVA_PATH={JDK}/bin/java")
    replace_in_file(SERVER / "config/voicechat/voicechat-server.properties", r"^port=\d+", f"port={VOICECHAT_PORT}")
    replace_in_file(SERVER / "config/groovyscript.cfg", r"languageServerPort=\d+", f"languageServerPort={GROOVY_LSP_PORT}")
    world = world_dir()
    # The reference every run starts from; the live world is only ever a scratch copy of it.
    with zipfile.ZipFile(SNAPSHOT, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(world.rglob("*")):
            if f.is_file() and f.name != "session.lock":
                z.write(f, f.relative_to(world))
    print(f"Created {SERVER} in {time.time() - t0:.0f}s (world {world.name}, snapshot {SNAPSHOT.stat().st_size >> 20} MB)")
    print(f"Ports: game {GAME_PORT}, rcon {RCON_PORT}, voicechat {VOICECHAT_PORT}")


def world_dir():
    return SERVER / read_props()["level-name"]


def newest_production_jar():
    jars = [p for p in (PROJECT / "build/libs").glob("travellers-*.jar")
            if not re.search(r"-(dev|uberdev|latest)\.jar$", p.name)]
    return max(jars, key=lambda p: p.stat().st_mtime) if jars else None


def git_head():
    out = subprocess.run(["git", "-C", str(PROJECT), "log", "-1", "--format=%h %s"], capture_output=True, text=True)
    dirty = subprocess.run(["git", "-C", str(PROJECT), "status", "--porcelain", "--", "src"],
                           capture_output=True, text=True).stdout.strip()
    return out.stdout.strip()[:70] + (" +uncommitted src changes" if dirty else "")


def run_then_stop(verb, args):
    try:
        return verb(args)
    finally:
        stop_if_running()


def stop_if_running():
    """A verb that needs the server down stops it first."""
    if server_pid():
        cmd_stop()


def cmd_deploy(args):
    stop_if_running()
    jar = Path(args[0]).resolve() if args else newest_production_jar()
    if not jar or not jar.exists():
        die("no jar to deploy; run ./gradlew build")
    for old in (SERVER / "mods").glob("travellers-*.jar"):
        old.unlink()
    shutil.copy2(jar, SERVER / "mods" / JAR_NAME)
    age_min = (time.time() - jar.stat().st_mtime) / 60
    source = git_head() if not args else "given path"
    DEPLOYED.write_text(f"{jar.name} built {age_min:.0f} min before deploy | {source}\n")
    print(f"Deployed {jar.name} (built {age_min:.0f} min ago) | {source}")


def cmd_reset():
    stop_if_running()
    world = world_dir()
    if world.exists():
        shutil.rmtree(world)
    world.mkdir()
    with zipfile.ZipFile(SNAPSHOT) as z:
        z.extractall(world)
    print(f"World restored from {SNAPSHOT.name}")


# ---------------------------------------------------------------- start / stop / status

def java_command():
    settings = {}
    for line in (SERVER / "settings.cfg").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            settings[k] = v.strip().strip('"')
    loader = SERVER / f"cleanroom-{settings['CLEANROOM_VER']}.jar"
    return ([settings["JAVA_PATH"], f"-Xmx{HEAP_GB}G", f"-Xms{HEAP_GB}G"] + settings["JAVA_ARGS"].split()
            + ["-Dtravellers.autotest=true", "-jar", str(loader)] + settings["GAME_ARGS"].split())


def apply_test_overrides():
    path = SERVER / "config/travellers/travellers_overrides.properties"
    text = path.read_text() if path.exists() else ""
    for key, value in TEST_OVERRIDES.items():
        line = f"{key}={value}"
        pattern = r"^" + re.escape(key) + r"=.*$"
        text = re.sub(pattern, line, text, flags=re.M) if re.search(pattern, text, re.M) else text + line + "\n"
    path.write_text(text)


def cmd_start():
    if server_pid():
        die("server already running")
    apply_test_overrides()
    if not (SERVER / "mods" / JAR_NAME).exists():
        die("no jar deployed; run deploy first")
    free = mem_available_gb()
    if free < HEAP_GB + 1.5:
        die(f"only {free:.1f} GB of memory free, the test server needs about {HEAP_GB + 1.5:.1f} GB")
    loaded = server_like_processes()
    log = latest_log()
    if log.exists():
        log.unlink()
    shell_cmd = "cd " + str(SERVER) + " && exec " + " ".join(java_command()) + " > logs/console.txt 2>&1"
    (SERVER / "logs").mkdir(exist_ok=True)
    # Detached from this process: the server outlives the runner call and reads no console input.
    subprocess.Popen(["bash", "-c", shell_cmd], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True)
    t0 = time.time()
    while time.time() - t0 < START_TIMEOUT_S:
        time.sleep(2)
        if log.exists() and "Done (" in log.read_text(errors="replace") and debug_ready():
            print(f"Server up in {time.time() - t0:.0f}s (console: logs/console.txt)")
            if loaded:
                print(f"Note: {loaded} other Minecraft process(es) running, timings may be skewed")
            return
        if not server_pid() and time.time() - t0 > 10:
            crash = sorted((SERVER / "crash-reports").glob("*.txt"), key=lambda p: p.stat().st_mtime)
            print("Server died during startup.")
            if crash and crash[-1].stat().st_mtime > t0:
                print("Crash report: " + str(crash[-1]))
            print("Log: " + str(log if log.exists() else SERVER / "logs/console.txt"))
            sys.exit(1)
    die(f"no 'Done' after {START_TIMEOUT_S}s; log: {log}")


def debug_ready():
    """The mod's command answers: "Done" alone has come before command registration settled."""
    try:
        return "profile" in debug("profile status")
    except OSError:
        return False


def server_like_processes():
    out = subprocess.run(["pgrep", "-f", r"java .*(-jar \S*(cleanroom|forge)\S*\.jar|net\.minecraft\.client|launchwrapper)"], capture_output=True, text=True)
    own = server_pid()
    return len([p for p in out.stdout.split() if int(p) != own])


def cmd_stop():
    pid = server_pid()
    if not pid:
        print("Server not running")
        return
    try:
        print("stop: " + (rcon("stop") or "(no reply)"))
    except OSError as e:
        print(f"RCON unreachable ({e}), killing")
    t0 = time.time()
    while server_pid() and time.time() - t0 < STOP_TIMEOUT_S:
        time.sleep(1)
    if server_pid():
        subprocess.run(["kill", "-9", str(pid)])
        print(f"Killed after {STOP_TIMEOUT_S}s (save may be incomplete)")
    else:
        print(f"Stopped in {time.time() - t0:.0f}s")


def cmd_status():
    pid = server_pid()
    print("Running (pid %d)" % pid if pid else "Not running")
    print("Deployed: " + (DEPLOYED.read_text().strip() if DEPLOYED.exists() else "nothing"))


# ---------------------------------------------------------------- RCON

class Rcon:
    """One connection reused for every command: vanilla 1.12 RCON drops some of many short-lived ones."""

    def __init__(self):
        props = read_props()
        self.sock = socket.create_connection(("127.0.0.1", int(props["rcon.port"])), timeout=60)
        self.send(1, 3, props["rcon.password"])
        if self.recv()[0] == -1:
            raise OSError("RCON authentication failed")

    def send(self, req_id, kind, body):
        data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(data)) + data)

    def recv(self):
        size = struct.unpack("<i", self.read_exact(4))[0]
        data = self.read_exact(size)
        req_id, _ = struct.unpack("<ii", data[:8])
        return req_id, data[8:-2].decode("utf-8", errors="replace")

    def read_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise OSError("connection closed")
            buf += chunk
        return buf

    def command(self, command):
        self.send(2, 2, command)
        return self.recv()[1]


_rcon = None


def rcon(command):
    global _rcon
    for attempt in (1, 2):
        try:
            if _rcon is None:
                _rcon = Rcon()
            return _rcon.command(command)
        except OSError:
            _rcon = None
            if attempt == 2:
                raise
            time.sleep(2)


def cmd_cmd(args):
    if not args:
        die('usage: cmd "<command>"')
    reply = re.sub(r"§.", "", rcon(" ".join(args))).strip()
    lines = reply.splitlines() or ["(no reply)"]
    for line in lines[:10]:
        print(line)
    if len(lines) > 10:
        print(f"... {len(lines) - 10} more lines")


# ---------------------------------------------------------------- soak

def debug(command):
    return re.sub(r"§.", "", rcon("travellersdebug " + command)).strip()


def layout():
    """(arena, zones) chosen by the mod: each is (x, z, radiusChunks); the arena is the first entry of the reply."""
    reply = debug("autotest layout")
    if not reply.startswith("layout:") or "no village" in reply:
        die("layout: " + reply)
    entries = [tuple(int(v) for v in m.groups()) for m in re.finditer(r"(-?\d+) (-?\d+) (\d+)", reply.split(" | ", 1)[0])]
    zones = [tuple(int(v) for v in m.groups()) for m in re.finditer(r"(-?\d+) (-?\d+) (\d+) \(", reply)]
    return entries[0], zones


def cmd_soak(args):
    run_then_stop(soak, args)


def soak(args):
    if not server_pid():
        die("server not running")
    minutes = int(args[0]) if args else SOAK_MINUTES
    _, soak_zones = layout()
    for x, z, r in soak_zones:
        reply = debug(f"autotest zone add {x} {z} {r}")
        if "zone added" not in reply:
            die("zone add: " + reply)
    reply = debug("profile start")
    if "started" not in reply:
        die("profile start: " + reply)
    verdicts = []
    player_minutes = 0
    tick_ms = []
    for _ in range(minutes):
        time.sleep(60)
        verdicts.append(debug("autotest invariants"))
        if players_online() > 0:
            player_minutes += 1
        ms = mean_tick_ms()
        if ms is not None:
            tick_ms.append(ms)
    reply = debug("profile stop")
    m = re.search(r"(profile_\S+)\.txt", reply)
    if not m:
        die("profile stop: " + reply)
    metrics = {}
    for line in (SERVER / "logs/travellers-profile" / (m.group(1) + ".metrics")).read_text().splitlines():
        k, v = line.split("=", 1)
        metrics[k] = float(v)
    if tick_ms:
        metrics["tick_ms_avg"] = sum(tick_ms) / len(tick_ms)
        metrics["tick_ms_worst"] = max(tick_ms)
    fails = [v for v in verdicts if not v.startswith("invariants: PASS")]
    report = next((v.split(" | ")[-1] for v in verdicts), "")
    SOAKS.mkdir(exist_ok=True)
    out = SOAKS / f"soak-{time.strftime('%Y%m%d-%H%M%S')}.metrics"
    deployed = DEPLOYED.read_text().strip() if DEPLOYED.exists() else "?"
    kinds = failure_kinds(SERVER / report) if fails else {}
    out.write_text(f"# {deployed}\n# {minutes} min, zones {soak_zones}\n"
                   + f"invariant_fail_passes={len(fails)}\n"
                   + f"player_minutes={player_minutes}\n"
                   + "".join(f"fail_kind:{k}={v}\n" for k, v in kinds.items())
                   + "".join(f"{k}={v}\n" for k, v in metrics.items()))
    for line in soak_summary(metrics, minutes, len(fails)):
        print(line)
    if player_minutes:
        print(f"A player was online {player_minutes}/{minutes} min: numbers include their area")
    if fails:
        print("Invariant failures: " + str(SERVER / report))
    print(f"Profile: logs/travellers-profile/{m.group(1)}.txt | metrics: {out}")


def cmd_forest(args):
    return run_then_stop(forest, args)


def forest(args):
    if not server_pid():
        die("server not running")
    seconds = int(args[0]) if args else FOREST_SECONDS
    if not FOREST_ROUTES:
        print("forest: no routes defined for this world, skipped")
        return None
    results = []
    for x1, z1, x2, z2 in FOREST_ROUTES:
        half = max(abs(x2 - x1), abs(z2 - z1)) // 2
        reply = debug(f"autotest zone add {(x1 + x2) // 2} {(z1 + z2) // 2} {half // 16 + 4}")
        if "zone added" not in reply:
            die("zone add: " + reply)
        time.sleep(5)
        reply = debug(f"autotest forest {x1} {z1} {x2} {z2} 4 3 {seconds}")
        if "forest started" not in reply:
            die("forest: " + reply)
        while True:
            time.sleep(10)
            reply = debug("autotest forest status")
            if reply.startswith("forest: done"):
                break
        results.append(reply.split(" | ", 1)[1].split(" | ")[0])
        debug("autotest zone clear")
    FORESTS.mkdir(exist_ok=True)
    out = FORESTS / f"forest-{time.strftime('%Y%m%d-%H%M%S')}.txt"
    deployed = DEPLOYED.read_text().strip() if DEPLOYED.exists() else "?"
    out.write_text(f"# {deployed}\n" + "".join(r + "\n" for r in results))
    for r in results:
        print(r)
    print(f"Per rider: logs/travellers-autotest (latest file) | summary: {out}")
    return out


def cmd_scenarios(args):
    return run_then_stop(scenarios, args)


def cmd_restart_go(args):
    """Go order, save and restart the server mid-walk, then check the worker still arrives."""
    return run_then_stop(restart_go, args)


def restart_go(args):
    if not server_pid():
        die("server not running")
    save = run_scenarios("go-restart-save", stop_zones=False)
    if "FAIL 0" not in save[0] or "SKIP 0" not in save[0]:
        die("first half: " + " | ".join(save))
    time.sleep(3)
    cmd_stop()
    cmd_start()
    resume = run_scenarios("go-restart-resume", stop_zones=True)
    for line in save[:1] + resume:
        print(line)
    return resume


def scenarios(args):
    if not server_pid():
        die("server not running")
    parts = run_scenarios(args[0] if args else "all", stop_zones=True)
    SCENARIOS.mkdir(exist_ok=True)
    out = SCENARIOS / f"scenarios-{time.strftime('%Y%m%d-%H%M%S')}.txt"
    deployed = DEPLOYED.read_text().strip() if DEPLOYED.exists() else "?"
    out.write_text(f"# {deployed}\n" + "\n".join(parts) + "\n")
    for line in parts:
        print(line)
    print(f"Details: logs/travellers-autotest (latest file) | summary: {out}")
    return out


def run_scenarios(which, stop_zones):
    """Runs the scenario selector on the running server; returns the result lines (without the report path)."""
    arena, villages = layout()
    x, z, radius = arena
    # The arena for the open-ground scenarios, plus the busy villages for the ones that look there.
    for zx, zz, zr in [arena] + villages:
        reply = debug(f"autotest zone add {zx} {zz} {zr}")
        if "zone added" not in reply:
            die("zone add: " + reply)
    time.sleep(5)
    reply = debug(f"autotest run {which} {x} {z}")
    if "scenarios started" not in reply:
        die("run: " + reply)
    deadline = time.time() + SCENARIO_TIMEOUT_S
    while True:
        time.sleep(10)
        reply = debug("autotest run status")
        if reply.startswith("scenarios: done"):
            break
        if time.time() > deadline:
            debug("autotest run stop")
            die("scenarios: timed out; last status: " + reply)
    if stop_zones:
        debug("autotest zone clear")
    return reply.split(" | ")[1:-1]


def mean_tick_ms():
    """Overworld mean tick time over the last 100 ticks, from Forge's /forge tps (50 ms = 20 TPS)."""
    m = re.search(r"Dim\s+0 .*?Mean tick time: ([\d.]+) ms", re.sub(r"§.", "", rcon("forge tps") or ""))
    return float(m.group(1)) if m else None


def players_online():
    m = re.search(r"There are (\d+)", rcon("list") or "")
    return int(m.group(1)) if m else 0


def failure_kinds(report):
    """FAIL lines without the unit, the block names and line numbers, so two commits' runs compare."""
    kinds = {}
    for line in report.read_text(errors="replace").splitlines():
        if not line.startswith("FAIL "):
            continue
        kind = re.sub(r" \([^)]*\)", "", line[5:])
        if " by " not in kind:
            kind = kind.split(": ")[0]
        kind = re.sub(r":.* by ", " by ", kind)
        kind = re.sub(r":\d+\b", "", kind)
        kind = re.sub(r": [0-9a-f-]{36}$", "", kind).replace("=", "-")
        kinds[kind] = kinds.get(kind, 0) + 1
    return kinds


def soak_summary(m, minutes, failed_passes):
    unit_hours = max(m["loaded_unit_seconds"] / 3600, 1e-6)
    rate = lambda v: f"{v / unit_hours:.1f}/uh"
    anomalies = sorted(((v, k[8:]) for k, v in m.items() if k.startswith("anomaly.") and v > 0), reverse=True)
    lines = [
        f"Soak {minutes} min: avg {m['loaded_unit_seconds'] / max(m['game_seconds'], 1):.0f} units loaded, "
        f"{unit_hours:.1f} unit-hours, {m['materialized']:.0f} materialized ({m['materialize_refused']:.0f} refused)",
        f"Invariants: {'PASS' if failed_passes == 0 else f'FAIL on {failed_passes}/{minutes} passes'}",
        f"Stalls: {m['stall_episodes']:.0f} ({rate(m['stall_episodes'])}), {m['stalls_60s_plus']:.0f} over 60 s, "
        f"longest {m['stall_max_seconds']:.0f} s, {100 * m['stall_seconds'] / max(m['loaded_unit_seconds'], 1):.1f}% of loaded time",
        "Anomalies (per loaded unit-hour): " + (", ".join(f"{k} {v:.0f} ({rate(v)})" for v, k in anomalies[:6]) or "none"),
        "Travel efficiency: " + ", ".join(f"{k[18:]} {v:.2f}" for k, v in m.items() if k.startswith("travel_efficiency.")),
        f"A*: {m['path_ms_per_tick']:.2f} ms/tick, worst tick {m['path_worst_tick_ms']:.0f} ms, budget full {m['travel_budget_full_ticks']:.0f} ticks",
    ]
    if "wedge_episodes" in m:
        kinds = ", ".join(f"{k[6:]} {v:.0f}" for k, v in m.items() if k.startswith("wedge.") and v > 0)
        lines.append(f"Mounted wedges: {m['wedge_episodes']:.0f} ({kinds or 'none'}), {m['wedge_over_2s']:.0f} over 2 s; "
                     f"mounted A* without a full path {m['path_mounted_failed']:.0f}/{m['path_mounted_searches']:.0f}")
    if "tick_ms_avg" in m:
        tps = lambda ms: min(20.0, 1000 / max(ms, 1e-6))
        lines.append(f"Server: {tps(m['tick_ms_avg']):.1f} TPS on average ({m['tick_ms_avg']:.0f} ms/tick), "
                     f"worst minute {tps(m['tick_ms_worst']):.1f} TPS")
    return lines


# ---------------------------------------------------------------- compare / ab

def load_metrics(name):
    path = Path(name) if Path(name).exists() else SOAKS / name
    m, header = {}, []
    for line in path.read_text().splitlines():
        if line.startswith("#"):
            header.append(line[2:])
        elif "=" in line:
            k, v = line.split("=", 1)
            m[k] = float(v)
    return m, header


def compare(a, b):
    """Returns (table lines, alert lines) for run b against run a."""
    uh_a = max(a["loaded_unit_seconds"] / 3600, 1e-6)
    uh_b = max(b["loaded_unit_seconds"] / 3600, 1e-6)
    table, alerts = [], []
    for k in sorted(k for k in a if k.startswith("travel_efficiency.")):
        if k not in b:
            continue
        table.append(f"{k[18:]:34}{a[k]:9.2f}{b[k]:9.2f}")
        if b[k] < a[k] * (1 - EFFICIENCY_DROP):
            alerts.append(f"efficiency {k[18:]} {a[k]:.2f} -> {b[k]:.2f}")
    incidents = ["stall_episodes", "stalls_60s_plus", "materialize_refused", "wedge_episodes", "wedge_over_2s",
                 "wedge_seconds"] + sorted(k for k in a if k.startswith(("anomaly.", "led.", "wedge.")))
    for k in incidents:
        ca, cb = a.get(k, 0), b.get(k, 0)
        if ca + cb == 0:
            continue
        ra, rb = ca / uh_a, cb / uh_b
        table.append(f"{k + ' /uh':34}{ra:9.2f}{rb:9.2f}")
        if cb >= INCIDENT_MIN_COUNT and rb > max(ra, 1e-6) * INCIDENT_RATIO:
            alerts.append(f"{k} {ra:.2f} -> {rb:.2f} per unit-hour ({cb:.0f} cases)")
    if "path_mounted_searches" in a and "path_mounted_searches" in b:
        fa = a["path_mounted_failed"] / max(a["path_mounted_searches"], 1)
        fb = b["path_mounted_failed"] / max(b["path_mounted_searches"], 1)
        table.append(f"{'mounted A* without full path':34}{fa:9.2f}{fb:9.2f}")
    k = "path_ms_per_tick"
    table.append(f"{k:34}{a[k]:9.2f}{b[k]:9.2f}")
    if "tick_ms_avg" in a and "tick_ms_avg" in b:
        table.append(f"{'tick_ms_avg':34}{a['tick_ms_avg']:9.1f}{b['tick_ms_avg']:9.1f}")
    if b[k] > a[k] * PATH_COST_RATIO:
        alerts.append(f"A* cost {a[k]:.2f} -> {b[k]:.2f} ms/tick")
    for k in sorted(k for k in set(a) | set(b) if k.startswith("fail_kind:")):
        table.append(f"{k[10:][:60]:60}{a.get(k, 0):6.0f}{b.get(k, 0):6.0f}")
        if k not in a:
            alerts.append("new invariant failure: " + k[10:])
    return table, alerts


def print_comparison(a, b, label_a, label_b):
    table, alerts = compare(a, b)
    pa, pb = a.get("player_minutes", 0), b.get("player_minutes", 0)
    if (pa > 0) != (pb > 0):
        print(f"WARNING: a player was online in only one run (A {pa:.0f} min, B {pb:.0f} min): not comparable")
    print(f"{'':34}{'A':>9}{'B':>9}   A = {label_a} | B = {label_b}")
    for line in table:
        print(line)
    print("ALERTS: " + ("; ".join(alerts) if alerts else "none"))
    return alerts


def cmd_compare(args):
    if len(args) != 2:
        die("usage: compare <a.metrics> <b.metrics>")
    (a, ha), (b, hb) = load_metrics(args[0]), load_metrics(args[1])
    print_comparison(a, b, ha[0][:50] if ha else args[0], hb[0][:50] if hb else args[1])


def build_commit(commit):
    tree = WORKTREES / commit
    if not tree.exists():
        WORKTREES.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "-C", str(PROJECT), "worktree", "add", "--detach", str(tree), commit],
                       check=True, capture_output=True)
        (tree / "libs").symlink_to(PROJECT / "libs")
    env = dict(os.environ, JAVA_HOME=JDK)
    # build ends with deployToServer (ssh to the remote server); its failure does not mean the jar is missing.
    # --no-daemon: a worktree build must not leave a second Gradle daemon and its workers behind (user).
    subprocess.run(["./gradlew", "build", "-q", "--no-daemon", "-x", "test", "-x", "deployToServer"], cwd=tree, env=env,
                   capture_output=True)
    jars = [p for p in (tree / "build/libs").glob("travellers-*.jar")
            if not re.search(r"-(dev|uberdev|latest)\.jar$", p.name)]
    if not jars:
        die(f"build of {commit} produced no jar; run ./gradlew build in {tree} to see why")
    return max(jars, key=lambda p: p.stat().st_mtime)


def soak_commit(commit, minutes):
    jar = build_commit(commit)
    cmd_deploy([str(jar)])
    DEPLOYED.write_text(f"{commit} ({jar.name})\n")
    cmd_reset()
    cmd_start()
    before = set(SOAKS.glob("*.metrics")) if SOAKS.exists() else set()
    try:
        forest([])
        soak([str(minutes)])
    finally:
        stop_if_running()
    produced = sorted(set(SOAKS.glob("*.metrics")) - before)
    if not produced:
        die(f"soak of {commit} produced no metrics")
    return produced[-1]


def cmd_ab(args):
    if len(args) < 2:
        die("usage: ab <commitA> <commitB> [minutes]")
    minutes = int(args[2]) if len(args) > 2 else SOAK_MINUTES
    commits = []
    for c in args[:2]:
        out = subprocess.run(["git", "-C", str(PROJECT), "rev-parse", "--short", c], capture_output=True, text=True)
        if out.returncode != 0:
            die("unknown commit " + c)
        commits.append(out.stdout.strip())
    results = []
    for c in commits:
        print(f"=== {c}")
        results.append(soak_commit(c, minutes))
    print("=== comparison")
    alerts = print_comparison(load_metrics(results[0])[0], load_metrics(results[1])[0], commits[0], commits[1])
    if alerts:
        print(f"Alert: confirm with a second run of {commits[1]} before concluding (user rule).")


# ---------------------------------------------------------------- log scan

HEADER = re.compile(r"^\[\d\d:\d\d:\d\d\] \[([^\]]+)/(\w+)\] \[([^\]]+)\]: (.*)$")
STD_CALLER = re.compile(r"^\[([^\]]+)\]: ?(.*)$")
EXCEPTION = re.compile(r"^(?:Caused by: )?([\w$.]+(?:Exception|Error|Throwable))\b")
FRAME = re.compile(r"^\s*at ([\w$.<>]+)")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)
OURS = re.compile(r"com\.jubitus\.travellers|travellers|TravellerSound", re.I)


class Event:
    def __init__(self, level, logger, first):
        self.level, self.logger, self.lines = level, logger, [first]


def parse_events(path):
    """Groups a log into events: a header line plus its continuation lines; consecutive STDERR lines from the
    same caller are one event (printStackTrace writes one header per frame)."""
    events, current, caller = [], None, None
    for raw in path.read_text(errors="replace").splitlines():
        m = HEADER.match(raw)
        if not m:
            if current:
                current.lines.append(raw)
            continue
        _, level, logger, msg = m.groups()
        if logger in ("STDERR", "STDOUT"):
            cm = STD_CALLER.match(msg)
            this_caller, msg = (cm.group(1), cm.group(2)) if cm else (None, msg)
            if current and current.logger == logger and this_caller == caller:
                current.lines.append(msg)
                continue
            caller = this_caller
        else:
            caller = None
        current = Event(level, logger, msg)
        events.append(current)
    return events


def signature(event):
    exc = next((m.group(1) for m in map(EXCEPTION.match, (l.strip() for l in event.lines)) if m), None)
    if exc:
        frames = [m.group(1) for m in map(FRAME.match, event.lines) if m]
        ours = [f for f in frames if "com.jubitus" in f]
        where = (ours or frames or ["?"])[0]
        return f"{event.logger} | {exc} @ {where}"
    text = re.sub(r"\d+", "#", UUID.sub("<uuid>", event.lines[0]))[:140]
    return f"{event.logger} | {event.level} | {text}"


def is_problem(event):
    has_exc = any(EXCEPTION.match(l.strip()) for l in event.lines)
    if has_exc or event.level in ("ERROR", "FATAL"):
        return True
    return event.level == "WARN" and OURS.search(event.logger + " " + event.lines[0]) is not None


def cmd_logscan(args):
    log = latest_log()
    if not log.exists():
        die("no log at " + str(log))
    found = {}
    for ev in parse_events(log):
        if is_problem(ev):
            sig = signature(ev)
            entry = found.setdefault(sig, [0, ev])
            entry[0] += 1
    if args[:1] == ["--baseline"]:
        BASELINE.write_text("\n".join(sorted(found)) + "\n")
        print(f"Baseline stored: {len(found)} known problem signatures")
        return
    known = set(BASELINE.read_text().splitlines()) if BASELINE.exists() else set()
    ours, new = [], []
    for sig, (count, ev) in found.items():
        is_ours = OURS.search(sig + "\n" + "\n".join(ev.lines)) is not None
        if is_ours:
            ours.append((sig in known, sig, count, ev))
        elif sig not in known:
            new.append((False, sig, count, ev))
    report = STATE / f"logscan-{time.strftime('%Y%m%d-%H%M%S')}.txt"
    with report.open("w") as out:
        for known_flag, sig, count, ev in ours + new:
            out.write(f"=== {count}x {'(known) ' if known_flag else ''}{sig}\n")
            out.write("\n".join(ev.lines[:60]) + "\n\n")
    print(f"{len(found)} problem kinds, {len(known)} in baseline"
          + ("" if known else " (NO BASELINE: run logscan --baseline on a reference run)"))
    lines = [f"OURS{' (known)' if k else ''} {c}x {s}" for k, s, c, _ in sorted(ours)]
    lines += [f"NEW {c}x {s}" for _, s, c, _ in new]
    for line in lines[:MAX_SCAN_LINES]:
        print(line[:200])
    if len(lines) > MAX_SCAN_LINES:
        print(f"... {len(lines) - MAX_SCAN_LINES} more")
    if not lines:
        print("Nothing new.")
    print("Details: " + str(report))


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return
    verb, args = sys.argv[1], sys.argv[2:]
    if verb != "setup" and not SERVER.exists():
        die(f"{SERVER} missing; run setup first")
    verbs = {
        "setup": lambda: cmd_setup(),
        "deploy": lambda: cmd_deploy(args),
        "reset": lambda: cmd_reset(),
        "start": lambda: cmd_start(),
        "cmd": lambda: cmd_cmd(args),
        "wait": lambda: time.sleep(float(args[0])),
        "soak": lambda: cmd_soak(args),
        "forest": lambda: cmd_forest(args),
        "scenarios": lambda: cmd_scenarios(args),
        "restart-go": lambda: cmd_restart_go(args),
        "compare": lambda: cmd_compare(args),
        "ab": lambda: cmd_ab(args),
        "stop": lambda: cmd_stop(),
        "status": lambda: cmd_status(),
        "logscan": lambda: cmd_logscan(args),
    }
    if verb not in verbs:
        die("unknown verb " + verb)
    verbs[verb]()


if __name__ == "__main__":
    main()
