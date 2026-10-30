import subprocess,time,json,urllib.request,pathlib,re,hashlib,xml.etree.ElementTree as ET
q=pathlib.Path(__file__).parent/'flow-01';q.mkdir(exist_ok=False)
a='/opt/homebrew/share/android-commandlinetools/platform-tools/adb';p='ai.vipseat.cargoconcierge';t0=None;timeline=[]
def adb(*x):return subprocess.check_output([a,'-s','emulator-5554',*x],timeout=20)
def installed_identity():
 paths=adb('shell','pm','path',p).decode().splitlines();assert len(paths)==1 and paths[0].startswith('package:'), 'single base APK required'
 sha=adb('shell','sha256sum',paths[0][8:]).decode().split()[0]
 assert sha=='8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c', 'frozen runtime06 APK required'
 package=adb('shell','dumpsys','package',p).decode();assert 'versionName=1.1.4-pleos' in package and re.search(r'versionCode=7\b',package)
 return {'versionName':'1.1.4-pleos','versionCode':7,'apk_sha256':sha,'product':adb('shell','getprop','ro.product.name').decode().strip(),'android_user':adb('shell','am','get-current-user').decode().strip()}
(q/'environment.json').write_text(json.dumps(installed_identity(),indent=2)+'\n')
(q/'harness-source.py').write_bytes(pathlib.Path(__file__).read_bytes())
def api(path,method='PUT'):return json.load(urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8088'+path,method=method),timeout=10))
def state():return api('/vehicle/state','GET')
def send(c,v=None):adb('shell','am','broadcast','-n',p+'/.demo.DemoBroadcastReceiver','-a',p+'.DEMO','--es','cmd',c,*(['--es','value',v] if v else []))
def mark(event):
 timeline.append({'seconds':round(time.monotonic()-t0,2),'event':event});print(timeline[-1],flush=True)
def shot(n):
 mark(n);(q/(n+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 adb('shell','uiautomator','dump','/sdcard/v22-ui.xml');xml=adb('exec-out','cat','/sdcard/v22-ui.xml');(q/(n+'.xml')).write_bytes(xml)
 (q/(n+'.json')).write_text(json.dumps(state(),indent=2));return xml.decode()
def tap_cake():
 adb('shell','uiautomator','dump','/sdcard/v22-ui.xml');ui=ET.fromstring(adb('exec-out','cat','/sdcard/v22-ui.xml'))
 nodes=[n for n in ui.iter('node') if n.attrib.get('text','').startswith('케이크 ') and n.attrib.get('text','').endswith('G')]
 assert nodes,'recent cake chip absent'
 x1,y1,x2,y2=map(int,re.findall(r'\d+',nodes[0].attrib['bounds']));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def temp(expected):assert state()['hvac']['passenger']['temp_c']==expected
from playwright.sync_api import sync_playwright
pw=sync_playwright().start();browser=pw.chromium.launch(headless=True);page=browser.new_page();page.goto('http://127.0.0.1:8088/',wait_until='domcontentloaded');page.wait_for_timeout(2000)
def park():
 page.get_by_role('button',name='P',exact=True).click();page.wait_for_timeout(2500)
park()
before=state();(q/'tester-before.json').write_text(json.dumps(before,indent=2))
assert adb('shell','getprop','ro.product.name').decode().strip()=='crp_avd_arm64'
assert before['gear']['actual']['value']=='Park' and before['controls']['speed_kph']==0
rec=None
try:
 api('/vehicle/hvac?dual=false&power=true&passenger_temp=22.0');api('/vehicle/seat?kind=occupied&area=passenger&value=false');api('/vehicle/body?door_fl=false')
 adb('shell','am','start','-W','-n',p+'/.MainActivity');time.sleep(3)
 # Clear the previous deliberately interrupted test through an actual owned apply/restore cycle.
 send('preset','CAKE');time.sleep(4);send('end');time.sleep(4);send('ack');time.sleep(2);temp(22)
 adb('shell','logcat','-c')
 rec=subprocess.Popen([a,'-s','emulator-5554','shell','screenrecord','--bit-rate','6000000','--time-limit','120','/sdcard/v22-flow.mp4'],stdout=subprocess.PIPE,stderr=subprocess.PIPE);t0=time.monotonic()
 time.sleep(2);shot('A1-home');tap_cake();mark('tap_recent_cake_once');time.sleep(4);temp(18);shot('A2-loaded')
 api('/vehicle/gear?gear=Drive');mark('console_drive_start')
 for k in range(19):api('/vehicle/controls?speed='+str(k*2)+'&accelerator=0&brake=0&steering=0');time.sleep(.5)
 api('/vehicle/controls?speed=36&accelerator=0&brake=0&steering=60');time.sleep(2)
 xml=shot('A3-driving');assert '0.15G 기준' in xml and '적재물 상태는 정차 후' in xml
 api('/vehicle/controls?speed=36&accelerator=0&brake=0&steering=0');time.sleep(3)
 for k in range(18,-1,-1):api('/vehicle/controls?speed='+str(k*2)+'&accelerator=0&brake=0&steering=0');time.sleep(.5)
 api('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0');park();time.sleep(3);api('/vehicle/body?door_fl=true');mark('console_park_driver_door_open');time.sleep(5)
 xml=shot('A4-arrival');temp(22);assert '복원' in xml and '챙' in xml
 assert '모의 입력' in xml and '실주행 평가 불가' in xml, 'simulated provenance must be visible in arrival report'
 send('ack');api('/vehicle/body?door_fl=false');time.sleep(2)
 api('/vehicle/hvac?power=false&passenger_temp=22.0');tap_cake();mark('power_off_second_trip');time.sleep(4);temp(18)
 xml=shot('A5-power-off');assert '공조 전원 꺼짐' in xml and state()['hvac']['power_on'] is False
 send('end');time.sleep(3);send('ack');time.sleep(2)
 api('/vehicle/seat?kind=occupied&area=passenger&value=true');tap_cake();mark('occupied_unknown_third_trip');time.sleep(4);temp(22);shot('A6-occupied-blocked')
 send('end');time.sleep(3);send('ack');mark('flow_complete')
 adb('shell','kill','-2',adb('shell','pidof','screenrecord').decode().strip());rec.communicate(timeout=15)
 (q/'전체동선_무음.mp4').write_bytes(adb('exec-out','cat','/sdcard/v22-flow.mp4'))
 pid=adb('shell','pidof',p).decode().strip();raw=adb('shell','logcat','-d','--pid='+pid,'-v','threadtime').decode()
 filtered=[l for l in raw.splitlines() if any(x in l for x in ('PleosVehicle:','CargoVM ','DemoBridge:','HVAC_TEMPERATURE_SET','SEAT_OCCUPANCY'))]
 (q/'sdk-flow.log').write_text('\n'.join(filtered)+'\n')
 assert 'apply TEMPERATURE: APPLIED' in raw and 'restore TEMPERATURE: APPLIED' in raw and '운전석 도어 열림' in raw
 (q/'results.json').write_text(json.dumps({'single_tap_preset':True,'readback_22_18':True,'console_simulated_motion':True,'park_driver_door_arrival':True,'readback_restore_22':True,'power_off_disclosed':True,'occupied_maps_unknown_and_blocks':True,'continuous_screenrecord_no_audio':True,'simulated_report_explicit':True},indent=2))
finally:
 (q/'timeline.json').write_text(json.dumps({'events':timeline,'limits':'Actual v1.1.4 APK in official AVD. All vehicle inputs including motion are synthetic Console/DDS simulator values; SDK calls/readbacks are real emulator operations. UI cake is tapped at observed bounds. end/ack use adb UI actions. No physical cargo test or measured audio. Uninterrupted silent screen recording.'},ensure_ascii=False,indent=2))
 adb('shell','am','force-stop',p)
 park();api('/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0')
 h=before['hvac'];api('/vehicle/hvac?dual=false&power='+str(h['power_on']).lower()+'&passenger_temp='+str(h['passenger']['temp_c'])+'&driver_temp='+str(h['driver']['temp_c']));api('/vehicle/hvac?dual='+str(h['dual_on']).lower())
 api('/vehicle/seat?kind=occupied&area=passenger&value='+str(before['seat']['occupied']['passenger']).lower());api('/vehicle/body?door_fl='+str(before['body']['doors']['front_left']).lower());time.sleep(2)
 after=state();verified=after['hvac']['power_on']==h['power_on'] and after['hvac']['passenger']['temp_c']==h['passenger']['temp_c'] and after['hvac']['driver']['temp_c']==h['driver']['temp_c'] and after['hvac']['dual_on']==h['dual_on'] and after['seat']['occupied']['passenger']==before['seat']['occupied']['passenger'] and after['controls']['speed_kph']==0 and after['gear']['actual']['value']=='Park'
 (q/'tester-cleanup.json').write_text(json.dumps({'verified':verified},indent=2));assert verified
 (q/'environment-after.json').write_text(json.dumps(installed_identity(),indent=2)+'\n')
 (q/'tester-after.json').write_text(json.dumps(after,indent=2)+'\n')
 browser.close();pw.stop()
files={str(f.relative_to(q)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(q.rglob('*')) if f.is_file()}
(q/'artifact-sha256.json').write_text(json.dumps(files,ensure_ascii=False,indent=2)+'\n')
print('Flow recording verified',flush=True)
