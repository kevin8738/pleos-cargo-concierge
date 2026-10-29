"""Offline raw-log and readback verification. Never imports/runs an AVD harness."""
from pathlib import Path
import ast
import hashlib
import json
import re
import xml.etree.ElementTree as ET

OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[3]
BASE=ROOT/'.omx/qa/submission-v22'
SHA='8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c'
# Reuse only pure evidence utilities, not module-level execution.
tree=ast.parse((OUT/'verify_boundary_audio.py').read_text())
exec(compile(ast.Module(body=[x for x in tree.body if isinstance(x,(ast.FunctionDef,ast.ClassDef)) and x.name in {'read','digest','texts','toggles','Review'}],type_ignores=[]),'<pure-evidence-helpers>','exec'))

def jsonlines(p):
    return [json.loads(l) for l in p.read_text().splitlines()]

def event(rows,name):
    return next(r for r in rows if r['event']==name)

def parse_log(folder, v):
    rows=jsonlines(folder/'live-log.jsonl')
    v.check(str(folder.relative_to(BASE))+' raw/log rows equal',[r['line'] for r in rows]==[l.rstrip() for l in (folder/'live.log').read_text().splitlines()])
    errors=[]
    for i,r in enumerate(rows):
        m=re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+([^:]+):\s?(.*)',r['line'])
        if not m: continue
        if r.get('deviceEpochSeconds')!=float(m[1]) or r.get('pid')!=int(m[2]) or r.get('tag')!=m[4].strip() or r.get('message')!=m[5]: errors.append(i)
        if 'data' in r and r['data']!=json.loads(m[5]): errors.append(i)
    v.check(str(folder.relative_to(BASE))+' independently parsed fields',not errors)
    return rows

def snapshot(p,v):
    r=read(p);prefix=p.name.removesuffix('-snapshot.json')
    service=(p.parent/(prefix+'-service.txt')).read_text()
    process=(p.parent/(prefix+'-process.txt')).read_text()
    notification=(p.parent/(prefix+'-notification.txt')).read_text()
    present='GuardForegroundService' in service and 'ServiceRecord{' in service
    mask=re.search(r'isForeground=true[^\n]*types=([\da-fA-F]+)',service)
    mask=int(mask[1],16) if mask else None
    frozen=re.search(r'isFrozen=(true|false)',process)
    count=len(re.findall(r'NotificationRecord\(',notification))
    valid=(r['servicePresent']==present and r['foregroundService']==(present and 'isForeground=true' in service) and r['serviceTypeMask']==mask and r['activeNotificationCount']==count and r['isFrozen']==(frozen[1]=='true' if frozen else None))
    valid=valid and (not r['pid'] or r['pid'] in process)
    if count: valid=valid and 'pkg=ai.vipseat.cargoconcierge' in notification and 'id=7301' in notification
    v.check(str(p.relative_to(BASE))+' raw snapshots', valid)
    return r

def cabin_readback(folder,v):
    before,after=read(folder/'vehicle-before.json'),read(folder/'vehicle-after.json')
    fields={'hvac':('power_on','auto_on','ac_on','dual_on','sync_on','recirc_on','fan_speed','auto_blower_level','driver','passenger','row2','front_defroster_on','rear_defroster_on'), 'seat':('positions','moves','occupied'), 'body':('doors','door_locks','windows','closures'), 'controls':('speed_kph','accelerator_pct','brake_pct','steering_deg')}
    v.check(folder.name+' unchanged measured cabin fields and Park',all(before[d][k]==after[d][k] for d,keys in fields.items() for k in keys) and after['gear']['actual']['value']=='Park')
    v.check(folder.name+' audio settings restored raw XML',toggles(folder/'settings-original.xml')==toggles(folder/'settings-restored_after_chime.xml')=={'tts':True,'chime':True})

def observed_hashes(folder):
    return {str(p.relative_to(folder)):digest(p) for p in sorted(folder.rglob('*')) if p.is_file()}

def is_write(r):
    m=r.get('message','')
    return r.get('tag') in ('CargoVM','PleosVehicle') and (('캐빈 적용' in m and '새 캐빈 적용 없음' not in m) or re.search(r'\b(apply|restore) \w+:',m) or '원복 ' in m)

