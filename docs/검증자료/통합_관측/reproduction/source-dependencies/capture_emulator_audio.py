#!/usr/bin/env python3
"""Bounded capture of emulator output only; never starts or configures an AVD.

Uses the installed grpc/protobuf modules without protoc or generated stubs.
Wire fields mirror emulator/lib/emulator_controller.proto AudioFormat/AudioPacket.
Authentication format was checked with the installed emulator -help-grpc-use-token.
"""

import argparse
import array
import datetime as dt
import hashlib
import ipaddress
import json
import math
from pathlib import Path
import re
import sys
import threading
import time
import wave

import grpc
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory


RPC_PATH = "/android.emulation.control.EmulatorController/streamAudio"
RATE = 48_000
CHANNELS = 2
FRAME_BYTES = 4
MAX_SECONDS = 300
MAX_PACKET_BYTES = 1_048_576


def message_classes():
    """The exact field numbers/types/enums from the installed emulator proto."""
    file = descriptor_pb2.FileDescriptorProto(
        name="emulator_audio_capture.proto", package="android.emulation.control", syntax="proto3"
    )
    fmt = file.message_type.add(name="AudioFormat")
    for name, values in (
        ("SampleFormat", (("AUD_FMT_U8", 0), ("AUD_FMT_S16", 1))),
        ("Channels", (("Mono", 0), ("Stereo", 1))),
        ("DeliveryMode", (("MODE_UNSPECIFIED", 0), ("MODE_REAL_TIME", 1))),
    ):
        enum = fmt.enum_type.add(name=name)
        for value, number in values:
            enum.value.add(name=value, number=number)
    for name, number, kind, type_name in (
        ("samplingRate", 1, 4, None),
        ("channels", 2, 14, ".android.emulation.control.AudioFormat.Channels"),
        ("format", 3, 14, ".android.emulation.control.AudioFormat.SampleFormat"),
        ("mode", 4, 14, ".android.emulation.control.AudioFormat.DeliveryMode"),
    ):
        field = fmt.field.add(name=name, number=number, label=1, type=kind)
        if type_name:
            field.type_name = type_name
    packet = file.message_type.add(name="AudioPacket")
    packet.field.add(name="format", number=1, label=1, type=11,
                     type_name=".android.emulation.control.AudioFormat")
    packet.field.add(name="timestamp", number=2, label=1, type=4)
    packet.field.add(name="audio", number=3, label=1, type=12)
    pool = descriptor_pool.DescriptorPool()
    pool.Add(file)
    return tuple(message_factory.GetMessageClass(pool.FindMessageTypeByName(
        "android.emulation.control." + name)) for name in ("AudioFormat", "AudioPacket"))


AudioFormat, AudioPacket = message_classes()


def loopback_endpoint(value):
    """Reject resolver URIs and remote hosts; localhost is pinned to IPv4 loopback."""
    match = re.fullmatch(r"\[([^\]]+)\]:(\d+)", value)
    if match:
        host, port_text = match.groups()
    elif value.count(":") == 1:
        host, port_text = value.split(":")
    else:
        raise argparse.ArgumentTypeError("endpoint must be loopback-host:port")
    if host == "localhost":
        host = "127.0.0.1"
    try:
        address = ipaddress.ip_address(host)
        if not address.is_loopback or "%" in host:
            raise ValueError
        if not re.fullmatch(r"[0-9]+", port_text):
            raise ValueError
        port = int(port_text)
        if not 1 <= port <= 65535:
            raise ValueError
    except ValueError:
        raise argparse.ArgumentTypeError("endpoint must use a literal loopback address and port 1..65535") from None
    return f"[{address}]:{port}" if address.version == 6 else f"{address}:{port}"


def bounded_seconds(value):
    try:
        seconds = float(value)
        if not math.isfinite(seconds) or not 0 < seconds <= MAX_SECONDS:
            raise ValueError
        return seconds
    except ValueError:
        raise argparse.ArgumentTypeError(f"seconds must be finite, greater than 0, and at most {MAX_SECONDS}") from None


def authorization(token_file):
    if token_file is None:
        return ()
    try:
        with Path(token_file).open("rb") as handle:
            data = handle.read(65_537)
        token = data.decode("ascii").strip()
        if len(data) > 65_536 or not token or any(not 33 <= ord(c) <= 126 for c in token):
            raise ValueError
    except (OSError, UnicodeError, ValueError):
        raise ValueError("token file must contain one raw ASCII token (at most 64 KiB); its contents are never logged") from None
    return (("authorization", "Bearer " + token),)


