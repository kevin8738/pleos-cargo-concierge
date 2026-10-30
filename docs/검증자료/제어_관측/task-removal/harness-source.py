#!/usr/bin/env python3
"""Bounded shell task-removal probe. Import and --self-test never call adb.

Execute only after the leader hands over AVD ownership:
  python3 task_removal_probe.py --run --out runtime-shell-task-removal-01 \
      --expected-apk-sha256 SHA256

Only a freshly observed, single-leaf VIP root belonging to the current user can
be removed. No supplied/cached task ID, recents swipe, force-stop or data clear.
GuardRuntime lost is observed; onTaskRemoved itself has no direct log marker.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import time
import known_answer_probe as qa
import runtime_avd_probe as runtime
from fgs_failure_probe import parked_preparation, stop_logs

BASE = Path(__file__).resolve().parent
OWN_COMPONENTS = {qa.PKG+"/.MainActivity", qa.PKG+"/"+qa.PKG+".MainActivity"}


def parse_roots(raw):
    """Parse the API34 RootTaskInfo.toString layout, rejecting unknown rows."""
    roots, root = [], None
    root_ids, task_ids = set(), set()
    for line in raw.splitlines():
        if not line.strip():
            continue
        head = re.fullmatch(r"RootTask id=(\d+) bounds=.+ displayId=(\d+) userId=(\d+)", line)
        if head:
            if head[1] in root_ids:
                raise ValueError("duplicate root ID")
            root_ids.add(head[1])
            root = {"rootId": int(head[1]), "displayId": int(head[2]), "userId": int(head[3]), "tasks": []}
            roots.append(root)
            continue
        if root is None:
            raise ValueError("unknown stack-list header")
        if line.startswith(" configuration="):
            continue
        child = re.fullmatch(r"  taskId=(\d+): (\S+)(?: bounds=.+)? userId=(\d+) visible=(true|false)(?: topActivity=ComponentInfo\{([^}]+)\})?", line)
        if not child:
            raise ValueError("unknown stack-list row; will not guess ownership")
        if child[1] in task_ids:
            raise ValueError("duplicate task ID")
        task_ids.add(child[1])
        root["tasks"].append({"taskId": int(child[1]), "component": child[2], "userId": int(child[3]),
                              "visible": child[4] == "true", "topActivity": child[5]})
    if not roots:
        raise ValueError("empty root-task inventory")
    return roots


def select_owned_root(roots, user):
    owners = [(root, task) for root in roots for task in root["tasks"]
              if task["component"].split("/", 1)[0] == qa.PKG
              or (task["topActivity"] or "").split("/", 1)[0] == qa.PKG]
    if len(owners) != 1:
        raise ValueError("exactly one own VIP task required")
    root, task = owners[0]
    if len(root["tasks"]) != 1 or root["rootId"] != task["taskId"]:
        raise ValueError("VIP must be the sole leaf task and its own root; no shared/nested root removal")
    if root["userId"] != user or task["userId"] != user:
        raise ValueError("VIP task user differs from current user")
    if task["component"] not in OWN_COMPONENTS or task["topActivity"] not in OWN_COMPONENTS or not task["visible"]:
        raise ValueError("visible own MainActivity/base component required")
    return root


def read_roots(label):
    raw = qa.adb("shell", "am", "stack", "list")
    observed = time.monotonic_ns()
    (qa.OUT/(label+".txt")).write_text(raw)
    roots = parse_roots(raw)
    qa.save(label+".json", roots)
    return roots, observed


def remove_owned_task(user):
    first, _ = read_roots("stack-before")
    first = select_owned_root(first, user)
    second, observed = read_roots("stack-immediate-before")
    second = select_owned_root(second, user)
    if first != second:
        raise RuntimeError("VIP root identity/ownership changed during final check")
    age_ms = (time.monotonic_ns()-observed)/1e6
    if age_ms > 1000:
        raise RuntimeError("final task inventory is stale")
    marker = qa.event("shell_task_remove_request", selectedRoot=second, inventoryAgeMs=age_ms)
    output = qa.adb("shell", "am", "stack", "remove", str(second["rootId"]))
    (qa.OUT/"remove-command.txt").write_text(output)
    if output.strip():
        raise RuntimeError("unexpected shell remove output; inspect before any retry")
    qa.event("shell_task_remove_returned")
    return marker, second


def assert_removed(roots, selected):
    if any(root["rootId"] == selected["rootId"] for root in roots):
        raise RuntimeError("selected root remains")
    if any(task["component"].split("/", 1)[0] == qa.PKG or
           (task["topActivity"] or "").split("/", 1)[0] == qa.PKG
           for root in roots for task in root["tasks"]):
        raise RuntimeError("VIP task remains or restarted automatically")


def self_test():
    def root(task_id=20, component=None, user=10):
        component = component or qa.PKG+"/.MainActivity"
        return (f"RootTask id={task_id} bounds=[0,0][100,100] displayId=0 userId={user}\n"
                " configuration={synthetic-parser-fixture}\n"
                f"  taskId={task_id}: {component} bounds=[0,0][100,100] userId={user} visible=true topActivity=ComponentInfo{{{component}}}\n")
    own = root()
    other = root(30, "example.other/.MainActivity")
    assert select_owned_root(parse_roots(other+own), 10)["rootId"] == 20
    cases = [other, own+root(21), own+own, root(user=11),
             own.replace("RootTask id=20", "RootTask id=19"),
             own.replace("visible=true", "visible=false"),
             own.replace(qa.PKG+"/.MainActivity", qa.PKG+".suffix/.MainActivity"),
             own.replace("topActivity=ComponentInfo{"+qa.PKG+"/.MainActivity}", "topActivity=ComponentInfo{example.other/.MainActivity}"),
             own+"  taskId=21: example.other/.MainActivity userId=10 visible=true\n",
             own+" unrecognized child layout\n"]
    for value in cases:
        try:
            select_owned_root(parse_roots(value), 10)
        except ValueError:
            continue
        raise AssertionError("unsafe synthetic ownership accepted")
    assert_removed(parse_roots(other), {"rootId": 20})
    print(json.dumps({"selfTest": "PASS", "unsafeOwnershipFixturesRejected": len(cases), "adbCalls": 0}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--run", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    parser.add_argument("--out")
    parser.add_argument("--expected-apk-sha256")
    args = parser.parse_args()
    if args.self_test:
        self_test(); return 0
    if not re.fullmatch(r"runtime-shell-task-removal-[a-z0-9-]+", args.out or "") or not re.fullmatch(r"[a-fA-F0-9]{64}", args.expected_apk_sha256 or ""):
        parser.error("new runtime-shell-task-removal-* folder and installed APK hash required")
    qa.OUT = BASE/args.out; qa.OUT.mkdir(exist_ok=False)
    for filename, source in (("harness-source.py", __file__), ("runtime-helper-source.py", runtime.__file__),
                             ("qa-helper-source.py", qa.__file__),
                             ("fault-helper-source.py", parked_preparation.__code__.co_filename)):
        (qa.OUT/filename).write_text(Path(source).read_text())
    before = flags = process = thread = removed = selected = None
    own_forward = False
    originals, errors, samples = {}, [], []
    evidence = {"onTaskRemovedDirectlyInstrumented": False,
                "callbackScope": "Exact shell task removal, matching GuardRuntime lost and teardown; no direct onTaskRemoved marker."}
    try:
        forwards = [line for line in qa.adb("forward", "--list").splitlines() if " tcp:8088 " in line]
        if forwards and forwards != [qa.SERIAL+" tcp:8088 tcp:8088"]:
            raise RuntimeError("8088 forward belongs to another endpoint")
        if not forwards:
            qa.adb("forward", "tcp:8088", "tcp:8088"); own_forward = True
        before = qa.api("/vehicle/state"); qa.save("vehicle-before.json", before); qa.check_park(before)
        if any(before["controls"][key] != 0 for key in ("accelerator_pct", "brake_pct", "steering_deg")):
            raise RuntimeError("existing P0/zero controls required")
        if qa.adb("shell", "getprop", "ro.product.name").strip() != "crp_avd_arm64" or qa.adb("shell", "getprop", "ro.build.version.sdk").strip() != "34":
            raise RuntimeError("official API34 AVD required")
        paths = qa.adb("shell", "pm", "path", qa.PKG).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("one installed base APK required")
        digest = hashlib.sha256(qa.adb("exec-out", "cat", paths[0][8:], binary=True)).hexdigest()
        if digest != args.expected_apk_sha256.lower():
            raise RuntimeError("installed APK differs")
        user = int(qa.adb("shell", "am", "get-current-user").strip())
        surface = qa.surface("initial_visible")
        if surface["vipActivityState"] != "RESUMED" or surface["vipVisible"] is not True:
            raise RuntimeError("initial visible VIP required")
        runtime.wait_ui("initial_idle", "프리셋 고르기")
        runtime.require_stopped(runtime.snapshot("initial_idle"))
        select_owned_root(read_roots("stack-preflight")[0], user)
        evidence.update(installedApkSha256=digest, user=user, initialPid=surface["pid"], clockCalibration=runtime.clock_sync())
        qa.demo("settings", "on")
        flags = {key: row["checked"] for key, row in qa.switches(qa.ui("settings-original")).items()}
        qa.save("settings-original.json", flags)
        qa.configure({"tts": False, "chime": False}, "settings-muted")
        for tag in runtime.TAGS:
            originals[tag] = qa.adb("shell", "getprop", "log.tag."+tag).strip()
            qa.adb("shell", "setprop", "log.tag."+tag, "DEBUG")
        runtime.LIVE.clear(); process, thread = runtime.start_logcat()
        tree = qa.ui("before-coffee")
        coffee = runtime.find(tree, "커피 0.12G", qa.PKG)
        if coffee is None:
            raise RuntimeError("COFFEE UI unavailable")
        parked_preparation()
        start = qa.event("guard_ui_start"); runtime.tap(coffee, "coffee_tap")
        ready = runtime.wait_runtime("ready", start["hostUnixNs"])
        if not ready["data"].get("guarding") or not ready["data"].get("visible"):
            raise RuntimeError("visible guard ready required")
        evidence["session"] = {key: ready["data"][key] for key in ("requestId", "startedMs")}
        samples.append(runtime.wait_active("guard_active", "record", start["hostUnixNs"]))
        if "캐빈 적용: 0개 성공, 0개 미적용" not in (qa.OUT/"live.log").read_text():
            raise RuntimeError("COFFEE zero-cabin result missing")
        removed, selected = remove_owned_task(user)
        evidence["removeRequest"] = removed
        # Observe before any relaunch or app-targeted IPC. PID may survive removal.
        anchor = time.monotonic()
        for delay in (1, 8, 18):
            time.sleep(max(0, anchor+delay-time.monotonic()))
            roots, _ = read_roots("stack-after-"+str(delay)); assert_removed(roots, selected)
            row = runtime.snapshot("removed_"+str(delay)); samples.append(row); runtime.require_stopped(row)
            if row["pid"] and row["pid"] != evidence["initialPid"]:
                raise RuntimeError("a new process appeared before manual launch")
        observed_end = qa.event("removal_observation_end")
        calibration = evidence["clockCalibration"]
        offset, uncertainty = calibration["deviceMinusHostNs"], calibration["uncertaintyNs"]
        lost = [r for r in runtime.runtime_rows("lost")
                if removed["hostUnixNs"] <= r["hostReceivedUnixNs"] < observed_end["hostUnixNs"]
                and removed["hostUnixNs"]+offset-uncertainty <= r.get("deviceEpochSeconds", 0)*1e9 < observed_end["hostUnixNs"]+offset+uncertainty
                and str(r.get("pid")) == evidence["initialPid"]
                and all(r["data"].get(key) == value for key, value in evidence["session"].items())]
        if len(lost) != 1:
            raise RuntimeError("exactly one matching session lost event required")
        evidence["lost"] = lost[0]
        relaunch = qa.event("manual_relaunch_request"); evidence["manualRelaunch"] = relaunch
        qa.adb("shell", "am", "start", "-W", "-n", qa.PKG+"/.MainActivity")
        runtime.wait_ui("manual-relaunch-idle", "프리셋 고르기")
        qa.ui("manual-relaunch-idle", True); runtime.require_stopped(runtime.snapshot("manual_relaunch"))
        qa.event("case_complete")
    except BaseException as exc:
        errors.append(str(exc)); qa.event("case_error", error=str(exc))
    finally:
        execution_errors = list(errors)
        if flags is not None:
            try:
                qa.adb("shell", "am", "start", "-W", "-n", qa.PKG+"/.MainActivity")
                tree = qa.ui("cleanup-current")
                node = runtime.find(tree, "내렸어요 (종료)", qa.PKG)
                if node is not None:
                    runtime.tap(node, "cleanup-normal-end"); time.sleep(1)
                qa.configure(flags, "settings-restored")
                runtime.wait_ui("cleanup-idle", "프리셋 고르기")
                qa.ui("cleanup-idle", True); runtime.require_stopped(runtime.snapshot("cleanup_idle"))
            except BaseException as exc:
                errors.append("cleanup UI/service: "+str(exc))
        for tag, value in originals.items():
            try:
                qa.adb("shell", "setprop log.tag."+tag+" "+shlex.quote(value))
                if qa.adb("shell", "getprop", "log.tag."+tag).strip() != value:
                    raise RuntimeError("property restoration differs")
            except BaseException as exc:
                errors.append("cleanup property: "+str(exc))
        stop_logs(process, thread)
        if before is not None:
            try:
                after = qa.api("/vehicle/state"); qa.save("vehicle-after.json", after); qa.check_park(after)
                if runtime.cabin_values(before) != runtime.cabin_values(after):
                    raise RuntimeError("observable vehicle values changed")
            except BaseException as exc:
                errors.append("cleanup vehicle: "+str(exc))
        if own_forward:
            try:
                qa.adb("forward", "--remove", "tcp:8088")
            except BaseException as exc:
                errors.append("cleanup forward: "+str(exc))
        qa.save("cleanup.json", {"verified": errors == execution_errors, "executionErrors": execution_errors,
                                 "cleanupErrors": errors[len(execution_errors):], "originalLogProperties": originals})
    if removed:
        rows = [r for r in runtime.LIVE if r["hostReceivedUnixNs"] >= removed["hostUnixNs"]]
        # Removal is asynchronous. A pre-loss tick is not a restarted session;
        # forbid any new ready immediately, and any active tick/state after loss.
        loss_ns = evidence.get("lost", {}).get("hostReceivedUnixNs", removed["hostUnixNs"])
        renewals = [r for r in rows if r.get("tag") == "GuardRuntime" and
                    (r.get("data", {}).get("event") == "ready" or
                     (r["hostReceivedUnixNs"] > loss_ns and r.get("data", {}).get("event") == "tick"))]
        guarded = [r for r in rows if r["hostReceivedUnixNs"] > loss_ns and
                   r.get("tag") == "MotionTrace" and r.get("data", {}).get("state") == "GUARDING"]
        writes = [r for r in rows if r.get("tag") in ("CargoVM", "PleosVehicle") and
                  ("캐빈 적용 →" in r.get("message", "") or re.search(r"\b(apply|restore) \w+: APPLIED\b", r.get("message", "")))]
        evidence.update(renewedRuntimeRows=len(renewals), postRemovalGuardRows=len(guarded), observedWriteRows=len(writes))
        if renewals or guarded or writes:
            errors.append("runtime renewal/Guarding/write after removal observed")
    evidence.update(passed=not errors and "manualRelaunch" in evidence, issues=errors, runtimeSnapshots=samples,
                    limits=["onTaskRemoved callback directly uninstrumented; lost alone is shared with other teardown paths.",
                            "Shell task removal is not a successful Recents swipe test; prior Recents run remains unexecuted.",
                            "18s stationary AVD observation; no bus-level write capture or physical driving claim."])
    qa.save("result.json", evidence)
    print(json.dumps({"out": str(qa.OUT), "passed": evidence["passed"], "issues": errors}))
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