v=Review();folder=BASE/'fgs-failure-03'
env=read(folder/'environment.json')
v.check('candidate actual and installed hash',digest(BASE/'candidates/runtime-06/app-pleos-release.apk')==env['installedApkSha256']==SHA)
v.check('Android14 user10 package-scoped operation',env['sdk']==34 and env['user']=='10' and env['packageUid']=='1010175' and env['operation']=='START_FOREGROUND')
mode_re=r'START_FOREGROUND:\s*(allow|ignore|deny|default)'
mode=lambda p:re.search(mode_re,p.read_text())[1]
v.check('exact package original mode restored',mode(folder/'original-appop.txt')==mode(folder/'finally-restored-appop.txt')=='allow')
v.check('UID override unchanged raw', (folder/'original-uid-appop.txt').read_text()==(folder/'finally-uid-appop.txt').read_text()=='No operations.\nDefault mode: allow\n')
cases=[]
for name in ('ignore','deny'):
    p=folder/name;ledger=jsonlines(p/'input-ledger.jsonl');rows=parse_log(p,v);record=read(p/'result.json')
    lo=event(ledger,'fault_preset_request')['hostUnixNs'];hi=event(ledger,'fault_observation_end')['hostUnixNs']
    selected=[r for r in rows if lo<=r['hostReceivedUnixNs']<hi]
    ready=[r for r in selected if r.get('tag')=='GuardRuntime' and r.get('data',{}).get('event')=='ready']
    motions=[r for r in selected if r.get('tag')=='MotionTrace' and 'data' in r]
    guarded=[r for r in motions if r['data'].get('state')=='GUARDING']
    writes=[r for r in selected if is_write(r)]
    failures=[r for r in selected if r.get('tag')=='CargoVM' and '가드 시작 실패: 요청' in r.get('message','')]
    v.check(name+' precise fault window and no ready guard cabin',not ready and not guarded and not writes and len(motions)==record['observedMotionTraces']==17 and len(failures)==1 and hi-lo>=8e9)
    latency=(failures[0]['hostReceivedUnixNs']-lo)/1e9
    v.check(name+' failure latency',latency==record['failureRequestLatencySeconds'] and latency<2)
    sysrows=jsonlines(p/'system-filtered.jsonl')
    v.check(name+' system raw/log equality',[r['line'] for r in sysrows]==[l.rstrip() for l in (p/'system-filtered.log').read_text().splitlines()])
    sysrows=[r for r in sysrows if lo<=r['hostReceivedUnixNs']<hi]
    silent=[r for r in sysrows if 'Service.startForeground() not allowed due to app op' in r['line']]
    v.check(name+' actual silent-ignore branch',len(silent)==record['silentIgnorePromotionLogs']==int(name=='ignore'))
    v.check(name+' appop injected/restored readback',mode(p/'injected-appop.txt')==name and mode(p/'restored-appop.txt')=='allow')
    v.check(name+' failure notice and idle controls',any('가드 실행을 시작하지 못했어요' in t for t in texts(p/'failure-ui.xml')) and '프리셋 고르기' in texts(p/'idle-after-failure.xml'))
    snaps=[snapshot(q,v) for q in sorted(p.glob('*-snapshot.json'))]
    after=next(s for s in snaps if s['label']=='after-failure');stopped=next(s for s in snaps if s['label']=='recovery-stopped')
    v.check(name+' failed and recovery-End service notification absent',not after['servicePresent'] and after['activeNotificationCount']==0 and not stopped['servicePresent'] and stopped['activeNotificationCount']==0)
    recovery=sorted((s for s in snaps if s['label'].startswith('recovery-active_')),key=lambda s:s['hostUnixNs'])
    v.check(name+' every recovery sample foreground same pid typed',all(s['pid']=='26822' and s['isFrozen'] is False and s['foregroundService'] and s['serviceTypeMask']==0x40000008 for s in recovery))
    recovery_start=event(ledger,'recovery_normal_start')['hostUnixNs']
    recovered_ready=[r for r in rows if r['hostReceivedUnixNs']>=recovery_start and r.get('tag')=='GuardRuntime' and r.get('data',{}).get('event')=='ready']
    v.check(name+' one matching recovery ready',len(recovered_ready)==1 and recovered_ready[0]['data']==record['recovery']['ready'])
    posted=next(s for s in recovery if s['activeNotificationCount']==1)
    cases.append({'mode':name,'fault_window_seconds':(hi-lo)/1e9,'failure_latency_seconds':latency,'ready':0,'guard_traces':0,'cabin_operation_logs':0,'motion_traces':len(motions),'system_silent_ignore_rows':len(silent),'recovery_first_foreground_snapshot_seconds':(recovery[0]['hostUnixNs']-recovery_start)/1e9,'recovery_first_notification_snapshot_seconds':(posted['hostUnixNs']-recovery_start)/1e9,'recovery_samples':len(recovery)})
