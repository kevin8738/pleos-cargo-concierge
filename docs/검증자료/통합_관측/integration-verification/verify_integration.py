#!/usr/bin/env python3
"""Offline independent review. Never imports a harness or uses ADB/network/runtime."""
from pathlib import Path
import array, collections, datetime, hashlib, json, math, re, sys, wave
BASE = Path(__file__).resolve().parent
RUN = BASE.parent / 'integration-01'
ROOT = BASE.parents[3]
CHECKS=[]
ERRORS=[]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def load(n): return json.loads((RUN/n).read_text())
def lines(n): return [json.loads(s) for s in (RUN/n).read_text().splitlines()]
def check(name, condition, detail=None):
    CHECKS.append({'name':name,'verified':bool(condition),'detail':detail})
    if not condition: ERRORS.append(name)
def close(a,b): return math.isclose(a,b,abs_tol=1e-9,rel_tol=1e-10)
manifest=load('artifact-sha256.json')
check('frozen original manifest entries',len(manifest)==118)
check('all original artifact hashes match',all((RUN/n).is_file() and sha(RUN/n)==v for n,v in manifest.items()))
expected='8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c'
reviewed='ec0618aae37a977f50e5af16b542b668f25262e896da42f0435c5d84edde3df6'
check('executed harness is reviewed frozen source',sha(RUN/'harness-source.py')==reviewed)
check('frozen helper source hash',sha(RUN/'console-lease-source.py')=='0c4de3eb0140aeb55078a33af967cb1bc631c9cc269d4c065d97ebbfaf600a14')
env=load('environment.json');after=load('environment-after.json');result=load('integration-result.json');cleanup=load('cleanup.json');oracle=load('oracle-result.json')
check('before after same approved APK PID and AVD',after=={k:env[k] for k in after} and after['installedApkSha256']==expected and after['pid']=='22692' and after['api']=='34' and after['product']=='crp_avd_arm64')
check('source hashes match current code',all(sha(ROOT/k)==v for k,v in env['sourceHashes'].items()))
check('original overall false retained',result['boundedObservationComplete'] is False)
expected_false={'motionOracle','clockEndpointsConsistent','mixedPcmSignalInEachResponse','parkedArrivalAndReport'}
check('exact original false components preserved',{k for k,v in result['componentChecks'].items() if not v}==expected_false)
check('execution errors empty independently recorded',result['executionCompleted'] and result['executionErrors']==cleanup['executionErrors']==[])
ledger=lines('input-ledger.jsonl');rows=lines('live-log.jsonl');traces=load('trace.json')
events={e['event']:e for e in ledger}
def ev(name):return events[name]['hostUnixNs']/1e9
guards=[r for r in traces if r['state']=='GUARDING'];arrivals=[r for r in traces if r['event']=='arrival'];guardlogs=[r for r in rows if r.get('tag')=='MotionTrace' and r.get('data',{}).get('state')=='GUARDING']
check('trace JSON comes from retained live log',traces==[r['data'] for r in rows if r.get('tag')=='MotionTrace' and r.get('data',{}).get('tMs',-1)>=env['traceStartElapsedMs']])
check('one COFFEE guard and arrival',len(arrivals)==1 and {r['trip']['startedMs'] for r in guards}=={19613878} and all(r['presetKind']=='COFFEE' and r['limitG']==.12 for r in guards))
runtime=[r for r in rows if r.get('tag')=='GuardRuntime' and r.get('data',{}).get('guarding')]
check('one runtime request matches same trip',{(r['data'].get('requestId'),r['data'].get('startedMs')) for r in runtime}=={(15,19613878)})
# Independent arithmetic over only the actually retained observations; no missing-row repair.
math_errors=[];last_v=last_t=None;ema=0.;dts=[];expected_max=0.;observed_count=observed_fresh=0;counter_residuals=[];increments=[];prior_g=0
model={'wheelbaseM':2.9000000953674316,'steeringRatio':15,'gravityMps2':9.80665,'smoothing':.5,'maxBrakeDecelMps2':8}
for i,r in enumerate(guards):
    raw,snap,actual,trip=r['raw'],r['snapshot'],r['estimate'],r['trip'];fresh=False
    if r['model']!=model:math_errors.append([i,'model'])
    if raw['kind']=='speed':
        if last_t is not None:
            dt=(r['tMs']-last_t)/1000.;dts.append(dt)
            ema=.5*((raw['mps']-last_v)/dt)+.5*ema;fresh=True
        last_t,last_v=r['tMs'],raw['mps']
    lat=abs(snap['speedMps']**2*math.tan(math.radians(snap['steeringDeg']/15))/model['wheelbaseM'])/9.80665
    lon=ema/9.80665;total=math.hypot(lat,lon)
    for k,v in [('lateralG',lat),('longitudinalG',lon),('totalG',total),('speedMps',snap['speedMps'])]:
        if not close(actual[k],v):math_errors.append([i,k])
    if actual['longitudinalFresh']!=fresh:math_errors.append([i,'longitudinalFresh'])
    if not snap['parkedStill']:
        observed_count+=1;observed_fresh+=fresh;expected_max=max(expected_max,total)
    counter_residuals.append(trip['gSamples']-observed_count)
    if trip['gSamples']-prior_g not in (0,1):increments.append({'guardRow':i,'tMs':r['tMs'],'kind':raw['kind'],'previousGCount':prior_g,'actualGCount':trip['gSamples']})
    prior_g=trip['gSamples']
