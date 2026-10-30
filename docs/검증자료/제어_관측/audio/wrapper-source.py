#!/usr/bin/env python3
"""Explicit runtime-06 audio matrix; preparation alone does not access ADB/audio.

Capture (only after root releases the AVD lane): --run
Offline packet/hash/settings checks (no ADB): --verify-only

Writes only NEW matrix_03; preserves matrix_01/02 and the shared runners.
"""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import wave
import xml.etree.ElementTree as ET

import run_matrix as matrix

BASE = Path(__file__).resolve().parent
OUT = BASE / "matrix_03"
EXPECTED = "8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c"
LIMITS = [
    "COFFEE selection only; TTS-only, chime-only and two both-off controls.",
    "Both-on and competing navigation/media audio are not exercised.",
    "PCM/WAV concatenate received packets; duration is not continuous wall time or utterance duration.",
    "Output contrast does not establish speech intelligibility, physical speaker output, vehicle effects or driver burden.",
    "Intermittent focus snapshots do not exclude short focus transitions.",
    "TTS_ERROR=0 refers only to the filtered app log, not every service.",
    "No microphone, STT, host audio input, HVAC command, AVD restart or app reinstall.",
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def installed_snapshot():
    """Read-only fixed-device check. Never record the private installed APK path."""
    paths = matrix.adb("shell", "pm", "path", matrix.PKG).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise RuntimeError("one installed base APK required")
    payload = subprocess.check_output(
        [matrix.ADB, "-s", matrix.SERIAL, "exec-out", "cat", paths[0][8:]],
        timeout=30, stderr=subprocess.DEVNULL,
    )
    package = matrix.adb("shell", "dumpsys", "package", matrix.PKG)
    name = re.search(r"\bversionName=([^\s]+)", package)
    code = re.search(r"\bversionCode=(\d+)", package)
    result = {
        **matrix.stamp(),
        "installed_base_apk_sha256": hashlib.sha256(payload).hexdigest(),
        "apk_bytes": len(payload),
        "versionName": name[1] if name else None,
        "versionCode": int(code[1]) if code else None,
        "product": matrix.adb("shell", "getprop", "ro.product.name").strip(),
        "sdk": matrix.adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "pid": matrix.adb("shell", "pidof", matrix.PKG).strip(),
    }
    result["matches_expected_candidate"] = (
        result["installed_base_apk_sha256"] == EXPECTED
        and result["versionName"] == "1.1.4-pleos"
        and result["versionCode"] == 7
        and result["product"] == "crp_avd_arm64"
        and result["sdk"] == "34"
        and result["pid"].isdigit()
    )
    return result


def run():
    if OUT.exists():
        raise RuntimeError("matrix_03 already exists; existing evidence is never overwritten")
    before = installed_snapshot()
    if not before["matches_expected_candidate"]:
        raise RuntimeError("runtime-06 pre-capture identity check failed; capture not started")
    matrix.OUT = OUT
    original_event = matrix.event

    def record_event(name, **fields):
        if name == "run_started":
            write("environment-before.json", before)
            write("environment.json", {
                "expected_apk_sha256": EXPECTED,
                "before": before,
                "after": None,
                "identity_verification": "PENDING_AFTER_CAPTURE",
                "host_input_allowed": False,
                "emulator_audio_enabled": True,
                "limits": LIMITS,
            })
            for source, target in ((Path(__file__), "wrapper-source.py"),
                                   (BASE / "run_matrix.py", "runner-source.py"),
                                   (BASE / "capture_emulator_audio.py", "capture-source.py")):
                (OUT / target).write_bytes(source.read_bytes())
        return original_event(name, **fields)

    matrix.event = record_event
    capture_exit = 1
    wrapper_error = None
    try:
        capture_exit = matrix.main()
    except BaseException as error:
        # Avoid serializing arbitrary exception text from credential/connection handling.
        wrapper_error = type(error).__name__
    finally:
        matrix.event = original_event
        after = None
        after_error = None
        try:
            after = installed_snapshot()
        except BaseException as error:
            after_error = type(error).__name__
        if OUT.exists():
            if after is not None:
                write("environment-after.json", after)
            identity_ok = bool(after and after["matches_expected_candidate"]
                               and after["pid"] == before["pid"])
            write("environment.json", {
                "expected_apk_sha256": EXPECTED,
                "before": before, "after": after,
                "identity_verification": "PASS" if identity_ok else "FAIL",
                "same_process": bool(after and after["pid"] == before["pid"]),
                "capture_exit_code": capture_exit,
                "wrapper_error_type": wrapper_error,
                "after_measurement_error_type": after_error,
                "host_input_allowed": False, "emulator_audio_enabled": True,
                "limits": LIMITS,
            })
    environment = json.loads((OUT / "environment.json").read_text()) if OUT.exists() else {}
    passed = capture_exit == 0 and wrapper_error is None and environment.get("identity_verification") == "PASS"
    print(json.dumps({"matrix": "matrix_03", "capture_and_identity_passed": passed,
                      "offline_verification": "Run --verify-only; capture alone is not an audio verdict."}))
    return 0 if passed else 1


def verify_only():
    """Offline recomputation; separate reviewer remains responsible for evidence interpretation."""
    errors, cases = [], []
    environment = json.loads((OUT / "environment.json").read_text())
    summary = json.loads((OUT / "summary.json").read_text())
    if environment.get("identity_verification") != "PASS":
        errors.append("before/after candidate identity failed")
    for phase in ("before", "after"):
        snap = environment.get(phase) or {}
        if (snap.get("installed_base_apk_sha256") != EXPECTED
                or snap.get("versionName") != "1.1.4-pleos" or snap.get("versionCode") != 7):
            errors.append(phase + " identity mismatch")
    if (environment.get("capture_exit_code") != 0 or environment.get("wrapper_error_type")
            or environment.get("after_measurement_error_type") or not environment.get("same_process")):
        errors.append("wrapper completion/process continuity failed")
    if summary.get("failure") or len(summary["results"]) != 4:
        errors.append("matrix did not complete all four contrasts")
    if not summary["cleanup"].get("completed") or not summary["cleanup"].get("settings_restored"):
        errors.append("cleanup/settings restoration failed")

    for name, tts, chime in matrix.CASES:
        folder = OUT / name
        capture = folder / "capture"
        metadata = json.loads((capture / "metadata.json").read_text())
        manifest = json.loads((capture / "packet_manifest.json").read_text())
        analysis = json.loads((folder / "analysis.json").read_text())
        pcm = (capture / "audio.s16le.pcm").read_bytes()
        parts = [(capture / row["raw_file"]).read_bytes() for row in manifest]
        if b"".join(parts) != pcm or hashlib.sha256(pcm).hexdigest() != metadata["pcm_sha256"]:
            errors.append(name + " raw packet/PCM/hash mismatch")
        with wave.open(str(capture / "audio.wav"), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (2, 2, 48000):
                errors.append(name + " WAV format mismatch")
            if wav.readframes(wav.getnframes()) != pcm:
                errors.append(name + " WAV payload mismatch")
        if len(parts) != metadata["packet_count"] or len(parts) != len(metadata["packets"]):
            errors.append(name + " packet count mismatch")
        offset, measurements = 0, []
        trigger = json.loads((folder / "trigger.json").read_text())
        trigger_s = trigger["started"]["host_unix_ns"] / 1e9
        for row, original, payload in zip(manifest, metadata["packets"], parts):
            if (row["pcm_byte_offset"] != offset or row["bytes"] != len(payload)
                    or len(payload) % 4 or any(row[k] != original[k] for k in ("index", "bytes", "frames", "timestamp_us"))):
                errors.append(name + " packet layout mismatch")
            samples = array.array("h", payload)
            if sys.byteorder != "little":
                samples.byteswap()
            peak = max(map(abs, samples), default=0)
            squares = sum(v*v for v in samples)
            relative = row["timestamp_us"] / 1e6 - trigger_s
            if (peak != row["peak_absolute_s16"] or squares != row["sum_squares"]
                    or len(samples) != row["sample_count"]
                    or not math.isclose(relative, row["relative_to_command_start_seconds"], abs_tol=1e-9)):
                errors.append(name + " packet metric mismatch")
            measurements.append((relative, len(samples), squares, peak))
            offset += len(payload)
        for key, bounds in (("pre_event", (-3, 0)), ("response_0_to_8s", (0, 8)), ("late_8_to_12s", (8, 12))):
            selected = [row for row in measurements if bounds[0] <= row[0] < bounds[1]]
            count = sum(row[1] for row in selected)
            peak = max((row[3] for row in selected), default=None)
            rms = math.sqrt(sum(row[2] for row in selected)/count) if count else None
            saved = analysis["windows"][key]
            if (saved["packet_count"] != len(selected) or saved["peak_absolute_s16"] != peak
                    or not math.isclose(saved["captured_pcm_seconds"], count/96000, abs_tol=1e-12)
                    or rms is None or not math.isclose(saved["rms_s16_over_received_samples"], rms, abs_tol=1e-10)):
                errors.append(name + " time-window summary mismatch")
        if metadata["capture_status"] != "CAPTURED":
            errors.append(name + " capture did not complete")
        state = matrix.switch_states(ET.fromstring((folder / "settings_confirmed.xml").read_text()))
        if state["tts"]["checked"] != tts or state["chime"]["checked"] != chime:
            errors.append(name + " settings XML mismatch")
        logs = (folder / "app_logcat.txt").read_text()
        lifecycle = {key: len(re.findall(r"\b" + key + r"\b", logs))
                     for key in ("TTS_START", "TTS_DONE", "TTS_ERROR")}
        cases.append({"case": name, "packets": len(parts), "pcm_sha256": digest(capture / "audio.s16le.pcm"),
                      "response": analysis["windows"]["response_0_to_8s"], "lifecycle": lifecycle})

    original = matrix.switch_states(ET.fromstring((OUT / "original_settings.xml").read_text()))
    restored = matrix.switch_states(ET.fromstring((OUT / "restored_settings_confirmed.xml").read_text()))
    if any(original[key]["checked"] != restored[key]["checked"] for key in matrix.LABELS):
        errors.append("original/restored settings XML mismatch")
    texts = [n.get("text", "") for n in ET.fromstring((OUT / "final_ui.xml").read_text()).iter("node")]
    if not all(text in texts for text in ("P", "0 km/h", "가드 종료", "프리셋 고르기")):
        errors.append("final UI does not establish idle selection/P0/end")
    by_name = {case["case"]: case for case in cases}
    off_peak = max(by_name[name]["response"]["peak_absolute_s16"]
                   for name in ("tts_off_chime_off", "both_off_chime_control"))
    for name in ("tts_on_chime_off", "tts_off_chime_on"):
        if by_name[name]["response"]["peak_absolute_s16"] <= off_peak:
            errors.append(name + " no output contrast above OFF controls")
    tts_events = by_name["tts_on_chime_off"]["lifecycle"]
    if tts_events != {"TTS_START": 1, "TTS_DONE": 1, "TTS_ERROR": 0}:
        errors.append("TTS-only lifecycle is not one completed/error-free filtered-log utterance")
    write("verification-runtime06.json", {
        "verdict": "PASS_WITH_SCOPE_LIMITATIONS" if not errors else "FAIL",
        "mode": "Offline raw packet/PCM/WAV/hash/time-window recomputation; settings use existing XML parser.",
        "expected_apk_sha256": EXPECTED, "cases": cases, "errors": errors, "limits": LIMITS,
        "independent_reviewer": "Not claimed; an independent reviewer can inspect this output and raw files.",
    })
    write("artifact-sha256-runtime06.json", {
        str(path.relative_to(OUT)): digest(path) for path in sorted(OUT.rglob("*"))
        if path.is_file() and path.name != "artifact-sha256-runtime06.json" and not path.name.startswith(".grpc-")
    })
    print(json.dumps({"matrix": "matrix_03", "offline_passed": not errors, "error_count": len(errors)}))
    return int(bool(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--run", action="store_true")
    mode.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        return run() if args.run else verify_only()
    except BaseException as error:
        print(json.dumps({"matrix": "matrix_03", "passed": False, "error_type": type(error).__name__,
                          "detail": "Stopped; inspect local evidence. Arbitrary exception text suppressed."}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
