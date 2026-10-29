#!/usr/bin/env python3
"""Exercise real SDK control on the verified AVD; never use on a physical car.

Open the official Virtual Console in a browser first (adb forward tcp:8088 tcp:8088).
This changes this test emulator's HVAC/seat/gear inputs, restarts the app, and drives
preset/end/ack UI actions. No mock vehicle signals are injected. Original HVAC,
driver temperature, DUAL flag, gear and passenger occupancy are restored as tester cleanup, separately from
assertions about the app's restoration. Output must be a new local directory.
"""
import argparse
import datetime
import json
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request
import re
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--adb', default=shutil.which('adb'))
args = parser.parse_args()
if not args.adb:
    parser.error('adb is required')
if args.out.exists():
    parser.error('output directory must be new')

PACKAGE = 'ai.vipseat.cargoconcierge'
def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], timeout=20)
def api(path, method='GET'):
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8088'+path, method=method), timeout=10) as response:
        return json.load(response)
def send(command, value=None):
    extra = ['--es','value',value] if value else []
    adb('shell','am','broadcast','-n',PACKAGE+'/.demo.DemoBroadcastReceiver',
        '-a',PACKAGE+'.DEMO','--es','cmd',command,*extra)
def log():
    pid = adb('shell','pidof',PACKAGE).decode().strip()
    return adb('shell','logcat','-d','--pid='+pid,'-v','threadtime').decode()