check('actual retained G arithmetic and freshness reproduce',not math_errors,{'guardRows':len(guards),'scalarComparisons':len(guards)*4,'freshnessComparisons':len(guards)})
check('observed speed dt normal original bounds',min(dts)==.401 and max(dts)==.603)
check('max G matches observed arithmetic and report',close(expected_max,oracle['expectedMaximumG']) and close(expected_max,arrivals[0]['report']['maxG']))
check('known missing trace counter shape',counter_residuals[:480]==[0]*480 and counter_residuals[480:]==[1]*(len(guards)-480) and increments==[{'guardRow':480,'tMs':19693995,'kind':'brake','previousGCount':174,'actualGCount':176}])
counts=collections.Counter(r['raw']['kind'] for r in guards)
check('one fewer steering trace than speed brake',counts=={'speed':261,'brake':261,'steering':260})
issue_counts=collections.Counter(x.split(': ',1)[-1] for x in oracle['issues'])
check('all original 303 issues are preserved counter failures',issue_counts=={'trip motion/fresh counters mismatch':302,'arrival maximum/counters mismatch':1})
check('report observed sample discrepancy not repaired',observed_count==461 and arrivals[0]['report']['gSamples']==462 and observed_fresh==arrivals[0]['report']['freshLongitudinalSamples']==154)
check('no parse/serialization failure falsely used to repair missing line',not any(r.get('parseError') for r in rows) and not any('diagnostic_serialization_failed' in r.get('message','') for r in rows))
# Native input remained real SDK events; no speculative added coaching explanation.
navi=[r for r in rows if r.get('tag')=='PleosNavi'];route=[r for r in navi if r.get('message')=='SDK callback navigation-status=ROUTE_DRIVE'];free=[r for r in navi if r.get('message')=='SDK callback navigation-status=FREE_DRIVE'];tbts=[r for r in navi if 'SDK callback tbt ' in r.get('message','')]
check('real route start and end callbacks retained',len(route)==len(free)==1 and ev('native_route_request')<route[0]['hostReceivedUnixNs']/1e9<ev('profile_begin') and ev('native_route_end_request')<free[0]['hostReceivedUnixNs']/1e9<ev('synthetic_arrival_request'))
background=[e for e in ledger if e['event'].startswith('background-sample-')]
check('all directly observed background samples same stopped process',all(e['vipActivityState']=='STOPPED' and e['vipVisible'] is False and e['pid']==env['pid'] and any('ai.umos.maps.android.navigation.app' in s for s in e['focus']) for e in background))
check('same profile runtime tick coverage',sum(r['data'].get('event')=='tick' and ev('profile_begin')<=r['hostReceivedUnixNs']/1e9<=ev('profile_finished') for r in runtime)>=60)
check('no extra native coach admitted in retained evidence',oracle['audioOracle']['drivingChimeCount']==1 and oracle['audioOracle']['drivingSpeechCount']==2 and oracle['audioOracle']['suppressed']=={'COALESCED':1} and all('admission' not in s for s in oracle['issues']))
# Clock mismatch is retained. Endpoint envelope sensitivity is not a new calibration.
start=env['clockCalibration'];end=load('clock-end.json')
intervals=[[c['deviceMinusHostNs']-c['uncertaintyNs'],c['deviceMinusHostNs']+c['uncertaintyNs']] for c in (start,end)]
gap=max(x[0] for x in intervals)-min(x[1] for x in intervals)
check('endpoint clock consistency genuinely false',gap==9253914 and result['clock']['consistent'] is False)
host_bases=[(r['hostReceivedUnixNs']-r['hostReceivedMonotonicNs'])/1e9 for r in rows]
device_bases=[r['deviceEpochSeconds']-r['data']['tMs']/1000 for r in rows if r.get('data',{}).get('tMs') is not None]
# Packet parser is independent; immutable raw metadata indexes original PCM.
meta=load('capture/metadata.json');pcm=(RUN/'capture/audio.s16le.pcm').read_bytes();parts=[];cursor=0
for packet in meta['packets']:
    n=packet['bytes'];data=pcm[cursor:cursor+n];assert n>0 and n%4==0 and len(data)==n
    values=array.array('h');values.frombytes(data)
    if sys.byteorder!='little':values.byteswap()
    parts.append({**packet,'pcmByteOffset':cursor,'sampleCount':len(values),'sumSquares':sum(x*x for x in values),'peakS16':max(map(abs,values))});cursor+=n
