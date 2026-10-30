#!/usr/bin/env python3
"""Explicit v23 COFFEE driving ON/OFF and optional one native-only audio probe.
No mutation on import. Existing runtime06 only; no app/source/policy changes.
"""
import argparse, array, datetime, hashlib, io, json, math, os, re, shlex, signal, subprocess, sys, tempfile, threading, time, wave
from pathlib import Path
BASE=Path(__file__).resolve().parent
ROOT=BASE.parents[2]
sys.dont_write_bytecode=True
sys.path.insert(0,str(BASE.parent/'submission-v22'))
import known_boundary_probe as qb
import runtime_avd_probe as runtime
sys.path.insert(0,str(ROOT/'.omx/qa/audio-feasibility'))
import run_matrix as matrix
EXPECTED='8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c'
ADB=qb.ADB;PKG=qb.PKG;SERIAL=qb.SERIAL;OUT=None;POLICY=qb.POLICY
save=qb.save
LIVE=[]


def bind(folder):
    global OUT
    OUT=folder;qb.OUT=folder;runtime.qa.OUT=folder;matrix.OUT=folder


def identity():
    paths=qb.adb('shell','pm','path',PKG).splitlines()
    if len(paths)!=1 or not paths[0].startswith('package:'):raise RuntimeError('single APK required')
    digest=hashlib.sha256(qb.adb('exec-out','cat',paths[0][8:],binary=True,timeout=30)).hexdigest()
    info={'installedApkSha256':digest,'pid':qb.adb('shell','pidof',PKG).strip(),
          'product':qb.adb('shell','getprop','ro.product.name').strip(),'api':qb.adb('shell','getprop','ro.build.version.sdk').strip()}
    if digest!=EXPECTED or info['product']!='crp_avd_arm64' or info['api']!='34' or not info['pid'].isdigit():raise RuntimeError('candidate/AVD identity mismatch')
    return info


