#!/usr/bin/env python3
"""Preparation-only synthetic WARN/OVER/recovery integration probe. Never runs on import.
Execution requires explicit --run --out known-boundary-<new> --expected-apk-sha256 <64hex>.
Requires root-released AVD lane, existing foreground app, route-off native Navi,
healthy official Console heartbeat at localhost:8088, and P0/zero controls.
Keeps TTS/chime OFF; admitted reducer requests are not speaker playback.
No app launch/reinstall, standby changes, HVAC writes, navigation or PCM collection.
Offline --analyze accepts only an existing known-boundary-* folder.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
SERIAL = "emulator-5554"
PKG = "ai.vipseat.cargoconcierge"
LABELS = {"tts": "음성 코칭 (TTS)", "chime": "경고음"}
OUT = None
LOCK = threading.Lock()


POLICY = {"limitG": 0.12, "warnRatio": 0.8, "chimeCooldownMs": 5000,
          "coachCooldownMs": 8000, "audibleCooldownMs": 8000, "badgeHoldMs": 3000,
          "maxVoiceCoachPerTrip": 3, "maxAudiblePerTrip": 6, "emergencyG": 0.6,
          "minCoachSpeedMps": 15 / 3.6}
ROOT = BASE.parents[2]
CORE = ROOT / "app/core/src/main/kotlin/ai/vipseat/cargoconcierge/core"


def source_hashes():
    return {str(f.relative_to(ROOT)): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in (CORE / "engine/CargoStateMachine.kt", CORE / "model/CargoPreset.kt",
                      CORE / "physics/GEstimator.kt")}


def validate_policy_sources():
    text = (CORE / "engine/CargoStateMachine.kt").read_text()
    for key, value in POLICY.items():
        if key in ("limitG", "minCoachSpeedMps"):
            continue
        match = re.search(r"val " + key + r": \w+ = ([0-9_.]+)", text)
        if not match or float(match[1].replace("_", "")) != value:
            raise RuntimeError("core policy changed: " + key)
    if "val minCoachSpeedMps: Double = 15.0 / 3.6" not in text:
        raise RuntimeError("minimum coach speed policy changed")
    preset = (CORE / "model/CargoPreset.kt").read_text().split("val COFFEE = CargoPreset(", 1)[1].split(")", 1)[0]
    if not re.search(r"limitG\s*=\s*0\.12\s*,", preset):
        raise RuntimeError("COFFEE limit changed")


class BoundaryOracle:
    """Independent received-time state/audio oracle; never uses host sleep as dt."""
    def __init__(self):
        self.badge = "OK"
        self.last_badge = self.last_warn = self.last_coach = self.last_audible = -10**18
        self.speech = self.chime = 0
        self.suppressed = {}
        self.admissions = []
        self.last_over_ms = -1

    def observe(self, row, total):
        t, snap, trip = row["tMs"], row["snapshot"], row["trip"]
        issues, proposals = [], []
        if snap["parkedStill"]:
            self.badge = "OK"
        else:
            level = "OVER" if total >= POLICY["limitG"] else "WARN" if total >= POLICY["limitG"]*POLICY["warnRatio"] else "OK"
            if level != "OK":
                entering = self.badge == "OK" or level == "OVER" and self.badge == "WARN"
                self.badge, self.last_badge = level, t
                parking = snap["speedMps"] < POLICY["minCoachSpeedMps"] and row["estimate"]["lateralG"] >= abs(row["estimate"]["longitudinalG"])
                if entering and not parking and t-self.last_warn >= POLICY["chimeCooldownMs"]:
                    proposals.append("chime")
                if level == "OVER" and not parking and t-self.last_coach >= POLICY["coachCooldownMs"]:
                    proposals.append("speech")
                if level == "OVER":
                    self.last_over_ms = t
            elif self.badge != "OK" and t-self.last_badge >= POLICY["badgeHoldMs"]:
                self.badge, self.last_badge = "OK", t
        if proposals:
            reason = ("SIGNAL_UNAVAILABLE" if snap["gear"] == "PARK" or row["motionStatus"] in ("STALE", "DISCONNECTED")
                      else "EMERGENCY" if total >= POLICY["emergencyG"]
                      else "TRIP_LIMIT" if self.speech+self.chime >= POLICY["maxAudiblePerTrip"]
                      else "COOLDOWN" if t-self.last_audible < POLICY["audibleCooldownMs"] else None)
            selected = None if reason else ("speech" if "speech" in proposals and self.speech < POLICY["maxVoiceCoachPerTrip"]
                                            else "chime" if "chime" in proposals else None)
            suppression = reason or ("VOICE_LIMIT" if selected is None else "COALESCED" if len(proposals)>1 else None)
            if suppression:
                self.suppressed[suppression] = self.suppressed.get(suppression, 0) + len(proposals)-int(selected is not None)
            if selected:
                if self.admissions and t-self.admissions[-1]["tMs"] < POLICY["audibleCooldownMs"]:
                    issues.append("admission interval below 8 seconds")
                self.admissions.append({"tMs": t, "kind": selected})
                self.last_audible = t
                if selected == "speech":
                    self.speech += 1; self.last_coach = t
                else:
                    self.chime += 1; self.last_warn = t
        if row["badge"] != self.badge:
            issues.append("badge/hold oracle mismatch")
        for key, value in self.counts().items():
            if trip[key] != value:
                issues.append(key + " admission oracle mismatch")
        return issues

    def counts(self):
        return {"drivingSpeechCount": self.speech, "drivingChimeCount": self.chime,
                "coachingCount": self.speech, "warnCount": self.chime}

    def check_report(self, report):
        issues = [key + " report mismatch" for key, value in self.counts().items() if report[key] != value]
        if report["audioSuppressionCounts"] != self.suppressed:
            issues.append("report suppression reasons/counts mismatch")
        if self.chime < 1 or self.speech < 1:
            issues.append("WARN chime / OVER speech request not observed")
        if self.speech > 3 or self.speech+self.chime > 6:
            issues.append("admission budget exceeded")
        if self.suppressed.get("COALESCED", 0) < 1:
            issues.append("entry chime/speech coalescing not exercised")
        return issues

    def summary(self):
        return {**self.counts(), "suppressed": self.suppressed, "admissions": self.admissions,
                "actualPlayback": "not measured; both settings OFF"}


def save(name, data):
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def event(name, **fields):
    row = {"event": name, "hostUnixNs": time.time_ns(),
           "hostMonotonicNs": time.monotonic_ns(), **fields}
    with LOCK, (OUT / "input-ledger.jsonl").open("a") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return row


def adb(*args, timeout=12, binary=False):
    result = subprocess.check_output([ADB, "-s", SERIAL, *args], timeout=timeout,
                                     stderr=subprocess.STDOUT)
    return result if binary else result.decode("utf-8", "replace")


def api(path, method="GET"):
    request = urllib.request.Request("http://127.0.0.1:8088" + path, method=method)
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)


def demo(cmd, value=None, phase="setup", step=None):
    if cmd not in {"speed", "steer", "brake", "preset", "settings", "end", "ack", "navi"}:
        raise ValueError("command outside probe scope")
    if cmd == "preset" and value != "COFFEE" or cmd == "navi" and value != "arrived":
        raise ValueError("only COFFEE and synthetic arrival are allowed")
    if cmd in {"speed", "steer", "brake"}:
        number = float(value)
        if not math.isfinite(number) or not {"speed": 0 <= number <= 18,
                                            "steer": number in (0, 60, 90, 100, 120),
                                            "brake": number == 0}[cmd]:
            raise ValueError("motion outside normal-range probe")
    args = ["shell", "am", "broadcast", "-n", PKG + "/.demo.DemoBroadcastReceiver",
            "-a", PKG + ".DEMO", "--es", "cmd", cmd]
    if value is not None:
        args += ["--es", "value", str(value)]
    start = event("command_start", command=cmd, value=value, phase=phase, step=step)
    result = adb(*args, timeout=6)
    if "Broadcast completed: result=0" not in result or 'data="error:' in result:
        raise RuntimeError("DEMO receiver rejected command")
    event("command_done", command=cmd, value=value, phase=phase, step=step,
          startHostMonotonicNs=start["hostMonotonicNs"])


def box(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise RuntimeError("UI node has no bounds")
    return values


def ui(name, screenshot=False):
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    xml = raw[raw.index("<?xml"):raw.index("</hierarchy>") + len("</hierarchy>")]
    (OUT / (name + ".xml")).write_text(xml + "\n")
    if screenshot:
        (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    return ET.fromstring(xml)


def switches(tree):
    nodes = [n for n in tree.iter("node") if n.get("package") == PKG]
    result = {}
    for key, label in LABELS.items():
        labels = [n for n in nodes if n.get("text") == label]
        if len(labels) != 1:
            raise RuntimeError("settings label is not uniquely visible")
        _, y1, x2, y2 = box(labels[0])
        matches = [n for n in nodes if n.get("checkable") == "true"
                   and box(n)[0] > x2 and max(y1, box(n)[1]) < min(y2, box(n)[3])]
        if len(matches) != 1 or matches[0].get("enabled") != "true":
            raise RuntimeError("settings switch is not uniquely enabled")
        n = matches[0]
        if n.get("checked") not in {"true", "false"}:
            raise RuntimeError("settings checked state unavailable")
        result[key] = {"checked": n.get("checked") == "true", "bounds": box(n)}
    return result


def configure(target, prefix):
    demo("settings", "on")
    current = switches(ui(prefix + "_before"))
    for key, wanted in target.items():
        if current[key]["checked"] != wanted:
            x1, y1, x2, y2 = current[key]["bounds"]
            adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
            current = switches(ui(prefix + "_after_" + key))
            if current[key]["checked"] != wanted:
                raise RuntimeError("settings toggle did not take effect")
    save(prefix + ".json", current)
    demo("settings", "off")
    return current


def elapsed():
    return round(float(adb("shell", "cat", "/proc/uptime").split()[0]) * 1000)


def surface(name):
    details = adb("shell", "dumpsys", "activity", "activities")
    # Save component/focus lines only; omit intent extras and navigation payloads.
    focus = [line.strip() for line in details.splitlines()
             if "mResumedActivity" in line or "topResumedActivity" in line]
    block = next((b for b in re.split(r"\n(?=\s*\* Hist)", details)
                  if PKG + "/.MainActivity" in b.splitlines()[0]), "")
    activity_state = re.search(r"\bstate=(\w+)", block)
    visible = re.search(r"mVisibleRequested=(true|false) mVisible=(true|false) mClientVisible=(true|false)", block)
    return event(name, deviceElapsedMs=elapsed(), pid=adb("shell", "pidof", PKG).strip(), focus=focus,
                 vipActivityState=activity_state.group(1) if activity_state else None,
                 vipVisible=visible.group(2) == "true" if visible else None)


def motion(kph, angle, phase, step):
    demo("steer", angle, phase, step)
    demo("brake", 0, phase, step)
    demo("speed", kph, phase, step)


def check_park(state):
    if state["gear"]["actual"]["value"] != "Park" or state["controls"]["speed_kph"] != 0:
        raise RuntimeError("probe requires existing official Console P and speed 0")


def analyze(folder):
    """Small normal-input math oracle; unsupported/gapped inputs fail, never get repaired."""
    global OUT
    OUT = folder
    env = json.loads((folder / "environment.json").read_text())
    ledger = [json.loads(line) for line in (folder / "input-ledger.jsonl").read_text().splitlines()]
    traces, issues, comparisons = [], [], []
    for line in (folder / "motion.log").read_text().splitlines():
        if "MotionTrace" not in line or "{" not in line:
            continue
        try:
            row = json.loads(line[line.index("{"):])
            if row["tMs"] >= env["traceStartElapsedMs"]:
                traces.append(row)
        except (ValueError, KeyError):
            issues.append("malformed MotionTrace row")
    save("trace.json", traces)
    guards = [r for r in traces if r["state"] == "GUARDING"]
    reports = [r for r in traces if r["event"] == "arrival"]
    if not guards:
        issues.append("no guarded motion traces")
    sessions = {r["trip"]["startedMs"] for r in guards}
    if len(sessions) != 1:
        issues.append("expected one uninterrupted guard session")
    last_speed, last_t, ema, cached_long = None, None, 0.0, 0.0
    expected_max, count, fresh_count = 0.0, 0, 0
    speed_rows, dt_values, model = [], [], None
    audio_oracle = BoundaryOracle()
    for index, row in enumerate(guards):
        raw, snap, actual, trip = row["raw"], row["snapshot"], row.get("estimate"), row["trip"]
        t, kind = row["tMs"], raw["kind"]
        reason = []
        if model is None:
            model = row["model"]
            expected_model = {"wheelbaseM": 2.9000000953674316, "steeringRatio": 15.0,
                              "gravityMps2": 9.80665, "smoothing": 0.5, "maxBrakeDecelMps2": 8.0}
            if model != expected_model:
                issues.append("model differs from predetermined Float32 2.9m / ratio15 fixture")
        if row["model"] != model:
            reason.append("model changed")
        if row["presetKind"] != "COFFEE" or row["limitG"] != 0.12:
            reason.append("preset/limit mismatch")
        if row["motionStatus"] != "FRESH":
            reason.append("motion reception is not FRESH")
        if snap["brakePressed"] is True:
            reason.append("unexpected brake input")
        if raw.get("provenance") != "SYNTHETIC" or snap["speedProvenance"] != "SYNTHETIC":
            reason.append("unexpected provenance")
        bad_quality = set(trip["quality"]) - {"INITIAL_BASELINE"}
        if bad_quality:
            reason.append("quality=" + ",".join(sorted(bad_quality)))
        expected_fresh, dt_seconds = False, None
        if kind == "speed":
            speed_rows.append(row)
            if raw["sourceId"] != 0 or not math.isclose(raw["mps"], snap["speedMps"], abs_tol=1e-12):
                reason.append("speed source/snapshot mismatch")
            if last_t is not None:
                dt_seconds = (t - last_t) / 1000.0
                dt_values.append(dt_seconds)
                if not 0.05 <= dt_seconds <= 2.0:
                    reason.append("unsupported received speed dt; no reset/replay shortcut")
                else:
                    acceleration = (raw["mps"] - last_speed) / dt_seconds
                    ema = model["smoothing"] * acceleration + (1-model["smoothing"]) * ema
                    expected_fresh = True
            cached_long = ema / model["gravityMps2"]
            last_t, last_speed = t, raw["mps"]
        elif kind == "steering" and raw["degrees"] != snap["steeringDeg"]:
            reason.append("steering raw/snapshot mismatch")
        speed, steering = snap["speedMps"], snap["steeringDeg"]
        if speed is None or steering is None or actual is None:
            reason.append("missing axis or estimate")
            expected_lat, expected_total = None, None
        else:
            expected_lat = abs(speed**2 * math.tan(math.radians(steering / model["steeringRatio"]))
                               / model["wheelbaseM"]) / model["gravityMps2"]
            expected_total = math.hypot(expected_lat, cached_long)
            for key, value in (("lateralG", expected_lat), ("longitudinalG", cached_long),
                               ("totalG", expected_total), ("speedMps", speed)):
                if not math.isclose(actual[key], value, rel_tol=1e-10, abs_tol=1e-10):
                    reason.append(key + " mismatch")
            if actual["longitudinalFresh"] != expected_fresh:
                reason.append("longitudinalFresh mismatch")
            if not actual["lateralAvailable"] or not actual["hasData"]:
                reason.append("unexpected axis availability")
            if not snap["parkedStill"]:
                expected_max = max(expected_max, expected_total)
                count += 1
                fresh_count += int(expected_fresh)
        if trip["gSamples"] != count or trip["freshLongitudinalSamples"] != fresh_count:
            reason.append("trip motion/fresh counters mismatch")
        if not math.isclose(trip["maxG"], expected_max, rel_tol=1e-10, abs_tol=1e-10):
            reason.append("trip maximum mismatch")
        if expected_total is not None:
            reason.extend(audio_oracle.observe(row, expected_total))
        comparisons.append({"row": index, "tMs": t, "kind": kind, "receivedDtSeconds": dt_seconds,
                            "expectedLateralG": expected_lat, "expectedLongitudinalG": cached_long,
                            "expectedTotalG": expected_total, "actual": actual, "issues": reason})
        issues.extend(f"row {index}: {s}" for s in reason)
    requests = [e for e in ledger if e["event"] == "command_start" and e.get("command") == "speed"
                and e.get("phase") in {"baseline", "acceleration", "settle", "ok60", "below90", "warn100", "over120", "recover60", "straight", "deceleration", "stop", "park"}]
    received = [r["raw"]["mps"] for r in speed_rows]
    if len(requests) != len(received) or any(not math.isclose(float(a["value"])/3.6, b, abs_tol=1e-12)
                                           for a, b in zip(requests, received)):
        issues.append("DEMO km/h requests do not map one-for-one to received source-0 m/s")
    if len(reports) != 1:
        issues.append("expected exactly one arrival trace")
    else:
        report = reports[0]["report"]
        if (not math.isclose(report["maxG"], expected_max, rel_tol=1e-10, abs_tol=1e-10)
                or report["gSamples"] != count or report["freshLongitudinalSamples"] != fresh_count):
            issues.append("arrival maximum/counters mismatch")
        if not report["hasSyntheticInput"] or report["canCompare"] or report["provenance"] != ["SYNTHETIC"]:
            issues.append("synthetic report boundary mismatch")
        if guards and (report["quality"] != guards[-1]["trip"]["quality"]
                       or report["provenance"] != guards[-1]["trip"]["provenance"]):
            issues.append("report provenance/quality does not carry through")
    if reports:
        issues.extend(audio_oracle.check_report(reports[0]["report"]))
    required = {60: "OK", 90: "OK", 100: "WARN", 120: "OVER"}
    for angle, badge in required.items():
        rows = [r for r in guards if r["snapshot"]["steeringDeg"] == angle
                and r["snapshot"]["speedMps"] == 5 and r["badge"] == badge]
        if len(rows) < 6:
            issues.append(f"insufficient observed {angle} degree / {badge} samples")
    recovery = next((e for e in ledger if e["event"] == "phase_start" and e.get("phase") == "recover60"), None)
    if not recovery or not any(r["snapshot"]["steeringDeg"] == 60 and r["badge"] == "OK"
                              for r in guards if r["tMs"] > audio_oracle.last_over_ms):
        issues.append("recovery to OK not observed")
    surface_events = [e for e in ledger if "pid" in e]
    if not surface_events or any(e["pid"] != env["pid"] for e in surface_events):
        issues.append("process identity changed")
    if not (folder / "report.xml").exists() or "실주행 평가 불가" not in (folder / "report.xml").read_text():
        issues.append("synthetic comparison boundary absent from report UI")
    if any(e["event"] == "execution_error" for e in ledger):
        issues.append("execution did not complete")
    cleanup = json.loads((folder / "cleanup.json").read_text()) if (folder / "cleanup.json").exists() else {}
    if not cleanup.get("verified"):
        issues.append("cleanup not verified")
    if not traces or traces[-1]["state"] != "IDLE" or traces[-1]["snapshot"]["gear"] != "PARK" or traces[-1]["snapshot"]["speedMps"] != 0:
        issues.append("final MotionTrace does not establish IDLE/P0")
    save("oracle-comparisons.json", comparisons)
    result = {"passed": not issues, "issues": issues, "traceRows": len(traces), "guardRows": len(guards),
              "receivedSpeedRows": len(speed_rows), "expectedMaximumG": expected_max,
              "motionEvents": count, "freshLongitudinalSamples": fresh_count, "model": model,
              "boundaryPolicy": POLICY, "audioOracle": audio_oracle.summary(), "receivedDtMinSeconds": min(dt_values, default=None),
              "receivedDtMaxSeconds": max(dt_values, default=None), "arrivalTraces": len(reports),
              "evidenceScope": "Synthetic DEMO -> production estimator/badge/audio-admission/report; received dt oracle. Both audio settings OFF; playback unmeasured.",
              "limits": ["Foreground synthetic boundary probe; no native SDK background claim.",
                         "No vehicle accuracy, physical cargo outcome, native navigation or audio claim.",
                         "Gaps/reordered batches are failures; no policy changes or discarded background interval."]}
    save("oracle-result.json", result)
    return result


def run(args):
    global OUT
    OUT = BASE / args.out
    if OUT.parent != BASE or not re.fullmatch(r"known-boundary-[A-Za-z0-9_-]+", args.out):
        raise ValueError("--out must be a new known-boundary-* folder name")
    validate_policy_sources()
    OUT.mkdir(exist_ok=False)
    (OUT / "harness-source.py").write_text(Path(__file__).read_text())
    original_flags = original_state = old_tag = None
    log_process = log_handle = None
    producer = None
    mutated = False
    stop = threading.Event()
    state = {"phase": None, "error": None}
    errors = []
    try:
        original_state = api("/vehicle/state")
        save("vehicle-before.json", original_state)
        check_park(original_state)
        if any(original_state["controls"][key] != 0 for key in ("accelerator_pct", "brake_pct", "steering_deg")):
            raise RuntimeError("probe requires existing zero accelerator/brake/steering controls")
        if adb("shell", "getprop", "ro.product.name").strip() != "crp_avd_arm64" or adb("shell", "getprop", "ro.build.version.sdk").strip() != "34":
            raise RuntimeError("requires official crp_avd_arm64 API34 AVD")
        pid = adb("shell", "pidof", PKG).strip()
        if not pid.isdigit():
            raise RuntimeError("existing app PID unavailable; probe does not launch/restart it")
        apk_paths = adb("shell", "pm", "path", PKG).splitlines()
        if len(apk_paths) != 1 or not apk_paths[0].startswith("package:"):
            raise RuntimeError("expected one installed base APK")
        installed_hash = hashlib.sha256(adb("exec-out", "cat", apk_paths[0][8:], binary=True)).hexdigest()
        if installed_hash != args.expected_apk_sha256.lower():
            raise RuntimeError("installed APK hash does not match approved candidate")
        package = adb("shell", "dumpsys", "package", PKG)
        versions = [line.strip() for line in package.splitlines() if "versionCode=" in line or "versionName=" in line]
        before_surface = surface("before")
        if not any(PKG in line for line in before_surface["focus"]):
            raise RuntimeError("existing app must be foreground before the probe")
        mutated = True
        demo("end"); demo("ack"); demo("settings", "on")
        original_flags = {key: item["checked"] for key, item in switches(ui("settings_original")).items()}
        save("settings-original.json", original_flags)
        configure({"tts": False, "chime": False}, "settings_muted")
        # Establish synthetic priority before selecting, so the new trip has a single speed source.
        motion(0, 0, "setup", 0)
        old_tag = adb("shell", "getprop", "log.tag.MotionTrace").strip()
        trace_start = elapsed()
        save("environment.json", {"pid": pid, "installedApkSha256": installed_hash, "versions": versions,
                                  "traceStartElapsedMs": trace_start, "originalMotionTraceProperty": old_tag,
                                  "motionPath": "DEMO synthetic", "consolePath": "actual gear setter only",
                                  "backgroundSurface": "none", "policy": POLICY, "sourceHashes": source_hashes(),
                                  "audioSettings": {"tts": False, "chime": False},
                                  "audioScope": "Reducer admitted requests only; no PCM/playback claim"})
        adb("shell", "setprop", "log.tag.MotionTrace", "DEBUG")
        log_handle = (OUT / "motion.log").open("w")
        log_process = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "epoch", "-T", "1", "--pid=" + pid,
                                        "MotionTrace:D", "CargoVM:D", "DemoBridge:I", "*:S"], stdout=log_handle, stderr=log_handle)
        demo("preset", "COFFEE")
        segments = [("baseline", [(0, 0)]*12), ("acceleration", [(n*.9, 0) for n in range(1, 21)]),
                    ("settle", [(18, 0)]*12), ("ok60", [(18, 60)]*8),
                    ("below90", [(18, 90)]*8), ("warn100", [(18, 100)]*20),
                    ("over120", [(18, 120)]*20), ("recover60", [(18, 60)]*12),
                    ("straight", [(18, 0)]*4), ("deceleration", [(n*.9, 0) for n in range(19, -1, -1)]),
                    ("stop", [(0, 0)]*8)]


        def produce():
            deadline = time.monotonic()
            try:
                for phase, values in segments:
                    state["phase"] = phase
                    event("phase_start", phase=phase)
                    for n, (kph, angle) in enumerate(values):
                        if stop.wait(max(0, deadline-time.monotonic())):
                            return
                        lateness = time.monotonic()-deadline
                        if lateness > 1:
                            raise RuntimeError("producer missed schedule by over one second")
                        motion(kph, angle, phase, n)
                        deadline += .5
                stop.wait(max(0, deadline-time.monotonic()))
                state["phase"] = "done"
            except BaseException as exc:
                state["error"] = str(exc)
                event("producer_error", error=str(exc))

        def wait_phase(wanted):
            until = time.monotonic()+75
            while state["phase"] != wanted:
                if state["error"] or state["phase"] == "done" or time.monotonic() > until:
                    raise RuntimeError(state["error"] or "phase unavailable or wait deadline")
                time.sleep(.05)

        producer = threading.Thread(target=produce, daemon=True)
        producer.start()
        wait_phase("baseline")
        start_ui = ui("start", True)
        text = " ".join(n.get("text", "") for n in start_ui.iter("node"))
        if "0.12G" not in text or "커피" not in text:
            raise RuntimeError("COFFEE guard not visible")
        if "캐빈 적용: 0개 성공, 0개 미적용" not in (OUT / "motion.log").read_text():
            raise RuntimeError("COFFEE zero-cabin-application evidence absent")
        if state["phase"] != "baseline":
            raise RuntimeError("setup UI exceeded stationary baseline window")
        api("/vehicle/gear?gear=Drive", "PUT")
        drive = api("/vehicle/state")["gear"]
        event("console_drive", actual=drive)
        if drive["actual"]["value"] != "Drive":
            raise RuntimeError("official Console did not reach Drive")
        for phase in ("ok60", "below90", "warn100", "over120", "recover60"):
            wait_phase(phase)
            surface(phase)
            # Screenshots run beside the 2 Hz producer; no motion pause to obtain UI.
            if phase in ("warn100", "over120"):
                ui(phase, True)
        wait_phase("done")
        producer.join(timeout=8)
        if producer.is_alive() or state["error"]:
            raise RuntimeError(state["error"] or "producer did not finish")
        api("/vehicle/gear?gear=Park", "PUT")
        motion(0, 0, "park", 0)
        check_park(api("/vehicle/state"))
        demo("navi", "arrived")
        time.sleep(1)
        ui("report", True); surface("report")
        event("execution_complete")
    except BaseException as exc:
        errors.append(str(exc))
        event("execution_error", error=str(exc))
    finally:
        execution_error_count = len(errors)
        stop.set()
        if producer:
            producer.join(timeout=20)
            if producer.is_alive():
                errors.append("producer still alive during cleanup")
        # Cleanup restores safe P0 and settings, without erasing failure evidence or restarting.
        for label, operation in [
            ("foreground", lambda: adb("shell", "am", "start", "-W", "-n", PKG + "/.MainActivity")),
            ("end", lambda: demo("end", phase="cleanup")),
            ("ack", lambda: demo("ack", phase="cleanup")),
            ("park", lambda: api("/vehicle/gear?gear=Park", "PUT")),
            ("motion_zero", lambda: motion(0, 0, "cleanup", 0)),
            ("settings", lambda: configure(original_flags, "settings_restored") if original_flags is not None else None),
        ]:
            if mutated:
                try:
                    operation()
                except BaseException as exc:
                    errors.append(label + ": " + str(exc))
        if old_tag is not None:
            try:
                adb("shell", "setprop log.tag.MotionTrace " + shlex.quote(old_tag))
                if adb("shell", "getprop", "log.tag.MotionTrace").strip() != old_tag:
                    raise RuntimeError("property readback mismatch")
            except BaseException as exc:
                errors.append("MotionTrace restoration: " + str(exc))
        if log_process:
            log_process.terminate()
            try:
                log_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                log_process.kill(); log_process.wait(timeout=5)
        if log_handle:
            log_handle.close()
        verified = False
        try:
            after = api("/vehicle/state")
            save("vehicle-after.json", after)
            check_park(after)
            unchanged = {
                "hvac": ("power_on", "auto_on", "ac_on", "dual_on", "sync_on", "recirc_on",
                         "front_defroster_on", "rear_defroster_on", "fan_speed", "auto_blower_level",
                         "driver", "passenger", "row2"),
                "seat": ("positions", "moves", "occupied"),
                "body": ("doors", "door_locks", "windows", "closures", "wipers", "cabin_light",
                         "reading_lights", "ambient_light"),
                "controls": ("speed_kph", "accelerator_pct", "brake_pct", "steering_deg"),
            }
            if original_state is not None and any(after[domain][key] != original_state[domain][key]
                                                  for domain, keys in unchanged.items() for key in keys):
                raise RuntimeError("cabin/seat/body/controls readback differs from original values")
            if mutated:
                ui("cleanup", True)
            verified = len(errors) == execution_error_count
        except BaseException as exc:
            errors.append("cleanup readback: " + str(exc))
        save("cleanup.json", {"verified": verified, "cleanupRequired": mutated,
                              "executionErrors": errors[:execution_error_count], "cleanupErrors": errors[execution_error_count:],
                              "originalMotionTracePropertyRestored": old_tag is not None and not any("MotionTrace restoration" in e for e in errors)})
    if (OUT / "environment.json").exists() and (OUT / "motion.log").exists():
        result = analyze(OUT)
        save("artifact-sha256.json", {f.name: hashlib.sha256(f.read_bytes()).hexdigest()
                                     for f in sorted(OUT.iterdir()) if f.is_file() and f.name != "artifact-sha256.json"})
        print(json.dumps({"out": str(OUT), "passed": result["passed"], "issueCount": len(result["issues"])}))
        return 0 if result["passed"] else 1
    print(json.dumps({"out": str(OUT), "passed": False, "errors": errors}))
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--run", action="store_true")
    mode.add_argument("--analyze", type=Path)
    parser.add_argument("--out")
    parser.add_argument("--expected-apk-sha256")
    args = parser.parse_args()
    if args.analyze:
        folder = args.analyze.resolve()
        if folder.parent != BASE or not folder.name.startswith("known-boundary-"):
            parser.error("--analyze requires an existing known-boundary-* folder beside this script")
        result = analyze(folder)
        print(json.dumps({"passed": result["passed"], "issueCount": len(result["issues"])}))
        return 0 if result["passed"] else 1
    if not args.out or not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256 or ""):
        parser.error("--run requires --out known-boundary-* and --expected-apk-sha256")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
