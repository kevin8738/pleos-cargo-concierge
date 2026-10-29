#!/usr/bin/env python3
"""One predeclared v24 observation, not a retry loop. Import/--self-test do not use ADB.

Runtime requires a separate root handoff and --run with the frozen source hash.
One 180 s emulator-output RPC; COFFEE UI start; native route/background; unchanged
synthetic boundary profile; native FREE_DRIVE; P0/synthetic ARRIVED; DONE/tail.
Mixed PCM and VIP callbacks never establish native speech or focus contention.
"""
import argparse
import array
import ast
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import shlex
import subprocess
import sys
import threading
import time
import wave
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
BASE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASE.parent / 'submission-v23'))
import driving_audio_probe as d

q, rt, matrix = d.qb, d.runtime, d.matrix
EXPECTED = d.EXPECTED
CAPTURE_SECONDS = 180
PROFILE = [
    ('baseline', [(0, 0)]*12),
    ('acceleration', [(n*.9, 0) for n in range(1, 21)]),
    ('settle', [(18, 0)]*12), ('ok60', [(18, 60)]*8),
    ('below90', [(18, 90)]*8), ('warn100', [(18, 100)]*20),
    ('over120', [(18, 120)]*20), ('recover60', [(18, 60)]*12),
    ('straight', [(18, 0)]*4),
    ('deceleration', [(n*.9, 0) for n in range(19, -1, -1)]),
    ('stop', [(0, 0)]*8),
]
LIMITS = [
    'DEMO source 0 SYNTHETIC input can wake the background process; no no-wakeup claim.',
    'Visibility is sampled, never inferred continuous between dumpsys observations.',
    'COFFEE applies zero cabin settings; no HVAC application/restoration efficacy claim.',
    'FREE_DRIVE is real route termination; DestinationArrived is separately injected at P0.',
    'VIP speech callbacks have no utterance ID; overlapping requests make attribution ambiguous.',
    'PCM is mixed emulator output; native speech/content, actual overlap and focus priority remain unverified.',
    'Sparse PCM/WAV concatenates received frames, not 180 seconds of continuous recorded sound.',
    'The reused motion-only admission oracle excludes native-triggered proposals; extra native coaching can cause mismatch without proving an app defect.',
    'No physical speaker, intelligibility, driver burden, cargo protection or G accuracy validation.',
]
TAGS = ('MotionTrace', 'GuardRuntime')
OUT = None


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dependencies():
    paths = [Path(__file__), BASE/'console_lease.py', Path(d.__file__), Path(q.__file__),
             Path(rt.__file__), Path(rt.qa.__file__), Path(matrix.__file__),
             d.ROOT/'.omx/qa/audio-feasibility/capture_emulator_audio.py']
    return {str(p.relative_to(d.ROOT)): digest(p) for p in paths}


def contract():
    return {'apkSha256': EXPECTED, 'preset': 'COFFEE', 'limitG': .12,
            'captureSeconds': CAPTURE_SECONDS, 'captureCalls': 1, 'runtimeAttempts': 1,
            'profileSeconds': sum(len(v) for _, v in PROFILE)*.5,
            'stagesSeconds': {'firstPcm': 5, 'baseline': 3, 'routeActivation': 20,
                             'profile': 76, 'routeEnd': 10, 'returnAndPark': 10,
                             'arrivalCallbacks': 10, 'tail': 3},
            'settledLateralG': {str(a): 25*math.tan(math.radians(a/15))/2.9000000953674316/9.80665
                                for a in (60, 90, 100, 120)},
            'profile': [{'phase': p, 'steps': len(v), 'seconds': len(v)*.5,
                         'kphAndSteeringDegrees': v} for p, v in PROFILE],
            'restore': ['native route off', 'original tts/chime/demo_curve readback',
                        'original log properties including empty value', 'Idle/P0',
                        'same APK/PID', 'zero service/notification', 'unchanged cabin/controls',
                        'owned child/browser/forward stopped', 'private token deleted'],
            'limits': LIMITS}


def safe_error(error):
    # Do not serialize command output/credentials/host home paths on exceptional paths.
    if isinstance(error, (subprocess.SubprocessError, OSError)):
        return type(error).__name__
    return str(error).replace(str(Path.home()), '<LOCAL_HOME>')


def live(tag=None, after=0):
    return [r for r in list(d.LIVE) if r.get('hostReceivedUnixNs', 0) >= after
            and (tag is None or r.get('tag') == tag)]


def wait_for(predicate, seconds, description, check=lambda: None):
    deadline = time.monotonic()+seconds
    while True:
        if time.monotonic() >= deadline:
            raise RuntimeError('deadline: '+description)
        check()
        value = predicate()
        if time.monotonic() >= deadline:
            raise RuntimeError('deadline: '+description)
        if value:
            return value
        time.sleep(.05)


def current_parked(row, session_started, after_host_ns, now_ns):
    data = row.get('data', {})
    return (data.get('state') == 'GUARDING' and data.get('trip', {}).get('startedMs') == session_started
            and after_host_ns <= row.get('hostReceivedUnixNs', 0) <= now_ns
            and now_ns-row.get('hostReceivedUnixNs', 0) <= 2_000_000_000
            and data.get('snapshot', {}).get('parkedStill') is True
            and data['snapshot'].get('gear') == 'PARK' and data['snapshot'].get('speedMps') == 0)


