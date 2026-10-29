"""Independent bounded readback/hash validation for runtime06 control avd-03."""
from pathlib import Path
import ast
import hashlib
import json
import re
import xml.etree.ElementTree as ET

OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[3];BASE=ROOT/'.omx/qa/submission-v22'
SHA='8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c'
tree=ast.parse((OUT/'verify_boundary_audio.py').read_text())
exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n,(ast.FunctionDef,ast.ClassDef)) and n.name in {'read','digest','texts','Review'}],type_ignores=[]),'<pure-helpers>','exec'))
v=Review();p=BASE/'avd-03';candidate=BASE/'candidates/runtime-06'
hashes=v.hashes(p,'artifact-sha256.json');manifest=read(p/'artifact-sha256.json')
v.check('manifest complete coverage and 63 files',set(manifest)=={x.name for x in p.iterdir() if x.is_file() and x.name!='artifact-sha256.json'} and len(manifest)==63)
env=read(p/'environment.json');summary=read(candidate/'build-summary.json')
v.check('candidate APK installed identity',digest(candidate/'app-pleos-release.apk')==env['apk_sha256']==SHA and env['versionCode']==7 and env['api']==34)
sources=read(candidate/'source-sha256.json')
v.check('21 current candidate source hashes',len(sources)==21 and all(digest(ROOT/'app'/n)==h for n,h in sources.items()))
prior=read(BASE/'candidates/runtime-05/source-sha256.json')
changed=[n for n in prior if prior[n]!=sources.get(n)]
v.check('runtime05 motion/freshness code unchanged in runtime06',changed==['app/src/main/kotlin/ai/vipseat/cargoconcierge/runtime/CargoSession.kt'])
v.check('10 recorded integrated results true',len(read(p/'results.json'))==10 and all(x is True for x in read(p/'results.json').values()))
expected={
 'C1-occupied-blocked':(22,22,True,True,False),'C2-vacant-before':(22,22,False,True,False),
 'C3-applied':(18,22,False,True,False),'C4-restored':(22,22,False,True,False),
 'C5-external-change':(21,22,False,True,False),'C6-vacancy-lost':(22,22,True,True,False),
 'C7-power-off-setting-only':(18,22,False,False,False),'C8-dual-false':(18,24,False,True,False),
 'C9-dual-true':(24,24,False,True,True),'C9B-external-sync-restore-deferred':(18,18,False,True,True),
 'C9C-sync-off-retry-restored':(22,18,False,True,False),'C10-drive-restore-deferred':(18,18,True,True,False),
 'C11-park-retry-restored':(22,18,True,True,False),'C12-restart-manual-check':(18,18,False,True,False)}
keys=['passenger_temperature_c','driver_temperature_c','console_passenger_occupied','hvac_power','hvac_dual']
for name,want in expected.items():
    actual=read(p/(name+'.json'))
    v.check(name+' console readback',tuple(actual[k] for k in keys)==want and actual['console_available'])
log=lambda n:(p/(n+'.log')).read_text()
get=lambda val:f'getProperty_onResult eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue {val:.1f}'
command='onResult() called with: propKey = HVAC_TEMPERATURE_SET, areaId = 4, result = true'
def in_order(text,parts):
    offset=0
    for part in parts:
        found=text.find(part,offset)
        if found<0:return False
        offset=found+len(part)
    return True
v.check('C3 SDK22 command SDK18 before APPLIED',in_order(log('C3-applied'),[get(22),command,get(18),'apply TEMPERATURE: APPLIED','confirmation=VALUE_CONFIRMED value=18.0']))
v.check('C4 apply then restore SDK18 command SDK22 before APPLIED',in_order(log('C4-restored'),['apply TEMPERATURE: APPLIED',get(18),command,get(22),'restore TEMPERATURE: APPLIED']))
v.check('C1 unconfirmed vacancy blocks',read(p/'C1-occupied-blocked.json')['passenger_temperature_c']==22 and 'apply TEMPERATURE: APPLIED' not in log('C1-occupied-blocked') and 'eventPropKey SEAT_OCCUPANCY eventAreaId 4 eventValue 2' in log('C1-occupied-blocked') and 'apply TEMPERATURE: BLOCKED_BY_OCCUPANCY' in log('C1-occupied-blocked') and '조수석 비착좌 확인 불가' in log('C1-occupied-blocked'))
v.check('C5 external21 readback preserved',in_order(log('C5-external-change'),[get(21),'restore TEMPERATURE: UNCHANGED']) and '덮어쓰지 않고 유지' in log('C5-external-change'))
v.check('C6 vacancy lost conditional restore',in_order(log('C6-vacancy-lost'),['비착좌 확인 상실',get(18),command,get(22),'restore TEMPERATURE: APPLIED']))
v.check('C7 power off disclosed in SDK and UI','hvacPowerAtCheck=false' in log('C7-power-off-setting-only') and any('전원' in t and '꺼' in t for t in texts(p/'C7-power-off-setting-only.xml')))
for name,beforepass,beforedriver,sync in [('C8-dual-false',22,24,False),('C9-dual-true',24,24,True)]:
    before=read(p/(name+'-before.json'));after=read(p/(name+'.json'))
    v.check(name+' before driver/sync baseline unchanged',before['passenger']['temp_c']==beforepass and before['driver']['temp_c']==after['driver_temperature_c']==beforedriver and before['dual_on']==before['sync_on']==after['hvac_dual']==sync)
