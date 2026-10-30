"""Read existing evidence only; write derived results beside this offline verifier."""
from pathlib import Path
import array
from collections import Counter
from datetime import datetime
import hashlib
import json
import math
import re
import sys
import wave
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[3]
SHA = '8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c'

def read(path):
    return json.loads(path.read_text())

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def texts(path):
    return [n.get('text') for n in ET.parse(path).iter('node') if n.get('text')]

def toggles(path):
    tree = ET.parse(path)
    bounds = lambda n: [int(x) for x in re.findall(r'\d+',n.get('bounds'))]
    result = {}
    for key, label in [('tts', '음성 코칭 (TTS)'), ('chime', '경고음')]:
        n = next(n for n in tree.iter('node') if n.get('text') == label)
        box = bounds(n)
        center_y = (box[1]+box[3])/2
        candidates = [x for x in tree.iter('node') if x.get('checkable') == 'true' and bounds(x)[0]>=box[2] and bounds(x)[1]<=center_y<=bounds(x)[3]]
        if len(candidates)==1:
            result[key] = candidates[0].get('checked') == 'true'
    return result

class Review:
    def __init__(self):
        self.checks = []
    def check(self, label, condition):
        self.checks.append({'check': label, 'passed': bool(condition)})
    def hashes(self, folder, name):
        manifest = read(folder/name)
        mismatches = [n for n, h in manifest.items() if not (folder/n).is_file() or digest(folder/n) != h]
        self.check(name+' hashes', not mismatches)
        return {'count': len(manifest), 'mismatches': mismatches}
    def save(self, name, data):
        data.update(checks=self.checks, check_count=len(self.checks), errors=[x['check'] for x in self.checks if not x['passed']])
        data['verdict'] = 'PASS_WITH_SCOPE_LIMITATIONS' if not data['errors'] else 'FAIL'
        (OUT/name).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
        print(name, data['verdict'], len(self.checks), 'checks', data['errors'])

candidate = ROOT/'.omx/qa/submission-v22/candidates/runtime-06'
candidate_hash = digest(candidate/'app-pleos-release.apk')
manifest = read(candidate/'source-sha256.json')
source_mismatches = [n for n, h in manifest.items() if digest(ROOT/'app'/n) != h]
identity = {'expected_apk_sha256': SHA, 'actual_apk_sha256': candidate_hash,
            'apk_bytes': (candidate/'app-pleos-release.apk').stat().st_size,
            'source_count': len(manifest), 'source_mismatches': source_mismatches}