def ui_find(label, text, package, deadline):
    attempt = 0
    while time.monotonic() < deadline:
        remaining = deadline-time.monotonic()
        raw = q.adb('exec-out', 'uiautomator', 'dump', '/dev/tty', timeout=min(8, remaining))
        xml = raw[raw.index('<?xml'):raw.index('</hierarchy>')+len('</hierarchy>')]
        (OUT/f'{label}-{attempt}.xml').write_text(xml+'\n')
        node = rt.find(ET.fromstring(xml), text, package)
        if node is not None:
            if time.monotonic() > deadline:
                raise RuntimeError('UI observation exceeded deadline: '+label)
            return node
        attempt += 1
        time.sleep(min(.25, max(0, deadline-time.monotonic())))
    raise RuntimeError('UI deadline: '+label)


def launch(package):
    q.adb('shell', 'am', 'start', '-W', '-n', package+'/.MainActivity', timeout=8)


def require_background(label, pid):
    row = q.surface(label)
    if (row['pid'] != pid or row['vipActivityState'] != 'STOPPED' or row['vipVisible'] is not False
            or not any(rt.NAVI in s for s in row['focus'])):
        raise RuntimeError('VIP background/native foreground not observed: '+label)
    return row


def vehicle_values(state):
    fields = {'hvac': ['power_on', 'auto_on', 'ac_on', 'dual_on', 'sync_on', 'recirc_on',
                      'front_defroster_on', 'rear_defroster_on', 'fan_speed', 'auto_blower_level',
                      'driver', 'passenger', 'row2'],
              'seat': ['positions', 'moves', 'occupied'],
              'body': ['doors', 'door_locks', 'windows', 'closures', 'wipers', 'cabin_light',
                       'reading_lights', 'ambient_light'],
              'controls': ['speed_kph', 'accelerator_pct', 'brake_pct', 'steering_deg']}
    return {domain: {key: state[domain][key] for key in keys} for domain, keys in fields.items()}


class Producer:
    """Existing 0.5 s fixture, with P0 holds while UI work proceeds independently."""
    def __init__(self):
        self.stop = threading.Event()
        self.begin = threading.Event()
        self.done = threading.Event()
        self.phase = 'configuration'
        self.error = None
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        deadline = time.monotonic()
        step = 0

        def sample(kph, angle, phase, index):
            nonlocal deadline
            if self.stop.wait(max(0, deadline-time.monotonic())):
                return False
            if time.monotonic()-deadline > 1:
                raise RuntimeError('producer late over one second')
            q.motion(kph, angle, phase, index)
            deadline += .5
            return True
        try:
            q.event('phase_start', phase='configuration')
            while not self.begin.is_set():
                if not sample(0, 0, 'configuration', step):
                    return
                step += 1
            for phase, values in PROFILE:
                self.phase = phase
                q.event('phase_start', phase=phase)
                if phase in ('warn100', 'over120', 'recover60'):
                    print(json.dumps({'stage': phase}), flush=True)
                for step, (kph, angle) in enumerate(values):
                    if not sample(kph, angle, phase, step):
                        return
            self.stop.wait(max(0, deadline-time.monotonic()))
            self.phase = 'park'
            q.event('phase_start', phase='park')
            self.done.set()
            step = 0
            while sample(0, 0, 'park', step):
                step += 1
        except BaseException as error:
            self.error = safe_error(error)
            q.event('producer_error', error=self.error)

    def check(self):
        if self.error:
            raise RuntimeError(self.error)

    def close(self, propagate=True):
        self.stop.set()
        self.thread.join(timeout=20)
        if self.thread.is_alive():
            raise RuntimeError('producer survives cleanup; no concurrent recovery writes allowed')
        if propagate:
            self.check()


def capture_finish(pair, start_mono):
    process, token = pair
    deadline = start_mono+CAPTURE_SECONDS+8
    try:
        next_progress = time.monotonic()
        while process.poll() is None and time.monotonic() < deadline:
            if time.monotonic() >= next_progress:
                print(json.dumps({'stage': 'capture-tail', 'remainingSeconds': round(deadline-time.monotonic(), 1)}), flush=True)
                next_progress = time.monotonic()+30
            time.sleep(min(.25, max(0, deadline-time.monotonic())))
        if process.poll() is None:
            raise RuntimeError('180 s capture exceeded shutdown allowance')
        _, stderr = process.communicate(timeout=2)
        q.save('capture-process.json', {'exitCode': process.returncode,
                                     'stderrPresentSuppressed': bool(stderr), 'singleRpc': True})
        if process.returncode:
            raise RuntimeError('capture transport failed; safe metadata retained')
    finally:
        d.capture_abort(pair)
        token.unlink(missing_ok=True)


def clock_model(start, end):
    low = max(c['deviceMinusHostNs']-c['uncertaintyNs'] for c in (start, end))
    high = min(c['deviceMinusHostNs']+c['uncertaintyNs'] for c in (start, end))
    # Intersection is a consistency test, not proof that no between-sample drift occurred.
    return {'consistent': low <= high, 'deviceMinusHostNs': (low+high)/2,
            'uncertaintyNs': max(c['uncertaintyNs'] for c in (start, end)),
            'calibrationIntersectionNs': [low, high], 'start': start, 'end': end,
            'assumption': 'bounded stable offset between two measured endpoints; not continuous clock calibration'}


def packet_index(metadata, pcm):
    parts, cursor, previous = [], 0, None
    for packet in metadata['packets']:
        size, stamp = packet['bytes'], packet['timestamp_us']/1e6
        if size <= 0 or size % 4 or (previous is not None and stamp < previous):
            raise ValueError('invalid nonempty S16LE stereo packet or nonmonotonic timestamp')
        data = pcm[cursor:cursor+size]
        if len(data) != size:
            raise ValueError('truncated PCM packet')
        values = array.array('h')
        values.frombytes(data)
        if sys.byteorder != 'little':
            values.byteswap()
        parts.append({**packet, 'pcmByteOffset': cursor, 'sampleCount': len(values),
                      'sumSquares': sum(v*v for v in values), 'peakS16': max(map(abs, values))})
        cursor += size
        previous = stamp
    if cursor != len(pcm) or not parts:
        raise ValueError('unindexed PCM or no received packets')
    return parts


