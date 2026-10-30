#!/usr/bin/env python3
"""Bounded official-AVD START_FOREGROUND ignore/deny probes; no action on import.

  python3 fgs_failure_probe.py --run --out fgs-failure-01 --expected-apk-sha256 SHA256

Only the current user's package-scoped START_FOREGROUND mode is changed and
restored. Never resets appops, grants permissions, changes API/device policy,
clears app data, restarts the app, writes HVAC, or records microphone audio.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import known_answer_probe as qa
import runtime_avd_probe as runtime

BASE = Path(__file__).resolve().parent
OP = "START_FOREGROUND"
FAILURE_TEXT = "가드 실행을 시작하지 못했어요"


def read_mode(user):
    raw = qa.adb("shell", "cmd", "appops", "get", "--user", user, qa.PKG, OP)
    if "Uid mode:" in raw:
        raise RuntimeError("UID override requires separate review; package-scoped probe will not alter it")
    match = re.search(r"^\s*START_FOREGROUND:\s*(allow|ignore|deny|default)\b", raw, re.M)
    if match:
        return match[1], raw
    if "No operations" in raw:
        return "default", raw
    raise RuntimeError("cannot determine original START_FOREGROUND mode")


def set_mode(user, mode):
    if mode not in {"allow", "ignore", "deny", "default"}:
        raise ValueError("unsupported appop mode")
    qa.adb("shell", "cmd", "appops", "set", "--user", user, qa.PKG, OP, mode)
    actual, raw = read_mode(user)
    if actual != mode:
        raise RuntimeError("START_FOREGROUND readback mismatch")
    qa.event("appop_mode", user=user, operation=OP, mode=mode)
    return raw


def stop_logs(process, thread):
    if process:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=5)
        thread.join(timeout=5)


def system_logs():
    rows = []
    process = subprocess.Popen([qa.ADB, "-s", qa.SERIAL, "logcat", "-v", "epoch", "-T", "1",
                                "ActivityManager:V", "ActiveServices:V", "AppOps:V", "AndroidRuntime:E", "*:S"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    path = qa.OUT
    def read():
        with (path/"system-filtered.log").open("w") as log, (path/"system-filtered.jsonl").open("w") as ledger:
            for line in process.stdout:
                if qa.PKG not in line and "GuardForegroundService" not in line:
                    continue
                row = {"hostReceivedUnixNs": time.time_ns(), "line": line.rstrip()}
                rows.append(row); log.write(line); log.flush()
                ledger.write(json.dumps(row)+"\n"); ledger.flush()
    thread = threading.Thread(target=read, daemon=True); thread.start()
    return process, thread, rows


def parked_preparation():
    qa.adb("shell", "am", "broadcast", "-n", qa.PKG+"/.demo.DemoBroadcastReceiver", "-a", qa.PKG+".DEMO",
           "--es", "cmd", "gear", "--es", "value", "P")
    qa.demo("speed", 0, phase="park_preparation")


def recovery():
    tree = qa.ui("recovery-before")
    coffee = runtime.find(tree, "커피 0.12G", qa.PKG)
    if coffee is None:
        raise RuntimeError("recovery COFFEE chip unavailable")
    parked_preparation()
    start = qa.event("recovery_normal_start")
    runtime.tap(coffee, "recovery_coffee_tap")
    ready = runtime.wait_runtime("ready", start["hostUnixNs"])
    if not ready["data"].get("guarding") or not ready["data"].get("visible"):
        raise RuntimeError("normal recovery did not start visible guard")
    active = runtime.wait_active("recovery-active", "record", start["hostUnixNs"])
    tree, node = runtime.wait_ui("recovery-end", "내렸어요 (종료)")
    end = qa.event("recovery_end_request"); runtime.tap(node, "recovery_end_tap")
    runtime.wait_runtime("finished", end["hostUnixNs"])
    time.sleep(.5)
    stopped = runtime.snapshot("recovery-stopped"); runtime.require_stopped(stopped)
    runtime.wait_ui("recovery-idle", "프리셋 고르기")
    qa.ui("recovery-idle", True)
    return {"passed": True, "ready": ready["data"], "active": active, "stopped": stopped}


def fault_case(folder, mode, user, original_mode):
    qa.OUT = folder; folder.mkdir(exist_ok=False); runtime.LIVE.clear()
    process = thread = sys_process = sys_thread = None
    systems, errors = [], []
    start = end = None
    ui_failed = False
    evidence = {"mode": mode}
    try:
        runtime.require_stopped(runtime.snapshot("before"))
        tree = qa.ui("before-request", True)
        if runtime.find(tree, "프리셋 고르기", qa.PKG) is None:
            raise RuntimeError("visible idle UI required before fault request")
        coffee = runtime.find(tree, "커피 0.12G", qa.PKG)
        if coffee is None:
            raise RuntimeError("COFFEE chip not observed")
        surface = qa.surface("visible_idle")
        if surface["vipActivityState"] != "RESUMED" or surface["vipVisible"] is not True:
            raise RuntimeError("app not visible")
        process, thread = runtime.start_logcat()
        sys_process, sys_thread, systems = system_logs()
        (folder/"injected-appop.txt").write_text(set_mode(user, mode))
        parked_preparation()
        start = qa.event("fault_preset_request", mode=mode)
        runtime.tap(coffee, "coffee_ui_tap")
        deadline = time.monotonic()+10
        while time.monotonic() < deadline:
            tree = qa.ui("failure-ui")
            if runtime.find(tree, FAILURE_TEXT, qa.PKG) is not None:
                ui_failed = True
                break
            time.sleep(.5)
        time.sleep(max(0, 8-(time.time_ns()-start["hostUnixNs"])/1e9))
        qa.ui("failure-ui", True)
        state = runtime.snapshot("after-failure"); evidence["afterFailure"] = state
        runtime.require_stopped(state)
        if not ui_failed:
            raise RuntimeError("explicit guard failure notice absent")
        tree = qa.ui("idle-after-failure")
        if runtime.find(tree, "프리셋 고르기", qa.PKG) is None:
            raise RuntimeError("idle UI absent after failed start")
        end = qa.event("fault_observation_end")
    except BaseException as exc:
        errors.append(str(exc)); qa.event("fault_case_error", error=str(exc))
    finally:
        if end is None:
            end = qa.event("fault_observation_end")
        try:
            (folder/"restored-appop.txt").write_text(set_mode(user, original_mode))
            if not errors:
                evidence["recovery"] = recovery()
        except BaseException as exc:
            errors.append("appop restoration/recovery: "+str(exc))
        stop_logs(process, thread); stop_logs(sys_process, sys_thread)
    rows = [row for row in runtime.LIVE if start and start["hostUnixNs"] <= row["hostReceivedUnixNs"] < end["hostUnixNs"]]
    system_window = [r for r in systems if start and start["hostUnixNs"] <= r["hostReceivedUnixNs"] < end["hostUnixNs"]]
    ready = [r for r in rows if r.get("tag") == "GuardRuntime" and r.get("data", {}).get("event") == "ready"]
    motions = [r for r in rows if r.get("tag") == "MotionTrace" and "data" in r]
    guarded = [r for r in motions if r["data"].get("state") == "GUARDING"]
    applies = [r for r in rows if r.get("tag") in {"CargoVM", "PleosVehicle"} and
               (("캐빈 적용" in r.get("message", "") and "새 캐빈 적용 없음" not in r.get("message", ""))
                or re.search(r"\b(apply|restore) \w+:", r.get("message", "")) or "원복 " in r.get("message", ""))]
    failures = [r for r in rows if "foreground promotion failed:" in r.get("message", "")]
    failed_requests = [r for r in rows if r.get("tag") == "CargoVM" and "가드 시작 실패: 요청" in r.get("message", "")]
    silent = [r for r in system_window if "Service.startForeground() not allowed due to app op" in r["line"]]
    downgrade = [r for r in system_window if "startForegroundService not allowed due to app op" in r["line"]]
    if ready or guarded or applies:
        errors.append("ready/guard/cabin operation observed despite denied foreground promotion")
    if not motions or not failed_requests:
        errors.append("missing motion/failure-request trace coverage")
    if mode == "ignore" and not silent:
        errors.append("silent-ignore promotion branch not observed; case is unexecuted for that claim")
    failure_latency = (failed_requests[0]["hostReceivedUnixNs"]-start["hostUnixNs"])/1e9 if failed_requests else None
    if mode == "deny" and (failure_latency is None or failure_latency >= 2):
        errors.append("deny immediate start-call rejection not observed separately from 5-second timeout")
    evidence.update(passed=not errors, issues=errors, readyCount=len(ready), guardTraceCount=len(guarded),
                    observedMotionTraces=len(motions), cabinOperationLogCount=len(applies), failureNotice=ui_failed,
                    promotionFailureMessages=[r["message"] for r in failures], failedRequestCount=len(failed_requests),
                    failureRequestLatencySeconds=failure_latency, silentIgnorePromotionLogs=len(silent),
                    ordinaryStartDowngradeLogs=len(downgrade), systemCaseRows=system_window)
    qa.save("result.json", evidence)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"fgs-failure-[a-z0-9-]+", args.out) or not re.fullmatch(r"[a-fA-F0-9]{64}", args.expected_apk_sha256):
        parser.error("new fgs-failure-* folder and full installed APK SHA256 required")
    out = BASE/args.out
    out.mkdir(exist_ok=False); qa.OUT = out
    (out/"harness-source.py").write_text(Path(__file__).read_text())
    (out/"runtime-helper-source.py").write_text(Path(runtime.__file__).read_text())
    own_forward = False
    before = flags = original_mode = user = uid = uid_raw = None
    original_properties = {}
    cases, errors = [], []
    try:
        occupying = [line for line in qa.adb("forward", "--list").splitlines() if " tcp:8088 " in line]
        if occupying and occupying != [qa.SERIAL+" tcp:8088 tcp:8088"]:
            raise RuntimeError("8088 is owned by another endpoint")
        if not occupying:
            qa.adb("forward", "tcp:8088", "tcp:8088"); own_forward = True
        before = qa.api("/vehicle/state"); qa.save("vehicle-before.json", before); qa.check_park(before)
        if any(before["controls"][key] != 0 for key in ("accelerator_pct", "brake_pct", "steering_deg")):
            raise RuntimeError("existing P0 and zero controls required")
        if qa.adb("shell", "getprop", "ro.product.name").strip() != "crp_avd_arm64" or qa.adb("shell", "getprop", "ro.build.version.sdk").strip() != "34":
            raise RuntimeError("official API34 AVD required")
        paths = qa.adb("shell", "pm", "path", qa.PKG).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("expected one installed base APK")
        digest = hashlib.sha256(qa.adb("exec-out", "cat", paths[0][8:], binary=True)).hexdigest()
        if digest != args.expected_apk_sha256.lower():
            raise RuntimeError("installed APK hash differs")
        user = qa.adb("shell", "am", "get-current-user").strip()
        if not user.isdecimal():
            raise RuntimeError("current Android user unavailable")
        packages = qa.adb("shell", "cmd", "package", "list", "packages", "--user", user, "-U", qa.PKG)
        uid_match = re.search(r"uid:(\d+)", packages)
        if not uid_match:
            raise RuntimeError("package UID baseline unavailable")
        uid = uid_match[1]
        uid_raw = qa.adb("shell", "cmd", "appops", "get", uid, OP)
        (out/"original-uid-appop.txt").write_text(uid_raw)
        raw = qa.adb("shell", "cmd", "appops", "get", "--user", user, qa.PKG, OP)
        (out/"original-appop.txt").write_text(raw)
        original_mode, raw = read_mode(user)
        qa.save("environment.json", {"installedApkSha256": digest, "user": user,
                "operation": OP, "originalMode": original_mode, "packageUid": uid, "uidOverrideRaw": uid_raw, "device": qa.SERIAL, "sdk": 34})
        surface = qa.surface("initial_visible")
        if surface["vipActivityState"] != "RESUMED" or surface["vipVisible"] is not True:
            raise RuntimeError("visible app required")
        runtime.require_stopped(runtime.snapshot("initial-idle"))
        if runtime.find(qa.ui("initial-idle", True), "프리셋 고르기", qa.PKG) is None:
            raise RuntimeError("idle UI required")
        qa.demo("settings", "on")
        flags = {key: row["checked"] for key, row in qa.switches(qa.ui("settings-original")).items()}
        qa.save("settings-original.json", flags)
        qa.configure({"tts": False, "chime": False}, "settings-muted")
        for tag in runtime.TAGS:
            original_properties[tag] = qa.adb("shell", "getprop", "log.tag."+tag).strip()
            qa.adb("shell", "setprop", "log.tag."+tag, "DEBUG")
        qa.save("original-log-properties.json", original_properties)
        for mode in ("ignore", "deny"):
            result = fault_case(out/mode, mode, user, original_mode)
            cases.append(result)
            if not result["passed"]:
                raise RuntimeError(mode+" fault case failed; no next case")
    except BaseException as exc:
        errors.append(str(exc))
    finally:
        qa.OUT = out
        execution_errors = list(errors)
        if original_mode is not None:
            try:
                (out/"finally-restored-appop.txt").write_text(set_mode(user, original_mode))
            except BaseException as exc:
                errors.append("finally appop restore: "+str(exc))
        if uid is not None:
            try:
                uid_after = qa.adb("shell", "cmd", "appops", "get", uid, OP)
                (out/"finally-uid-appop.txt").write_text(uid_after)
                if uid_after != uid_raw:
                    raise RuntimeError("UID app-op baseline differs")
            except BaseException as exc:
                errors.append("UID appop readback: "+str(exc))
        if flags is not None:
            try:
                tree = qa.ui("cleanup-current")
                node = runtime.find(tree, "내렸어요 (종료)", qa.PKG)
                if node is not None:
                    runtime.tap(node, "cleanup_end"); time.sleep(1)
                qa.configure(flags, "settings-restored")
                tree, _ = runtime.wait_ui("cleanup-idle", "프리셋 고르기")
                qa.ui("cleanup-idle", True)
                runtime.require_stopped(runtime.snapshot("cleanup-idle"))
            except BaseException as exc:
                errors.append("cleanup UI/service: "+str(exc))
        import shlex
        for tag, value in original_properties.items():
            try:
                qa.adb("shell", "setprop log.tag."+tag+" "+shlex.quote(value))
                if qa.adb("shell", "getprop", "log.tag."+tag).strip() != value:
                    raise RuntimeError("property readback differs")
            except BaseException as exc:
                errors.append("restore "+tag+": "+str(exc))
        if before is not None:
            try:
                after = qa.api("/vehicle/state"); qa.save("vehicle-after.json", after); qa.check_park(after)
                if runtime.cabin_values(before) != runtime.cabin_values(after):
                    raise RuntimeError("vehicle values differ")
            except BaseException as exc:
                errors.append("vehicle cleanup: "+str(exc))
        if own_forward:
            try:
                qa.adb("forward", "--remove", "tcp:8088")
            except BaseException as exc:
                errors.append("forward cleanup: "+str(exc))
        qa.save("cleanup.json", {"verified": errors == execution_errors, "executionErrors": execution_errors,
                                  "cleanupErrors": errors[len(execution_errors):], "restoredMode": original_mode,
                                  "restoredProperties": original_properties})
    result = {"passed": not errors and len(cases)==2 and all(case["passed"] for case in cases),
              "cases": cases, "issues": errors,
              "scope": "Actual Android14 package app-op ignore/deny; no forced API or device-policy simulation.",
              "limits": "API28 and API29-33 zero-type fallback untested. Unchanged readback plus absent observed cabin effects is not a bus-level no-write measurement."}
    qa.save("result.json", result)
    print(json.dumps({"out":str(out),"passed":result["passed"],"issues":errors},ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
