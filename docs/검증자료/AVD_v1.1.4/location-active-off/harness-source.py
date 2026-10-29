#!/usr/bin/env python3
"""Prepared only. --run required; one newly named output folder per case.

No execution on import. Requires exclusive AVD ownership and approved installed APK.
Permission/app-op modes are observed, never changed. permission-denied requires an
already denied standard-location fixture; no destructive revoke or dialog guessing.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import known_answer_probe as qa
import runtime_avd_probe as rt

BASE = Path(__file__).resolve().parent
EXPECTED = "6d886a66a3170eda552dcbdb5b0951474134e94a3db78aad3f0923a30770947d"
PERMISSIONS = ("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
               "pleos.car.permission.FUSED_LOCATION")
ROWS = []
USER = None


def access_state():
    package = qa.adb("shell", "dumpsys", "package", qa.PKG)
    lines = package.splitlines()
    start = next((i for i, line in enumerate(lines) if re.match(r"^\s*User " + re.escape(USER) + r":", line)), None)
    if start is None:
        raise RuntimeError("current Android user block unavailable")
    indent = len(lines[start])-len(lines[start].lstrip())
    block = []
    for line in lines[start+1:]:
        if line.strip() and len(line)-len(line.lstrip()) <= indent:
            break
        block.append(line)
    user_package = "\n".join(block)
    permissions = {}
    for permission in PERMISSIONS:
        matches = re.findall(r"^\s*"+re.escape(permission)+r": granted=(true|false)(?:, flags=\[([^\]]*)\])?\s*$", user_package, re.M)
        if len(matches) != 1:
            raise RuntimeError("permission state is not uniquely readable: "+permission)
        permissions[permission] = {"granted": matches[0][0] == "true", "flags": matches[0][1]}
    ops = {}
    for op in ("COARSE_LOCATION", "FINE_LOCATION"):
        raw = qa.adb("shell", "cmd", "appops", "get", "--user", USER, qa.PKG, op)
        match = re.search(re.escape(op)+r": (allow|foreground|ignore|deny|default|errored)\b", raw)
        if not match and "No operations" not in raw:
            raise RuntimeError("unrecognized app-op layout: "+op)
        ops[op] = match[1] if match else "default"
    return {"permissions": permissions, "appOps": ops}


def location_enabled():
    value = qa.adb("shell", "cmd", "location", "is-location-enabled", "--user", USER).strip()
    if value not in ("true", "false"):
        raise RuntimeError("system location state unavailable")
    return value == "true"


def set_location(value):
    qa.event("system_location_request", enabled=value)
    qa.adb("shell", "cmd", "location", "set-location-enabled", "true" if value else "false", "--user", USER)
    if location_enabled() != value:
        raise RuntimeError("system location readback differs")
    return qa.event("system_location_confirmed", enabled=value)


def capture():
    tags = ("GuardRuntime", "MotionTrace", "PleosLocationSpeed", "AospCarVehicle", "PleosNavi", "CargoVM")
    process = subprocess.Popen([qa.ADB, "-s", qa.SERIAL, "logcat", "-v", "epoch", "-T", "1",
                                *[tag+":V" for tag in tags], "*:S"], stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, bufsize=1)
    def read():
        with (qa.OUT/"observations.jsonl").open("w") as handle:
            for line in process.stdout:
                match = re.match(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+):\s?(.*)", line.rstrip())
                if not match:
                    continue
                tag, message = match[2].strip(), match[3]
                # Strict allowlist; no raw SDK payload, user text, GPS fix or coordinates saved.
                row = {"hostNs": time.time_ns(), "deviceEpochSeconds": float(match[1]), "tag": tag}
                if tag in ("GuardRuntime", "MotionTrace") and message.startswith("{"):
                    try:
                        row["data"] = json.loads(message)
                    except ValueError:
                        continue
                elif (tag == "PleosLocationSpeed" and message in ("location speed subscription active", "location speed subscription stopped")
                      or tag == "AospCarVehicle" and message.startswith("subscriptions:")
                      or tag == "PleosNavi" and message == "NaviHelper ready"
                      or tag == "CargoVM" and (message.startswith("연결:") or message == "캐빈 적용: 0개 성공, 0개 미적용")):
                    row["message"] = message
                else:
                    continue
                ROWS.append(row)
                if tag == "GuardRuntime":
                    rt.LIVE.append({**row, "hostReceivedUnixNs": row["hostNs"]})
                handle.write(json.dumps(row, ensure_ascii=False)+"\n"); handle.flush()
    thread = threading.Thread(target=read, daemon=True); thread.start()
    return process, thread


def rows_after(start, tag=None, message=None):
    return [row for row in list(ROWS) if row["hostNs"] >= start and (tag is None or row["tag"] == tag)
            and (message is None or message in row.get("message", ""))]


def start_coffee(expect_location):
    tree = qa.ui("coffee-before")
    node = rt.find(tree, "커피 0.12G", qa.PKG)
    if node is None:
        raise RuntimeError("observed COFFEE chip unavailable")
    qa.adb("shell", "am", "broadcast", "-n", qa.PKG+"/.demo.DemoBroadcastReceiver", "-a", qa.PKG+".DEMO",
           "--es", "cmd", "gear", "--es", "value", "P")
    qa.demo("speed", 0, phase="setup-only")
    event = qa.event("coffee_start")
    rt.tap(node, "coffee_tap")
    ready = rt.wait_runtime("ready", event["hostUnixNs"])
    rt.wait_active("guard-start", "on" if expect_location else "off", event["hostUnixNs"])
    if not rows_after(event["hostUnixNs"], "CargoVM", "캐빈 적용: 0개 성공, 0개 미적용"):
        raise RuntimeError("COFFEE zero-cabin result missing")
    return event, ready["data"]


def main():
    global USER
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", required=True)
    parser.add_argument("--case", choices=("on", "off", "active-off", "permission-denied", "reconnect"), required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--expected-apk-sha256", default=EXPECTED)
    args = parser.parse_args()
    if not re.fullmatch(r"location-[a-z0-9-]+", args.out) or not re.fullmatch(r"[a-f0-9]{64}", args.expected_apk_sha256):
        parser.error("new location-* output folder and full lowercase APK SHA256 required")
    qa.OUT = BASE/args.out; qa.OUT.mkdir(exist_ok=False)
    (qa.OUT/"harness-source.py").write_bytes(Path(__file__).read_bytes())
    process = thread = None
    original_access = original_location = flags = before = None
    properties = {}; errors = []; evidence = {}; own_forward = False; changed = False
    try:
        USER = qa.adb("shell", "am", "get-current-user").strip()
        if not USER.isdigit():
            raise RuntimeError("current Android user unavailable")
        qa.save("current-user.json", {"androidUser": int(USER)})
        paths = qa.adb("shell", "pm", "path", qa.PKG).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("expected one APK")
        digest = hashlib.sha256(qa.adb("exec-out", "cat", paths[0][8:], binary=True)).hexdigest()
        if digest != args.expected_apk_sha256:
            raise RuntimeError("installed APK hash mismatch")
        if qa.adb("shell", "getprop", "ro.product.name").strip() != "crp_avd_arm64" or qa.adb("shell", "getprop", "ro.build.version.sdk").strip() != "34":
            raise RuntimeError("official API34 AVD required")
        forward = [line for line in qa.adb("forward", "--list").splitlines() if " tcp:8088 " in line]
        if forward and forward != [qa.SERIAL+" tcp:8088 tcp:8088"]:
            raise RuntimeError("Console forward already owned elsewhere")
        if not forward:
            qa.adb("forward", "tcp:8088", "tcp:8088"); own_forward = True
        before = qa.api("/vehicle/state"); qa.check_park(before)
        qa.save("vehicle-before.json", rt.cabin_values(before))
        if rt.find(qa.ui("initial-idle"), "프리셋 고르기", qa.PKG) is None:
            raise RuntimeError("requires foreground idle VIP")
        rt.require_stopped(rt.snapshot("initial-idle"))
        original_access = access_state(); original_location = location_enabled()
        qa.save("access-before.json", {**original_access, "locationEnabled": original_location})
        granted = original_access["permissions"]
        standard = any(granted[p]["granted"] for p in PERMISSIONS[:2])
        if args.case == "permission-denied":
            if standard:
                raise RuntimeError("PRECONDITION: both standard location permissions must already be denied; harness never revokes")
        elif not standard or not granted[PERMISSIONS[2]]["granted"]:
            raise RuntimeError("PRECONDITION: standard plus Pleos location permissions must already be allowed")
        changed = True
        qa.demo("settings", "on")
        flags = {key: value["checked"] for key, value in qa.switches(qa.ui("settings-original")).items()}
        qa.configure({"tts": False, "chime": False}, "settings-muted")
        for tag in rt.TAGS:
            properties[tag] = qa.adb("shell", "getprop", "log.tag."+tag).strip()
            qa.adb("shell", "setprop", "log.tag."+tag, "DEBUG")
        qa.save("log-properties-before.json", properties)
        process, thread = capture()
        set_location(args.case not in ("off", "reconnect")); time.sleep(3)
        if args.case == "reconnect":
            evidence["cycles"] = []
            for cycle in range(2):
                qa.adb("shell", "am", "start", "-W", "-n", rt.NAVI+"/.MainActivity")
                time.sleep(4)
                hidden = qa.surface("idle-hidden-"+str(cycle))
                if hidden["vipVisible"] is not False or hidden["vipActivityState"] != "STOPPED":
                    raise RuntimeError("VIP not hidden behind native navigation")
                rt.require_stopped(rt.snapshot("idle-hidden-"+str(cycle)))
                anchor = qa.event("idle-return", cycle=cycle)
                qa.adb("shell", "am", "start", "-W", "-n", qa.PKG+"/.MainActivity")
                deadline = time.monotonic()+35
                while time.monotonic() < deadline:
                    found = rows_after(anchor["hostUnixNs"])
                    if any(r["tag"] == "PleosNavi" for r in found) and any(r["tag"] == "CargoVM" and r.get("message", "").startswith("연결:") for r in found):
                        break
                    time.sleep(.5)
                found = rows_after(anchor["hostUnixNs"])
                evidence["cycles"].append({"cycle": cycle, "newAospSubscription": any(r["tag"] == "AospCarVehicle" for r in found),
                    "newNaviReady": any(r["tag"] == "PleosNavi" for r in found),
                    "newCombinedConnection": any(r["tag"] == "CargoVM" and r.get("message", "").startswith("연결:") for r in found),
                    "motionTraceRows": [r for r in found if r["tag"] == "MotionTrace"]})
                rt.require_stopped(rt.snapshot("idle-return-"+str(cycle)))
            evidence["verdict"] = "REVIEW_REQUIRED: source setup logs and fresh trace must be distinguished; no gear/Pleos getter trace assertion"
        else:
            event, session = start_coffee(args.case in ("on", "active-off"))
            evidence["session"] = {key: session.get(key) for key in ("requestId", "startedMs")}
            if args.case in ("off", "permission-denied"):
                if rows_after(event["hostUnixNs"], "PleosLocationSpeed", "subscription active"):
                    raise RuntimeError("location subscription while unavailable")
            else:
                if not rows_after(event["hostUnixNs"], "PleosLocationSpeed", "subscription active"):
                    raise RuntimeError("location subscription not observed after guard request")
            if args.case == "active-off":
                off = set_location(False); time.sleep(4)
                if not rows_after(off["hostUnixNs"], "PleosLocationSpeed", "subscription stopped"):
                    raise RuntimeError("location stop not observed")
                on = set_location(True); time.sleep(5)
                if rows_after(on["hostUnixNs"], "PleosLocationSpeed", "subscription active"):
                    raise RuntimeError("location auto-resumed inside the same guard")
                tick = rt.wait_runtime("tick", on["hostUnixNs"])["data"]
                if any(tick.get(key) != value for key, value in evidence["session"].items()):
                    raise RuntimeError("guard identity changed")
            evidence["verdict"] = "CONTROL_BOUNDARY_OBSERVED_ONLY"
        evidence["installedApkSha256"] = digest
    except BaseException as exc:
        errors.append(str(exc))
    finally:
        execution_errors = list(errors)
        if changed:
            try:
                qa.adb("shell", "am", "start", "-W", "-n", qa.PKG+"/.MainActivity")
                qa.demo("end", phase="cleanup"); time.sleep(2); qa.demo("ack", phase="cleanup")
                rt.require_stopped(rt.snapshot("cleanup"))
            except BaseException as exc:
                errors.append("cleanup: "+str(exc))
            if original_location is not None:
                try:
                    set_location(original_location)
                except BaseException as exc:
                    errors.append("location cleanup: "+str(exc))
            if flags is not None:
                try:
                    qa.configure(flags, "settings-restored")
                    rt.wait_ui("cleanup-idle", "프리셋 고르기")
                except BaseException as exc:
                    errors.append("settings cleanup: "+str(exc))
            try:
                final_access = access_state()
                qa.save("access-after.json", {**final_access, "locationEnabled": location_enabled()})
                if original_access != final_access:
                    raise RuntimeError("permission flags/app-op modes changed; no broad reset attempted")
            except BaseException as exc:
                errors.append("access cleanup: "+str(exc))
        for tag, value in properties.items():
            try:
                qa.adb("shell", "setprop log.tag."+tag+" "+shlex.quote(value))
                if qa.adb("shell", "getprop", "log.tag."+tag).strip() != value:
                    raise RuntimeError("log property readback differs")
            except BaseException as exc:
                errors.append("log property cleanup: "+str(exc))
        if properties:
            qa.save("log-properties-after.json", {tag: qa.adb("shell", "getprop", "log.tag."+tag).strip() for tag in properties})
        if process:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
            thread.join(timeout=5)
        if before is not None:
            try:
                after = qa.api("/vehicle/state"); qa.check_park(after)
                qa.save("vehicle-after.json", rt.cabin_values(after))
                if rt.cabin_values(after) != rt.cabin_values(before):
                    raise RuntimeError("observable vehicle state changed")
            except BaseException as exc:
                errors.append("vehicle cleanup: "+str(exc))
        if own_forward:
            try:
                qa.adb("forward", "--remove", "tcp:8088")
            except BaseException as exc:
                errors.append("forward cleanup: "+str(exc))
        qa.save("result.json", {"case": args.case, **evidence, "issues": errors,
                "cleanupVerified": errors == execution_errors, "executedWithoutError": not errors,
                "limits": ["No GPS fix injected or recorded; cache invalidation and real speed accuracy unmeasured.",
                           "Reconnect logs alone do not prove fresh gear/Pleos getter or native route callback delivery.",
                           "Stationary COFFEE AVD only; no human/real-car validation."]})
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
