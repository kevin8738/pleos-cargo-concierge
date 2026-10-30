#!/usr/bin/env python3
"""Opt-in v1.1.5/code8 official-AVD flow capture; inert on import.

Root supplies an active Console heartbeat without a browser physics/control
writer, plus exclusive AVD access. Opening the API panel alone does not disable
the cached Console physics loop. Replays v22's nine synthetic UI checks, five principal screens plus
one blocked-seat screen, and a silent screenrecord. No install or physical-car use.
A successful run is UI/SDK smoke evidence, not motion accuracy or field validation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import select
import shlex
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

PACKAGE = 'ai.vipseat.cargoconcierge'
CONTROL_KEYS = ('speed_kph', 'accelerator_pct', 'brake_pct', 'steering_deg')
HVAC_KEYS = ('power_on', 'auto_on', 'ac_on', 'dual_on', 'sync_on', 'recirc_on',
             'front_defroster_on', 'rear_defroster_on', 'fan_speed', 'auto_blower_level',
             'driver', 'passenger', 'row2')
CHECKS = ('single_tap_preset', 'readback_22_18', 'console_simulated_motion',
          'park_driver_door_arrival', 'readback_restore_22', 'power_off_disclosed',
          'occupied_maps_unknown_and_blocks', 'continuous_screenrecord_no_audio',
          'simulated_report_explicit')


class Probe:
    def __init__(self, args):
        self.args, self.out = args, args.out.resolve()
        self.results = {key: False for key in CHECKS}
        self.events, self.errors = [], []
        self.started = time.monotonic()
        self.recorder = self.remote_pid = self.remote_start = None
        self.remote_video = '/sdcard/vipseat-v26-flow-' + uuid.uuid4().hex + '.mp4'

    def save(self, name, value):
        (self.out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

    def adb(self, *parts, binary=False):
        raw = subprocess.check_output([self.args.adb, '-s', self.args.serial, *parts], timeout=20)
        return raw if binary else raw.decode('utf-8', 'replace')

    def api(self, path, method='GET', **values):
        if values:
            path += '?' + urllib.parse.urlencode({k: str(v).lower() if isinstance(v, bool) else v for k, v in values.items()})
        with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8088' + path, method=method), timeout=10) as response:
            return json.load(response)

    def mark(self, event):
        self.events.append({'seconds': round(time.monotonic() - self.started, 2), 'event': event})
        self.save('timeline.json', {'events': self.events, 'limits': __doc__})
        print(self.events[-1], flush=True)

    def fail(self, stage, error):
        self.errors.append({'stage': stage, 'type': type(error).__name__, 'message': str(error)})
        self.save('failures.json', self.errors)

    def identity(self):
        assert self.args.serial.startswith('emulator-'), 'Emulator serial required'
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        assert self.adb('shell', 'getprop', 'ro.product.name').strip() == 'crp_avd_arm64'
        assert self.adb('shell', 'getprop', 'ro.build.version.sdk').strip() == '34'
        service = self.adb('shell', 'dumpsys', 'package', 'ai.pleos.playground.service.vehicle')
        assert 'versionName=2.3.22' in service and re.search(r'versionCode=74\b', service)
        package = self.adb('shell', 'dumpsys', 'package', PACKAGE)
        assert re.search(r'versionName=1\.1\.5-pleos(?:\s|$)', package) and re.search(r'versionCode=8\b', package)
        paths = self.adb('shell', 'pm', 'path', PACKAGE).splitlines()
        assert len(paths) == 1 and paths[0].startswith('package:'), 'Single installed base APK required'
        digest = hashlib.sha256(self.adb('exec-out', 'cat', paths[0][8:], binary=True)).hexdigest()
        assert digest == self.args.expected_apk_sha256, 'Installed APK differs from reviewed candidate'
        assert [self.args.serial, 'tcp:8088', 'tcp:8088'] in [line.split() for line in self.adb('forward', '--list').splitlines()]
        return {'versionName': '1.1.5-pleos', 'versionCode': 8, 'apk_sha256': digest,
                'product': 'crp_avd_arm64', 'api': 34, 'vehicle_service': '2.3.22',
                'vehicle_service_code': 74, 'serial': self.args.serial,
                'android_user': self.adb('shell', 'am', 'get-current-user').strip()}

    def preflight(self):
        self.save('environment.json', self.identity())
        before = self.api('/vehicle/state')
        assert before['availability']['available'] and before['hvac']['emulator_active'], 'Root Console heartbeat required'
        assert before['gear']['actual']['value'] == 'Park'
        assert all(before['controls'][key] == 0 for key in CONTROL_KEYS), 'Original controls must already be P/zero'
        assert before['hvac']['passenger']['seat_temp'] == 0 and before['hvac']['passenger']['seat_mode'] == 'Off', 'Already-OFF passenger seat required'
        assert before['hvac']['dual_on'] == before['hvac']['sync_on'], 'Ambiguous SYNC baseline'
        self.save('tester-before.json', before)
        return before

    def park(self):
        # Same ordered requests as the Console P button. Root must disable its
        # browser physics loop; this script never creates a competing browser.
        self.api('/vehicle/controls', 'PUT', speed=0, accelerator=0, brake=0, steering=0)
        self.api('/vehicle/gear', 'PUT', gear='Park')
        time.sleep(2.5)
        actual = self.api('/vehicle/state')
        assert actual['gear']['actual']['value'] == 'Park'
        assert all(actual['controls'][key] == 0 for key in CONTROL_KEYS), 'Park/zero readback failed'

    def send(self, command, value=None):
        assert command in ('preset', 'end', 'ack') and (command != 'preset' or value == 'CAKE')
        extra = ['--es', 'value', value] if value else []
        result = self.adb('shell', 'am', 'broadcast', '-n', PACKAGE + '/.demo.DemoBroadcastReceiver',
                          '-a', PACKAGE + '.DEMO', '--es', 'cmd', command, *extra)
        assert 'Broadcast completed: result=0' in result and 'data="error:' not in result

    def tree(self):
        raw = self.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        return raw[raw.index('<?xml'):raw.index('</hierarchy>') + len('</hierarchy>')]

    def shot(self, name):
        self.mark(name)
        xml = self.tree()
        (self.out / (name + '.xml')).write_text(xml)
        (self.out / (name + '.png')).write_bytes(self.adb('exec-out', 'screencap', '-p', binary=True))
        self.save(name + '.json', self.api('/vehicle/state'))
        return xml

    def tap_cake(self):
        nodes = [node for node in ET.fromstring(self.tree()).iter('node')
                 if node.get('package') == PACKAGE and node.get('text', '').startswith('케이크 ')
                 and node.get('text', '').endswith('G') and node.get('enabled') == 'true']
        assert len(nodes) == 1, 'One visible enabled recent cake chip required'
        bounds = list(map(int, re.findall(r'\d+', nodes[0].get('bounds', ''))))
        assert len(bounds) == 4
        self.adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))

    def temp(self, expected):
        assert self.api('/vehicle/state')['hvac']['passenger']['temp_c'] == expected

    def recorder_identity(self):
        # Both start ticks and the exact generated output path bind this PID.
        fields = self.adb('exec-out', 'cat', f'/proc/{self.remote_pid}/cmdline', binary=True).split(b'\0')
        assert fields[0].split(b'/')[-1] == b'screenrecord' and self.remote_video.encode() in fields
        stat = self.adb('exec-out', 'cat', f'/proc/{self.remote_pid}/stat')
        return stat[stat.rindex(')') + 2:].split()[19]

    def start_recording(self):
        # No local shell interpolation. The remote shell PID becomes screenrecord via exec.
        script = 'echo $$; exec screenrecord --bit-rate 6000000 --time-limit 180 ' + shlex.quote(self.remote_video)
        self.recorder = subprocess.Popen([self.args.adb, '-s', self.args.serial, 'shell', script],
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert select.select([self.recorder.stdout], [], [], 5)[0], 'Recorder PID handshake timed out'
        self.remote_pid = self.recorder.stdout.readline().decode().strip()
        assert re.fullmatch(r'[1-9][0-9]*', self.remote_pid), 'Invalid owned recorder PID'
        # exec may still be in flight immediately after the handshake.
        for attempt in range(10):
            try:
                self.remote_start = self.recorder_identity()
                break
            except (AssertionError, subprocess.CalledProcessError):
                if attempt == 9:
                    raise
                time.sleep(.1)
        self.save('recorder.json', {'remote_pid': self.remote_pid, 'start_ticks': self.remote_start,
                                    'remote_path': self.remote_video, 'audio': False})
        self.mark('screenrecord_started')

    def finish_recording(self):
        if self.recorder is None:
            return False
        count = len(self.errors)
        try:
            if self.recorder.poll() is None:
                assert self.remote_start and self.recorder_identity() == self.remote_start, 'Owned recorder identity changed; no signal sent'
                self.adb('shell', 'kill', '-2', self.remote_pid)
            stdout, stderr = self.recorder.communicate(timeout=15)
            (self.out / 'screenrecord.log').write_bytes(stdout + stderr)
        except BaseException as error:
            self.fail('recording_stop', error)
        finally:
            if self.recorder.poll() is None:
                try:
                    # Never pidof/killall. Escalate only this same owned screenrecord.
                    assert self.remote_start and self.recorder_identity() == self.remote_start
                    self.adb('shell', 'kill', '-15', self.remote_pid)
                    stdout, stderr = self.recorder.communicate(timeout=5)
                    (self.out / 'screenrecord.log').write_bytes(stdout + stderr)
                except BaseException as error:
                    self.fail('recording_escalation', error)
            try:
                video = self.adb('exec-out', 'cat', self.remote_video, binary=True)
                assert len(video) > 1024, 'Recording absent or truncated'
                (self.out / '전체동선_무음.mp4').write_bytes(video)
            except BaseException as error:
                self.fail('recording_retrieve', error)
        return len(self.errors) == count

    def flow(self):
        self.adb('shell', 'am', 'force-stop', PACKAGE)
        self.api('/vehicle/hvac', 'PUT', dual=False, power=True, passenger_temp=22.0)
        self.api('/vehicle/seat', 'PUT', kind='occupied', area='passenger', value=False)
        self.api('/vehicle/body', 'PUT', door_fl=False)
        self.adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity'); time.sleep(3)
        # Original warm-up cycle creates the observed recent CAKE chip.
        self.send('preset', 'CAKE'); time.sleep(4); self.send('end'); time.sleep(4)
        self.send('ack'); time.sleep(2); self.temp(22)
        self.adb('shell', 'logcat', '-c')
        self.start_recording(); time.sleep(2)
        self.shot('A1-home'); self.tap_cake(); self.mark('tap_recent_cake_once')
        time.sleep(4); self.temp(18); self.shot('A2-loaded')
        self.results.update(single_tap_preset=True, readback_22_18=True)
        self.api('/vehicle/gear', 'PUT', gear='Drive'); self.mark('console_drive_start')
        for k in range(19):
            self.api('/vehicle/controls', 'PUT', speed=k*2, accelerator=0, brake=0, steering=0); time.sleep(.5)
        self.api('/vehicle/controls', 'PUT', speed=36, accelerator=0, brake=0, steering=60); time.sleep(2)
        xml = self.shot('A3-driving')
        assert '0.15G 기준' in xml and '적재물 상태는 정차 후' in xml
        self.results['console_simulated_motion'] = True
        self.api('/vehicle/controls', 'PUT', speed=36, accelerator=0, brake=0, steering=0); time.sleep(3)
        for k in range(18, -1, -1):
            self.api('/vehicle/controls', 'PUT', speed=k*2, accelerator=0, brake=0, steering=0); time.sleep(.5)
        self.park(); time.sleep(3); self.api('/vehicle/body', 'PUT', door_fl=True)
        self.mark('console_park_driver_door_open'); time.sleep(5)
        xml = self.shot('A4-arrival'); self.temp(22)
        assert '복원' in xml and '챙' in xml
        assert '모의 입력' in xml and '실주행 평가 불가' in xml
        self.results.update(park_driver_door_arrival=True, readback_restore_22=True, simulated_report_explicit=True)
        self.send('ack'); self.api('/vehicle/body', 'PUT', door_fl=False); time.sleep(2)
        self.api('/vehicle/hvac', 'PUT', power=False, passenger_temp=22.0)
        self.tap_cake(); self.mark('power_off_second_trip'); time.sleep(4); self.temp(18)
        xml = self.shot('A5-power-off')
        assert '공조 전원 꺼짐' in xml and self.api('/vehicle/state')['hvac']['power_on'] is False
        self.results['power_off_disclosed'] = True
        self.send('end'); time.sleep(3); self.send('ack'); time.sleep(2)
        self.api('/vehicle/seat', 'PUT', kind='occupied', area='passenger', value=True)
        self.tap_cake(); self.mark('occupied_unknown_third_trip'); time.sleep(4)
        self.temp(22); self.shot('A6-occupied-blocked')
        self.send('end'); time.sleep(3); self.send('ack'); self.mark('flow_complete')
        pid = self.adb('shell', 'pidof', PACKAGE).strip()
        assert re.fullmatch(r'[1-9][0-9]*', pid)
        raw = self.adb('shell', 'logcat', '-d', '--pid=' + pid, '-v', 'threadtime')
        (self.out / 'sdk-flow.log').write_text(raw)
        assert 'apply TEMPERATURE: APPLIED' in raw and 'restore TEMPERATURE: APPLIED' in raw and '운전석 도어 열림' in raw
        assert 'apply TEMPERATURE: BLOCKED_BY_OCCUPANCY' in raw and 'eventPropKey SEAT_OCCUPANCY eventAreaId 4 eventValue 2' in raw
        self.results['occupied_maps_unknown_and_blocks'] = True
        assert self.recorder.poll() is None, 'Recording ended before flow completion'

    def cleanup(self, before):
        cleanup = {'verified': False, 'errors': []}
        h = before['hvac']
        # Restoring all known HVAC controls also repairs a failed app path. Unknown
        # row2/metadata differences are detected below, never silently accepted.
        payload = {wire: h[key] for wire, key in {'power': 'power_on', 'auto': 'auto_on', 'ac': 'ac_on',
                   'recirc': 'recirc_on', 'front_defroster': 'front_defroster_on',
                   'rear_defroster': 'rear_defroster_on', 'fan': 'fan_speed'}.items()}
        for zone in ('driver', 'passenger'):
            payload.update({zone+'_temp': h[zone]['temp_c'], zone+'_seat': h[zone]['seat_temp'],
                            zone+'_direction': h[zone]['fan_direction']})
        essential = {k: payload.pop(k) for k in ('power', 'driver_temp', 'passenger_temp', 'driver_seat', 'passenger_seat')}
        actions = [('stop_app', lambda: self.adb('shell', 'am', 'force-stop', PACKAGE)),
                   ('park_zero', self.park),
                   ('unsync', lambda: self.api('/vehicle/hvac', 'PUT', dual=False)),
                   ('hvac_values', lambda: self.api('/vehicle/hvac', 'PUT', **essential)),
                   ('hvac_other', lambda: self.api('/vehicle/hvac', 'PUT', **payload)),
                   ('sync', lambda: self.api('/vehicle/hvac', 'PUT', dual=h['dual_on'])),
                   ('occupancy', lambda: self.api('/vehicle/seat', 'PUT', kind='occupied', area='passenger', value=before['seat']['occupied']['passenger'])),
                   ('door', lambda: self.api('/vehicle/body', 'PUT', door_fl=before['body']['doors']['front_left']))]
        for name, action in actions:
            try:
                action()
            except BaseException as error:
                cleanup['errors'].append({'operation': name, 'error': str(error)})
        try:
            time.sleep(2)
            after = self.api('/vehicle/state'); self.save('tester-after.json', after)
            assert after['gear']['actual']['value'] == 'Park'
            assert all(after['controls'][k] == before['controls'][k] for k in CONTROL_KEYS)
            assert all(after['hvac'][k] == h[k] for k in HVAC_KEYS), 'HVAC/temperature/SYNC/seat readback differs'
            assert after['seat']['occupied'] == before['seat']['occupied']
            assert after['body']['doors'] == before['body']['doors'], 'Door readback differs'
            self.save('environment-after.json', self.identity())
            cleanup['verified'] = not cleanup['errors']
        except BaseException as error:
            cleanup['errors'].append({'operation': 'verify', 'error': str(error)})
        self.save('tester-cleanup.json', cleanup)
        return cleanup['verified']

    def run(self):
        self.out.mkdir(parents=True, exist_ok=False)
        (self.out / 'harness-source.py').write_bytes(Path(__file__).read_bytes())
        before = None; mutated = False; complete = False; cleanup_ok = False
        try:
            before = self.preflight()  # Every prerequisite is read-only and precedes mutation.
            mutated = True
            self.flow(); complete = True
        except BaseException as error:
            self.fail('execution', error)
        finally:
            try:
                recording_ok = self.finish_recording()
                self.results['continuous_screenrecord_no_audio'] = complete and recording_ok
            except BaseException as error:
                self.fail('recording', error)
            if mutated:
                try:
                    cleanup_ok = self.cleanup(before)
                    if not cleanup_ok:
                        raise RuntimeError('Tester cleanup incomplete; inspect tester-cleanup.json')
                except BaseException as error:
                    self.fail('cleanup', error)
            self.save('results.json', {'checks': self.results, 'passed': all(self.results.values()) and cleanup_ok and not self.errors,
                                       'errors': self.errors, 'cleanup_verified': cleanup_ok, 'physical_g_accuracy_validated': False,
                                       'limits': __doc__})
            files = {str(f.relative_to(self.out)): hashlib.sha256(f.read_bytes()).hexdigest()
                     for f in sorted(self.out.rglob('*')) if f.is_file()}
            self.save('artifact-sha256.json', files)
        return 0 if all(self.results.values()) and cleanup_ok and not self.errors else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--expected-apk-sha256', required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
    args = parser.parse_args()
    if args.out.exists():
        parser.error('Output directory must be new; prior failures are never overwritten')
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_apk_sha256):
        parser.error('Expected SHA-256 must be 64 lowercase hexadecimal characters')
    return Probe(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
