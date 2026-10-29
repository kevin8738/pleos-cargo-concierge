#!/usr/bin/env python3
"""Authorized COFFEE-only audio contrasts; writes only matrix_01 and this script.

No microphone/STT, host audio, external media, HVAC commands, or AVD restart.
The credential pattern follows capture_current.py; token values never leave a
0600 temporary file or the capture child's authorization metadata.
"""
import array
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
OUT = BASE / "matrix_01"
ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
SERIAL = "emulator-5554"
PKG = "ai.vipseat.cargoconcierge"
PYTHON = "<LOCAL_HOME>/miniforge3/bin/python3"
LABELS = {"tts": "음성 코칭 (TTS)", "chime": "경고음", "demo_curve": "데모 급커브 주입 허용"}
CASES = [("tts_on_chime_off", True, False), ("tts_off_chime_off", False, False),
         ("tts_off_chime_on", False, True), ("both_off_chime_control", False, False)]
SECONDS = 15


def stamp():
    return {"host_unix_ns": time.time_ns(), "host_monotonic_ns": time.monotonic_ns(),
            "host_utc": dt.datetime.now(dt.timezone.utc).isoformat()}


def event(name, **fields):
    item = {"event": name, **stamp(), **fields}
    with (OUT / "events.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(item, ensure_ascii=False) + "\n")
    return item


def adb(*args, timeout=20):
    return subprocess.check_output([ADB, "-s", SERIAL, *args], timeout=timeout).decode("utf-8", "replace")


def demo(command, value=None):
    # Keep the allowed mutation surface explicit.
    if command not in {"gear", "speed", "settings", "preset", "end", "ack"}:
        raise ValueError("unapproved demo command")
    expected = {"gear": {"P"}, "speed": {"0"}, "settings": {"on", "off"}, "preset": {"COFFEE"}}
    if command in expected and value not in expected[command]:
        raise ValueError("unapproved demo value")
    args = ["shell", "am", "broadcast", "-n", PKG + "/.demo.DemoBroadcastReceiver",
            "-a", PKG + ".DEMO", "--es", "cmd", command]
    if value is not None:
        args += ["--es", "value", value]
    before = stamp()
    result = adb(*args)
    after = stamp()
    if "Broadcast completed: result=0" not in result or 'data="error:' in result:
        raise RuntimeError("demo broadcast failed")
    event("demo", command=command, value=value, started=before, finished=after)
    return {"command": command, "value": value, "started": before, "finished": after}


def parked():
    demo("gear", "P")
    demo("speed", "0")


def ui(path):
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    xml = raw[raw.index("<?xml"):raw.index("</hierarchy>") + len("</hierarchy>")]
    path.write_text(xml + "\n", encoding="utf-8")
    return ET.fromstring(xml)


def box(node):
    numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(numbers) != 4:
        raise RuntimeError("UI bounds missing")
    return numbers


def switch_states(tree):
    nodes = [n for n in tree.iter("node") if n.get("package") == PKG]
    switches = [n for n in nodes if n.get("checkable") == "true"]
    result = {}
    for key, label in LABELS.items():
        labels = [n for n in nodes if n.get("text") == label]
        if len(labels) != 1:
            raise RuntimeError("expected settings label not uniquely visible")
        lx1, ly1, lx2, ly2 = box(labels[0])
        matching = [n for n in switches if box(n)[0] > lx2 and
                    max(ly1, box(n)[1]) < min(ly2, box(n)[3])]
        if len(matching) != 1 or matching[0].get("enabled") != "true":
            raise RuntimeError("expected settings switch not uniquely enabled")
        node = matching[0]
        if node.get("checked") not in {"true", "false"}:
            raise RuntimeError("switch checked attribute unavailable")
        result[key] = {"checked": node.get("checked") == "true", "bounds": box(node)}
    return result


def configure(target, folder, prefix="settings"):
    demo("settings", "on")
    states = switch_states(ui(folder / f"{prefix}_before.xml"))
    for key in ("tts", "chime"):
        if states[key]["checked"] != target[key]:
            x1, y1, x2, y2 = states[key]["bounds"]
            before = stamp()
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            event("switch_tap", key=key, target=target[key], started=before)
            states = switch_states(ui(folder / f"{prefix}_after_{key}_tap.xml"))
            if states[key]["checked"] != target[key]:
                raise RuntimeError("switch did not reach requested state")
    final = switch_states(ui(folder / f"{prefix}_confirmed.xml"))
    if any(final[key]["checked"] != value for key, value in target.items()):
        raise RuntimeError("settings verification mismatch")
    (folder / f"{prefix}_states.json").write_text(json.dumps(final, indent=2) + "\n")
    event("settings_confirmed", settings={k: v["checked"] for k, v in final.items()})
    return final


def focus(path):
    before = stamp()
    path.write_text(adb("shell", "dumpsys", "audio"), encoding="utf-8")
    event("focus_snapshot", file=str(path.relative_to(OUT)), started=before)


def start_capture(folder):
    connection = json.loads((BASE / "environment-local.json").read_text())["connection"]
    if (connection["avd"] != "Pleos_Connect_v2.1" or connection["audio_disabled"]
            or connection["host_audio_input_allowed"]):
        raise RuntimeError("environment contract mismatch")
    # Only the existing helper's specific connection file is read. No credential
    # values or full INI contents are serialized into evidence or exceptions.
    entries = dict(line.split("=", 1) for line in Path(connection["ini_path"]).read_text().splitlines() if "=" in line)
    if entries.get("avd.name") != "Pleos_Connect_v2.1" or entries.get("grpc.port") != "8554":
        raise RuntimeError("emulator connection contract mismatch")
    fd, token_path = tempfile.mkstemp(prefix=".grpc-", dir=OUT)
    os.chmod(token_path, 0o600)
    try:
        with os.fdopen(fd, "w") as handle:
            handle.write(entries["grpc.token"])
        process = subprocess.Popen([PYTHON, str(BASE / "capture_emulator_audio.py"),
                                    "--endpoint", "127.0.0.1:8554", "--seconds", str(SECONDS),
                                    "--out", str(folder / "capture"), "--token-file", token_path],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                   env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
        event("capture_started", case=folder.name, seconds=SECONDS)
        return process, Path(token_path)
    except BaseException:
        Path(token_path).unlink(missing_ok=True)
        raise


def analyse(folder, trigger):
    capture = folder / "capture"
    metadata = json.loads((capture / "metadata.json").read_text())
    raw = (capture / "audio.s16le.pcm").read_bytes()
    packets_dir = capture / "packets"
    packets_dir.mkdir()
    offset = 0
    rows = []
    trigger_s = trigger["started"]["host_unix_ns"] / 1e9
    for entry in metadata["packets"]:
        length = entry["bytes"]
        payload = raw[offset:offset + length]
        if len(payload) != length or length % 4:
            raise RuntimeError("packet byte reconstruction mismatch")
        name = f"packet_{entry['index']:05d}.s16le"
        (packets_dir / name).write_bytes(payload)
        samples = array.array("h")
        samples.frombytes(payload)
        if sys.byteorder != "little":
            samples.byteswap()
        squares = sum(value * value for value in samples)
        peak = max(map(abs, samples), default=0)
        rows.append({**entry, "raw_file": f"packets/{name}", "pcm_byte_offset": offset,
                     "relative_to_command_start_seconds": entry["timestamp_us"] / 1e6 - trigger_s,
                     "sample_count": len(samples), "sum_squares": squares, "peak_absolute_s16": peak,
                     "rms_s16": math.sqrt(squares / len(samples)) if samples else None})
        offset += length
    if offset != len(raw):
        raise RuntimeError("unaccounted PCM bytes")
    (capture / "packet_manifest.json").write_text(json.dumps(rows, indent=2) + "\n")
    windows = {}
    for label, start, end in (("pre_event", -3, 0), ("response_0_to_8s", 0, 8), ("late_8_to_12s", 8, 12)):
        selected = [p for p in rows if start <= p["relative_to_command_start_seconds"] < end]
        count = sum(p["sample_count"] for p in selected)
        sq = sum(p["sum_squares"] for p in selected)
        windows[label] = {"window_seconds": [start, end], "packet_count": len(selected),
                          "captured_pcm_seconds": count / (48000 * 2),
                          "peak_absolute_s16": max((p["peak_absolute_s16"] for p in selected), default=None),
                          "rms_s16_over_received_samples": math.sqrt(sq / count) if count else None}
    summary = {"case": folder.name, "trigger": trigger, "windows": windows,
               "capture_status": metadata["capture_status"], "rpc_status": metadata["rpc_status"],
               "wall_seconds": metadata["wall_seconds"], "packet_count": metadata["packet_count"],
               "pcm_duration_seconds": metadata["pcm_duration_seconds"],
               "peak_absolute_s16": metadata["peak_absolute_s16"],
               "rms_normalized": metadata["rms_normalized"],
               "packet_timestamp_span_seconds": ((rows[-1]["timestamp_us"] - rows[0]["timestamp_us"]) / 1e6) if rows else None,
               "wav_is_packet_concatenation_not_continuous_wall_time": True,
               "attribution_limit": "Only an ON/OFF amplitude/time-window contrast; content intelligibility, navigation contention and vehicle effects unverified."}
    (folder / "analysis.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return summary


def case(name, tts, chime):
    folder = OUT / name
    folder.mkdir()
    parked()
    demo("end")
    demo("ack")
    configure({"tts": tts, "chime": chime}, folder)
    demo("settings", "off")
    event("setup_quiet_wait", seconds=8, case=name)
    time.sleep(8)
    ui(folder / "before.xml")
    focus(folder / "focus_before.txt")
    pid = adb("shell", "pidof", PKG).strip()
    if not pid.isdecimal():
        raise RuntimeError("app process unavailable")
    process = token_path = log_process = None
    with (folder / "app_logcat.txt").open("w") as log_handle:
        try:
            log_process = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "epoch", "--pid=" + pid, "-T", "1",
                                            "CargoVM:I", "AndroidTts:I", "PleosSpeech:I", "ChimePlayer:I",
                                            "DemoBridge:I", "AudioTrack:D", "*:S"], stdout=log_handle, stderr=subprocess.DEVNULL)
            process, token_path = start_capture(folder)
            time.sleep(2.5)
            parked()
            trigger = demo("preset", "COFFEE")
            (folder / "trigger.json").write_text(json.dumps(trigger, indent=2) + "\n")
            time.sleep(0.3)
            focus(folder / "focus_during.txt")
            stdout, stderr = process.communicate(timeout=SECONDS + 5)
            (folder / "capture_process.json").write_text(json.dumps({"exit_code": process.returncode,
                                                                      "stdout": stdout, "stderr_present_suppressed": bool(stderr)}, indent=2) + "\n")
            if process.returncode not in (0, 2):
                raise RuntimeError("capture failed; inspect safe metadata")
        finally:
            if process and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)
            if token_path:
                token_path.unlink(missing_ok=True)
            if log_process:
                log_process.terminate()
                log_process.wait(timeout=3)
    focus(folder / "focus_after.txt")
    tree = ui(folder / "after.xml")
    texts = [n.get("text", "") for n in tree.iter("node")]
    if "커피" not in texts or "캐빈 적용: 0개 성공, 0개 미적용" not in texts:
        raise RuntimeError("COFFEE guard with zero cabin changes not confirmed")
    summary = analyse(folder, trigger)
    event("case_complete", case=name, capture_status=summary["capture_status"], peak=summary["peak_absolute_s16"])
    print(json.dumps({"case": name, "peak": summary["peak_absolute_s16"], "packets": summary["packet_count"],
                      "pcm_seconds": summary["pcm_duration_seconds"], "response": summary["windows"]["response_0_to_8s"]}), flush=True)
    return summary


