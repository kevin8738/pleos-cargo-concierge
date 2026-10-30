"""Bounded offline lifecycle/access readback verification; no harness execution."""
from pathlib import Path
import ast
import hashlib
import json
import re
import xml.etree.ElementTree as ET

OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[3];BASE=ROOT/'.omx/qa/submission-v22'
SHA='8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c'
for name,wanted in [('verify_boundary_audio.py',{'read','digest','texts','toggles','Review'}),('verify_fgs_native.py',{'jsonlines','event','parse_log','snapshot','cabin_readback','observed_hashes'})]:
    tree=ast.parse((OUT/name).read_text())
    exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n,(ast.FunctionDef,ast.ClassDef)) and n.name in wanted],type_ignores=[]),'<pure-helpers>','exec'))

v=Review();cases=[]
for name in ['runtime-force-stop-02','runtime-shell-task-removal-01']:
    p=BASE/name;result=read(p/'result.json');env=read(p/'environment.json') if (p/'environment.json').exists() else result
    v.check(name+' candidate',env['installedApkSha256']==SHA)
    rows=parse_log(p,v);ledger=jsonlines(p/'input-ledger.jsonl')
    snaps=[snapshot(q,v) for q in sorted(p.glob('*-snapshot.json'))]
    shell='shell' in name
    lo=result['removeRequest']['hostUnixNs'] if shell else env['interruptedHostNs']
    hi=result['manualRelaunch']['hostUnixNs'] if shell else env['manualRelaunchHostNs']
    loss=result['lost']['hostReceivedUnixNs'] if shell else lo
    selected=[r for r in rows if r['hostReceivedUnixNs']>=lo]
    renewed=[r for r in selected if r.get('tag')=='GuardRuntime' and (r.get('data',{}).get('event')=='ready' or r['hostReceivedUnixNs']>loss and r.get('data',{}).get('event')=='tick')]
    guarded=[r for r in selected if r['hostReceivedUnixNs']>loss and r.get('tag')=='MotionTrace' and r.get('data',{}).get('state')=='GUARDING']
    writes=[r for r in selected if r.get('tag') in ('CargoVM','PleosVehicle') and ('캐빈 적용 →' in r.get('message','') or re.search(r'\b(apply|restore) \w+: APPLIED\b',r.get('message','')))]
    v.check(name+' no renewal guarding or observed successful write after loss',not renewed and not guarded and not writes)
    stopped=[s for s in snaps if s['label'].startswith('removed_' if shell else 'interrupted_')]
    v.check(name+' 1/8/18s interrupted samples absent pid service notification',len(stopped)==3 and all(not s['pid'] and not s['servicePresent'] and s['activeNotificationCount']==0 for s in stopped) and hi-lo>=18e9)
    relaunched=next(s for s in snaps if s['label']==('manual_relaunch' if shell else 'relaunch_no_guard'))
    oldpid=env['initialPid'] if shell else env['originalPid']
    v.check(name+' manual relaunch new process idle no guard',relaunched['pid'] and relaunched['pid']!=oldpid and not relaunched['servicePresent'] and relaunched['activeNotificationCount']==0)
    if shell:
        root=result['removeRequest']['selectedRoot'];before=read(p/'stack-immediate-before.json')
        v.check('selected root solely owned by VIP user10',root in before and root['userId']==10 and len(root['tasks'])==1 and root['tasks'][0]['component']=='ai.vipseat.cargoconcierge/ai.vipseat.cargoconcierge.MainActivity' and root['rootId']==root['tasks'][0]['taskId'])
        v.check('fresh task inventory',result['removeRequest']['inventoryAgeMs']<1000)
        # Compare shell output itself, not only parsed inventory.
        raw=(p/'stack-immediate-before.txt').read_text()
        v.check('raw stack ownership anchor',str(root['rootId']) in raw and root['tasks'][0]['component'] in raw)
        for delay in [1,8,18]:
            after=read(p/f'stack-after-{delay}.json');raw=(p/f'stack-after-{delay}.txt').read_text()
            v.check('task root removed '+str(delay),all(r['rootId']!=root['rootId'] for r in after) and 'ai.vipseat.cargoconcierge' not in raw)
        lost=[r for r in selected if r.get('tag')=='GuardRuntime' and r.get('data',{}).get('event')=='lost' and r['hostReceivedUnixNs']<hi]
        v.check('one lost matching original session',len(lost)==1 and lost[0]==result['lost'] and all(lost[0]['data'][k]==value for k,value in result['session'].items()))
        v.check('empty success shell return',not (p/'remove-command.txt').read_text().strip())
    cabin_readback(p,v)
    cleanup=read(p/'cleanup.json')
    v.check(name+' cleanup verified and no errors',cleanup['verified'] and not cleanup['executionErrors'] and not cleanup['cleanupErrors'])
    cases.append({'case':name,'candidate_sha256':env['installedApkSha256'],'observation_seconds_before_manual_relaunch':(hi-lo)/1e9,'original_pid':oldpid,'manual_relaunch_pid':relaunched['pid'],'renewed_runtime':len(renewed),'guarded_after_loss':len(guarded),'observed_successful_write':len(writes),'review_input_sha256':observed_hashes(p)})
