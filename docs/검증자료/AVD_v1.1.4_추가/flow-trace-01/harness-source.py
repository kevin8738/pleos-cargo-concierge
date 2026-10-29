#!/usr/bin/env python3
"""Bounded A1-A4 Console replay with opt-in numeric MotionTrace. Import is inert.
New observations cannot establish the missing raw pair of the historical 4.49G.
"""
import argparse, hashlib, json, math, re, shlex, sys, time
from pathlib import Path
import driving_audio_probe as d
q=d.qb
BASE=Path(__file__).resolve().parent


def setter(path):
    start=q.event('console_request',path=path)
    result=q.api(path,'PUT')
    q.event('console_response',path=path,startHostMonotonicNs=start['hostMonotonicNs'])
    return result


def command(cmd,value=None):
    if cmd not in {'preset','end','ack'} or cmd=='preset' and value!='CAKE':
        raise ValueError('bounded CAKE lifecycle only; no motion DEMO')
    q.event('lifecycle_request',command=cmd,value=value)
    args=['shell','am','broadcast','-n',d.PKG+'/.demo.DemoBroadcastReceiver','-a',d.PKG+'.DEMO','--es','cmd',cmd]
    if value is not None:args+=['--es','value',value]
    result=q.adb(*args,timeout=12)
    if 'Broadcast completed: result=0' not in result or 'data="error:' in result:raise RuntimeError('lifecycle rejected')
    q.event('lifecycle_completed',command=cmd)


def shot(name):
    tree=q.ui(name,True);q.save(name+'.json',q.api('/vehicle/state'));q.event(name)
    return tree


def tap_cake():
    tree=q.ui('recent-cake-before-tap')
    nodes=[n for n in tree.iter('node') if n.get('package')==d.PKG and n.get('text','').startswith('케이크 ') and n.get('text','').endswith('G')]
    if len(nodes)!=1:raise RuntimeError('single observed recent CAKE chip required')
    d.runtime.tap(nodes[0],'tap_recent_cake_once')


def temp(value):
    actual=q.api('/vehicle/state')['hvac']['passenger']['temp_c']
    if actual!=value:raise RuntimeError('passenger temperature readback mismatch')