def window_stats(parts, start, end):
    selected = [p for p in parts if start <= p['timestamp_us']/1e6 < end]
    count = sum(p['sampleCount'] for p in selected)
    covered = bool(parts and parts[0]['timestamp_us']/1e6 <= start
                   and parts[-1]['timestamp_us']/1e6 >= end and count > 0)
    return {'hostEpochWindow': [start, end], 'packetCount': len(selected), 'sampleCount': count,
            'receivedPcmSeconds': count/96000, 'coveredWithReceivedSamples': covered,
            'rmsS16': math.sqrt(sum(p['sumSquares'] for p in selected)/count) if count else None,
            'peakS16': max((p['peakS16'] for p in selected), default=None),
            'maxInternalPacketGapSeconds': max((b['timestamp_us']/1e6-a['timestamp_us']/1e6
                                                for a, b in zip(selected, selected[1:])), default=None)}


def callback_order(rows):
    starts = [i for i, r in enumerate(rows) if r.get('message') == 'TTS_START']
    dones = [i for i, r in enumerate(rows) if r.get('message') == 'TTS_DONE']
    valid = len(starts) == len(dones) == 1 and starts[0] < dones[0]
    interrupted = valid and any(r.get('message', '').startswith(('TTS_STOP', 'TTS_ERROR'))
                               for r in rows[starts[0]+1:dones[0]])
    error = any(r.get('message', '').startswith('TTS_ERROR') for r in rows)
    return {'singleStartThenDone': bool(valid and not interrupted and not error),
            'interruptedAfterStart': bool(interrupted), 'error': error,
            'events': [{k: r[k] for k in ('message', 'deviceEpochSeconds', 'hostReceivedUnixNs') if k in r} for r in rows]}