check('all packets and byte offsets reproduce stored analysis',parts==load('capture/packet-analysis.json'))
check('one capture exact raw packet byte frame counts',len(parts)==15545 and cursor==len(pcm)==9026060 and meta['frame_count']==2256515 and meta['sample_count']==4513030 and meta['empty_packet_count']==0)
check('capture raw SHA and duration',sha(RUN/'capture/audio.s16le.pcm')==meta['pcm_sha256'] and close(meta['pcm_duration_seconds'],len(pcm)/192000) and meta['wall_seconds']==179.977896 and meta['requested_seconds']==180)
check('capture ended by own single duration bound',meta['capture_status']=='CAPTURED' and meta['connection_status']=='READY' and meta['stop_reason']=='duration_limit' and meta['exit_code']==load('capture-process.json')['exitCode']==0)
with wave.open(str(RUN/'capture/audio.wav'),'rb') as w:
    check('WAV exactly wraps original stereo PCM',(w.getnchannels(),w.getsampwidth(),w.getframerate())==(2,2,48000) and w.readframes(w.getnframes())==pcm)
def stats(a,b):
    selected=[p for p in parts if a<=p['timestamp_us']/1e6<b];n=sum(p['sampleCount'] for p in selected)
    return {'packetCount':len(selected),'sampleCount':n,'receivedPcmSeconds':n/96000,'rmsS16':math.sqrt(sum(p['sumSquares'] for p in selected)/n) if n else None,'peakS16':max((p['peakS16'] for p in selected),default=None),'coveredWithReceivedSamples':parts[0]['timestamp_us']/1e6<=a and parts[-1]['timestamp_us']/1e6>=b and n>0}
windows=[(w['label']+'.'+name,x) for w in result['admissions']+[result['arrival']] for name,x in w.items() if name in ('pre','response','tail')]+[('route',result['routeWindow'])]
for name,w in windows:
    got=stats(*w['hostEpochWindow'])
    check('original window arithmetic '+name,all(close(got[k],w[k]) if isinstance(got[k],float) else got[k]==w[k] for k in got))
contrast=[]
for w in result['admissions']+[result['arrival']]:
    a,b=w['pre'],w['response'];ok=b['peakS16']>max(16,a['peakS16']*3) and b['rmsS16']>max(3,a['rmsS16']*3)
    contrast.append({'label':w['label'],'prePeak':a['peakS16'],'responsePeak':b['peakS16'],'preRms':a['rmsS16'],'responseRms':b['rmsS16'],'peakRatio':b['peakS16']/a['peakS16'],'rmsRatio':b['rmsS16']/a['rmsS16'],'operationalContrast':ok})