class InvalidPacket(Exception):
    pass


def sample_stats(payload):
    if len(payload) % FRAME_BYTES:
        raise InvalidPacket("payload is not aligned to signed-16 stereo frames")
    samples = array.array("h")
    samples.frombytes(payload)
    if sys.byteorder != "little":
        samples.byteswap()
    sums = [sum(s * s for s in samples[channel::CHANNELS]) for channel in range(CHANNELS)]
    return len(samples), sums, max((abs(s) for s in samples), default=0)


def capture(endpoint, seconds, out, token_file=None):
    """Return (metadata, exit_code). Does not issue any RPC except streamAudio."""
    endpoint = loopback_endpoint(endpoint)
    seconds = bounded_seconds(str(seconds))
    headers = authorization(token_file)
    out = Path(out)
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    started = time.monotonic()
    ends = started + seconds
    metadata = {
        "schema_version": 1,
        "source": "emulator_output_streamAudio_only",
        "rpc": RPC_PATH,
        "endpoint": endpoint,
        "bearer_token_supplied": bool(headers),
        "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "requested_seconds": seconds,
        "format": {"encoding": "signed_16_bit_little_endian", "sample_rate_hz": RATE,
                   "channels": CHANNELS, "frame_bytes": FRAME_BYTES},
        "files": {"pcm": "audio.s16le.pcm", "wav": "audio.wav", "metadata": "metadata.json"},
        "connection_status": "NOT_READY",
        "rpc_status": "NOT_STARTED",
        "stop_reason": None,
        "packet_count": 0,
        "validated_packet_count": 0,
        "empty_packet_count": 0,
        "packets": [],
        "bytes_written": 0,
        "frame_count": 0,
        "sample_count": 0,
        "first_packet_timestamp_us": None,
        "last_packet_timestamp_us": None,
        "environment_probed": False,
        "interpretation": "PCM is emulator output, not proof of a physical speaker or a particular app. "
                          "No packets alone never prove silence; check capture transport, -no-audio, routes and engine readiness.",
    }
    channel = None
    call = None
    timer = None
    deadline_fired = threading.Event()
    sums = [0, 0]
    peak = 0
    digest = hashlib.sha256()
    exit_code = 0
    try:
        with (out / "audio.s16le.pcm").open("xb") as pcm, wave.open(str(out / "audio.wav"), "wb") as wav:
            wav.setnchannels(CHANNELS)
            wav.setsampwidth(2)
            wav.setframerate(RATE)
            channel = grpc.insecure_channel(endpoint, options=[("grpc.max_receive_message_length", MAX_PACKET_BYTES)])
            ready = grpc.channel_ready_future(channel)
            try:
                ready.result(timeout=max(0.001, min(5.0, ends - time.monotonic())))
            except grpc.FutureTimeoutError:
                ready.cancel()
                metadata.update(connection_status="CONNECTION_TIMEOUT", stop_reason="connection_timeout")
                exit_code = 3
            else:
                metadata["connection_status"] = "READY"
                remaining = ends - time.monotonic()
                if remaining <= 0:
                    metadata["stop_reason"] = "duration_limit_before_rpc"
                else:
                    rpc = channel.unary_stream(RPC_PATH, request_serializer=AudioFormat.SerializeToString,
                                               response_deserializer=AudioPacket.FromString)
                    call = rpc(AudioFormat(samplingRate=RATE, channels=1, format=1),
                               timeout=remaining, metadata=headers)
                    metadata["rpc_status"] = "STARTED"

                    def stop_at_deadline():
                        deadline_fired.set()
                        call.cancel()

                    timer = threading.Timer(remaining, stop_at_deadline)
                    timer.daemon = True
                    timer.start()
                    for packet in call:
                        if time.monotonic() >= ends:
                            stop_at_deadline()
                            metadata["stop_reason"] = "duration_limit"
                            break
                        metadata["packet_count"] += 1
                        if not packet.HasField("format") or (packet.format.samplingRate, packet.format.channels,
                                                               packet.format.format) != (RATE, 1, 1):
                            raise InvalidPacket("packet format differs from requested 48000 Hz signed-16 stereo")
                        if (metadata["last_packet_timestamp_us"] is not None
                                and packet.timestamp < metadata["last_packet_timestamp_us"]):
                            raise InvalidPacket("packet timestamp regressed")
                        count, packet_sums, packet_peak = sample_stats(packet.audio)
                        if metadata["bytes_written"] + len(packet.audio) > RATE * FRAME_BYTES * (seconds + 1):
                            raise InvalidPacket("PCM exceeds requested duration plus one-second buffer allowance")
                        pcm.write(packet.audio)
                        wav.writeframesraw(packet.audio)
                        digest.update(packet.audio)
                        metadata["validated_packet_count"] += 1
                        metadata["empty_packet_count"] += int(not packet.audio)
                        metadata["bytes_written"] += len(packet.audio)
                        metadata["sample_count"] += count
                        if metadata["first_packet_timestamp_us"] is None:
                            metadata["first_packet_timestamp_us"] = packet.timestamp
                        metadata["last_packet_timestamp_us"] = packet.timestamp
                        metadata["packets"].append({"index": metadata["packet_count"],
                                                    "timestamp_us": packet.timestamp,
                                                    "host_elapsed_seconds": round(time.monotonic() - started, 6),
                                                    "bytes": len(packet.audio), "frames": count // CHANNELS})
                        sums = [a + b for a, b in zip(sums, packet_sums)]
                        peak = max(peak, packet_peak)
                    metadata["rpc_status"] = call.code().name
                    if metadata["stop_reason"] is None:
                        metadata["stop_reason"] = "server_closed"
    except grpc.RpcError as error:
        # Never serialize server error details: they can echo authorization data.
        metadata["rpc_status"] = error.code().name
        if error.code() == grpc.StatusCode.DEADLINE_EXCEEDED or (
            error.code() == grpc.StatusCode.CANCELLED and deadline_fired.is_set()
        ):
            metadata["stop_reason"] = "duration_limit"
        else:
            metadata["stop_reason"] = "rpc_error"
            exit_code = 3
    except InvalidPacket as error:
        metadata["stop_reason"] = "invalid_packet"
        metadata["validation_error"] = str(error)  # Only fixed, locally generated messages.
        exit_code = 4
    except (OSError, ValueError):
        metadata["stop_reason"] = "local_io_or_decode_error"
        exit_code = 4
    finally:
        if timer:
            timer.cancel()
        if call:
            call.cancel()
            if metadata["rpc_status"] == "STARTED":
                metadata["rpc_status"] = "CANCELLED"
        if channel:
            channel.close()
        metadata["wall_seconds"] = round(time.monotonic() - started, 6)
        metadata["frame_count"] = metadata["sample_count"] // CHANNELS
        frames = metadata["frame_count"]
        metadata["pcm_duration_seconds"] = frames / RATE
        metadata["rms_normalized"] = math.sqrt(sum(sums) / (frames * CHANNELS)) / 32768 if frames else None
        metadata["channel_rms_normalized"] = [math.sqrt(s / frames) / 32768 for s in sums] if frames else [None, None]
        metadata["peak_absolute_s16"] = peak if frames else None
        metadata["pcm_sha256"] = digest.hexdigest()
        metadata["audio_observation"] = "NO_PCM_SAMPLES" if not frames else "ZERO_PCM" if peak == 0 else "NONZERO_PCM"
        metadata["capture_status"] = "ERROR" if exit_code else "INCONCLUSIVE_NO_PCM" if not frames else "CAPTURED"
        if not exit_code and not frames:
            exit_code = 2
        metadata["exit_code"] = exit_code
        with (out / "metadata.json").open("x", encoding="utf-8") as handle:
            json.dump(metadata, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    return metadata, exit_code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True, type=loopback_endpoint)
    parser.add_argument("--seconds", required=True, type=bounded_seconds)
    parser.add_argument("--out", required=True, type=Path, help="new output directory; existing paths are refused")
    parser.add_argument("--token-file", type=Path, help="file containing only a raw token; never logged")
    args = parser.parse_args(argv)
    try:
        result, code = capture(args.endpoint, args.seconds, args.out, args.token_file)
    except FileExistsError:
        parser.error("output directory already exists; choose a new path")
    except (OSError, ValueError):
        parser.error("cannot prepare output or token file; no token contents were logged")
    print(json.dumps({"capture_status": result["capture_status"], "audio_observation": result["audio_observation"],
                      "rpc_status": result["rpc_status"], "packet_count": result["packet_count"],
                      "bytes_written": result["bytes_written"], "exit_code": code}))
    return code


if __name__ == "__main__":
    sys.exit(main())