def log_start(pid):
    LIVE.clear()
    process=subprocess.Popen([ADB,'-s',SERIAL,'logcat','-v','epoch','-T','1','--pid='+pid,
        'MotionTrace:D','CargoVM:D','PleosSpeech:I','ChimePlayer:I','GuardRuntime:V','PleosNavi:V','DemoBridge:I','*:S'],
        stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
    folder=OUT
    def consume():
        with (folder/'motion.log').open('w') as raw,(folder/'live-log.jsonl').open('w') as output:
            for line in process.stdout:
                raw.write(line);raw.flush()
                row={'hostReceivedUnixNs':time.time_ns(),'hostReceivedMonotonicNs':time.monotonic_ns(),'line':line.rstrip()}
                match=re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+([^:]+):\s?(.*)',line.rstrip())
                if match:
                    row.update(deviceEpochSeconds=float(match[1]),pid=int(match[2]),tag=match[4].strip(),message=match[5])
                    if row['tag'] in {'MotionTrace','GuardRuntime'} and row['message'].startswith('{'):
                        try:row['data']=json.loads(row['message'])
                        except ValueError:row['parseError']=True
                LIVE.append(row);output.write(json.dumps(row,ensure_ascii=False)+'\n');output.flush()
    thread=threading.Thread(target=consume,daemon=True);thread.start()
    return process,thread


def log_stop(pair):
    if pair:
        p,t=pair;p.terminate()
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
        t.join(timeout=5)
        if t.is_alive():raise RuntimeError('log reader did not stop')


def configure(tts,chime,label):
    # Actual settings XML including untouched demo-curve flag.
    return matrix.configure({'tts':tts,'chime':chime},OUT,label)


def capture_start(seconds=40):
    connection=json.loads((ROOT/'.omx/qa/audio-feasibility/environment-local.json').read_text())['connection']
    if connection['avd']!='Pleos_Connect_v2.1' or connection['audio_disabled'] or connection['host_audio_input_allowed']:raise RuntimeError('audio connection contract mismatch')
    fields=dict(line.split('=',1) for line in Path(connection['ini_path']).read_text().splitlines() if '=' in line)
    if fields.get('avd.name')!='Pleos_Connect_v2.1' or fields.get('grpc.port')!='8554':raise RuntimeError('emulator connection mismatch')
    fd,path=tempfile.mkstemp(prefix='.grpc-',dir=OUT);os.chmod(path,0o600)
    try:
        with os.fdopen(fd,'w') as handle:handle.write(fields['grpc.token'])
        process=subprocess.Popen([sys.executable,str(ROOT/'.omx/qa/audio-feasibility/capture_emulator_audio.py'),
             '--endpoint','127.0.0.1:8554','--seconds',str(seconds),'--out',str(OUT/'capture'),'--token-file',path],
             stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'})
        qb.event('capture_started',seconds=seconds)
        return process,Path(path)
    except BaseException:
        Path(path).unlink(missing_ok=True);raise


def capture_finish(pair):
    if not pair:return
    process,token=pair
    try:
        try:stdout,stderr=process.communicate(timeout=45)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:process.wait(timeout=3)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=3)
            raise RuntimeError('bounded capture did not finish')
        qb.save('capture-process.json',{'exitCode':process.returncode,'stderrPresentSuppressed':bool(stderr)})
        if process.returncode!=0:raise RuntimeError('capture failed; see safe metadata')
    finally:token.unlink(missing_ok=True)


def capture_abort(pair):
    if not pair:return
    p,token=pair
    try:
        if p.poll() is None:
            p.terminate()
            try:p.wait(timeout=3)
            except subprocess.TimeoutExpired:p.kill();p.wait(timeout=3)
    finally:token.unlink(missing_ok=True)


def video_start():
    path='/sdcard/v23-driving-on.mp4'
    existing=subprocess.run([ADB,'-s',SERIAL,'shell','pidof','screenrecord'],capture_output=True,text=True,timeout=10)
    if existing.stdout.strip():raise RuntimeError('another screenrecord exists')
    if subprocess.run([ADB,'-s',SERIAL,'shell','test','-e',path],timeout=10).returncode==0:raise RuntimeError('owned video path already exists')
    p=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--bit-rate','4000000','--time-limit','150',path],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    time.sleep(.2);pids=qb.adb('shell','pidof','screenrecord').split()
    if len(pids)!=1:
        p.terminate();p.wait(timeout=5);raise RuntimeError('screenrecord ownership ambiguous')
    qb.event('screenrecord_started',devicePid=pids[0],path=path,audio=False)
    return p,pids[0],path


def video_finish(record):
    if not record:return
    process,pid,path=record
    if process.poll() is None:qb.adb('shell','kill','-2',pid)
    try:_,stderr=process.communicate(timeout=15)
    except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=5);raise RuntimeError('screenrecord did not stop')
    data=qb.adb('exec-out','cat',path,binary=True,timeout=30)
    (OUT/'continuous-silent.mp4').write_bytes(data)
    qb.save('video.json',{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'exitCode':process.returncode,'stderrPresent':bool(stderr),'audioScope':'silent original screenrecord; no audio synchronization/edit'})
    qb.adb('shell','rm',path)


def finish_manifest():
    qb.save('artifact-sha256.json',{str(p.relative_to(OUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.rglob('*')) if p.is_file() and p.name!='artifact-sha256.json'})


class BoundaryOracle(qb.BoundaryOracle):
    def summary(self):
        result=super().summary();result['actualPlayback']='separate PCM/callback observation in audio-result.json; reducer counts alone are not playback'
        return result


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
                and e.get("phase") in {"configuration", "baseline", "acceleration", "settle", "ok60", "below90", "warn100", "over120", "recover60", "straight", "deceleration", "stop", "park"}]
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
              "evidenceScope": "Synthetic DEMO -> production estimator/badge/audio-admission/report; received dt oracle. Audio measured separately; reducer admission is not playback.",
              "limits": ["Foreground synthetic boundary probe; no native SDK background claim.",
                         "No vehicle accuracy, physical cargo outcome or native navigation claim; PCM/callback scope is separate.",
                         "Gaps/reordered batches are failures; no policy changes or discarded background interval."]}
    save("oracle-result.json", result)
    return result