check('three driving contrasts pass arrival contrast fails',[w['operationalContrast'] for w in contrast]==[True,True,True,False])
check('arrival is not silent or empty',result['arrival']['response']['packetCount']==145 and result['arrival']['response']['peakS16']==1480 and result['arrival']['pre']['peakS16']==1449)
check('actual received tail retained beyond requested end',result['arrival']['tail']['packetCount']==246 and close(result['arrival']['tail']['receivedPcmSeconds'],3.007) and parts[-1]['timestamp_us']/1e6>result['arrival']['tail']['hostEpochWindow'][1])
# Callback ordering is also visible on device's own clock, without pretending clock alignment passed.
life=[r for r in rows if r.get('tag')=='PleosSpeech'];starts=[r for r in life if r.get('message')=='TTS_START'];dones=[r for r in life if r.get('message')=='TTS_DONE']
pairs=[]
for s,dn in zip(starts,dones):
    between=[r['message'] for r in life if s['deviceEpochSeconds']<r['deviceEpochSeconds']<dn['deviceEpochSeconds']]
    pairs.append({'deviceStartEpoch':s['deviceEpochSeconds'],'deviceDoneEpoch':dn['deviceEpochSeconds'],'deviceDurationSeconds':dn['deviceEpochSeconds']-s['deviceEpochSeconds'],'hostStartReceipt':s['hostReceivedUnixNs']/1e9,'hostDoneReceipt':dn['hostReceivedUnixNs']/1e9,'interrupted':any(x.startswith(('TTS_STOP','TTS_ERROR')) for x in between)})
check('three uninterrrupted device-clock TTS START DONE pairs',len(starts)==len(dones)==3 and all(p['deviceDurationSeconds']>0 and not p['interrupted'] for p in pairs) and not any(r['message'].startswith('TTS_ERROR') for r in life))
report=arrivals[0]['report'];parked=load('parked-before-arrival.json')
check('direct parked arrival and synthetic report observed independent of oracle',parked['data']['snapshot']['gear']=='PARK' and parked['data']['snapshot']['speedMps']==0 and arrivals[0]['snapshot']['parkedStill'] and report['hasSyntheticInput'] and not report['canCompare'] and report['provenance']==['SYNTHETIC'])
check('report UI explicitly synthetic and no actual drive assessment','모의 입력' in (RUN/'report.xml').read_text() and '실주행 평가 불가' in (RUN/'report.xml').read_text())
# Cleanup readbacks. Only expected gear timestamp differs in entire vehicle JSON.
before_vehicle=load('vehicle-before.json');after_vehicle=load('vehicle-after.json')
def changed(a,b,p=''):
    if isinstance(a,dict) and isinstance(b,dict):return sum((changed(a.get(k),b.get(k),p+'/'+k) for k in a.keys()|b.keys()),[])
    return [] if a==b else [p]
check('all vehicle values unchanged except gear observation timestamp',changed(before_vehicle,after_vehicle)==['/gear/actual/timestamp_ms'])
flags=load('settings-original.json');restored=load('settings-restored_states.json')
check('all three original settings restored',flags==restored and all(v['checked'] for v in restored.values()))
check('readback stopped service and notification',load('cleanup-runtime-snapshot.json')['servicePresent'] is False and load('cleanup-runtime-snapshot.json')['activeNotificationCount']==0 and 'ServiceRecord{' not in (RUN/'cleanup-runtime-service.txt').read_text() and not (RUN/'cleanup-runtime-notification.txt').read_text().strip())
check('final actual trace Idle P0',traces[-1]['state']=='IDLE' and traces[-1]['snapshot']['gear']=='PARK' and traces[-1]['snapshot']['speedMps']==0)
check('cleanup native and VIP idle UI','어디로 갈까요' in (RUN/'cleanup-native-idle-0.xml').read_text() and '프리셋 고르기' in (RUN/'cleanup-idle-0.xml').read_text())
lease=load('console-lease.json');check('owned helper closure reported clean',lease=={'ready':True,'closed':True,'ownForward':True,'errors':[]})
check('cleanup succeeds without original error rewriting',cleanup['verified'] and cleanup['cleanupErrors']==[] and cleanup['originalLogProperties']=={'MotionTrace':'','GuardRuntime':''})
check('no private token artifact remains',not list(RUN.glob('.grpc-*')))
# Text pattern screening records only filenames/counts, never possible secret values.
patterns={'home_path':r'<LOCAL_HOME>/\s"\']+','email':r'(?<![\w.])[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}','mobile_phone':r'(?<!\d)(?:010|011|016|017|018|019)[- ]?\d{3,4}[- ]?\d{4}(?!\d)','literal_credential':r'(?:sk-[A-Za-z0-9_-]{20,}|hf_[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|Bearer\s+[A-Za-z0-9._-]{20,})'}
scanned=[];hits={}
for n in sorted(manifest):
    p=RUN/n
    if p.suffix in {'.json','.jsonl','.py','.txt','.xml','.log'}:
        s=p.read_text(errors='replace');scanned.append(n);h={k:len(re.findall(v,s)) for k,v in patterns.items() if re.search(v,s)}
        if h:hits[n]=h