v.check('C9 sync failed no temperature command success','apply TEMPERATURE: FAILED' in log('C9-dual-true') and '온도 동기화 켜짐' in log('C9-dual-true') and command not in log('C9-dual-true'))
v.check('C9B restore deferred with retry UI','restore TEMPERATURE: FAILED' in log('C9B-external-sync-restore-deferred') and any('재시도' in t for t in texts(p/'C9B-external-sync-restore-deferred.xml')))
v.check('C9C after failed restore explicit retry reads18 writes reads22',in_order(log('C9C-sync-off-retry-restored'),['restore TEMPERATURE: FAILED',get(18),command,get(22),'restore TEMPERATURE: APPLIED']))
v.check('C10 drive restriction then C11 P retry readback', 'restore TEMPERATURE: DRIVING_RESTRICTED' in log('C10-drive-restore-deferred') and in_order(log('C11-park-retry-restored'),['restore TEMPERATURE: DRIVING_RESTRICTED',get(18),command,get(22),'restore TEMPERATURE: APPLIED']))
restart=log('C12-restart-manual-check')
pid=lambda text:{m[1] for m in re.finditer(r'^\d\d-\d\d \S+\s+(\d+)\s+\d+\s+[VDIWEF]',text,re.M)}
oldpid=pid(log('C11-park-retry-restored'));newpid=pid(restart)
v.check('C12 different process',len(oldpid)==len(newpid)==1 and not oldpid&newpid)
v.check('C12 warning Idle and initial SDK18',get(18) in restart and any('이전 실행의 공조 복원을 확인하지 못했어요' in t for t in texts(p/'C12-restart-manual-check.xml')) and '프리셋 고르기' in texts(p/'C12-restart-manual-check.xml'))
v.check('C12 no automatic observed apply restore command or guard start',not re.search(r'(apply|restore) TEMPERATURE:|캐빈 적용 →|가드 시작:',restart) and command not in restart)
cleanup=read(p/'tester-cleanup.json');before=read(p/'tester-before.json');after=cleanup['observed']
v.check('external tester cleanup assertion',cleanup['verified'] and all(x=='ok' for x in cleanup['attempts'].values()))
for key in ['power_on','dual_on','driver','passenger']:
    v.check('cleanup HVAC '+key,before['hvac'][key]==after['hvac'][key])
v.check('cleanup parked vacant zero controls',after['gear']['actual']['value']=='Park' and not after['seat']['occupied']['passenger'] and all(after['controls'][k]==before['controls'][k]==0 for k in ['speed_kph','accelerator_pct','brake_pct','steering_deg']))
failed=BASE/'avd-01'
v.check('prior runtime04 C3 failure preserved',read(failed/'C3-applied.json')['passenger_temperature_c']==22 and '최신 차속 확인 불가' in (failed/'C3-applied.log').read_text())
v.save('control-verification.json',{'candidate_sha256':SHA,'artifact_hashes':hashes,'current_source_count':len(sources),'integration_assertions':10,'old_pid':sorted(oldpid),'restart_pid':sorted(newpid),'critical_readbacks':{n:read(p/(n+'.json')) for n in expected},'source_changes_vs_runtime05':changed,'limits':['Official API34 AVD/VehicleService2.3.22 settings readback, not cabin/cargo temperature or cooling effect.','C8/C9 End-after driver/SYNC assertions are inside harness but separate after-End checkpoints are absent.','C12 is one restart checkpoint and selected logs; no perpetual no-write or full vehicle-bus proof.','No input-ledger/clock calibration here; exact command latency not established.','Tester cleanup resets fixture externally; separate from app conditional restore in C4/C9C/C11.','runtime05 control PASS is scoped only; later runtime05 ANR remains failed/preserved.']})