b = ROOT/'.omx/qa/submission-v22/known-boundary-02'
v = Review()
v.check('runtime06 candidate hash and 21 source manifest entries', candidate_hash == SHA and len(manifest) == 21 and not source_mismatches)
hashes = v.hashes(b, 'artifact-sha256.json')
env, result = read(b/'environment.json'), read(b/'oracle-result.json')
v.check('installed hash', env['installedApkSha256'] == SHA)
v.check('core source hashes', all(digest(ROOT/n) == h for n, h in env['sourceHashes'].items()))
traces = [json.loads(l[l.index('{'):]) for l in (b/'motion.log').read_text().splitlines() if 'MotionTrace' in l and '{' in l]
traces = [r for r in traces if r['tMs'] >= env['traceStartElapsedMs']]
v.check('raw MotionTrace matches retained trace JSON', traces == read(b/'trace.json'))
guards = [r for r in traces if r['state'] == 'GUARDING']
arrivals = [r for r in traces if r['event'] == 'arrival']
v.check('record counts', len(traces) == result['traceRows'] == 439 and len(guards) == result['guardRows'] == 435 and len(arrivals) == 1)
v.check('one guard session', len({r['trip']['startedMs'] for r in guards}) == 1)
fixture = dict(wheelbaseM=2.9000000953674316, steeringRatio=15, smoothing=.5, gravityMps2=9.80665, maxBrakeDecelMps2=8)
v.check('fixed model all rows', all(r['model'] == fixture for r in traces))
v.check('fixed model guard retained in harness', 'model != expected_model' in (b/'harness-source.py').read_text())
prev_t = prev_v = None
ema = max_g = 0.
motion_count = fresh_count = speech = chime = 0
badge = 'OK'
last_badge = last_warn = last_coach = last_audible = -10**18
math_issues, badge_issues, admissions, suppression, dts = [], [], [], Counter(), []
for i, r in enumerate(guards):
    raw, snap, est, trip, t = r['raw'], r['snapshot'], r['estimate'], r['trip'], r['tMs']
    fresh = False
    if raw['kind'] == 'speed':
        if prev_t is not None:
            dt = (t-prev_t)/1000
            dts.append(dt)
            fresh = .05 <= dt <= 2.
            ema = .5*(raw['mps']-prev_v)/dt + .5*ema
        prev_t, prev_v = t, raw['mps']
    lat = abs(snap['speedMps']**2 * math.tan(math.radians(snap['steeringDeg']/15)) / fixture['wheelbaseM'])/9.80665
    lon = ema/9.80665
    total = math.hypot(lat, lon)
    if any(not math.isclose(est[k], value, abs_tol=1e-10, rel_tol=1e-10) for k, value in [('lateralG', lat), ('longitudinalG', lon), ('totalG', total)]):
        math_issues.append(i)
    if not snap['parkedStill']:
        max_g = max(max_g, total)
        motion_count += 1
        fresh_count += fresh
    if est['longitudinalFresh'] != fresh or trip['gSamples'] != motion_count or trip['freshLongitudinalSamples'] != fresh_count or not math.isclose(trip['maxG'], max_g, abs_tol=1e-10):
        math_issues.append(i)
    proposals = []
    if snap['parkedStill']:
        badge = 'OK'
    else:
        level = 'OVER' if total >= .12 else 'WARN' if total >= .096 else 'OK'
        if level == 'OK':
            if badge != 'OK' and t-last_badge >= 3000:
                badge, last_badge = 'OK', t
        else:
            entering = badge == 'OK' or (badge == 'WARN' and level == 'OVER')
            badge, last_badge = level, t
            parking = snap['speedMps'] < 15/3.6 and lat >= abs(lon)
            if entering and not parking and t-last_warn >= 5000:
                proposals.append('chime')
            if level == 'OVER' and not parking and t-last_coach >= 8000:
                proposals.append('speech')
    if proposals:
        reason = ('SIGNAL_UNAVAILABLE' if snap['gear'] == 'PARK' or r['motionStatus'] in ('STALE','DISCONNECTED') else
                  'EMERGENCY' if total >= .6 else 'TRIP_LIMIT' if speech+chime >= 6 else 'COOLDOWN' if t-last_audible < 8000 else None)
        selected = None if reason else 'speech' if 'speech' in proposals and speech < 3 else 'chime' if 'chime' in proposals else None
        reason = reason or ('VOICE_LIMIT' if selected is None else 'COALESCED' if len(proposals)>1 else None)
        if reason: suppression[reason] += len(proposals)-int(selected is not None)
        if selected:
            admissions.append({'tMs': t, 'kind': selected})
            last_audible = t
            if selected == 'speech': speech += 1; last_coach = t
            else: chime += 1; last_warn = t
    if r['badge'] != badge or trip['drivingSpeechCount'] != speech or trip['drivingChimeCount'] != chime or trip['coachingCount'] != speech or trip['warnCount'] != chime:
        badge_issues.append(i)