check('text privacy screening no matches',not hits,{'files':len(scanned),'patterns':list(patterns)})
background_start=min(e['hostUnixNs']/1e9 for e in background);background_end=max(e['hostUnixNs']/1e9 for e in background)
positive_tbts=[r for r in tbts if not r['message'].startswith('SDK callback tbt count=0')]
summary={'schema':'v24-integration-independent-review-v1','source_run':'integration-01','source_manifest_sha256':sha(RUN/'artifact-sha256.json'),'harness_sha256':reviewed,'apk_sha256':expected,'reviewer':'guard_runtime_review','runtime_or_adb_executed':False,'original_verdict':False,'verification_error_count':len(ERRORS),'verification_errors':ERRORS,'checks':CHECKS,
'counter_gap':{'original_issue_count':303,'issue_types':dict(issue_counts),'guard_rows':len(guards),'trace_kinds':dict(counts),'actual_counter_jumps':increments,'observed_motion_events':observed_count,'reported_motion_events':report['gSamples'],'observed_and_reported_fresh_longitudinal':observed_fresh,'observed_g_arithmetic_errors':math_errors,'observed_maximum_g':expected_max,'reported_maximum_g':report['maxG'],'missing_row_cause':'UNDETERMINED; no synthetic row inserted','native_added_coaching_explanation_supported':False},
'clock':{'original_constant_offset_failed':True,'start_offset_ns':start['deviceMinusHostNs'],'end_offset_ns':end['deviceMinusHostNs'],'endpoint_intervals_ns':intervals,'interval_gap_ns':gap,'host_epoch_minus_monotonic_range_seconds':max(host_bases)-min(host_bases),'device_epoch_minus_elapsed_range_seconds':max(device_bases)-min(device_bases),'interpretation':'Host wall-clock vs monotonic changed about 33 ms; compatible with observed offset change, system cause undetermined. No interpolated offset substituted. Raw callback order and PCM data are retained; fixed-offset cross-clock true fields remain conditional.'},
'route_background':{'route_request_host_epoch':ev('native_route_request'),'route_ended_observed_host_epoch':ev('native_route_ended'),'request_through_end_seconds':ev('native_route_ended')-ev('native_route_request'),'direct_background_samples':len(background),'first_last_direct_sample_seconds':background_end-background_start,'positive_tbt_callbacks_whole_route':len(positive_tbts),'positive_tbt_callbacks_between_direct_samples':sum(background_start<=r['hostReceivedUnixNs']/1e9<=background_end for r in positive_tbts),'all_tbt_callbacks':len(tbts),'scope':'Host receipt window, not continuous visibility or native speech.'},
'audio':{'packets':len(parts),'pcm_bytes':len(pcm),'pcm_seconds':meta['pcm_duration_seconds'],'wall_seconds':meta['wall_seconds'],'window_contrasts':contrast,'device_clock_tts_pairs':pairs,'arrival_tail':result['arrival']['tail'],'native_speech_attributed':False,'actual_overlap_validated':False,'focus_priority_validated':False,'human_listening_performed':False,'limits':'Sparse mixed emulator PCM, no native utterance ID; fixed-offset clock assumption failed. Arrival contrast failure is not silence.'},
'cleanup':{'readback_consistent':cleanup['verified'],'vehicle_changed_paths':changed(before_vehicle,after_vehicle),'properties':'Original blanks restored by reviewed harness asserted getprop path with no errors; separate post-return raw getprop not in this initial manifest.','owned_helper':lease,'fresh_ownership_return_reviewed':False},
'privacy':{'scanned_text_files':len(scanned),'pattern_hits':hits,'report_png_sha256':sha(RUN/'report.png'),'report_png_actually_viewed':True,'report_png_private_content_observed':False,'audio_content_review_complete':False,'audio_publication_requires_root_privacy_review':True,'raw_auth_or_global_focus_dump_in_manifest':False},
'limits':['Overall original false and all four false predicates are preserved.','Observed G math pass does not verify the unrecorded steering trace or exact report event count.','Direct P0 arrival is separately observed; original parkedArrivalAndReport stays false because it conjoins failed oracle.','No AVD rerun, calibration repair, audio re-edit, synthetic row or policy change.','No physical vehicle accuracy, native voice contention, human listening, driver benefit or cargo efficacy claim.']}
supp=RUN.parent/'integration-01-supplement'
sm=json.loads((supp/'artifact-sha256.json').read_text())
check('supplement original-source hashes preserved',all((supp/n).is_file() and sha(supp/n)==v for n,v in sm.items()))
handoff=json.loads((supp/'handoff.json').read_text())
check('supplement handoff links unchanged original run',handoff['originalManifestSha256']==sha(RUN/'artifact-sha256.json') and handoff['originalArtifactHashesVerified']==118 and handoff['originalArtifactsChanged']==[] and handoff['originalRunVerdictUnchanged'])
summary['supplement_review']={'files':len(sm),'manifest_sha256':sha(supp/'artifact-sha256.json'),'handoff_sha256':sha(supp/'handoff.json'),'declarative_return_report_consistent':True,'independent_fresh_adb_return_readback':False,'scope':'Handoff declarations match saved cleanup and reviewed code assertions. No separate getprop/forward raw transcript in supplement and no ADB performed by reviewer.'}
summary['checks']=CHECKS;summary['verification_error_count']=len(ERRORS);summary['verification_errors']=ERRORS
summary['script_sha256']=sha(Path(__file__))
selection=[]
for n,h in manifest.items():
    if n in ('capture/audio.s16le.pcm','capture/audio.wav'):
        disposition='ROOT_AUDIO_CONTENT_PRIVACY_REVIEW_REQUIRED'
    elif n=='report.png':disposition='DIRECTLY_VIEWED_NO_PRIVATE_CONTENT_OBSERVED'
    elif n.startswith('native-') and n.endswith('.xml'):
        disposition='PUBLIC_DESTINATION_UI_SCOPE_REVIEW'
    elif n.endswith('-notification.txt') and (RUN/n).stat().st_size>1:
        disposition='OWN_PACKAGE_NOTIFICATION_FIELD_SCOPE_REVIEW'
    else:continue
    selection.append({'path':'integration-01/'+n,'sha256':h,'disposition':disposition})
(BASE/'privacy-selection.json').write_text(json.dumps({'text_files_scanned':len(scanned),'text_pattern_matches':hits,'files_needing_explicit_media_or_dynamic_scope_selection':selection,'preserve_even_on_false':['integration-result.json','oracle-result.json','oracle-comparisons.json','clock-end.json','environment.json','cleanup.json','motion.log','live-log.jsonl','input-ledger.jsonl','capture/metadata.json','artifact-sha256.json'],'exclude_if_encountered':['authentication discovery/configuration files','raw token files','unfiltered global audio-focus/intent dumps','unrelated external private content'],'limitations':['Text regex screening is not exhaustive privacy certification.','Only report.png was visually viewed by reviewer; PCM/WAV not listened to or transcribed.','Public route fixture does not establish permission to publish any unrelated user location.']},ensure_ascii=False,indent=2)+'\n')
(BASE/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'checks':len(CHECKS),'verificationErrors':ERRORS,'originalVerdict':False,'artifacts':len(manifest),'counterKinds':dict(counts),'clockIntervalGapMs':gap/1e6,'background':summary['route_background']},ensure_ascii=False))