def checkpoint(name):
    state = api('/vehicle/state')
    evidence = {'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'passenger_temperature_c':state['hvac']['passenger']['temp_c'],
                'driver_temperature_c':state['hvac']['driver']['temp_c'],
                'console_passenger_occupied':state['seat']['occupied']['passenger'],
                'hvac_power':state['hvac']['power_on'],
                'hvac_dual':state['hvac']['dual_on'],
                'console_available':state['availability']['available']}
    (args.out/(name+'.json')).write_text(json.dumps(evidence,indent=2)+'\n')
    (args.out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    adb('shell','uiautomator','dump','/sdcard/vipseat-ui.xml')
    (args.out/(name+'.xml')).write_bytes(adb('exec-out','cat','/sdcard/vipseat-ui.xml'))
    raw = log()
    # Keep exact SDK property requests/results and app outcomes, omitting unrelated system noise.
    relevant = [line for line in raw.splitlines() if
                'PleosVehicle:' in line or 'CargoVM ' in line or
                any(x in line for x in ('HVAC_TEMPERATURE_SET','SEAT_OCCUPANCY'))]
    (args.out/(name+'.log')).write_text('\n'.join(relevant)+'\n')
    print(name, evidence['passenger_temperature_c'], flush=True)
    return evidence, raw

def tap_restore_retry():
    adb('shell','uiautomator','dump','/sdcard/vipseat-ui.xml')
    ui = ET.fromstring(adb('exec-out','cat','/sdcard/vipseat-ui.xml'))
    nodes = [n for n in ui.iter('node') if n.attrib.get('text') == 'P 정차 후 복원 다시 확인']
    assert nodes, 'Parked retry control must be visible'
    x1,y1,x2,y2 = map(int, re.findall(r'\d+',nodes[0].attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); time.sleep(4)

def temperature(expected):
    actual = api('/vehicle/state')['hvac']['passenger']['temp_c']
    assert actual == expected, (expected, actual)

def finish():
    send('end'); time.sleep(3); send('ack'); time.sleep(1)

def start():
    adb('shell','logcat','-c'); send('preset','CAKE'); time.sleep(4)

assert args.serial.startswith('emulator-')
assert adb('shell','getprop','ro.kernel.qemu').decode().strip() == '1'
assert adb('shell','getprop','ro.product.name').decode().strip() == 'crp_avd_arm64'
assert adb('shell','getprop','ro.build.version.sdk').decode().strip() == '34'
service = adb('shell','dumpsys','package','ai.pleos.playground.service.vehicle').decode()
assert 'versionName=2.3.22' in service and 'versionCode=74 ' in service
forwards = adb('forward','--list').decode().splitlines()
assert any(line.split() == [args.serial,'tcp:8088','tcp:8088'] for line in forwards), '8088 must forward to the selected emulator'
package_info = adb('shell','dumpsys','package',PACKAGE).decode()
assert 'versionName=1.1.3-pleos' in package_info and 'versionCode=6 ' in package_info
before = api('/vehicle/state')
assert before['gear']['actual']['value'] == 'Park'
assert all(before['controls'][key] == 0 for key in ('speed_kph','accelerator_pct','brake_pct','steering_deg'))
assert before['availability']['available'] and before['hvac']['emulator_active'], 'Open official Console in browser'
args.out.mkdir(parents=True)
(args.out/'tester-before.json').write_text(json.dumps(before,indent=2)+'\n')
(args.out/'environment.json').write_text(json.dumps({'app':'1.1.3-pleos','versionCode':6,'vehicle_sdk':'2.3.2','vehicle_service':'2.3.22','vehicle_service_code':74,'product':'crp_avd_arm64','api':34,'serial':args.serial,'port_forward_verified':True},indent=2)+'\n')
results = {}
try:
    api('/vehicle/hvac?power=true&passenger_temp=22.0','PUT')
    api('/vehicle/gear?gear=Park','PUT')
    api('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0','PUT')
    api('/vehicle/seat?kind=occupied&area=passenger&value=true','PUT')
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity')
    time.sleep(4)
    start()
    state, raw = checkpoint('C1-occupied-blocked')
    assert state['passenger_temperature_c'] == 22
    assert 'apply TEMPERATURE: BLOCKED_BY_OCCUPANCY' in raw
    assert 'eventPropKey SEAT_OCCUPANCY eventAreaId 4 eventValue 2' in raw
    results['occupied_maps_unknown_and_blocks'] = True
    finish()
    api('/vehicle/seat?kind=occupied&area=passenger&value=false','PUT'); time.sleep(2)
    checkpoint('C2-vacant-before')
    start()
    state, raw = checkpoint('C3-applied')
    assert state['passenger_temperature_c'] == 18
    assert 'apply TEMPERATURE: APPLIED' in raw
    assert 'eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue 18.0' in raw
    send('end'); time.sleep(4)
    state, raw = checkpoint('C4-restored')
    assert state['passenger_temperature_c'] == 22
    assert 'restore TEMPERATURE: APPLIED' in raw
    assert '복원 요청 후 원래 차량 값 확인' in raw
    restored_log = raw[raw.index('가드 종료'):]
    readback = 'getProperty_onResult eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue 22.0'
    assert restored_log.index(readback) < restored_log.index('restore TEMPERATURE: APPLIED')
    results['sdk_readback_roundtrip_22_18_22'] = True
    send('ack'); time.sleep(1)
    start(); temperature(18)
    api('/vehicle/hvac?passenger_temp=21.0','PUT'); time.sleep(2)
    send('end'); time.sleep(4)
    state, raw = checkpoint('C5-external-change')
    assert state['passenger_temperature_c'] == 21
    assert '덮어쓰지 않고 유지' in raw
    results['external_21_preserved'] = True
    send('ack'); time.sleep(1)
    api('/vehicle/hvac?passenger_temp=22.0','PUT'); time.sleep(2)
    start(); temperature(18)
    api('/vehicle/seat?kind=occupied&area=passenger&value=true','PUT'); time.sleep(4)
    state, raw = checkpoint('C6-vacancy-lost')
    assert state['passenger_temperature_c'] == 22
    assert '비착좌 확인 상실' in raw and 'restore TEMPERATURE: APPLIED' in raw
    results['vacancy_loss_ends_and_restores'] = True
    send('ack'); time.sleep(1)
    api('/vehicle/seat?kind=occupied&area=passenger&value=false','PUT')
    api('/vehicle/hvac?power=false&passenger_temp=22.0','PUT'); time.sleep(2)
    start()
    state, raw = checkpoint('C7-power-off-setting-only')
    assert state['passenger_temperature_c'] == 18 and state['hvac_power'] is False
    assert 'confirmation=VALUE_CONFIRMED' in raw and 'hvacPowerAtCheck=false' in raw
    assert '공조 전원 꺼짐' in (args.out/'C7-power-off-setting-only.xml').read_text()
    results['power_off_disclosed_without_turning_on'] = True
    finish(); temperature(22)
    # These are AVD property observations, not proof of real-car SYNC semantics.
    for enabled, name in [(False,'C8-dual-false'), (True,'C9-dual-true')]:
        api('/vehicle/hvac?power=true&driver_temp=24.0&passenger_temp=22.0&dual='+str(enabled).lower(),'PUT'); time.sleep(2)
        baseline = api('/vehicle/state')['hvac']
        (args.out/(name+'-before.json')).write_text(json.dumps(baseline,indent=2)+'\n')
        start()
        state, raw = checkpoint(name)
        assert state['driver_temperature_c'] == baseline['driver']['temp_c'] and state['hvac_dual'] == enabled
        if enabled:
            assert state['passenger_temperature_c'] == baseline['passenger']['temp_c']
            assert 'apply TEMPERATURE: FAILED' in raw and '동기화' in raw
        else:
            assert state['passenger_temperature_c'] == 18
        finish(); temperature(baseline['passenger']['temp_c'])
        state_after = api('/vehicle/state')
        assert state_after['hvac']['driver']['temp_c'] == baseline['driver']['temp_c'] and state_after['hvac']['dual_on'] == enabled
        results[name+'_driver_and_sync_unchanged'] = True
    # User enables synchronization after application while the values still match our target.
    api('/vehicle/hvac?dual=false&driver_temp=18.0&passenger_temp=22.0','PUT'); time.sleep(2)
    start(); temperature(18)
    api('/vehicle/hvac?dual=true','PUT'); time.sleep(2)
    send('end'); time.sleep(4)
    state, raw = checkpoint('C9B-external-sync-restore-deferred')
    assert state['passenger_temperature_c'] == 18 and state['hvac_dual'] is True
    assert 'restore TEMPERATURE: FAILED' in raw and '동기화' in raw
    api('/vehicle/hvac?dual=false','PUT'); time.sleep(2)
    tap_restore_retry()
    state, raw = checkpoint('C9C-sync-off-retry-restored')
    assert state['passenger_temperature_c'] == 22 and state['hvac_dual'] is False
    assert state['driver_temperature_c'] == 18 and 'restore TEMPERATURE: APPLIED' in raw
    results['external_sync_respected_then_explicit_retry'] = True
    send('ack'); time.sleep(1)
    # Loss of vacancy while Drive is selected must defer restoration until parked.
    start(); temperature(18)
    api('/vehicle/gear?gear=Drive','PUT'); time.sleep(2)
    api('/vehicle/seat?kind=occupied&area=passenger&value=true','PUT'); time.sleep(4)
    state, raw = checkpoint('C10-drive-restore-deferred')
    assert state['passenger_temperature_c'] == 18 and 'DRIVING_RESTRICTED' in raw
    api('/vehicle/gear?gear=Park','PUT'); time.sleep(3)
    tap_restore_retry()
    state, raw = checkpoint('C11-park-retry-restored')
    assert state['passenger_temperature_c'] == 22 and 'restore TEMPERATURE: APPLIED' in raw
    results['drive_deferred_then_park_retry_restored'] = True
    send('ack'); time.sleep(1)
    api('/vehicle/seat?kind=occupied&area=passenger&value=false','PUT'); time.sleep(2)
    start(); temperature(18)
    adb('shell','am','force-stop',PACKAGE); adb('shell','logcat','-c')
    adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity'); time.sleep(4)
    state, raw = checkpoint('C12-restart-manual-check')
    assert state['passenger_temperature_c'] == 18
    assert '이전 실행의 공조 복원을 확인하지 못했어요' in (args.out/'C12-restart-manual-check.xml').read_text()
    assert 'setHvacTemperature' not in raw
    results['restart_discloses_unconfirmed_restore_no_automatic_write'] = True
finally:
    # Stop app commands before tester cleanup. Cleanup never proves app restoration.
    cleanup = {'attempts':{}, 'verified':False}
    operations = {
        'stop_app': lambda: adb('shell','am','force-stop',PACKAGE),
        'hvac': lambda: api('/vehicle/hvac?passenger_temp='+str(before['hvac']['passenger']['temp_c'])+'&driver_temp='+str(before['hvac']['driver']['temp_c'])+'&dual='+str(before['hvac']['dual_on']).lower()+'&power='+str(before['hvac']['power_on']).lower(),'PUT'),
        'gear': lambda: api('/vehicle/gear?gear=Park','PUT'),
        'seat': lambda: api('/vehicle/seat?kind=occupied&area=passenger&value='+str(before['seat']['occupied']['passenger']).lower(),'PUT'),
    }
    for name, operation in operations.items():
        try:
            operation()
            cleanup['attempts'][name] = 'ok'
        except Exception as error:
            cleanup['attempts'][name] = type(error).__name__
    try:
        after = api('/vehicle/state')
        cleanup['observed'] = after
        cleanup['verified'] = (after['hvac']['passenger']['temp_c'] == before['hvac']['passenger']['temp_c'] and
                               after['hvac']['power_on'] == before['hvac']['power_on'] and
                               after['hvac']['driver']['temp_c'] == before['hvac']['driver']['temp_c'] and
                               after['hvac']['dual_on'] == before['hvac']['dual_on'] and
                               after['gear']['actual']['value'] == before['gear']['actual']['value'] and
                               after['seat']['occupied']['passenger'] == before['seat']['occupied']['passenger'])
    except Exception as error:
        cleanup['read_error'] = type(error).__name__
    (args.out/'tester-cleanup.json').write_text(json.dumps(cleanup,indent=2)+'\n')
    (args.out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
    if not cleanup['verified'] or any(x != 'ok' for x in cleanup['attempts'].values()):
        raise RuntimeError('Tester cleanup incomplete; inspect tester-cleanup.json')