v.check('independent G and running count oracle', not math_issues)
v.check('independent badge/admission oracle', not badge_issues)
v.check('received dt supported and reported bounds', min(dts) == result['receivedDtMinSeconds'] and max(dts) == result['receivedDtMaxSeconds'] and all(.05 <= d <= 2 for d in dts))
v.check('final counters and maximum', motion_count == result['motionEvents'] == 416 and fresh_count == result['freshLongitudinalSamples'] == 139 and math.isclose(max_g, result['expectedMaximumG'], abs_tol=1e-10))
v.check('admissions and coalescing', admissions == result['audioOracle']['admissions'] and speech == 2 and chime == 1 and dict(suppression) == {'COALESCED':1})
ledger = [json.loads(l) for l in (b/'input-ledger.jsonl').read_text().splitlines()]
phases = {'baseline','acceleration','settle','ok60','below90','warn100','over120','recover60','straight','deceleration','stop','park'}
commands = [e for e in ledger if e['event'] == 'command_start' and e.get('phase') in phases and e.get('command') in ('speed','steer','brake')]
expected_raw = [('speed', float(e['value'])/3.6) if e['command']=='speed' else ('steering', e['value']) if e['command']=='steer' else ('brake',e['value']) for e in commands]
actual_raw = [(r['raw']['kind'], r['raw'].get('mps', r['raw'].get('degrees',r['raw'].get('percent')))) for r in guards]
v.check('all speed steering brake ledger requests received in order', len(expected_raw)==len(actual_raw) and all(a[0]==c[0] and math.isclose(float(a[1]),float(c[1]),abs_tol=1e-12) for a,c in zip(expected_raw,actual_raw)))
speeds = sum(r['raw']['kind']=='speed' for r in guards)
v.check('speed count', speeds == result['receivedSpeedRows'] == 145)
v.check('same PID at observed surfaces', all(e['pid']==env['pid'] for e in ledger if 'pid' in e))
report = arrivals[0]['report']
v.check('arrival report transfer', arrivals[0]['state']=='ARRIVED' and report['gSamples']==motion_count and report['freshLongitudinalSamples']==fresh_count and math.isclose(report['maxG'],max_g,abs_tol=1e-10) and report['drivingSpeechCount']==speech and report['drivingChimeCount']==chime and report['audioSuppressionCounts']==dict(suppression))
v.check('synthetic noncomparability', report['hasSyntheticInput'] and not report['canCompare'] and report['provenance']==['SYNTHETIC'] and '실주행 평가 불가' in texts(b/'report.xml'))
v.check('cleanup final Idle P0', traces[-1]['state']=='IDLE' and traces[-1]['snapshot']['gear']=='PARK' and traces[-1]['snapshot']['speedMps']==0 and '조수석에 무엇을 실으셨나요?' in texts(b/'cleanup.xml'))
log = (b/'motion.log').read_text()
def log_time(fragment):
    return float(next(l.split()[0] for l in log.splitlines() if fragment in l))
arrival_log, drain_log, end_log = map(log_time, ['도착 (내비 목적지 도착)', '가드 서비스 종료: 요청 2', 'I DemoBridge: end'])
v.check('drain return before cleanup End', arrival_log <= drain_log < end_log and '가드 서비스 종료: 요청 2 · 공조 처리 정리' in texts(b/'report.xml'))
before, after = read(b/'vehicle-before.json'), read(b/'vehicle-after.json')
fields = {'hvac':('power_on','auto_on','ac_on','dual_on','sync_on','recirc_on','front_defroster_on','rear_defroster_on','fan_speed','auto_blower_level','driver','passenger','row2'), 'seat':('positions','moves','occupied'), 'body':('doors','door_locks','windows','closures','wipers','cabin_light','reading_lights','ambient_light'), 'controls':('speed_kph','accelerator_pct','brake_pct','steering_deg')}
v.check('vehicle cleanup readback fields', all(before[d][k]==after[d][k] for d,keys in fields.items() for k in keys) and after['gear']['actual']['value']=='Park')
v.check('settings raw XML muted and restored', toggles(b/'settings_muted_after_chime.xml')=={'tts':False,'chime':False} and toggles(b/'settings_original.xml')==toggles(b/'settings_restored_after_chime.xml')=={'tts':True,'chime':True})
v.check('no recorded execution or cleanup errors', read(b/'cleanup.json')['verified'] and not any(e['event']=='execution_error' for e in ledger) and not read(b/'cleanup.json')['executionErrors'] and not read(b/'cleanup.json')['cleanupErrors'])
v.save('boundary-verification.json', {'candidate':identity,'artifact_hashes':hashes,'derived':{'trace_rows':len(traces),'guard_rows':len(guards),'speed_rows':speeds,'motion_events':motion_count,'fresh_longitudinal_samples':fresh_count,'maximum_g':max_g,'admissions':admissions,'suppression':dict(suppression),'admission_intervals_ms':[b['tMs']-a['tMs'] for a,b in zip(admissions,admissions[1:])],'arrival_state':arrivals[0]['state'],'final_state':traces[-1]['state'],'drain_after_arrival_ms':round((drain_log-arrival_log)*1000),'drain_before_cleanup_end_ms':round((end_log-drain_log)*1000)}, 'limits':['Foreground DEMO synthetic input; not native speed accuracy or physical cargo protection.','COALESCED and admitted intervals observed; voice/trip budget saturation and cooldown rejection not exercised.','Arrival reaches ARRIVED/report; final Idle follows explicit cleanup End/ack.','Service-finish log and responsive report show drain progressed; no service dump in this dataset.','Property restoration is harness readback assertion; raw property-after output not separately retained.','Filtered logs and one bounded run do not establish universal ANR absence.']})