def analyze(folder):
    """Independent arithmetic using accepted same-source receive times, never setter times.
    Unobservable baseline resets are reported as unresolved rather than fitted away.
    """
    d.bind(folder);env=json.loads((folder/'environment.json').read_text())
    rows=[]
    for raw in (folder/'motion.log').read_text().splitlines():
        if 'MotionTrace' in raw and '{' in raw:
            row=json.loads(raw[raw.index('{'):])
            if row['tMs']>=env['traceStartElapsedMs']:rows.append(row)
    q.save('trace.json',rows)
    guards=[r for r in rows if r['state']=='GUARDING'];reports=[r for r in rows if r['event']=='arrival']
    issues=[];pairs=[];checks=[];baseline=None;ema=0.;previous_receive=None;previous_estimate=None;last_pair=None
    sessions={r['trip']['startedMs'] for r in guards}
    model={'wheelbaseM':2.9000000953674316,'steeringRatio':15,'smoothing':.5,'gravityMps2':9.80665,'maxBrakeDecelMps2':8}
    max_row=None;max_pair=None;computed_max=0.;tolerance=1e-9
    for index,r in enumerate(guards):
        raw,snap,e,t=r['raw'],r['snapshot'],r.get('estimate'),r['tMs'];kind=raw['kind']
        if r['model']!=model:issues.append({'index':index,'reason':'fixed model differs'});continue
        if raw.get('provenance')!='SYNTHETIC' or snap['speedProvenance']!='SYNTHETIC':issues.append({'index':index,'reason':'non-synthetic origin'})
        if kind=='speed' and raw['sourceId']==0:issues.append({'index':index,'reason':'DEMO speed contaminated Console replay'})
        if snap['brakePressed'] or snap['brakePercent'] not in (None,0):issues.append({'index':index,'reason':'unexpected brake contribution'})
        if not e:continue
        reasons=[];expected_fresh=False;speed=snap['speedMps'];long_g=previous_estimate['longitudinalG'] if previous_estimate else 0.
        duplicate=kind=='speed' and previous_receive is not None and t<=previous_receive
        if duplicate:
            checks.append({'index':index,'tMs':t,'path':'reducer rejects nonincreasing receive time','actual':e})
            continue
        if kind=='speed':
            gap=previous_receive is None or t-previous_receive>2000
            previous_receive=t
            if gap:baseline=None;ema=0.;last_pair=None
            if baseline and raw['sourceId']!=baseline['sourceId']:
                baseline=None;ema=0.;last_pair=None
            if baseline is None:
                baseline={'tMs':t,'mps':raw['mps'],'sourceId':raw['sourceId'],'traceIndex':index};long_g=0.
            else:
                dt=(t-baseline['tMs'])/1000
                if dt<.05:
                    speed=baseline['mps'];long_g=ema/model['gravityMps2']
                else:
                    prior=ema;raw_accel=(raw['mps']-baseline['mps'])/dt
                    ema=.5*raw_accel+.5*prior;long_g=ema/model['gravityMps2'];expected_fresh=True
                    last_pair={'from':dict(baseline),'to':{'tMs':t,'mps':raw['mps'],'sourceId':raw['sourceId'],'traceIndex':index},
                        'dtSeconds':dt,'deltaMps':raw['mps']-baseline['mps'],'rawAccelerationMps2':raw_accel,
                        'previousEmaMps2':prior,'emaMps2':ema,'expectedLongitudinalG':long_g,
                        'formula':'(.5 * (v1_mps-v0_mps)/((t1_ms-t0_ms)/1000) + .5 * priorEMA) / 9.80665'}
                    pairs.append(last_pair)
                    baseline={'tMs':t,'mps':raw['mps'],'sourceId':raw['sourceId'],'traceIndex':index}
        lateral=(abs(speed*speed*math.tan(math.radians(snap['steeringDeg']/15))/model['wheelbaseM'])/9.80665
                 if e['lateralAvailable'] and snap['steeringDeg'] is not None else 0.)
        total=math.hypot(lateral,long_g)
        for key,want in [('speedMps',speed),('lateralG',lateral),('longitudinalG',long_g),('totalG',total)]:
            if not math.isclose(e[key],want,abs_tol=tolerance,rel_tol=tolerance):reasons.append(key+' arithmetic mismatch')
        if e['longitudinalFresh']!=expected_fresh:reasons.append('freshness/baseline mismatch; no inferred reset repair')
        checks.append({'index':index,'tMs':t,'kind':kind,'expectedTotalG':total,'actualTotalG':e['totalG'],'issues':reasons})
        if reasons:issues.append({'index':index,'tMs':t,'reasons':reasons})
        previous_estimate=e
        if not snap['parkedStill'] and e['hasData'] and e['totalG']>computed_max:
            computed_max=e['totalG'];max_row={'traceIndex':index,'row':r,'independentExpectedTotalG':total,'arithmeticVerified':not reasons};max_pair=last_pair
    if len(sessions)!=1 or len(reports)!=1:issues.append({'reason':'one guarded session and one arrival required'})
    report=reports[0]['report'] if len(reports)==1 else None
    if report:
        if not math.isclose(report['maxG'],computed_max,abs_tol=tolerance):issues.append({'reason':'report max differs from trace maximum'})
        if not report['hasSyntheticInput'] or report['canCompare']:issues.append({'reason':'report synthetic/assessment boundary absent'})
        if report['maxG']>=.6 and 'HIGH_G_UNASSESSED' not in report['quality']:issues.append({'reason':'high G limitation missing'})
    if max_pair is None:issues.append({'reason':'maximum lacks a resolved accepted speed pair'})
    q.save('accepted-speed-pairs.json',pairs);q.save('arithmetic-checks.json',checks)
    result={'arithmeticVerified':not issues,'issues':issues,'guardRows':len(guards),'acceptedPairs':len(pairs),'sessions':sorted(sessions),
        'maximum':max_row,'maximumLongitudinalPair':max_pair,'report':report,'highGObserved':computed_max>=.6,
        'historical449CauseEstablished':False,'physicalGAccuracyValidated':False,
        'limits':['New Console synthetic replay only; historical run omitted raw MotionTrace and cannot be reconstructed.',
                  'Setters are requests, not accepted speed samples. Numeric dt uses actual MotionTrace tMs.',
                  'Steering freshness uses traced availability; unlogged resets are unresolved if arithmetic disagrees.',
                  'The independent pair calculation does not validate vehicle calibration, cargo forces or audible output.']}
    q.save('analysis.json',result);return result