v.save('loss-verification.json',{'cases':cases,'limits':['Scope is stationary COFFEE with zero cabin changes, bounded roughly18s loss window and manual relaunch.','Shell-owned root removal with matching lost/teardown is observed; onTaskRemoved callback itself is not directly instrumented.','No successful Recents swipe: prior runtime04 runtime-task-removal-01 remains unexecuted/failed and preserved.','Filtered no-successful-write logs plus readback is not bus-level no-write evidence or physical cargo safety.','Input hashes generated at review; no earlier artifact manifest in these datasets.']})

v=Review();cases=[]
for name in ['location-on-03','location-off-02','location-active-off-02','location-reconnect-02']:
    p=BASE/name;result=read(p/'result.json');rows=jsonlines(p/'observations.jsonl');ledger=jsonlines(p/'input-ledger.jsonl')
    v.check(name+' runtime06 identity/no execution errors',result['installedApkSha256']==SHA and result['executedWithoutError'] and result['cleanupVerified'] and not result['issues'])
    snaps=[snapshot(q,v) for q in sorted(p.glob('*-snapshot.json'))]
    v.check(name+' access restored including flags/appops/location',read(p/'access-before.json')==read(p/'access-after.json'))
    v.check(name+' log properties restored readback',read(p/'log-properties-before.json')==read(p/'log-properties-after.json'))
    v.check(name+' retained cabin subset unchanged',read(p/'vehicle-before.json')==read(p/'vehicle-after.json'))
    v.check(name+' audio toggles restored XML',toggles(p/'settings-original.xml')==toggles(p/'settings-restored_after_chime.xml')=={'tts':True,'chime':True})
    cleanup=next(s for s in snaps if s['label']=='cleanup')
    v.check(name+' cleanup no service notification',not cleanup['servicePresent'] and cleanup['activeNotificationCount']==0)
    detail={'case':name,'installed_sha256':result['installedApkSha256'],'observations':len(rows),'review_input_sha256':observed_hashes(p)}
    active=lambda lo,hi:[r for r in rows if lo<=r['hostNs']<hi and r.get('message')=='location speed subscription active']
    stopped=lambda lo,hi:[r for r in rows if lo<=r['hostNs']<hi and r.get('message')=='location speed subscription stopped']
    end=next(e['hostUnixNs'] for e in ledger if e['event']=='command_start' and e.get('command')=='end' and e.get('phase')=='cleanup')
    if result['case']=='reconnect':
        cycles=[]
        for i in [0,1]:
            lo=next(e['hostUnixNs'] for e in ledger if e['event']=='idle-return' and e['cycle']==i)
            hi=next(s['hostUnixNs'] for s in snaps if s['label']=='idle-return-'+str(i))
            window=[r for r in rows if lo<=r['hostNs']<hi];reported=result['cycles'][i]
            traces=[r for r in window if r['tag']=='MotionTrace']
            logs={tag:[r for r in window if r['tag']==tag] for tag in ['AospCarVehicle','PleosNavi','CargoVM']}
            v.check(name+' cycle '+str(i)+' setup and Idle traces',all(logs.values()) and bool(traces) and all(r['data']['state']=='IDLE' for r in traces) and reported['newAospSubscription'] and reported['newNaviReady'] and reported['newCombinedConnection'])
            v.check(name+' cycle '+str(i)+' saved traces are in raw period',all(r in traces for r in reported['motionTraceRows']))
            cycles.append({'cycle':i,'window_seconds':(hi-lo)/1e9,'observed_motion_rows':len(traces),'saved_motion_rows':len(reported['motionTraceRows']),'setup_log_counts':{k:len(value) for k,value in logs.items()}})
        detail['cycles']=cycles
    else:
        lo=event(ledger,'coffee_start')['hostUnixNs'];guard_snaps=[s for s in snaps if s['label'].startswith('guard-start_')]
        expect=0x40000000 if result['case']=='off' else 0x40000008
        v.check(name+' foreground types match location gate',all(s['foregroundService'] and s['serviceTypeMask']==expect and s['isFrozen'] is False for s in guard_snaps))
        ready=[r for r in rows if lo<=r['hostNs']<end and r['tag']=='GuardRuntime' and r.get('data',{}).get('event')=='ready']
        v.check(name+' single ready matching session',len(ready)==1 and all(ready[0]['data'][k]==value for k,value in result['session'].items()))
        v.check(name+' location subscription gate',len(active(lo,end))==(0 if result['case']=='off' else 1))
        detail.update(guard_window_seconds=(end-lo)/1e9,location_active_in_guard=len(active(lo,end)),type_mask=hex(expect),first_foreground_snapshot_seconds=(min(s['hostUnixNs'] for s in guard_snaps)-lo)/1e9,first_notification_snapshot_seconds=(min(s['hostUnixNs'] for s in guard_snaps if s['activeNotificationCount'])-lo)/1e9)
        if result['case']=='active-off':
            off=next(e['hostUnixNs'] for e in ledger if e['event']=='system_location_confirmed' and not e['enabled'] and e['hostUnixNs']>lo)
            on_request=next(e['hostUnixNs'] for e in ledger if e['event']=='system_location_request' and e['enabled'] and e['hostUnixNs']>off)
            on=next(e['hostUnixNs'] for e in ledger if e['event']=='system_location_confirmed' and e['enabled'] and e['hostUnixNs']>off)
            ticks=[r for r in rows if on<=r['hostNs']<end and r['tag']=='GuardRuntime' and r.get('data',{}).get('event')=='tick']
            v.check(name+' revoke then ON never resumes same guard',len(stopped(off,on_request))==1 and not active(off,end) and bool(ticks) and all(all(r['data'][k]==value for k,value in result['session'].items()) for r in ticks))
            finished=next(r['hostNs'] for r in rows if r['tag']=='GuardRuntime' and r.get('data',{}).get('event')=='finished')
            post=active(end,10**30)
            v.check(name+' later active belongs after End finished',bool(post) and all(r['hostNs']>finished for r in post))
            detail.update(off_window_seconds=(on_request-off)/1e9,on_same_guard_seconds=(end-on)/1e9,off_to_stop_ms=(stopped(off,on_request)[0]['hostNs']-off)/1e6,on_same_guard_ticks=len(ticks),post_end_active_after_finished_ms=(post[0]['hostNs']-finished)/1e6)
    cases.append(detail)
v.save('location-verification.json',{'cases':cases,'limits':['Control-boundary observations only: no injected/recorded GPS fix, location cache value or physical speed accuracy proof.','New source setup logs and MotionTrace do not prove actual fresh gear or Pleos getter/native route callback.','Observation stream is selected structured records, not a separately retained complete raw log; live host times lack clock calibration.','Permission flags/appops/location and log-property after readbacks are now retained and match before.','Input hashes generated at review; prior parser failure and earlier runtime04 observations remain preserved.']})
