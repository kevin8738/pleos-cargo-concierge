#!/usr/bin/env python3
"""Prepared runtime AVD probes. No action on import; --run is mandatory.

After leader build/install and execution handoff:
  python3 runtime_avd_probe.py --run --case navigation --out runtime-navigation-01 \
      --expected-apk-sha256 <installed SHA256>
Other independent raw runs: --case force-stop or --case task-removal.
Both disruption cases wait 18 seconds without restarting the app, then manually
launch it and require idle/no service/no notification. No app-data deletion.

Reuses the import-safe known_answer_probe utilities; never imports the existing
navi_probe_guard.py/process scripts because those mutate the AVD on import.
No GPS route progression, HVAC/seat/door setter, microphone, permission grant,
background-policy change, app install, or motion DEMO in the background window.
Task removal requires an explicit VIP-only dismiss control in system Recents;
it never substitutes force-stop or guesses a swipe over an unidentified task.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import known_answer_probe as qa

BASE = Path(__file__).resolve().parent
PKG = qa.PKG
NAVI = "ai.umos.maps.android.navigation.app"
SERVICE = PKG + "/.runtime.GuardForegroundService"
NOTIFICATION_ID = 7301
# Verified from the local API34 android.jar ServiceInfo constants using javap.
SPECIAL_USE = 1073741824
LOCATION = 8
TAGS = ("GuardRuntime", "MotionTrace")
LIVE = []


def tap(node, label):
    x1, y1, x2, y2 = qa.box(node)
    qa.event(label, bounds=[x1, y1, x2, y2])
    qa.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def find(tree, text, package=None):
    return next((n for n in tree.iter("node") if text in n.get("text", "")
                 and (package is None or n.get("package") == package)), None)


def wait_ui(label, text, package=PKG):
    for n in range(7):
        tree = qa.ui(f"{label}_{n}")
        found = find(tree, text, package)
        if found is not None:
            return tree, found
        time.sleep(.5)
    raise RuntimeError("expected UI missing: " + text)


def clock_sync():
    before = time.time_ns()
    raw = qa.adb("shell", "date", "+%s.%N").strip()
    after = time.time_ns()
    if not re.fullmatch(r"\d+\.\d{9}", raw):
        raise RuntimeError("device wall-clock calibration unavailable")
    seconds, nanos = raw.split(".")
    device = int(seconds)*10**9 + int(nanos)
    return {"deviceMinusHostNs": device-(before+after)//2,
            "uncertaintyNs": (after-before)//2, "hostBeforeNs": before,
            "hostAfterNs": after, "deviceEpochNs": device}


def start_logcat():
    process = subprocess.Popen([qa.ADB, "-s", qa.SERIAL, "logcat", "-v", "epoch", "-T", "1",
                                "GuardRuntime:V", "MotionTrace:D", "PleosNavi:V", "CargoVM:D",
                                "CargoGuard:V", "PleosVehicle:D", "DemoBridge:I", "*:S"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)

    def read():
        with (qa.OUT / "live.log").open("w") as raw, (qa.OUT / "live-log.jsonl").open("w") as rows:
            for line in process.stdout:
                host_ns, mono_ns = time.time_ns(), time.monotonic_ns()
                raw.write(line); raw.flush()
                match = re.match(r"^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)", line.rstrip())
                row = {"hostReceivedUnixNs": host_ns, "hostReceivedMonotonicNs": mono_ns,
                       "line": line.rstrip()}
                if match:
                    row.update(deviceEpochSeconds=float(match[1]), pid=int(match[2]),
                               tag=match[5].strip(), message=match[6])
                    if row["tag"] in TAGS and row["message"].startswith("{"):
                        try:
                            row["data"] = json.loads(row["message"])
                        except ValueError:
                            row["parseError"] = True
                LIVE.append(row)
                rows.write(json.dumps(row, ensure_ascii=False)+"\n"); rows.flush()
    thread = threading.Thread(target=read, daemon=True)
    thread.start()
    return process, thread


def runtime_rows(event=None):
    return [r for r in list(LIVE) if r.get("tag") == "GuardRuntime" and "data" in r
            and (event is None or r["data"].get("event") == event)]


def wait_runtime(event, after_host_ns, seconds=8):
    until = time.monotonic()+seconds
    while time.monotonic() < until:
        found = [r for r in runtime_rows(event) if r["hostReceivedUnixNs"] >= after_host_ns]
        if found:
            return found[-1]
        time.sleep(.1)
    raise RuntimeError("runtime event unavailable: " + event)


def active_notifications(raw):
    lines = raw.splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == "Notification List:"), None)
    if start is None:
        raise RuntimeError("unknown notification dump layout; cannot claim absence")
    indent = len(lines[start])-len(lines[start].lstrip())
    active = []
    for line in lines[start+1:]:
        if line.strip() and len(line)-len(line.lstrip()) <= indent:
            break
        active.append(line)
    section = "\n".join(active)
    blocks = re.split(r"(?=^\s*NotificationRecord\()", section, flags=re.M)
    return [b for b in blocks if re.search(r"pkg="+re.escape(PKG)+r"\b", b)
            and re.search(r"\bid="+str(NOTIFICATION_ID)+r"\b", b)]


def snapshot(label):
    commands = {"process": ("shell", "dumpsys", "activity", "processes"),
                "service": ("shell", "dumpsys", "activity", "services", PKG),
                "notification": ("shell", "dumpsys", "notification")}
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {key: pool.submit(qa.adb, *cmd) for key, cmd in commands.items()}
        raw = {key: future.result() for key, future in jobs.items()}
    match = re.search(r"^  \*APP\*[^\n]*"+re.escape(PKG)+r"[^\n]*\n.*?(?=^  \*APP\*|\Z)", raw["process"], re.M|re.S)
    own = match.group(0) if match else ""
    process_lines = [line for line in own.splitlines() if any(key in line for key in
                     ("*APP*", "pid=", "curProcState", "cached=", "isFrozen=", "Foreground", "foreground"))]
    (qa.OUT / (label+"-process.txt")).write_text("\n".join(process_lines)+"\n")
    (qa.OUT / (label+"-service.txt")).write_text(raw["service"])
    notifications = active_notifications(raw["notification"])
    (qa.OUT / (label+"-notification.txt")).write_text("\n".join(notifications)+"\n")
    service_present = "GuardForegroundService" in raw["service"] and "ServiceRecord{" in raw["service"]
    foreground = service_present and "isForeground=true" in raw["service"]
    # API34 ServiceRecord.dump prints types with Integer.toHexString (no 0x).
    mask_match = re.search(r"\bisForeground=true[^\n]*\btypes=([\da-fA-F]+)\b", raw["service"])
    mask = int(mask_match[1], 16) if mask_match else None
    if mask is None:
        mask_match = re.search(r"foregroundServiceType=(0x[\da-fA-F]+|\d+)", raw["service"])
        mask = int(mask_match[1], 0) if mask_match else None
    frozen_match = re.search(r"\bisFrozen=(true|false)", own)
    # pidof exits 1 for the expected absent process after force-stop.
    pid_result = subprocess.run([qa.ADB, "-s", qa.SERIAL, "shell", "pidof", PKG], capture_output=True, text=True, timeout=10)
    if pid_result.returncode not in (0, 1):
        raise RuntimeError("process identity query failed")
    row = qa.event("runtime_snapshot", label=label, pid=pid_result.stdout.strip(),
                   isFrozen=frozen_match[1] == "true" if frozen_match else None,
                   servicePresent=service_present, foregroundService=foreground, serviceTypeMask=mask,
                   foregroundIdCorrect=bool(re.search(r"foregroundId=7301\b", raw["service"])),
                   activeNotificationCount=len(notifications))
    qa.save(label+"-snapshot.json", row)
    return row


def require_active(row, location_expectation, require_notification=True):
    if row["isFrozen"] is not False or not row["foregroundService"] or not row["foregroundIdCorrect"]:
        raise RuntimeError("active guard lacks unfrozen foreground service")
    mask = row["serviceTypeMask"]
    if mask is None or not mask & SPECIAL_USE:
        raise RuntimeError("specialUse service type not observed")
    if location_expectation != "record" and bool(mask & LOCATION) != (location_expectation == "on"):
        raise RuntimeError("location foreground service type differs from expectation")
    if require_notification and row["activeNotificationCount"] != 1:
        raise RuntimeError("expected one active guard notification")


def wait_active(label, location_expectation, started_host_ns):
    # Android may defer the initial FGS notification. Require real posting within
    # 18 seconds of this same UI start; keep every sample, never infer posting.
    deadline = time.monotonic()+max(0, 18-(time.time_ns()-started_host_ns)/1e9)
    attempt = 0
    while True:
        row = snapshot(label+"_"+str(attempt))
        require_active(row, location_expectation, require_notification=False)
        if row["activeNotificationCount"] or time.monotonic() >= deadline:
            require_active(row, location_expectation)
            return row
        time.sleep(min(1, max(0, deadline-time.monotonic())))
        attempt += 1


def require_stopped(row):
    if row["servicePresent"] or row["activeNotificationCount"]:
        raise RuntimeError("guard service/notification remains after stop")


def navigate():
    qa.event("native_open_start")
    qa.adb("shell", "am", "start", "-W", "-n", NAVI+"/.MainActivity")
    tree, node = wait_ui("navi_home", "어디로 갈까요", NAVI)
    tap(node, "native_search_open")
    wait_ui("navi_search", "어디로 갈까요", NAVI)
    qa.adb("shell", "input", "text", "Seoul")
    qa.adb("shell", "input", "keyevent", "66")
    tree, node = wait_ui("navi_results", "더현대서울", NAVI)
    tap(node, "native_public_destination")
    tree, node = wait_ui("navi_detail", "목적지로", NAVI)
    request = qa.event("native_route_request")
    tap(node, "native_destination_tap")
    tree, node = wait_ui("navi_route", "안내 종료", NAVI)
    qa.ui("native-route", True)
    active = qa.event("native_route_active", deviceElapsedMs=qa.elapsed())
    return request, active


def end_native_route():
    tree = qa.ui("native_end_check")
    node = find(tree, "안내 종료", NAVI)
    if node is None:
        raise RuntimeError("active native route end control unavailable")
    before = qa.event("native_route_end_request")
    tap(node, "native_route_end_tap")
    wait_ui("native_ended", "어디로 갈까요", NAVI)
    qa.event("native_route_ended", deviceElapsedMs=qa.elapsed())
    return before


def remove_task():
    qa.adb("shell", "input", "keyevent", "KEYCODE_APP_SWITCH")
    tree = qa.ui("system-recents", True)
    candidates = [n for n in tree.iter("node") if n.get("enabled") == "true"
                  and n.get("clickable") == "true" and "VIP 조수석" in n.get("content-desc", "")
                  and any(word in n.get("content-desc", "").lower() for word in ("dismiss", "닫기", "제거"))]
    if len(candidates) != 1:
        raise RuntimeError("VIP-only task-dismiss control unavailable; no guessed swipe or force-stop substitute")
    marker = qa.event("task_removal_request")
    tap(candidates[0], "owned_task_dismiss")
    return marker


def cabin_values(state):
    fields = {"hvac": ("power_on", "auto_on", "ac_on", "dual_on", "sync_on", "recirc_on", "fan_speed",
                       "auto_blower_level", "driver", "passenger", "row2", "front_defroster_on", "rear_defroster_on"),
              "seat": ("positions", "moves", "occupied"), "body": ("doors", "door_locks", "windows", "closures"),
              "controls": ("speed_kph", "accelerator_pct", "brake_pct", "steering_deg")}
    return {domain: {key: state[domain][key] for key in keys} for domain, keys in fields.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", required=True)
    parser.add_argument("--case", choices=("navigation", "force-stop", "task-removal"), required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--location-type", choices=("record", "on", "off"), default="record")
    args = parser.parse_args()
    if not re.fullmatch(r"runtime-[a-z0-9-]+", args.out) or not re.fullmatch(r"[a-fA-F0-9]{64}", args.expected_apk_sha256):
        parser.error("new runtime-* folder and full APK SHA256 are required")
    qa.OUT = BASE / args.out
    qa.OUT.mkdir(exist_ok=False)
    (qa.OUT/"harness-source.py").write_text(Path(__file__).read_text())
    own_forward = mutated = route = False
    flags, originals, before = None, {}, None
    process = thread = None
    errors, samples = [], []
    evidence = {"case": args.case, "scope": "Official AVD runtime; no physical sensor accuracy or audio measurement."}
    try:
        forwards = qa.adb("forward", "--list")
        occupying = [line for line in forwards.splitlines() if " tcp:8088 " in line]
        if occupying and occupying != [qa.SERIAL+" tcp:8088 tcp:8088"]:
            raise RuntimeError("8088 forward already owned by another endpoint")
        if not occupying:
            qa.adb("forward", "tcp:8088", "tcp:8088"); own_forward = True
        before = qa.api("/vehicle/state"); qa.save("vehicle-before.json", before); qa.check_park(before)
        if any(before["controls"][key] != 0 for key in ("accelerator_pct", "brake_pct", "steering_deg")):
            raise RuntimeError("requires existing zero controls")
        if qa.adb("shell", "getprop", "ro.product.name").strip() != "crp_avd_arm64" or qa.adb("shell", "getprop", "ro.build.version.sdk").strip() != "34":
            raise RuntimeError("official API34 AVD required")
        paths = qa.adb("shell", "pm", "path", PKG).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("expected one installed APK")
        digest = hashlib.sha256(qa.adb("exec-out", "cat", paths[0][8:], binary=True)).hexdigest()
        if digest != args.expected_apk_sha256.lower():
            raise RuntimeError("installed APK differs from approved candidate")
        surface = qa.surface("initial_activity")
        if surface["vipActivityState"] != "RESUMED" or surface["vipVisible"] is not True:
            raise RuntimeError("VIP must already be foreground")
        idle_tree = qa.ui("initial-idle", True)
        if find(idle_tree, "프리셋 고르기", PKG) is None:
            raise RuntimeError("initial idle UI not observed")
        require_stopped(snapshot("initial_idle"))
        mutated = True
        qa.demo("settings", "on")
        flags = {key: row["checked"] for key, row in qa.switches(qa.ui("settings-original")).items()}
        qa.save("settings-original.json", flags)
        qa.configure({"tts": False, "chime": False}, "settings-muted")
        for tag in TAGS:
            originals[tag] = qa.adb("shell", "getprop", "log.tag."+tag).strip()
            qa.adb("shell", "setprop", "log.tag."+tag, "DEBUG")
        calibration = clock_sync()
        evidence.update(installedApkSha256=digest, originalPid=surface["pid"], clockCalibration=calibration,
                        originalLogProperties=originals, locationTypeExpectation=args.location_type)
        qa.save("environment.json", evidence)
        process, thread = start_logcat()
        tree = qa.ui("before-coffee-tap")
        coffee = find(tree, "커피 0.12G", PKG)
        if coffee is None:
            raise RuntimeError("observed COFFEE chip unavailable; no coordinate guess")
        # The only motion DEMO preparation, before UI selection and all background windows.
        qa.event("park_preparation_start")
        qa.adb("shell", "am", "broadcast", "-n", PKG+"/.demo.DemoBroadcastReceiver", "-a", PKG+".DEMO",
               "--es", "cmd", "gear", "--es", "value", "P")
        qa.demo("speed", 0, phase="single_park_preparation")
        qa.event("park_preparation_done")
        started = qa.event("guard_ui_start")
        tap(coffee, "coffee_chip_tap")
        ready = wait_runtime("ready", started["hostUnixNs"])
        if not ready["data"].get("guarding") or not ready["data"].get("visible") or ready["data"].get("startedMs") is None or ready["data"].get("requestId") is None:
            raise RuntimeError("guard was not started while UI visible")
        evidence["session"] = {key: ready["data"].get(key) for key in ("requestId", "startedMs")}
        qa.ui("coffee-active", True)
        active = wait_active("guard_foreground", args.location_type, started["hostUnixNs"]); samples.append(active)
        if "캐빈 적용: 0개 성공, 0개 미적용" not in (qa.OUT/"live.log").read_text():
            raise RuntimeError("COFFEE zero-cabin result missing")
        if args.case == "navigation":
            route = True
            route_request, route_active = navigate()
            background = qa.surface("guard_background_activity")
            if background["vipActivityState"] != "STOPPED" or background["vipVisible"] is not False:
                raise RuntimeError("VIP did not stop behind native navigation")
            anchor = time.monotonic()
            # No DEMO broadcast, app-targeted IPC, motion setter, or VIP UI action here.
            for delay in (0, 8, 17.2):
                time.sleep(max(0, anchor+delay-time.monotonic()))
                sample = snapshot("background_"+str(delay).replace(".", "_")); samples.append(sample)
                require_active(sample, args.location_type)
            time.sleep(max(0, anchor+18-time.monotonic()))
            route_end = end_native_route(); route = False
            time.sleep(3)
            returning = qa.event("vip_return_start")
            qa.adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
            qa.surface("vip_returned_activity"); qa.ui("vip-returned", True)
            tick = wait_runtime("tick", returning["hostUnixNs"])
            if any(tick["data"].get(key) != value for key, value in evidence["session"].items()):
                raise RuntimeError("session identity changed after foreground return")
            qa.save("navigation-window.json", {"request": route_request, "active": route_active,
                    "background": background, "endRequest": route_end, "returnStart": returning})
            evidence["navigationWindow"] = {"startHostNs": route_request["hostUnixNs"],
                                            "endRequestHostNs": route_end["hostUnixNs"],
                                            "returnHostNs": returning["hostUnixNs"],
                                            "backgroundStartHostNs": background["hostUnixNs"]}
            tree, node = wait_ui("end-guard", "내렸어요 (종료)")
            ended = qa.event("guard_end_request"); tap(node, "guard_end_ui_tap")
            wait_runtime("finished", ended["hostUnixNs"])
            stopped = snapshot("after_guard_end")
            for attempt in range(5):
                if not stopped["servicePresent"] and not stopped["activeNotificationCount"]:
                    break
                time.sleep(.3)
                stopped = snapshot("after_guard_end_"+str(attempt))
            require_stopped(stopped)
        else:
            if args.case == "force-stop":
                interrupted = qa.event("force_stop_request")
                qa.adb("shell", "am", "force-stop", PKG)
            else:
                interrupted = remove_task()
            evidence["interruptedHostNs"] = interrupted["hostUnixNs"]
            anchor = time.monotonic()
            for delay in (1, 8, 18):
                time.sleep(max(0, anchor+delay-time.monotonic()))
                sample = snapshot("interrupted_"+str(delay)); samples.append(sample); require_stopped(sample)
                if args.case == "force-stop" and sample["pid"]:
                    raise RuntimeError("app process restarted without user launch")
            relaunch = qa.event("manual_relaunch_request")
            evidence["manualRelaunchHostNs"] = relaunch["hostUnixNs"]
            qa.adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
            time.sleep(3)
            tree, _ = wait_ui("relaunch-idle", "프리셋 고르기")
            qa.ui("relaunch-idle", True); qa.surface("relaunch_activity")
            require_stopped(snapshot("relaunch_no_guard"))
        qa.event("case_complete")
    except BaseException as exc:
        errors.append(str(exc)); qa.event("case_error", error=str(exc))
    finally:
        execution_errors = list(errors)
        if mutated:
            try:
                if route:
                    qa.adb("shell", "am", "start", "-W", "-n", NAVI+"/.MainActivity")
                    tree = qa.ui("cleanup-native-route")
                    node = find(tree, "안내 종료", NAVI)
                    if node is not None:
                        tap(node, "cleanup_native_end"); wait_ui("cleanup-route-off", "어디로 갈까요", NAVI)
                qa.adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
                # UI end on recovery is explicit; its timestamp remains outside case windows.
                tree = qa.ui("cleanup-current")
                if find(tree, "프리셋 고르기", PKG) is None:
                    node = find(tree, "내렸어요 (종료)", PKG)
                    if node is not None:
                        tap(node, "cleanup_guard_end")
                        time.sleep(2)
                if flags is not None:
                    qa.configure(flags, "settings-restored")
                tree, _ = wait_ui("cleanup-idle", "프리셋 고르기")
                qa.ui("cleanup-idle", True); require_stopped(snapshot("cleanup_idle"))
            except BaseException as exc:
                errors.append("cleanup UI/service: "+str(exc))
        for tag, value in originals.items():
            try:
                qa.adb("shell", "setprop log.tag."+tag+" "+shlex.quote(value))
                if qa.adb("shell", "getprop", "log.tag."+tag).strip() != value:
                    raise RuntimeError("property readback differs")
            except BaseException as exc:
                errors.append("restore "+tag+": "+str(exc))
        if process:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
            thread.join(timeout=5)
        if before is not None:
            try:
                after = qa.api("/vehicle/state"); qa.save("vehicle-after.json", after); qa.check_park(after)
                if cabin_values(after) != cabin_values(before):
                    raise RuntimeError("observable vehicle values changed")
            except BaseException as exc:
                errors.append("vehicle readback: "+str(exc))
        if own_forward:
            try:
                qa.adb("forward", "--remove", "tcp:8088")
            except BaseException as exc:
                errors.append("forward cleanup: "+str(exc))
        qa.save("cleanup.json", {"verified": errors == execution_errors,
                                  "executionErrors": execution_errors, "cleanupErrors": errors[len(execution_errors):],
                                  "ownedForwardRemoved": own_forward})

    # Analyze only case-window rows captured live, not a post-return logcat dump.
    details = {"runtimeSnapshots": samples, "liveRows": len(LIVE), "issues": list(errors)}
    if args.case == "navigation" and "navigationWindow" in evidence:
        window = evidence["navigationWindow"]
        offset = evidence["clockCalibration"]["deviceMinusHostNs"]
        uncertainty = evidence["clockCalibration"]["uncertaintyNs"]
        def in_window(row, start, end):
            stamp = row.get("deviceEpochSeconds")
            return (stamp is not None and start <= row["hostReceivedUnixNs"] < end
                    and start+offset-uncertainty <= stamp*1e9 < end+offset+uncertainty)
        if any(row["pid"] != evidence["originalPid"] for row in samples):
            details["issues"].append("process identity changed during active session")
        nav = [r for r in LIVE if r.get("tag") == "PleosNavi" and in_window(r, window["startHostNs"], window["returnHostNs"])]
        live_drive = [r for r in nav if "navigation-status=ROUTE_DRIVE" in r["message"]]
        live_tbt = [r for r in nav if "SDK callback tbt" in r["message"]]
        live_free = [r for r in nav if "navigation-status=FREE_DRIVE" in r["message"] and in_window(r, window["endRequestHostNs"], window["returnHostNs"])]
        ticks = [r for r in runtime_rows("tick") if in_window(r, window["backgroundStartHostNs"], window["endRequestHostNs"])]
        times = [r["data"]["elapsedMs"] for r in ticks]
        gaps = [b-a for a, b in zip(times, times[1:])]
        details.update(liveRouteDrive=len(live_drive), liveTbt=len(live_tbt), liveFreeDrive=len(live_free),
                       backgroundTicks=len(ticks), maximumTickGapMs=max(gaps, default=None))
        qa.save("live-navigation-callbacks.json", nav)
        qa.save("background-ticks.json", ticks)
        if not live_drive or not live_tbt or not live_free:
            details["issues"].append("native callbacks did not arrive live inside pre-return case windows")
        if len(ticks) < 15 or (gaps and max(gaps) > 2000) or any(r["data"].get("visible") is not False or r["data"].get("guarding") is not True for r in ticks):
            details["issues"].append("background active tick continuity failed")
        if any(any(r["data"].get(key) != value for key, value in evidence["session"].items()) for r in ticks):
            details["issues"].append("runtime session identity changed in background")
        if any(r.get("tag") == "DemoBridge" and window["backgroundStartHostNs"] <= r["hostReceivedUnixNs"] < window["endRequestHostNs"] for r in LIVE):
            details["issues"].append("DEMO broadcast contaminated no-wake background interval")
    elif "interruptedHostNs" in evidence:
        after = [r for r in LIVE if r["hostReceivedUnixNs"] >= evidence["interruptedHostNs"]]
        renewed = [r for r in after if r.get("tag") == "GuardRuntime" and r.get("data", {}).get("event") in ("ready", "tick")]
        guarded = [r for r in after if r.get("tag") == "MotionTrace" and r.get("data", {}).get("state") == "GUARDING"]
        writes = [r for r in after if r.get("tag") in ("CargoVM", "PleosVehicle") and
                  (re.search(r"\b(apply|restore) \w+: APPLIED\b", r.get("message", "")) or "캐빈 적용 →" in r.get("message", ""))]
        details.update(renewedRuntimeRows=len(renewed), postInterruptionGuardRows=len(guarded), observedWriteRows=len(writes))
        if renewed or guarded or writes:
            details["issues"].append("unexpected guard restart or observed cabin operation after disruption")
    details["passed"] = not details["issues"] and any(e["event"] == "case_complete" for e in
        (json.loads(line) for line in (qa.OUT/"input-ledger.jsonl").read_text().splitlines()))
    details["limits"] = ["Stationary official AVD and public synthetic destination; no route progression, driving or audio claim.",
                          "No motion DEMO after one parked preparation; real source freshness and physical accuracy are separate.",
                          "No logged cabin write outcomes plus unchanged readback is not a vehicle-bus capture.",
                          "Task-removal case blocks if a unique VIP dismiss control cannot be observed."]
    qa.save("environment.json", evidence); qa.save("result.json", details)
    print(json.dumps({"out": str(qa.OUT), "passed": details["passed"], "issues": details["issues"]}, ensure_ascii=False))
    return 0 if details["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