a = ROOT/'.omx/qa/audio-feasibility/matrix_03'
v = Review()
v.check('runtime06 candidate identity and current source', candidate_hash==SHA and not source_mismatches)
hashes = v.hashes(a,'artifact-sha256-runtime06.json')
env = read(a/'environment.json')
v.check('installed before/after same candidate and PID', all(env[x]['installed_base_apk_sha256']==SHA for x in ('before','after')) and env['before']['pid']==env['after']['pid'])
cases = []
for name, tts, chime in read(a/'scope.json')['cases']:
    p=a/name; cap=p/'capture'
    md, pm, analysis, trigger = read(cap/'metadata.json'),read(cap/'packet_manifest.json'),read(p/'analysis.json'),read(p/'trigger.json')
    pcm=(cap/'audio.s16le.pcm').read_bytes()
    payloads=[(cap/r['raw_file']).read_bytes() for r in pm]
    v.check(name+' packet concat equals PCM', b''.join(payloads)==pcm)
    with wave.open(str(cap/'audio.wav'),'rb') as wav:
        v.check(name+' WAV header/payload equals PCM', (wav.getframerate(),wav.getnchannels(),wav.getsampwidth())==(48000,2,2) and wav.readframes(wav.getnframes())==pcm)
    calculated=[]; offset=0; packet_errors=[]
    start_ns=trigger['started']['host_unix_ns']
    capture_start_ns=round(datetime.fromisoformat(md['started_utc']).timestamp()*1e9)
    clock_residual=[]
    for i,(row,orig,payload) in enumerate(zip(pm,md['packets'],payloads)):
        samples=array.array('h',payload)
        if sys.byteorder!='little': samples.byteswap()
        n=len(samples); sq=sum(x*x for x in samples); peak=max(map(abs,samples),default=0)
        delta=(row['timestamp_us']*1000-start_ns)/1e9
        if not (row['index']==i+1 and row['pcm_byte_offset']==offset and row['bytes']==len(payload) and row['frames']*4==len(payload) and row['sample_count']==n and row['sum_squares']==sq and row['peak_absolute_s16']==peak and math.isclose(row['rms_s16'], math.sqrt(sq/n), abs_tol=1e-12) and math.isclose(row['relative_to_command_start_seconds'],delta,abs_tol=1e-6) and all(row[k]==orig[k] for k in orig)):
            packet_errors.append(i)
        offset += len(payload)
        calculated.append((delta,n,sq,peak))
        clock_residual.append((row['timestamp_us']*1000-capture_start_ns)/1e9-row['host_elapsed_seconds'])
    v.check(name+' every packet metadata/statistic/offset', not packet_errors and len(pm)==len(md['packets'])==md['packet_count']==analysis['packet_count'])
    v.check(name+' monotonic packet stamps', all(x['timestamp_us']<=y['timestamp_us'] for x,y in zip(pm,pm[1:])))
    windows={}
    for label,(lo,hi) in {'pre_event':(-3,0),'response_0_to_8s':(0,8),'late_8_to_12s':(8,12)}.items():
        selected=[r for r in calculated if lo<=r[0]<hi]
        n=sum(r[1] for r in selected);sq=sum(r[2] for r in selected)
        values={'window_seconds':[lo,hi],'packet_count':len(selected),'captured_pcm_seconds':n/96000,'peak_absolute_s16':max(r[3] for r in selected),'rms_s16_over_received_samples':math.sqrt(sq/n)}
        windows[label]=values
        v.check(name+' '+label+' independently recomputed', all(values[k]==analysis['windows'][label][k] for k in values))
    all_n=sum(r[1] for r in calculated);all_sq=sum(r[2] for r in calculated)
    v.check(name+' overall PCM metrics/hash', digest(cap/'audio.s16le.pcm')==md['pcm_sha256'] and len(pcm)==md['bytes_written']==all_n*2 and all_n==md['sample_count'] and all_n/96000==md['pcm_duration_seconds'] and math.isclose(math.sqrt(all_sq/all_n)/32768,md['rms_normalized'],abs_tol=1e-12) and max(r[3] for r in calculated)==md['peak_absolute_s16'])
    v.check(name+' duration-limit transport complete', md['stop_reason']=='duration_limit' and md['capture_status']=='CAPTURED' and md['connection_status']=='READY' and md['exit_code']==read(p/'capture_process.json')['exit_code']==0 and md['rpc_status'] in ('CANCELLED','DEADLINE_EXCEEDED') and 14.9<md['wall_seconds']<15.1)
    v.check(name+' settings raw XML', toggles(p/'settings_confirmed.xml')=={'tts':tts,'chime':chime})
    ui_text=texts(p/'after.xml')
    v.check(name+' COFFEE and zero cabin changes', '커피' in ui_text and '캐빈 적용: 0개 성공, 0개 미적용' in ui_text)
    log=(p/'app_logcat.txt').read_text()
    life={k:len(re.findall(r'\b'+k+r'\b',log)) for k in ('TTS_START','TTS_DONE','TTS_ERROR')}
    v.check(name+' filtered TTS lifecycle',life=={'TTS_START':int(tts),'TTS_DONE':int(tts),'TTS_ERROR':0})
    cases.append({'case':name,'packets':len(pm),'pcm_bytes':len(pcm),'pcm_sha256':md['pcm_sha256'],'windows':windows,'pcm_seconds':md['pcm_duration_seconds'],'wall_seconds':md['wall_seconds'],'tts':life,'packet_clock_residual_seconds':[min(clock_residual),max(clock_residual)]})