def analyze(folder, execution_errors):
    env = json.loads((folder/'environment.json').read_text())
    rows = [json.loads(s) for s in (folder/'live-log.jsonl').read_text().splitlines()]
    ledger = [json.loads(s) for s in (folder/'input-ledger.jsonl').read_text().splitlines()]
    meta = json.loads((folder/'capture/metadata.json').read_text())
    pcm = (folder/'capture/audio.s16le.pcm').read_bytes()
    with wave.open(str(folder/'capture/audio.wav'), 'rb') as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (2, 2, 48000) or wav.readframes(wav.getnframes()) != pcm:
            raise RuntimeError('PCM/WAV format or byte equality failed')
    if (meta['capture_status'] != 'CAPTURED' or meta['connection_status'] != 'READY'
            or meta['requested_seconds'] != CAPTURE_SECONDS or meta['stop_reason'] != 'duration_limit'
            or meta['pcm_sha256'] != hashlib.sha256(pcm).hexdigest()):
        raise RuntimeError('capture completion/hash contract failed')
    parts = packet_index(meta, pcm)
    q.save('capture/packet-analysis.json', parts)
    clock = clock_model(env['clockCalibration'], json.loads((folder/'clock-end.json').read_text()))
    offset, uncertainty = clock['deviceMinusHostNs']/1e9, clock['uncertaintyNs']/1e9
    received_lags = [datetime.datetime.fromisoformat(meta['started_utc']).timestamp()+p['host_elapsed_seconds']-p['timestamp_us']/1e6 for p in parts]
    def host(row):
        return row['deviceEpochSeconds']-offset
    def event(name):
        selected = [e for e in ledger if e['event'] == name]
        if len(selected) != 1:
            raise RuntimeError('expected one marker: '+name)
        return selected[0]
    route_start, route_end = event('native_route_request'), event('native_route_ended')
    arrival_request = event('synthetic_arrival_request')
    guards = [r for r in rows if r.get('tag') == 'MotionTrace' and r.get('data', {}).get('state') == 'GUARDING']
    arrivals = [r for r in rows if r.get('tag') == 'MotionTrace' and r.get('data', {}).get('event') == 'arrival']
    sessions = {r['data']['trip']['startedMs'] for r in guards}
    runtime = [r for r in rows if r.get('tag') == 'GuardRuntime' and r.get('data', {}).get('guarding')]
    runtime_ids = {(r['data'].get('requestId'), r['data'].get('startedMs')) for r in runtime}
    session = env['session']
    same_session = (sessions == {session['startedMs']} and len(arrivals) == 1
                    and runtime_ids == {(session['requestId'], session['startedMs'])})
    admissions, prior = [], {'drivingSpeechCount': 0, 'drivingChimeCount': 0}
    for r in guards:
        trace = r['data']
        for key, kind in [('drivingSpeechCount', 'speech'), ('drivingChimeCount', 'chime')]:
            value = trace['trip'][key]
            if value > prior[key]:
                if value != prior[key]+1:
                    raise RuntimeError('admission jump; missing trace or additional SDK-triggered requests')
                admissions.append({'kind': kind, 'tMs': trace['tMs'], 'badge': trace['badge'],
                                   'when': host(r), 'row': r})
            prior[key] = value
    windows = []
    for index, a in enumerate(admissions):
        when, duration = a['when'], 7.8 if a['kind'] == 'speech' else 2
        life = [r for r in rows if r.get('tag') == 'PleosSpeech' and when-uncertainty <= host(r) < when+duration+uncertainty]
        requests = [r for r in rows if r.get('tag') == 'CargoVM' and r.get('message', '').startswith('TTS:')
                    and when-.05 <= host(r) < when+duration]
        windows.append({'label': 'driving-'+str(index), 'kind': a['kind'], 'badge': a['badge'], 'tMs': a['tMs'],
                        'pre': window_stats(parts, when-3-uncertainty, when-.1-uncertainty),
                        'response': window_stats(parts, when-uncertainty, when+duration+uncertainty),
                        'lifecycle': callback_order(life), 'ttsRequestCount': len(requests),
                        'requestAttributionUnambiguous': len(requests) == 1 if a['kind'] == 'speech' else None})
    arrival_start = arrival_request['hostUnixNs']/1e9
    arrival_life = [r for r in rows if r.get('tag') == 'PleosSpeech' and arrival_start-uncertainty <= host(r) < arrival_start+10+uncertainty]
    arrival_order = callback_order(arrival_life)
    done = next((host(r) for r in arrival_life if r.get('message') == 'TTS_DONE'), None)
    arrival_requests = [r for r in rows if r.get('tag') == 'CargoVM' and r.get('message', '').startswith('TTS:')
                        and arrival_start-uncertainty <= host(r) < arrival_start+10+uncertainty]
    arrival_window = {'label': 'arrival', 'lifecycle': arrival_order, 'ttsRequestCount': len(arrival_requests),
                      'pre': window_stats(parts, arrival_start-3, arrival_start-.1),
                      'response': window_stats(parts, arrival_start-uncertainty, (done or arrival_start+10)+uncertainty),
                      'tail': window_stats(parts, (done or arrival_start+10)+uncertainty,
                                           (done or arrival_start+10)+uncertainty+3)}
    observed = [e for e in ledger if e['event'].startswith('background-sample-')]
    def bracketed_by_background_samples(when):
        previous = [e['hostUnixNs']/1e9 for e in observed if e['hostUnixNs']/1e9 <= when]
        following = [e['hostUnixNs']/1e9 for e in observed if e['hostUnixNs']/1e9 >= when]
        return bool(previous and following and min(following)-max(previous) <= 5)
    tick_rows = [r for r in runtime if r['data'].get('event') == 'tick'
                 and event('profile_begin')['hostUnixNs'] <= r['hostReceivedUnixNs'] <= event('profile_finished')['hostUnixNs']]
    tick_times = [host(r) for r in tick_rows]
    callback_rows = [r for r in rows if r.get('tag') == 'PleosSpeech'
                     and route_start['hostUnixNs']/1e9 <= host(r) <= (done or arrival_start+10)+uncertainty]
    callback_lags = [r['hostReceivedUnixNs']/1e9-host(r) for r in callback_rows]
    cabin_counts = [r['message'] for r in rows if r.get('tag') == 'CargoVM' and r.get('message', '').startswith('캐빈 적용:')]
    active = next((r for r in rows if r.get('tag') == 'PleosNavi'
                   and r.get('message') == 'SDK callback navigation-status=ROUTE_DRIVE'
                   and route_start['hostUnixNs'] <= r['hostReceivedUnixNs'] <= event('profile_begin')['hostUnixNs']), None)
    free = next((r for r in rows if r.get('tag') == 'PleosNavi'
                 and r.get('message') == 'SDK callback navigation-status=FREE_DRIVE'
                 and event('native_route_end_request')['hostUnixNs'] <= r['hostReceivedUnixNs'] <= arrival_request['hostUnixNs']), None)
    callbacks_in_device_window = all(r and -uncertainty <= r['hostReceivedUnixNs']/1e9-host(r) <= 1+uncertainty for r in (active, free))
    callbacks_in_device_window = bool(callbacks_in_device_window
        and route_start['hostUnixNs']/1e9-uncertainty <= host(active) <= event('profile_begin')['hostUnixNs']/1e9+uncertainty
        and event('native_route_end_request')['hostUnixNs']/1e9-uncertainty <= host(free) <= arrival_start+uncertainty)
    route_window = window_stats(parts, route_start['hostUnixNs']/1e9-3, route_end['hostUnixNs']/1e9)
    all_windows = [route_window, arrival_window['pre'], arrival_window['response'], arrival_window['tail']]
    all_windows += [w[k] for w in windows for k in ('pre', 'response')]
    oracle = d.analyze(folder)
    oracle['limits'][0] = 'Normal-motion arithmetic/admission oracle reused unchanged; native/lifecycle claims checked separately.'
    q.save('oracle-result.json', oracle)
    cleanup = json.loads((folder/'cleanup.json').read_text())
    checks = {
        'sameSession': same_session,
        'nativeRouteCallbacks': bool(active and free and callbacks_in_device_window),
        'vipBackgroundAtObservedSamples': bool(observed) and all(e['vipActivityState'] == 'STOPPED' and e['vipVisible'] is False
                         and e['pid'] == env['pid'] and any(rt.NAVI in s for s in e['focus']) for e in observed),
        'drivingAdmissionsBracketedByBackgroundSamples': bool(admissions) and all(bracketed_by_background_samples(a['when']) for a in admissions),
        'liveRuntimeTicks': len(tick_times) >= 60 and all(0 < b-a <= 3 for a, b in zip(tick_times, tick_times[1:]))
                             and all(-uncertainty <= r['hostReceivedUnixNs']/1e9-host(r) <= 1+uncertainty for r in tick_rows),
        'liveVipCallbacks': bool(callback_lags) and min(callback_lags) >= -uncertainty and max(callback_lags) <= 1+uncertainty,
        'zeroCabinApplication': cabin_counts == ['캐빈 적용: 0개 성공, 0개 미적용'],
        'motionOracle': oracle['passed'], 'clockEndpointsConsistent': clock['consistent'],
        'packetClockRelationship': min(received_lags) >= -.25 and max(received_lags) <= 1,
        'lifecycleCaptureCoverage': bool(windows) and all(w['coveredWithReceivedSamples'] for w in all_windows),
        'vipTtsLifecycle': any(w['kind'] == 'speech' for w in windows) and all(w['lifecycle']['singleStartThenDone'] and w['requestAttributionUnambiguous']
                                for w in windows if w['kind'] == 'speech') and arrival_order['singleStartThenDone'] and len(arrival_requests) == 1,
        'mixedPcmSignalInEachResponse': bool(windows) and all(w['response']['peakS16'] is not None and w['pre']['rmsS16'] is not None
                     and w['response']['peakS16'] > max(16, (w['pre']['peakS16'] or 0)*3)
                     and w['response']['rmsS16'] > max(3, w['pre']['rmsS16']*3) for w in windows+[arrival_window]),
        'parkedArrivalAndReport': len(arrivals) == 1 and arrivals[0]['data']['snapshot']['parkedStill'] is True
                                    and arrivals[0]['data']['report']['hasSyntheticInput'] is True and oracle['passed'],
        'cleanup': cleanup['verified'],
    }
    result = {'executionCompleted': not execution_errors, 'componentChecks': checks,
              'boundedObservationComplete': not execution_errors and all(checks.values()),
              'nativeSpeechAttributed': None, 'actualAcousticOverlap': None, 'audioFocusPriorityValidated': None,
              'strictNativeVipContention': 'UNVERIFIED', 'clock': clock, 'sameSessionRuntime': session,
              'motionTripStartedMs': sorted(sessions), 'admissions': windows, 'arrival': arrival_window,
              'routeWindow': route_window, 'packetCount': len(parts), 'pcmDurationSeconds': meta['pcm_duration_seconds'],
              'wallSeconds': meta['wall_seconds'], 'executionErrors': execution_errors, 'limits': LIMITS}
    q.save('integration-result.json', result)
    return result