cabin_readback(folder,v)
for q in folder.glob('*-snapshot.json'): snapshot(q,v)
cleanup=read(folder/'cleanup.json')
v.check('cleanup assertion no execution errors',cleanup['verified'] and not cleanup['executionErrors'] and not cleanup['cleanupErrors'])
v.save('fgs-verification.json',{'candidate_sha256':SHA,'cases':cases,'review_input_sha256':observed_hashes(folder),'hash_scope':'Input hashes generated at independent review; no pre-existing artifact manifest in this dataset. Installed hash is recorded by harness plus candidate APK recomputed here.','limits':['Android14 only; API28 and API29-33 fallback not measured.','Deny confirms immediate rejected request, not a recorded exception class/stack trace.','No ready/cabin operation in live selected logs plus unchanged readback is not bus-level no-write proof.','Notification first-seen times are sampled posting bounds, not foreground-promotion latency.','Failure windows use host receipt times and are separate from recovery; no device clock calibration retained in this probe.','Log-property restoration is harness assertion without separately retained raw after value.']})

v=Review();folder=BASE/'runtime-avd-03';env=read(folder/'environment.json');result=read(folder/'result.json')
v.check('installed runtime06 hash',env['installedApkSha256']==SHA)
rows=parse_log(folder,v);ledger=jsonlines(folder/'input-ledger.jsonl')
c=env['clockCalibration'];offset=c['deviceEpochNs']-(c['hostBeforeNs']+c['hostAfterNs'])//2;unc=(c['hostAfterNs']-c['hostBeforeNs'])//2
v.check('clock arithmetic',offset==c['deviceMinusHostNs'] and unc==c['uncertaintyNs'])
w=env['navigationWindow'];n=read(folder/'navigation-window.json')
v.check('window boundaries from raw ledger',all(w[k]==event(ledger,e)['hostUnixNs'] for k,e in [('startHostNs','native_route_request'),('endRequestHostNs','native_route_end_request'),('returnHostNs','vip_return_start'),('backgroundStartHostNs','guard_background_activity')]))
v.check('direct hidden observation',n['background']['vipVisible'] is False and n['background']['vipActivityState']=='STOPPED' and n['background']['pid']==env['originalPid'])
def within(r,lo,hi,strict=False):
    if 'deviceEpochSeconds' not in r:return False
    t=round(r['deviceEpochSeconds']*1e9)
    return lo<=r['hostReceivedUnixNs']<hi and lo+offset+(unc if strict else -unc)<=t<hi+offset+(-unc if strict else unc)