v.check('original audio toggles restored XML',toggles(a/'original_settings.xml')==toggles(a/'restored_settings_confirmed.xml')=={'tts':True,'chime':True})
v.check('final UI Idle P0 and service drain message', '조수석에 무엇을 실으셨나요?' in texts(a/'final_ui.xml') and '0 km/h' in texts(a/'final_ui.xml') and 'P' in texts(a/'final_ui.xml') and '가드 서비스 종료: 요청 15 · 공조 처리 정리' in texts(a/'final_ui.xml'))
v.check('summary cleanup no failure',read(a/'summary.json')['failure'] is None and read(a/'summary.json')['cleanup']['completed'] and read(a/'summary.json')['cleanup']['settings_restored'])
clock=read(a/'clock_sync.json')
offset=int(clock['device_epoch_ns_text'])-(clock['host_before']['host_unix_ns']+clock['host_after']['host_unix_ns'])//2
uncertainty=(clock['host_after']['host_unix_ns']-clock['host_before']['host_unix_ns'])/2
v.save('audio-verification.json',{'candidate':identity,'artifact_hashes':hashes,'cases':cases,'total_packets':sum(c['packets'] for c in cases),'clock':{'device_minus_host_ns':offset,'half_roundtrip_ns':uncertainty,'window_clock':'capture packet epoch empirically tracks capture host epoch, not device logcat epoch; windows are relative to host command start'},'limits':['Packet files are post-capture slices of the PCM, not an independent network packet recording; equality verifies faithful segmentation.','RMS uses received samples and duration is packet concatenation, not wall-time silence or utterance length.','ON/OFF amplitude contrast only; no intelligibility or physical speaker/cargo/driver burden proof.','COFFEE selection: TTS-only, chime-only and two both-off controls. Both-on and navigation/media contention untested.','TTS_ERROR=0 is limited to selected app log tags.','Cleanup establishes XML settings/Idle P0/drain log; this dataset contains no final service/notification dump.','Clock sync is a single point; no drift bound. Packet windows are not aligned to device logcat without clock conversion.']})