def run(folder):
    folder.mkdir(exist_ok=False);d.bind(folder);(folder/'harness-source.py').write_bytes(Path(__file__).read_bytes())
    original=None;old_tag=None;flags=None;logger=record=browser=pw=None;errors=[];mutated=False
    try:
        before=d.identity();original=q.api('/vehicle/state');q.check_park(original)
        if any(original['controls'][k]!=0 for k in ['accelerator_pct','brake_pct','steering_deg']):raise RuntimeError('original controls nonzero')
        d.runtime.require_stopped(d.runtime.snapshot('preflight-runtime'));q.save('vehicle-before.json',original)
        q.demo('settings','on');flags=d.matrix.switch_states(d.matrix.ui(folder/'settings-original.xml'));q.save('settings-original.json',flags)
        q.demo('settings','off')
        # Prior driving DEMO arbitration expires after 60s; no motion DEMO is sent here.
        q.event('demo_hold_expiry_wait',seconds=61);time.sleep(61)
        from playwright.sync_api import sync_playwright
        pw=sync_playwright().start();browser=pw.chromium.launch(headless=True);page=browser.new_page()
        page.goto('http://127.0.0.1:8088/',wait_until='domcontentloaded');page.wait_for_timeout(2000)
        def park():
            q.event('console_park_request');page.get_by_role('button',name='P',exact=True).click();page.wait_for_timeout(2500)
        mutated=True;park()
        setter('/vehicle/hvac?dual=false&power=true&passenger_temp=22.0')
        setter('/vehicle/seat?kind=occupied&area=passenger&value=false');setter('/vehicle/body?door_fl=false')
        q.adb('shell','am','start','-W','-n',d.PKG+'/.MainActivity');time.sleep(3)
        command('preset','CAKE');time.sleep(4);command('end');time.sleep(4);command('ack');time.sleep(2);temp(22)
        old_tag=q.adb('shell','getprop','log.tag.MotionTrace').strip()
        q.save('environment.json',{**before,'traceStartElapsedMs':q.elapsed(),'originalMotionTraceProperty':old_tag,
            'clockCalibration':d.runtime.clock_sync(),'motionPath':'official Console setters -> simulator -> SDK raw callbacks; synthetic',
            'sourceHashes':q.source_hashes(),'historical449CauseEstablished':False})
        q.adb('shell','setprop','log.tag.MotionTrace','DEBUG');logger=d.log_start(before['pid']);record=d.video_start()
        time.sleep(2);shot('A1-home');tap_cake();time.sleep(4);temp(18);shot('A2-loaded')
        setter('/vehicle/gear?gear=Drive')
        for k in range(19):setter('/vehicle/controls?speed='+str(k*2)+'&accelerator=0&brake=0&steering=0');time.sleep(.5)
        setter('/vehicle/controls?speed=36&accelerator=0&brake=0&steering=60');time.sleep(2);shot('A3-driving')
        xml=(folder/'A3-driving.xml').read_text()
        if '0.15G 기준' not in xml or '적재물 상태는 정차 후' not in xml:raise RuntimeError('driving guard UI missing')
        setter('/vehicle/controls?speed=36&accelerator=0&brake=0&steering=0');time.sleep(3)
        for k in range(18,-1,-1):setter('/vehicle/controls?speed='+str(k*2)+'&accelerator=0&brake=0&steering=0');time.sleep(.5)
        setter('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0');park();time.sleep(3)
        setter('/vehicle/body?door_fl=true');q.event('console_park_driver_door_open');time.sleep(5);shot('A4-arrival');temp(22)
        xml=(folder/'A4-arrival.xml').read_text()
        if not all(x in xml for x in ['복원','챙','모의 입력','실주행 평가 불가']):raise RuntimeError('arrival UI/limitations missing')
        q.event('execution_complete')
    except BaseException as error:errors.append(str(error));q.event('execution_error',error=str(error))
    finally:
        count=len(errors)
        try:d.video_finish(record)
        except BaseException as error:errors.append('video: '+str(error))
        if mutated:
            try:
                setter('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0');park()
                command('end');time.sleep(3);command('ack');time.sleep(2)
                h=original['hvac'];setter('/vehicle/hvac?dual=false&power='+str(h['power_on']).lower()+'&passenger_temp='+str(h['passenger']['temp_c'])+'&driver_temp='+str(h['driver']['temp_c']))
                setter('/vehicle/hvac?dual='+str(h['dual_on']).lower())
                setter('/vehicle/seat?kind=occupied&area=passenger&value='+str(original['seat']['occupied']['passenger']).lower())
                setter('/vehicle/body?door_fl='+str(original['body']['doors']['front_left']).lower());time.sleep(2)
                q.demo('settings','on');restored=d.matrix.switch_states(d.matrix.ui(folder/'settings-restored.xml'))
                if any(restored[k]['checked']!=flags[k]['checked'] for k in flags):raise RuntimeError('settings changed')
                q.demo('settings','off');q.ui('cleanup',True)
                after=q.api('/vehicle/state');q.save('vehicle-after.json',after);q.check_park(after)
                for domain,keys in {'hvac':['power_on','auto_on','ac_on','dual_on','sync_on','recirc_on','front_defroster_on','rear_defroster_on','fan_speed','auto_blower_level','driver','passenger','row2'],
                    'seat':['positions','moves','occupied'],'body':['doors','door_locks','windows','closures','wipers','cabin_light','reading_lights','ambient_light'],
                    'controls':['speed_kph','accelerator_pct','brake_pct','steering_deg']}.items():
                    if any(after[domain][key]!=original[domain][key] for key in keys):raise RuntimeError('original domain differs: '+domain)
                d.runtime.require_stopped(d.runtime.snapshot('cleanup-runtime'));final=d.identity();q.save('environment-after.json',final)
                if final!=before:raise RuntimeError('identity/PID changed')
            except BaseException as error:errors.append('cleanup readback: '+str(error))
        if old_tag is not None:
            try:
                q.adb('shell','setprop log.tag.MotionTrace '+shlex.quote(old_tag))
                if q.adb('shell','getprop','log.tag.MotionTrace').strip()!=old_tag:raise RuntimeError('property restore failed')
            except BaseException as error:errors.append('property: '+str(error))
        d.log_stop(logger)
        if browser:browser.close()
        if pw:pw.stop()
        q.save('cleanup.json',{'verified':len(errors)==count,'executionErrors':errors[:count],'cleanupErrors':errors[count:]})
    analysis=None
    if (folder/'motion.log').exists():
        try:analysis=analyze(folder)
        except BaseException as error:errors.append('analysis: '+str(error))
    result={'executionPassed':not errors,'arithmeticVerified':bool(analysis and analysis['arithmeticVerified']),'issues':errors,
        'historical449CauseEstablished':False,'physicalGAccuracyValidated':False}
    q.save('result.json',result);d.finish_manifest();print(json.dumps(result),flush=True)
    return 0 if result['executionPassed'] and result['arithmeticVerified'] else 1


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',action='store_true',required=True);p.add_argument('--out',default='flow-trace-01')
    args=p.parse_args()
    if not re.fullmatch('flow-trace-[A-Za-z0-9_-]+',args.out):p.error('new flow-trace folder required')
    raise SystemExit(run(BASE/args.out))