windows={}
for label,lo,hi in [('route_request_to_return',w['startHostNs'],w['returnHostNs']),('direct_hidden_before_end',w['backgroundStartHostNs'],w['endRequestHostNs']),('direct_hidden_to_return',w['backgroundStartHostNs'],w['returnHostNs'])]:
    windows[label]={'host_duration_seconds':(hi-lo)/1e9}
    for strict in (False,True):
        selected=[r for r in rows if within(r,lo,hi,strict)]
        nav=[r for r in selected if r.get('tag')=='PleosNavi'];ticks=[r for r in selected if r.get('tag')=='GuardRuntime' and r.get('data',{}).get('event')=='tick']
        counts={'route_drive':sum('navigation-status=ROUTE_DRIVE' in r['message'] for r in nav),'tbt':sum('SDK callback tbt' in r['message'] for r in nav),'free_drive':sum('navigation-status=FREE_DRIVE' in r['message'] for r in nav),'ticks':len(ticks),'max_tick_gap_ms':max((y['data']['elapsedMs']-x['data']['elapsedMs'] for x,y in zip(ticks,ticks[1:])),default=None)}
        windows[label]['conservative' if strict else 'harness_tolerance']=counts
        if label=='direct_hidden_before_end':
            v.check('hidden ticks same session, visible false '+str(strict),len(ticks)>=15 and counts['max_tick_gap_ms']<=2000 and all(r['data']['visible'] is False and r['data']['guarding'] is True and r['pid']==int(env['originalPid']) and all(r['data'][k]==value for k,value in env['session'].items()) for r in ticks))
            if not strict:v.check('saved background ticks match raw selected',ticks==read(folder/'background-ticks.json') and len(ticks)==result['backgroundTicks'] and counts['max_tick_gap_ms']==result['maximumTickGapMs'])
        if label=='route_request_to_return' and not strict:
            v.check('saved callback rows and counts match raw',nav==read(folder/'live-navigation-callbacks.json') and counts['route_drive']==result['liveRouteDrive']==1 and counts['tbt']==result['liveTbt']==12 and counts['free_drive']==result['liveFreeDrive']==1)
v.check('no DEMO in directly observed background window',not any(r.get('tag')=='DemoBridge' and w['backgroundStartHostNs']<=r['hostReceivedUnixNs']<w['endRequestHostNs'] for r in rows))
snapshots=[snapshot(q,v) for q in sorted(folder.glob('*-snapshot.json'))]
active=[s for s in snapshots if s['label'].startswith(('guard_foreground','background_'))]
v.check('all active snapshots same PID foreground location and notification',all(s['pid']==env['originalPid'] and s['isFrozen'] is False and s['foregroundService'] and s['serviceTypeMask']==0x40000008 and s['activeNotificationCount']==1 for s in active))
stopped=next(s for s in snapshots if s['label']=='after_guard_end_0')
v.check('post-End service/notification absent',not stopped['servicePresent'] and stopped['activeNotificationCount']==0)
ready=[r for r in rows if r.get('tag')=='GuardRuntime' and r.get('data',{}).get('event')=='ready'];finished=[r for r in rows if r.get('tag')=='GuardRuntime' and r.get('data',{}).get('event')=='finished']
v.check('one ready/finished same request',len(ready)==len(finished)==1 and ready[0]['data']['requestId']==finished[0]['data']['requestId']==env['session']['requestId'])
cabin_readback(folder,v)
v.check('cleanup assertion no errors',read(folder/'cleanup.json')['verified'] and not read(folder/'cleanup.json')['executionErrors'] and not read(folder/'cleanup.json')['cleanupErrors'])
v.check('cleanup UI idle and service absent','프리셋 고르기' in texts(folder/'cleanup-idle.xml') and not next(s for s in snapshots if s['label']=='cleanup_idle')['servicePresent'])
v.save('native-verification.json',{'candidate_sha256':SHA,'clock':c,'windows':windows,'session':env['session'],'pid':env['originalPid'],'end_to_no_service_snapshot_seconds':(stopped['hostUnixNs']-event(ledger,'guard_end_request')['hostUnixNs'])/1e9,'review_input_sha256':observed_hashes(folder),'hash_scope':'Review-generated input hashes; dataset has no prior artifact manifest.','limits':['One tick falls within clock uncertainty at the hidden observation start; 21 with harness tolerance, 20 conservative.','ROUTE_DRIVE 1/TBT12/FREE1 use route-request-to-return, not the directly observed hidden interval.','Stopped stationary official AVD, public route and live callbacks; no actual route progression or real vehicle accuracy.','Host receipt and calibrated device timestamp both bounded; single calibration gives no long-term drift bound.','Readback and selected logs are not a vehicle bus or physical temperature measurement.','Property restore/forward ownership are harness assertions; no separate final raw proof.']})