def audio_analysis(folder):
    metadata=json.loads((folder/'capture/metadata.json').read_text())
    pcm=(folder/'capture/audio.s16le.pcm').read_bytes()
    with wave.open(str(folder/'capture/audio.wav'),'rb') as wav:
        if (wav.getnchannels(),wav.getsampwidth(),wav.getframerate())!=(2,2,48000) or wav.readframes(wav.getnframes())!=pcm:raise RuntimeError('PCM/WAV mismatch')
    if metadata['capture_status']!='CAPTURED' or metadata['pcm_sha256']!=hashlib.sha256(pcm).hexdigest():raise RuntimeError('PCM capture/hash failed')
    rows=[json.loads(line) for line in (folder/'live-log.jsonl').read_text().splitlines()]
    env=json.loads((folder/'environment.json').read_text())
    offset=env['clockCalibration']['deviceMinusHostNs']/1e9
    uncertainty=env['clockCalibration']['uncertaintyNs']/1e9
    started=datetime.datetime.fromisoformat(metadata['started_utc']).timestamp()
    parts=[];cursor=0;lags=[]
    for packet in metadata['packets']:
        payload=pcm[cursor:cursor+packet['bytes']]
        if len(payload)!=packet['bytes'] or len(payload)%4:raise RuntimeError('incomplete stereo frame')
        samples=array.array('h');samples.frombytes(payload)
        if sys.byteorder!='little':samples.byteswap()
        count=len(samples);sq=sum(x*x for x in samples);peak=max(map(abs,samples),default=0)
        lags.append(started+packet['host_elapsed_seconds']-packet['timestamp_us']/1e6)
        parts.append({**packet,'pcm_byte_offset':cursor,'sample_count':count,'sum_squares':sq,'peak_absolute_s16':peak})
        cursor+=len(payload)
    if cursor!=len(pcm):raise RuntimeError('unindexed PCM')
    if not lags or min(lags)<-.25 or max(lags)>1:raise RuntimeError('packet epoch/host receipt relationship unbounded')
    def stats(start,end):
        selected=[p for p in parts if start<=p['timestamp_us']/1e6<end]
        n=sum(p['sample_count'] for p in selected)
        return {'hostEpochWindow':[start,end],'packetCount':len(selected),'receivedPcmSeconds':n/96000,
                'rmsS16':math.sqrt(sum(p['sum_squares'] for p in selected)/n) if n else None,
                'peakS16':max((p['peak_absolute_s16'] for p in selected),default=None)}
    admissions=[];previous={'drivingSpeechCount':0,'drivingChimeCount':0};sessions=set()
    for row in rows:
        trace=row.get('data',{})
        if row.get('tag')!='MotionTrace' or trace.get('state')!='GUARDING':continue
        sessions.add(trace['trip']['startedMs'])
        for key,kind in [('drivingSpeechCount','speech'),('drivingChimeCount','chime')]:
            value=trace['trip'][key]
            if value>previous[key]:
                if value!=previous[key]+1:raise RuntimeError('admission trace gap')
                when=row['deviceEpochSeconds']-offset
                end=when+(7.8 if kind=='speech' else 2)
                life=[r for r in rows if r.get('tag')=='PleosSpeech' and when-uncertainty<=r['deviceEpochSeconds']-offset<end+uncertainty]
                admissions.append({'kind':kind,'tMs':trace['tMs'],'tripStartedMs':trace['trip']['startedMs'],'hostEpochSeconds':when,
                    'clockUncertaintySeconds':uncertainty,'badge':trace['badge'],'estimate':trace['estimate'],
                    'pre':stats(when-3,when-.1),'response':stats(when,end),
                    'lifecycle':{k:sum(r['message']==k or r['message'].startswith(k+':') for r in life) for k in ['TTS_START','TTS_DONE','TTS_ERROR','TTS_STOP']}})
            previous[key]=value
    if len(sessions)!=1 or not admissions:raise RuntimeError('single guarded session/admissions absent')
    start_packet=parts[0]['timestamp_us']/1e6;end_packet=parts[-1]['timestamp_us']/1e6
    if any(a['hostEpochSeconds']-3<start_packet or a['response']['hostEpochWindow'][1]>end_packet for a in admissions):raise RuntimeError('capture does not cover admission baseline/response window')
    result={'session':next(iter(sessions)),'audioSettings':env['audioSettings'],'admissions':admissions,
            'packetCount':len(parts),'pcmSha256':hashlib.sha256(pcm).hexdigest(),'pcmDurationSeconds':metadata['pcm_duration_seconds'],
            'wallSeconds':metadata['wall_seconds'],'packetReceiveLagSeconds':{'min':min(lags),'max':max(lags)},
            'limits':['mixed emulator output only; no microphone/host input','PCM concatenates sparse received samples, not continuous wall time','no speech intelligibility or physical effect claim','driving window only; arrival speech playback not measured']}
    (folder/'capture/packet-analysis.json').write_text(json.dumps(parts,indent=2)+'\n')
    (folder/'audio-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return result


def driving_case(folder,enabled,record_video=False):
    folder.mkdir();bind(folder)
    original=original_flags=old_tag=None;logger=cap=record=None;producer=None;mutated=False
    stop=threading.Event();configured=threading.Event();state={'phase':None,'error':None};errors=[]
    try:
        before=identity();original=qb.api('/vehicle/state');qb.check_park(original)
        if any(original['controls'][k]!=0 for k in ('accelerator_pct','brake_pct','steering_deg')):raise RuntimeError('nonzero original controls')
        qb.save('vehicle-before.json',original)
        surface=qb.surface('before')
        if not any(PKG in row for row in surface['focus']):raise RuntimeError('VIP must already be visible')
        mutated=True
        qb.demo('end');qb.demo('ack');qb.demo('settings','on')
        original_flags=matrix.switch_states(matrix.ui(folder/'settings-original.xml'))
        qb.save('settings-original.json',original_flags)
        configure(False,False,'settings-selection-muted');qb.demo('settings','off')
        qb.motion(0,0,'setup',0)
        old_tag=qb.adb('shell','getprop','log.tag.MotionTrace').strip()
        calibration=runtime.clock_sync();trace_start=qb.elapsed()
        qb.save('environment.json',{**before,'traceStartElapsedMs':trace_start,'originalMotionTraceProperty':old_tag,
            'clockCalibration':calibration,'audioSettings':{'tts':enabled,'chime':enabled},'motionPath':'DEMO synthetic','policy':POLICY,
            'sourceHashes':qb.source_hashes(),'selectionAudio':'both OFF; switched while P0 before driving','continuousVideo':record_video})
        qb.adb('shell','setprop','log.tag.MotionTrace','DEBUG');logger=log_start(before['pid'])
        if record_video:record=video_start()
        qb.demo('preset','COFFEE')
        until=time.monotonic()+2
        while not any('캐빈 적용: 0개 성공, 0개 미적용' in r.get('message','') for r in LIVE):
            if time.monotonic()>until:raise RuntimeError('COFFEE zero-application guard not ready')
            time.sleep(.03)
        segments=[('baseline',[(0,0)]*12),('acceleration',[(n*.9,0) for n in range(1,21)]),('settle',[(18,0)]*12),
                  ('ok60',[(18,60)]*8),('below90',[(18,90)]*8),('warn100',[(18,100)]*20),('over120',[(18,120)]*20),
                  ('recover60',[(18,60)]*12),('straight',[(18,0)]*4),('deceleration',[(n*.9,0) for n in range(19,-1,-1)]),('stop',[(0,0)]*8)]
        def produce():
            deadline=time.monotonic();n=0
            try:
                state['phase']='configuration';qb.event('phase_start',phase='configuration')
                while not configured.is_set():
                    if stop.wait(max(0,deadline-time.monotonic())):return
                    qb.motion(0,0,'configuration',n);n+=1;deadline+=.5
                for phase,values in segments:
                    state['phase']=phase;qb.event('phase_start',phase=phase)
                    for n,(kph,angle) in enumerate(values):
                        if stop.wait(max(0,deadline-time.monotonic())):return
                        if time.monotonic()-deadline>1:raise RuntimeError('producer late over one second')
                        qb.motion(kph,angle,phase,n);deadline+=.5
                stop.wait(max(0,deadline-time.monotonic()));state['phase']='done'
            except BaseException as error:state['error']=str(error);qb.event('producer_error',error=str(error))
        producer=threading.Thread(target=produce,daemon=True);producer.start()
        configure(enabled,enabled,'settings-driving-confirmed');qb.demo('settings','off')
        qb.event('selection_quiet_wait',seconds=8);time.sleep(8);configured.set()
        def wait_phase(phase):
            deadline=time.monotonic()+85
            while state['phase']!=phase:
                if state['error'] or state['phase']=='done' or time.monotonic()>deadline:raise RuntimeError(state['error'] or 'phase unavailable: '+phase)
                time.sleep(.025)
        wait_phase('baseline');tree=qb.ui('start',True)
        if '0.12G' not in (folder/'start.xml').read_text():raise RuntimeError('COFFEE guard not visible')
        if state['phase']!='baseline':raise RuntimeError('start UI exceeded baseline')
        qb.api('/vehicle/gear?gear=Drive','PUT');gear=qb.api('/vehicle/state')['gear'];qb.event('console_drive',actual=gear)
        if gear['actual']['value']!='Drive':raise RuntimeError('Drive not observed')
        wait_phase('ok60');cap=capture_start(40);qb.surface('ok60')
        for phase in ['below90','warn100','over120','recover60']:
            wait_phase(phase);qb.surface(phase)
            if phase in {'warn100','over120'}:qb.ui(phase,True)
        wait_phase('done');producer.join(timeout=5)
        if producer.is_alive() or state['error']:raise RuntimeError(state['error'] or 'producer did not finish')
        capture_finish(cap);cap=None
        qb.api('/vehicle/gear?gear=Park','PUT');qb.motion(0,0,'park',0);qb.check_park(qb.api('/vehicle/state'))
        qb.demo('navi','arrived');time.sleep(1);qb.ui('report',True);qb.surface('report');time.sleep(6)
        qb.event('execution_complete')
    except BaseException as error:
        errors.append(str(error));qb.event('execution_error',error=str(error))
    finally:
        if not mutated:
            qb.save('cleanup.json',{'verified':True,'cleanupRequired':False,'executionErrors':errors,'cleanupErrors':[]})
            result={'passed':False,'issues':errors,'oraclePassed':False,'audioObserved':False}
            qb.save('result.json',result);finish_manifest()
            return result,None
        execution_count=len(errors);stop.set();configured.set()
        if producer:
            producer.join(timeout=15)
            if producer.is_alive():errors.append('producer survives cleanup')
        capture_abort(cap)
        try:video_finish(record)
        except BaseException as error:errors.append('video cleanup: '+str(error))
        for label,fn in [('park',lambda:qb.api('/vehicle/gear?gear=Park','PUT')),('zero',lambda:qb.motion(0,0,'cleanup',0)),
                         ('end',lambda:qb.demo('end',phase='cleanup')),('ack',lambda:qb.demo('ack',phase='cleanup'))]:
            try:fn()
            except BaseException as error:errors.append(label+': '+str(error))
        try:
            if original_flags:
                restored=configure(original_flags['tts']['checked'],original_flags['chime']['checked'],'settings-restored')
                if any(restored[k]['checked']!=original_flags[k]['checked'] for k in original_flags):raise RuntimeError('settings differ from original')
            qb.demo('settings','off');qb.motion(0,0,'cleanup',1);qb.ui('cleanup',True);time.sleep(1)
            runtime.require_stopped(runtime.snapshot('cleanup-runtime'))
            after=qb.api('/vehicle/state');qb.save('vehicle-after.json',after);qb.check_park(after)
            if original:
                for domain,keys in {'hvac':['power_on','auto_on','ac_on','dual_on','sync_on','recirc_on','front_defroster_on','rear_defroster_on','fan_speed','auto_blower_level','driver','passenger','row2'],
                   'seat':['positions','moves','occupied'],'body':['doors','door_locks','windows','closures','wipers','cabin_light','reading_lights','ambient_light'],
                   'controls':['speed_kph','accelerator_pct','brake_pct','steering_deg']}.items():
                    if any(after[domain][key]!=original[domain][key] for key in keys):raise RuntimeError('original state differs: '+domain)
            final=identity();qb.save('environment-after.json',final)
            if before['pid']!=final['pid']:raise RuntimeError('PID changed')
        except BaseException as error:errors.append('cleanup readback: '+str(error))
        if old_tag is not None:
            try:
                # adb shell reconstructs argv; quote empty original values explicitly.
                qb.adb('shell','setprop log.tag.MotionTrace '+shlex.quote(old_tag))
                if qb.adb('shell','getprop','log.tag.MotionTrace').strip()!=old_tag:raise RuntimeError('trace property restore failed')
            except BaseException as error:errors.append('property cleanup: '+str(error))
        try:log_stop(logger)
        except BaseException as error:errors.append('log cleanup: '+str(error))
        qb.save('cleanup.json',{'verified':len(errors)==execution_count,'executionErrors':errors[:execution_count],'cleanupErrors':errors[execution_count:]})
    oracle=analyze(folder) if (folder/'motion.log').exists() else {'passed':False,'issues':['no trace']}
    audio=None
    try:audio=audio_analysis(folder)
    except BaseException as error:errors.append('audio analysis: '+str(error))
    result={'passed':oracle['passed'] and not errors,'issues':errors,'oraclePassed':oracle['passed'],'audioObserved':audio is not None}
    qb.save('result.json',result);finish_manifest();print(json.dumps({'case':folder.name,**result}),flush=True)
    return result,audio


def native_only(folder):
    """One bounded native-only observation; no route progress or voice attribution claim."""
    folder.mkdir(exist_ok=False);bind(folder)
    (folder/'harness-source.py').write_bytes(Path(__file__).read_bytes())
    flags=before=original=cap=None;errors=[];active=None;ended=None;route_checked=False
    try:
        before=identity();original=qb.api('/vehicle/state');qb.check_park(original)
        runtime.require_stopped(runtime.snapshot('preflight-runtime'));qb.save('vehicle-before.json',original)
        qb.demo('settings','on');flags=matrix.switch_states(matrix.ui(folder/'settings-original.xml'));qb.save('settings-original.json',flags)
        configure(False,False,'settings-vip-muted');qb.demo('settings','off');time.sleep(3)
        qb.adb('shell','am','start','-W','-n',runtime.NAVI+'/.MainActivity')
        runtime.wait_ui('native-initial-idle','어디로 갈까요',runtime.NAVI);route_checked=True
        qb.save('environment.json',{**before,'clockCalibration':runtime.clock_sync(),'nativeRouteInitiallyOff':True,
            'vipGuardActive':False,'vipAudioSettings':{'tts':False,'chime':False},'gpsProgressionInjected':False})
        cap=capture_start(40);time.sleep(3)
        request,active=runtime.navigate();time.sleep(10)
        runtime.end_native_route();ended=qb.event('native_audio_observation_route_ended')
        capture_finish(cap);cap=None
        qb.save('route-window.json',{'request':request,'active':active,'ended':ended})
    except BaseException as error:errors.append(str(error));qb.event('execution_error',error=str(error))
    finally:
        count=len(errors);capture_abort(cap)
        try:
            if route_checked:
                tree=qb.ui('native-cleanup-check')
                if runtime.find(tree,'안내 종료',runtime.NAVI) is not None:runtime.end_native_route()
                runtime.wait_ui('native-final-idle','어디로 갈까요',runtime.NAVI)
            qb.adb('shell','am','start','-W','-n',PKG+'/.MainActivity')
            if flags:
                restored=configure(flags['tts']['checked'],flags['chime']['checked'],'settings-restored')
                if any(restored[k]['checked']!=flags[k]['checked'] for k in flags):raise RuntimeError('settings changed')
                qb.demo('settings','off')
            tree=qb.ui('cleanup',True)
            if runtime.find(tree,'조수석에 무엇을 실으셨나요?',PKG) is None:raise RuntimeError('VIP idle not visible')
            runtime.require_stopped(runtime.snapshot('cleanup-runtime'))
            after=qb.api('/vehicle/state');qb.save('vehicle-after.json',after);qb.check_park(after)
            if original:
                for domain in ['hvac','seat','body','controls']:
                    if after[domain]!=original[domain]:raise RuntimeError('untouched vehicle domain differs: '+domain)
            final=identity();qb.save('environment-after.json',final)
            if before and before!=final:raise RuntimeError('identity/PID changed')
        except BaseException as error:errors.append('cleanup: '+str(error))
        qb.save('cleanup.json',{'verified':len(errors)==count,'executionErrors':errors[:count],'cleanupErrors':errors[count:]})
    observation={'nativeSpeechContentVerified':False,'nativeVipContentionVerified':False}
    try:
        metadata=json.loads((folder/'capture/metadata.json').read_text());pcm=(folder/'capture/audio.s16le.pcm').read_bytes()
        if metadata['capture_status']!='CAPTURED' or metadata['pcm_sha256']!=hashlib.sha256(pcm).hexdigest():raise RuntimeError('capture/hash incomplete')
        with wave.open(str(folder/'capture/audio.wav'),'rb') as wav:
            if (wav.getnchannels(),wav.getsampwidth(),wav.getframerate())!=(2,2,48000) or wav.readframes(wav.getnframes())!=pcm:raise RuntimeError('PCM/WAV mismatch')
        packets=[];cursor=0;lags=[];started=datetime.datetime.fromisoformat(metadata['started_utc']).timestamp()
        for row in metadata['packets']:
            payload=pcm[cursor:cursor+row['bytes']];cursor+=len(payload)
            if len(payload)!=row['bytes'] or len(payload)%4:raise RuntimeError('PCM framing mismatch')
            samples=array.array('h');samples.frombytes(payload)
            if sys.byteorder!='little':samples.byteswap()
            lags.append(started+row['host_elapsed_seconds']-row['timestamp_us']/1e6)
            packets.append({**row,'sampleCount':len(samples),'sumSquares':sum(x*x for x in samples),'peakS16':max(map(abs,samples),default=0)})
        if cursor!=len(pcm) or not packets:raise RuntimeError('packet index incomplete')
        if min(lags)<-.25 or max(lags)>1:raise RuntimeError('packet epoch relationship unbounded')
        def window(start,end):
            selected=[p for p in packets if start<=p['timestamp_us']/1e6<end];n=sum(p['sampleCount'] for p in selected)
            return {'hostEpochWindow':[start,end],'packetCount':len(selected),'receivedPcmSeconds':n/96000,
                'rmsS16':math.sqrt(sum(p['sumSquares'] for p in selected)/n) if n else None,'peakS16':max((p['peakS16'] for p in selected),default=None)}
        window_raw=json.loads((folder/'route-window.json').read_text());request_epoch=window_raw['request']['hostUnixNs']/1e9
        active_epoch=window_raw['active']['hostUnixNs']/1e9;end_epoch=window_raw['ended']['hostUnixNs']/1e9
        baseline=window(packets[0]['timestamp_us']/1e6,request_epoch)
        response=window(request_epoch,end_epoch)
        covered=packets[0]['timestamp_us']/1e6<request_epoch and packets[-1]['timestamp_us']/1e6>=end_epoch
        samples=all(w['packetCount']>0 and w['rmsS16'] is not None for w in [baseline,response])
        contrast=bool(covered and samples and response['rmsS16']>3*baseline['rmsS16'] and response['peakS16']>3*baseline['peakS16'])
        observation.update(routeWindowCovered=covered,baseline=baseline,response=response,amplitudeContrastObserved=contrast,
            routeActiveHostEpochSeconds=active_epoch,packetCount=len(packets),pcmDurationSeconds=metadata['pcm_duration_seconds'],
            wallSeconds=metadata['wall_seconds'],pcmSha256=hashlib.sha256(pcm).hexdigest(),
            conclusion='Native route interval mixed-output amplitude contrast observed; voice source/content unverified' if contrast else 'Native navigation audible output not established in this one bounded probe',
            limits=['No VIP guard or driving output in this case; no contention experiment',
                    'Native-only UI operations can also emit sounds; PCM alone does not identify spoken route guidance',
                    'Sparse received PCM concatenation is not continuous wall-time audio; no GPS progression or microphone'])
        qb.save('packet-analysis.json',packets)
    except BaseException as error:errors.append('audio analysis: '+str(error))
    qb.save('audio-result.json',observation);qb.save('result.json',{'executionPassed':not errors,'issues':errors,**observation})
    finish_manifest();print(json.dumps({'nativeOnlyExecutionPassed':not errors,'issues':errors,'amplitudeContrastObserved':observation.get('amplitudeContrastObserved')}),flush=True)
    return int(bool(errors))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',action='store_true',required=True)
    parser.add_argument('--out',default='driving-audio-01');parser.add_argument('--native-only',action='store_true');args=parser.parse_args()
    if args.native_only:
        if not re.fullmatch(r'native-audio-probe-[A-Za-z0-9_-]+',args.out):parser.error('new native-audio-probe-* folder required')
        return native_only(BASE/args.out)
    if not re.fullmatch(r'driving-audio-[A-Za-z0-9_-]+',args.out):parser.error('new driving-audio-* folder required')
    folder=BASE/args.out;folder.mkdir(exist_ok=False);bind(folder)
    (folder/'harness-source.py').write_bytes(Path(__file__).read_bytes())
    qb.validate_policy_sources();results=[]
    # Observe the native route-off UI before selecting; do not cancel an unknown route.
    identity()
    runtime.require_stopped(runtime.snapshot('before-route-check'))
    try:
        qb.event('route_off_check_open')
        qb.adb('shell','am','start','-W','-n',runtime.NAVI+'/.MainActivity')
        runtime.wait_ui('native-route-off','어디로 갈까요',runtime.NAVI)
        qb.save('route-off-preflight.json',{'confirmed':True,'method':'actual native idle UI; no route cancellation'})
    finally:
        qb.adb('shell','am','start','-W','-n',PKG+'/.MainActivity')
        qb.ui('returned-before-driving',True)
    for name,enabled in [('both-on',True),('both-off',False)]:
        result,audio=driving_case(folder/name,enabled,record_video=enabled);results.append((result,audio))
        if not result['passed']:
            bind(folder);qb.save('result.json',{'passed':False,'reason':'case failed; queue stopped','cases':[r for r,a in results]});return 1
    issues=[];contrasts=[]
    on,off=results[0][1],results[1][1]
    if [a['kind'] for a in on['admissions']]!=[a['kind'] for a in off['admissions']]:issues.append('admission sequences differ')
    for a,b in zip(on['admissions'],off['admissions']):
        own=a['pre'];control=b['response'];response=a['response']
        if any(w['packetCount']<=0 or w['receivedPcmSeconds']<=0 or w['rmsS16'] is None or w['peakS16'] is None for w in [own,control,response]):
            issues.append(a['kind']+' comparison window has no received samples; not silence')
            contrasts.append({'kind':a['kind'],'on':response,'off':control,'onPre':own,'amplitudeContrastObserved':False,'reason':'missing comparison samples'})
            continue
        baseline_rms=max(own['rmsS16'] or 0,control['rmsS16'] or 0);baseline_peak=max(own['peakS16'] or 0,control['peakS16'] or 0)
        contrast=bool(response['rmsS16'] and response['rmsS16']>3*baseline_rms and response['peakS16']>3*baseline_peak)
        if not contrast:issues.append(a['kind']+' amplitude contrast not established')
        if a['kind']=='speech' and (a['lifecycle']['TTS_START']<1 or a['lifecycle']['TTS_DONE']<1 or a['lifecycle']['TTS_ERROR']):issues.append('driving TTS lifecycle incomplete/error')
        if b['lifecycle']['TTS_START'] or b['lifecycle']['TTS_DONE']:issues.append('OFF driving window contains TTS callback')
        contrasts.append({'kind':a['kind'],'on':response,'off':control,'onPre':own,'onLifecycle':a['lifecycle'],'offLifecycle':b['lifecycle'],
             'amplitudeContrastObserved':contrast,'criterion':'Both RMS and peak exceed 3x own pre-event and OFF response; an operational contrast, not perceptual/physical accuracy'})
    bind(folder);qb.save('result.json',{'passed':not issues,'issues':issues,'cases':[r for r,a in results],'contrasts':contrasts,
        'scope':'Same-session synthetic normal/WARN/OVER -> driving audio -> synthetic arrival; no navigation contention/intelligibility claim',
        'expectedApkSha256':EXPECTED});finish_manifest();print(json.dumps({'run':args.out,'passed':not issues,'issues':issues}),flush=True)
    return int(bool(issues))

if __name__=='__main__':raise SystemExit(main())