def main():
    OUT.mkdir(mode=0o700, exist_ok=False)
    original = None
    results = []
    failure = None
    cleanup = {"completed": False}
    def stop(signum, frame):
        raise RuntimeError("runner interrupted")
    signal.signal(signal.SIGTERM, stop)
    try:
        event("run_started", allowed_preset="COFFEE", cases=CASES)
        (OUT / "scope.json").write_text(json.dumps({"capture_seconds": SECONDS,
            "cases": CASES, "trigger": "COFFEE select for all four; end has no success chime in current source",
            "excluded": ["microphone", "STT", "host audio", "external media", "actual HVAC mutation", "AVD restart"],
            "wav_interpretation": "Concatenated received packets, not continuous wall-time audio; raw packet bytes/timestamps retained."}, indent=2) + "\n")
        parked()
        demo("settings", "on")
        original = switch_states(ui(OUT / "original_settings.xml"))
        (OUT / "original_settings.json").write_text(json.dumps(original, indent=2) + "\n")
        clock_before = stamp()
        device_time = adb("shell", "date", "+%s%N").strip()
        (OUT / "clock_sync.json").write_text(json.dumps({"host_before": clock_before,
            "device_epoch_ns_text": device_time, "host_after": stamp()}, indent=2) + "\n")
        for name, tts, chime in CASES:
            results.append(case(name, tts, chime))
    except BaseException as error:
        # Do not serialize arbitrary exception strings from connection handling.
        failure = {"type": type(error).__name__, "message": "matrix stopped; inspect local case evidence"}
        event("run_failed", **failure)
    finally:
        try:
            parked()
            demo("end")
            demo("ack")
            if original is not None:
                target = {key: original[key]["checked"] for key in ("tts", "chime", "demo_curve")}
                restored = configure(target, OUT, "restored_settings")
                cleanup["settings_restored"] = all(restored[k]["checked"] == v for k, v in target.items())
            demo("settings", "off")
            parked()
            demo("end")
            demo("ack")
            tree = ui(OUT / "final_ui.xml")
            texts = [n.get("text", "") for n in tree.iter("node") if n.get("text")]
            (OUT / "final_ui_texts.json").write_text(json.dumps(texts, ensure_ascii=False, indent=2) + "\n")
            cleanup.update(completed=True, end_ack_sent=True, gear_P_speed_0_sent=True)
            focus(OUT / "focus_final.txt")
            event("cleanup_complete", **cleanup)
        except BaseException as error:
            cleanup["error_type"] = type(error).__name__
            event("cleanup_failed", **cleanup)
        (OUT / "summary.json").write_text(json.dumps({"results": results, "failure": failure,
                                                       "cleanup": cleanup}, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"case_count": len(results), "failure": failure, "cleanup": cleanup}), flush=True)
    return int(failure is not None or not cleanup["completed"])


if __name__ == "__main__":
    sys.exit(main())