def execute(folder):
    global OUT
    folder.mkdir(exist_ok=False)
    OUT = folder
    d.bind(folder)
    before = original = flags = logger = cap = producer = helper = None
    tags, errors, cleanup_errors, env = {}, [], [], {}
    mutated = route_requested = route_off = False
    background_stop = threading.Event()
    monitor = None
    monitor_errors = []
    q.save('design.json', contract())
    q.save('dependency-sha256.json', dependencies())
    (folder/'harness-source.py').write_bytes(Path(__file__).read_bytes())
    (folder/'console-lease-source.py').write_bytes((BASE/'console_lease.py').read_bytes())
    try:
        before = d.identity()
        initial = q.surface('initial-activity')
        if initial['vipActivityState'] != 'RESUMED' or initial['vipVisible'] is not True or initial['pid'] != before['pid']:
            raise RuntimeError('requires existing foreground VIP')
        ui_find('initial-idle', '프리셋 고르기', q.PKG, time.monotonic()+8)
        rt.require_stopped(rt.snapshot('initial-idle'))
        helper = subprocess.Popen([sys.executable, str(BASE/'console_lease.py'), '--state', str(folder/'console-lease.json')],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                  env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
        def helper_ready():
            if helper.poll() is not None:
                raise RuntimeError('own Console helper exited before ready')
            path = folder/'console-lease.json'
            return path.exists() and json.loads(path.read_text()).get('ready')
        wait_for(helper_ready, 25, 'Console heartbeat')
        original = q.api('/vehicle/state')
        q.save('vehicle-before.json', original)
        q.check_park(original)
        if any(original['controls'][k] != 0 for k in ('accelerator_pct', 'brake_pct', 'steering_deg')):
            raise RuntimeError('nonzero original controls')
        q.validate_policy_sources()
        mutated = True
        launch(rt.NAVI)
        ui_find('native-original-idle', '어디로 갈까요', rt.NAVI, time.monotonic()+8)
        route_off = True
        launch(q.PKG)
        ui_find('vip-return-idle', '프리셋 고르기', q.PKG, time.monotonic()+8)
        q.demo('settings', 'on')
        flags = matrix.switch_states(matrix.ui(folder/'settings-original.xml'))
        q.save('settings-original.json', flags)
        d.configure(False, False, 'settings-selection-muted')
        q.demo('settings', 'off')
        for tag in TAGS:
            tags[tag] = q.adb('shell', 'getprop', 'log.tag.'+tag).strip()
        env = {**before, 'traceStartElapsedMs': q.elapsed(), 'originalLogProperties': tags,
               'audioSettings': {'tts': True, 'chime': True}, 'sourceHashes': q.source_hashes(),
               'clockCalibration': rt.clock_sync(), 'limits': LIMITS}
        q.save('environment.json', env)
        for tag in TAGS:
            q.adb('shell', 'setprop', 'log.tag.'+tag, 'DEBUG')
        logger = d.log_start(before['pid'])
        coffee = ui_find('coffee-choice', '커피 0.12G', q.PKG, time.monotonic()+8)
        q.motion(0, 0, 'setup', 0)
        started = q.event('guard_ui_start')
        rt.tap(coffee, 'coffee_chip_tap')
        ready = wait_for(lambda: next((r for r in live('GuardRuntime', started['hostUnixNs']) if r.get('data', {}).get('event') == 'ready'), None), 5, 'guard ready')
        if not ready['data'].get('visible') or not ready['data'].get('guarding') or any(ready['data'].get(k) is None for k in ('requestId', 'startedMs')):
            raise RuntimeError('guard start contract')
        env['session'] = {k: ready['data'][k] for k in ('requestId', 'startedMs')}
        q.save('environment.json', env)
        producer = Producer()
        producer.thread.start()
        wait_for(lambda: any('캐빈 적용: 0개 성공, 0개 미적용' in r.get('message', '') for r in live('CargoVM', started['hostUnixNs'])), 5, 'zero cabin application', producer.check)
        rt.wait_active('guard-active', 'record', started['hostUnixNs'])
        d.configure(True, True, 'settings-driving-confirmed')
        q.demo('settings', 'off')
        q.event('selection_quiet_wait', seconds=8)
        time.sleep(8)
        producer.check()
        launch(rt.NAVI)
        rt.tap(ui_find('native-home', '어디로 갈까요', rt.NAVI, time.monotonic()+8), 'native_search_open')
        ui_find('native-search', '어디로 갈까요', rt.NAVI, time.monotonic()+8)
        q.adb('shell', 'input', 'text', 'Seoul')
        q.adb('shell', 'input', 'keyevent', '66')
        rt.tap(ui_find('native-results', '더현대서울', rt.NAVI, time.monotonic()+12), 'native_public_destination')
        destination = ui_find('native-detail', '목적지로', rt.NAVI, time.monotonic()+8)
        q.event('native_detail_prepared')
        env['clockCalibration'] = rt.clock_sync()
        q.save('environment.json', env)
        cap_mono = time.monotonic()
        cap = d.capture_start(CAPTURE_SECONDS)
        capture_deadline = cap_mono+CAPTURE_SECONDS
        def check():
            producer.check()
            if monitor_errors:
                raise RuntimeError(monitor_errors[0])
            if any(r.get('data', {}).get('event') == 'lost' for r in live('GuardRuntime', started['hostUnixNs'])):
                raise RuntimeError('guard runtime lost')
            if any(r.get('message', '').startswith('TTS_ERROR') for r in live('PleosSpeech', started['hostUnixNs'])):
                raise RuntimeError('VIP TTS error')
            if time.monotonic() >= capture_deadline:
                raise RuntimeError('single 180 s capture deadline reached before workflow completion')
            if cap[0].poll() is not None:
                raise RuntimeError('capture exited before lifecycle completion')
        pcm_path = folder/'capture/audio.s16le.pcm'
        wait_for(lambda: pcm_path.exists() and pcm_path.stat().st_size >= 4, 5, 'first PCM bytes', check)
        q.event('pcm_bytes_observed')
        time.sleep(3)
        request = q.event('native_route_request')
        route_requested, route_off = True, False
        activation_deadline = time.monotonic()+20
        rt.tap(destination, 'native_destination_tap')
        ui_find('native-route', '안내 종료', rt.NAVI, activation_deadline)
        wait_for(lambda: any(r.get('message') == 'SDK callback navigation-status=ROUTE_DRIVE'
                            for r in live('PleosNavi', request['hostUnixNs'])), max(0, activation_deadline-time.monotonic()), 'real ROUTE_DRIVE', check)
        q.event('native_route_active')
        require_background('background-sample-initial', before['pid'])
        rt.require_active(rt.snapshot('native-active'), 'record')
        q.api('/vehicle/gear?gear=Drive', 'PUT')
        if q.api('/vehicle/state')['gear']['actual']['value'] != 'Drive':
            raise RuntimeError('Console Drive not observed')
        q.event('console_drive')
        def monitor_background():
            n = 0
            try:
                while not background_stop.is_set():
                    require_background('background-sample-'+str(n), before['pid'])
                    n += 1
                    if background_stop.wait(2):
                        return
            except BaseException as error:
                monitor_errors.append(safe_error(error))
                q.event('background_observation_error', error=monitor_errors[-1])
        monitor = threading.Thread(target=monitor_background, daemon=True)
        monitor.start()
        q.event('profile_begin')
        producer.begin.set()
        print(json.dumps({'stage': 'synthetic-profile-started', 'seconds': 72}), flush=True)
        wait_for(producer.done.is_set, 76, 'fixed motion profile', check)
        check()
        q.event('profile_finished')
        rt.require_active(rt.snapshot('profile-finished'), 'record')
        end_deadline = time.monotonic()+10
        node = ui_find('native-end-control', '안내 종료', rt.NAVI, end_deadline)
        ending = q.event('native_route_end_request')
        rt.tap(node, 'native_route_end_tap')
        ui_find('native-ended', '어디로 갈까요', rt.NAVI, end_deadline)
        wait_for(lambda: any(r.get('message') == 'SDK callback navigation-status=FREE_DRIVE'
                            for r in live('PleosNavi', ending['hostUnixNs'])), max(0, end_deadline-time.monotonic()), 'real FREE_DRIVE', check)
        route_off = True
        q.event('native_route_ended')
        background_stop.set()
        monitor.join(timeout=15)
        if monitor.is_alive():
            raise RuntimeError('visibility monitor did not stop')
        check()
        return_deadline = time.monotonic()+10
        park_request = q.event('console_park_request')
        q.api('/vehicle/gear?gear=Park', 'PUT')
        q.check_park(q.api('/vehicle/state'))
        launch(q.PKG)
        ui_find('vip-return-same-session', '0.12G', q.PKG, return_deadline)
        q.surface('vip-return')
        parked = wait_for(lambda: next((r for r in reversed(live('MotionTrace'))
                    if current_parked(r, env['session']['startedMs'], park_request['hostUnixNs'], time.time_ns())), None),
                    max(0, return_deadline-time.monotonic()), 'trace parked state', check)
        q.save('parked-before-arrival.json', parked)
        producer.close()
        arrival = q.event('synthetic_arrival_request', provenance='DEMO/SYNTHETIC; not native arrival')
        q.demo('navi', 'arrived', phase='arrival')
        done = wait_for(lambda: next((r for r in live('PleosSpeech', arrival['hostUnixNs']) if r.get('message') == 'TTS_DONE'), None), 10, 'arrival TTS_DONE', check)
        q.event('arrival_done_observed', callbackDeviceEpochSeconds=done['deviceEpochSeconds'])
        q.ui('report', True)
        time.sleep(3)
        check()
        q.event('arrival_tail_wait_complete', seconds=3)
        capture_finish(cap, cap_mono)
        cap = None
        q.save('clock-end.json', rt.clock_sync())
        q.event('execution_complete')
    except BaseException as error:
        errors.append(safe_error(error))
        q.event('execution_error', error=errors[-1])
    finally:
        background_stop.set()
        if monitor:
            monitor.join(timeout=15)
            if monitor.is_alive():
                cleanup_errors.append('visibility monitor remains alive')
        if producer:
            try:
                producer.close(propagate=False)
            except BaseException as error:
                cleanup_errors.append('producer: '+safe_error(error))
        producer_alive = producer is not None and producer.thread.is_alive()
        def recover(label, action):
            try:
                return action()
            except BaseException as error:
                cleanup_errors.append(label+': '+safe_error(error))
                return None
        recover('capture stop', lambda: d.capture_abort(cap))
        if mutated and not producer_alive:
            def close_native():
                launch(rt.NAVI)
                if route_requested and not route_off:
                    node = ui_find('cleanup-native-end', '안내 종료', rt.NAVI, time.monotonic()+8)
                    rt.tap(node, 'cleanup_native_end_tap')
                ui_find('cleanup-native-idle', '어디로 갈까요', rt.NAVI, time.monotonic()+8)
            recover('native route cleanup', close_native)
            recover('Park', lambda: q.api('/vehicle/gear?gear=Park', 'PUT'))
            recover('zero', lambda: q.motion(0, 0, 'cleanup', 0))
            recover('VIP return', lambda: launch(q.PKG))
            recover('end', lambda: q.demo('end', phase='cleanup'))
            recover('ack', lambda: q.demo('ack', phase='cleanup'))
            if flags:
                def restore_flags():
                    actual = d.configure(flags['tts']['checked'], flags['chime']['checked'], 'settings-restored')
                    if any(actual[k]['checked'] != flags[k]['checked'] for k in flags):
                        raise RuntimeError('settings original values differ')
                recover('settings', restore_flags)
            recover('settings close', lambda: q.demo('settings', 'off'))
            recover('final P0 trace', lambda: q.motion(0, 0, 'cleanup', 1))
            recover('Idle UI', lambda: ui_find('cleanup-idle', '프리셋 고르기', q.PKG, time.monotonic()+8))
            recover('service/notification stop', lambda: rt.require_stopped(rt.snapshot('cleanup-runtime')))
            def vehicle_readback():
                after = q.api('/vehicle/state')
                q.save('vehicle-after.json', after)
                q.check_park(after)
                if original is None or vehicle_values(after) != vehicle_values(original):
                    raise RuntimeError('original cabin/controls differ')
            recover('vehicle original values', vehicle_readback)
            def identity_readback():
                after = d.identity()
                q.save('environment-after.json', after)
                if after != before:
                    raise RuntimeError('APK/PID/AVD identity changed')
            recover('identity', identity_readback)
        for tag, value in tags.items():
            def restore_tag(tag=tag, value=value):
                q.adb('shell', 'setprop log.tag.'+tag+' '+shlex.quote(value))
                if q.adb('shell', 'getprop', 'log.tag.'+tag).strip() != value:
                    raise RuntimeError('original log property differs')
            recover('property '+tag, restore_tag)
        recover('log stop', lambda: d.log_stop(logger))
        if helper:
            def helper_close():
                helper.terminate()
                try:
                    helper.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    helper.kill()
                    helper.wait(timeout=3)
                    raise RuntimeError('helper killed; owned resource cleanup unverified')
                state = json.loads((folder/'console-lease.json').read_text())
                if not state['closed'] or state['errors']:
                    raise RuntimeError('Console lease cleanup not verified')
            recover('own Console helper', helper_close)
        if list(folder.glob('.grpc-*')):
            cleanup_errors.append('private token remains')
        q.save('cleanup.json', {'verified': not cleanup_errors, 'cleanupRequired': mutated,
                               'executionErrors': errors, 'cleanupErrors': cleanup_errors,
                               'originalLogProperties': tags, 'noCabinWritesIntended': True})
    try:
        result = analyze(folder, errors)
    except BaseException as error:
        result = {'executionCompleted': not errors, 'boundedObservationComplete': False,
                  'executionErrors': errors, 'analysisError': safe_error(error),
                  'strictNativeVipContention': 'UNVERIFIED', 'limits': LIMITS}
        q.save('integration-result.json', result)
    d.finish_manifest()
    print(json.dumps({'out': folder.name, 'boundedObservationComplete': result['boundedObservationComplete'],
                      'cleanupVerified': not cleanup_errors, 'executionErrors': errors}, ensure_ascii=False), flush=True)
    return result['boundedObservationComplete'] and not cleanup_errors


def self_test():
    """Pure fixtures only: never invoke ADB, HTTP, a child process, or an existing raw run."""
    checks = []
    def check(name, value):
        if not value:
            raise AssertionError(name)
        checks.append(name)
    for path in (Path(__file__), BASE/'console_lease.py'):
        ast.parse(path.read_text())
        check('AST '+path.name, True)
    info = contract()
    check('fixed 72 second / 144 step fixture', info['profileSeconds'] == 72 and sum(len(v) for _, v in PROFILE) == 144)
    check('137 s declared phases fit single 180 s RPC', sum(info['stagesSeconds'].values()) == 137 < CAPTURE_SECONDS)
    check('normal limit fixtures', info['settledLateralG']['90'] < .096 < info['settledLateralG']['100'] < .12 < info['settledLateralG']['120'] < .13)
    check('no impossible profile values', all(0 <= k <= 18 and a in (0, 60, 90, 100, 120) for _, values in PROFILE for k, a in values))
    packet = {'packets': [{'bytes': 4, 'timestamp_us': 1000000}, {'bytes': 4, 'timestamp_us': 2000000}, {'bytes': 4, 'timestamp_us': 4000000}]}
    parts = packet_index(packet, b'\xff\x7f\x00\x80'*3)
    check('signed LE stereo and original offsets', parts[0]['peakS16'] == 32768 and parts[2]['pcmByteOffset'] == 8 and parts[0]['sumSquares'] == 32767**2+32768**2)
    empty = window_stats(parts, 2.5, 3.5)
    check('internal empty window never silence/PASS', empty['packetCount'] == 0 and empty['rmsS16'] is None and not empty['coveredWithReceivedSamples'])
    check('received window required', window_stats(parts, 1.5, 3)['coveredWithReceivedSamples'])
    check('missing tail invalid', not window_stats(parts, 3, 5)['coveredWithReceivedSamples'])
    try:
        packet_index({'packets': [{'bytes': 3, 'timestamp_us': 1}]}, b'123')
    except ValueError:
        check('partial stereo frame rejected', True)
    else:
        raise AssertionError('partial frame accepted')
    check('clock endpoints agree', clock_model({'deviceMinusHostNs': 10, 'uncertaintyNs': 2}, {'deviceMinusHostNs': 12, 'uncertaintyNs': 2})['consistent'])
    check('clock disagreement not repaired', not clock_model({'deviceMinusHostNs': 10, 'uncertaintyNs': 2}, {'deviceMinusHostNs': 20, 'uncertaintyNs': 2})['consistent'])
    def events(*names):
        return [{'message': n} for n in names]
    check('stop-before-start allowed', callback_order(events('TTS_STOP', 'TTS_START', 'TTS_DONE'))['singleStartThenDone'])
    check('interrupted speech rejected', not callback_order(events('TTS_START', 'TTS_STOP', 'TTS_DONE'))['singleStartThenDone'])
    check('missing DONE rejected', not callback_order(events('TTS_START'))['singleStartThenDone'])
    check('multiple starts ambiguous', not callback_order(events('TTS_START', 'TTS_DONE', 'TTS_START', 'TTS_DONE'))['singleStartThenDone'])
    parked = {'hostReceivedUnixNs': 4_000_000_000, 'data': {'state': 'GUARDING', 'trip': {'startedMs': 7},
              'snapshot': {'gear': 'PARK', 'speedMps': 0, 'parkedStill': True}}}
    check('fresh same-session park accepted', current_parked(parked, 7, 3_000_000_000, 5_000_000_000))
    check('old configuration park excluded', not current_parked(parked, 7, 4_500_000_000, 5_000_000_000))
    check('stale or other session park excluded', not current_parked(parked, 7, 3_000_000_000, 7_000_000_000)
          and not current_parked(parked, 8, 3_000_000_000, 5_000_000_000))
    (BASE/'integration-contract.json').write_text(json.dumps(info, ensure_ascii=False, indent=2)+'\n')
    preparation = {'preparedOnly': True, 'adbCalls': 0, 'captureCalls': 0, 'runtimeAttempts': 0,
                   'selfTestChecks': checks, 'dependencySha256': dependencies(),
                   'contractSha256': digest(BASE/'integration-contract.json'),
                   'rootHandoffStillRequired': True, 'independentReviewStillRequired': True}
    (BASE/'integration-preparation.json').write_text(json.dumps(preparation, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'pureChecks': len(checks), 'harnessSha256': digest(Path(__file__)), 'preparedOnly': True}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--self-test', action='store_true')
    mode.add_argument('--run', action='store_true')
    parser.add_argument('--out', default='integration-01')
    parser.add_argument('--expected-apk-sha256')
    parser.add_argument('--design-sha256')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.out != 'integration-01' or args.expected_apk_sha256 != EXPECTED or args.design_sha256 != digest(Path(__file__)):
        parser.error('one predetermined folder, runtime06 SHA and exact reviewed harness SHA required')
    prepared = json.loads((BASE/'integration-preparation.json').read_text())
    if prepared['dependencySha256'] != dependencies() or prepared['contractSha256'] != digest(BASE/'integration-contract.json'):
        parser.error('prepared dependencies changed; freeze/review required before any ADB')
    if (BASE/args.out).exists():
        parser.error('raw folder already exists; no reroll')
    with (BASE/'runtime-attempt.json').open('x') as handle:
        json.dump({'attempt': 1, 'hostUnixNs': time.time_ns(), 'harnessSha256': args.design_sha256,
                   'expectedApkSha256': EXPECTED, 'out': args.out}, handle, indent=2)
    raise SystemExit(0 if execute(BASE/args.out) else 1)


if __name__ == '__main__':
    main()
