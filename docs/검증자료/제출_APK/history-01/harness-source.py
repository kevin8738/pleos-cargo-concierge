#!/usr/bin/env python3
"""Bounded history UI regression on the official test AVD only.

Requires root's exclusive AVD handoff, installed reviewed candidate and an active
official Console heartbeat. No action on import. No app data or history injection.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

PKG = 'ai.vipseat.cargoconcierge'
REQUIRED_HISTORY_TEXT = (
    '당시 변경 전 조회값: 22.0 °C',
    '당시 적용 요청값: 18.0 °C',
    '마지막 확인값: 18.0 °C',
    '제어 출처: Pleos Vehicle SDK 2.3.2',
    '실제 영역: ROW_1_RIGHT',
    '과거 조회값이며 현재 차량 상태와 다를 수 있어요.',
    '이 화면은 설정을 복원하지 않으며',
)
EXPECTED_CHECKS = {
    'restart_idle_no_restore', 'actual_prior_values_visible',
    'repeated_view_no_observed_control', 'drive_closes_history',
    'new_session_restores_23_prior_22_retained',
    'later_restart_does_not_replay_prior_original',
}


class Probe:
    def __init__(self, args):
        self.args = args
        self.out = args.out.resolve()
        self.results = {}
        self.steps = []

    def save(self, name, value):
        (self.out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

    def adb(self, *parts, binary=False):
        raw = subprocess.check_output([self.args.adb, '-s', self.args.serial, *parts], timeout=20)
        return raw if binary else raw.decode('utf-8', 'replace')

    def api(self, path, method='GET'):
        with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8088' + path, method=method), timeout=10) as r:
            return json.load(r)

    def event(self, name):
        self.steps.append({'event': name, 'hostMonotonicNs': time.monotonic_ns(), 'hostUnixNs': time.time_ns()})
        self.save('steps.json', self.steps)

    def demo(self, command, value=None):
        assert command in {'preset', 'end', 'ack'}
        assert command != 'preset' or value == 'CAKE'
        self.event('demo:' + command)
        extra = ['--es', 'value', value] if value else []
        response = self.adb('shell', 'am', 'broadcast', '-n', PKG + '/.demo.DemoBroadcastReceiver',
                            '-a', PKG + '.DEMO', '--es', 'cmd', command, *extra)
        assert 'Broadcast completed: result=0' in response and 'data="error:' not in response

    def ui(self, name):
        raw = self.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        xml = raw[raw.index('<?xml'):raw.index('</hierarchy>') + len('</hierarchy>')]
        (self.out / (name + '.xml')).write_text(xml)
        (self.out / (name + '.png')).write_bytes(self.adb('exec-out', 'screencap', '-p', binary=True))
        return ET.fromstring(xml)

    @staticmethod
    def texts(tree):
        return [n.get('text', '') for n in tree.iter('node') if n.get('package') == PKG and n.get('text')]

    def tap(self, label, name):
        tree = self.ui(name + '-before')
        nodes = [n for n in tree.iter('node') if n.get('text') == label and n.get('package') == PKG]
        assert len(nodes) == 1, 'Expected one visible control: ' + label
        assert nodes[0].get('enabled') == 'true', 'Disabled control: ' + label
        bounds = list(map(int, re.findall(r'\d+', nodes[0].get('bounds', ''))))
        assert len(bounds) == 4
        self.event('tap:' + label)
        self.adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        time.sleep(0.6)
        return self.ui(name + '-after')

    def checkpoint(self, name):
        state = self.api('/vehicle/state')
        pid = self.adb('shell', 'pidof', PKG).strip()
        # Retain all own-process tags: actual setter callbacks use an SDK tag,
        # not PleosVehicle. Filtering that tag would make absence assertions vacuous.
        log = self.adb('shell', 'logcat', '-d', '--pid=' + pid, '-v', 'threadtime')
        (self.out / (name + '.log')).write_text(log)
        services = self.adb('shell', 'dumpsys', 'activity', 'services', PKG)
        (self.out / (name + '-services.txt')).write_text(services)
        self.save(name + '.json', {'pid': pid, 'console': state, 'hostMonotonicNs': time.monotonic_ns()})
        return state, pid, log, services, self.ui(name)

    def expect_temp(self, value):
        actual = self.api('/vehicle/state')['hvac']['passenger']['temp_c']
        assert actual == value, (value, actual)

    @staticmethod
    def no_control(log, services):
        # This covers selected app SDK/result logs, not every possible vehicle-bus message.
        assert not re.search(r'(?:apply|restore) TEMPERATURE:|캐빈 적용 →|가드 서비스 준비:|setHvac|'
                             r'onResult\(\) called with: propKey = (?:HVAC_|SEAT_)|'
                             r'"event"\s*:\s*"ready"|"guarding"\s*:\s*true', log)
        assert 'ServiceRecord{' not in services or 'GuardForegroundService' not in services

    def setup_check(self):
        assert self.args.serial.startswith('emulator-')
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        assert self.adb('shell', 'getprop', 'ro.product.name').strip() == 'crp_avd_arm64'
        assert self.adb('shell', 'getprop', 'ro.build.version.sdk').strip() == '34'
        service = self.adb('shell', 'dumpsys', 'package', 'ai.pleos.playground.service.vehicle')
        assert 'versionName=2.3.22' in service and 'versionCode=74 ' in service
        package = self.adb('shell', 'dumpsys', 'package', PKG)
        assert 'versionName=' + self.args.version in package and f'versionCode={self.args.code} ' in package
        paths = self.adb('shell', 'pm', 'path', PKG).splitlines()
        assert len(paths) == 1 and paths[0].startswith('package:')
        digest = hashlib.sha256(self.adb('exec-out', 'cat', paths[0][8:], binary=True)).hexdigest()
        assert digest == self.args.apk_sha256
        forwards = [line.split() for line in self.adb('forward', '--list').splitlines()]
        assert [self.args.serial, 'tcp:8088', 'tcp:8088'] in forwards
        before = self.api('/vehicle/state')
        assert before['availability']['available'] and before['hvac']['emulator_active']
        assert before['gear']['actual']['value'] == 'Park'
        assert all(before['controls'][key] == 0 for key in ('speed_kph', 'accelerator_pct', 'brake_pct', 'steering_deg'))
        # CAKE requests heater/vent OFF as well as temperature. This probe does
        # not own seat-mode restoration, so only an already-OFF fixture is admitted.
        assert before['hvac']['passenger']['seat_temp'] == 0
        assert before['hvac']['passenger']['seat_mode'] == 'Off'
        self.save('environment.json', {'version': self.args.version, 'versionCode': self.args.code,
                                      'apkSha256': digest, 'api': 34, 'product': 'crp_avd_arm64',
                                      'vehicleService': '2.3.22', 'vehicleServiceCode': 74})
        self.save('tester-before.json', before)
        return before

    def run(self):
        self.out.mkdir(parents=True, exist_ok=False)
        shutil.copy2(__file__, self.out / 'harness-source.py')
        before = self.setup_check()
        try:
            self.adb('shell', 'am', 'force-stop', PKG)
            self.api('/vehicle/hvac?dual=false&power=true&passenger_temp=22.0', 'PUT')
            self.api('/vehicle/seat?kind=occupied&area=passenger&value=false', 'PUT')
            self.adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
            time.sleep(4)
            self.demo('preset', 'CAKE'); time.sleep(4)
            self.expect_temp(18)
            _, old_pid, apply_log, _, _ = self.checkpoint('01-applied')
            assert 'apply TEMPERATURE: APPLIED' in apply_log
            assert 'onResult() called with: propKey = HVAC_TEMPERATURE_SET, areaId = 4, result = true' in apply_log
            assert 'getProperty_onResult eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue 18.0' in apply_log
            self.adb('shell', 'am', 'force-stop', PKG)
            self.adb('shell', 'logcat', '-c')
            self.adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
            time.sleep(4)
            state, pid, log, services, tree = self.checkpoint('02-restart')
            assert pid != old_pid and state['hvac']['passenger']['temp_c'] == 18
            assert any('이전 실행의 공조 복원을 확인하지 못했어요' in t for t in self.texts(tree))
            assert '프리셋 고르기' in self.texts(tree)
            self.no_control(log, services)
            self.results['restart_idle_no_restore'] = True
            detail = self.tap(self.args.open_label, '03-open')
            # Freeze exact labelled values and prior-session identity before the live run.
            history_text = '\n'.join(self.texts(detail))
            for text in REQUIRED_HISTORY_TEXT:
                assert text in history_text, 'History text missing: ' + text
            prior_headers = [t for t in self.texts(detail) if t.startswith('이전 미완료 실행 · ')]
            assert len(prior_headers) == 1
            prior_header = prior_headers[0]
            self.save('03-history-text.json', self.texts(detail))
            self.results['actual_prior_values_visible'] = True
            for repeat in range(2):
                self.tap(self.args.close_label, f'04-close-{repeat}')
                self.tap(self.args.open_label, f'04-reopen-{repeat}')
            _, _, log, services, _ = self.checkpoint('04-view-only')
            self.expect_temp(18); self.no_control(log, services)
            self.results['repeated_view_no_observed_control'] = True
            self.api('/vehicle/gear?gear=Drive', 'PUT'); time.sleep(3)
            restricted = self.ui('05-drive')
            assert '적재물 없음 · 정차 후 설정' in self.texts(restricted), 'Static driving screen absent'
            assert self.args.close_label not in self.texts(restricted)
            assert self.args.open_label not in self.texts(restricted)
            assert not any(t in '\n'.join(self.texts(restricted)) for t in REQUIRED_HISTORY_TEXT)
            self.api('/vehicle/gear?gear=Park', 'PUT'); time.sleep(3)
            parked = self.ui('05-park')
            assert '프리셋 고르기' in self.texts(parked), 'Parked home screen absent'
            assert self.args.close_label not in self.texts(parked), 'History automatically reopened'
            _, _, log, services, _ = self.checkpoint('05-restriction-complete')
            self.expect_temp(18); self.no_control(log, services)
            self.results['drive_closes_history'] = True
            self.api('/vehicle/hvac?passenger_temp=23.0', 'PUT'); time.sleep(2)
            self.checkpoint('06-new-baseline-23')
            self.demo('preset', 'CAKE'); time.sleep(4); self.expect_temp(18)
            _, _, new_apply_log, _, _ = self.checkpoint('06-new-applied-18')
            assert 'getProperty_onResult eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue 23.0' in new_apply_log
            assert 'apply TEMPERATURE: APPLIED' in new_apply_log
            self.demo('end'); time.sleep(4); self.expect_temp(23)
            _, _, new_restore_log, _, _ = self.checkpoint('06-new-restored-23')
            restore = new_restore_log.rfind('가드 종료')
            assert restore >= 0
            restore_log = new_restore_log[restore:]
            setter = restore_log.index('onResult() called with: propKey = HVAC_TEMPERATURE_SET, areaId = 4, result = true')
            readback = restore_log.index('getProperty_onResult eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue 23.0', setter)
            outcome = restore_log.index('restore TEMPERATURE: APPLIED', readback)
            assert setter < readback < outcome
            self.demo('ack'); time.sleep(1)
            retained = self.tap(self.args.open_label, '06-retained')
            retained_text = '\n'.join(self.texts(retained))
            assert prior_header in self.texts(retained), 'Prior session identity changed'
            for text in REQUIRED_HISTORY_TEXT:
                assert text in retained_text, 'Prior reference replaced: ' + text
            self.results['new_session_restores_23_prior_22_retained'] = True
            self.adb('shell', 'am', 'force-stop', PKG)
            self.adb('shell', 'logcat', '-c')
            self.adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity'); time.sleep(4)
            _, _, log, services, _ = self.checkpoint('07-final-restart')
            self.expect_temp(23); self.no_control(log, services)
            self.results['later_restart_does_not_replay_prior_original'] = True
            assert set(self.results) == EXPECTED_CHECKS and all(self.results.values())
        finally:
            cleanup = {'verified': False, 'errors': []}
            actions = [
                ('stop', lambda: self.adb('shell', 'am', 'force-stop', PKG)),
                ('park', lambda: self.api('/vehicle/gear?gear=Park', 'PUT')),
                ('hvac', lambda: self.api('/vehicle/hvac?dual=' + str(before['hvac']['dual_on']).lower()
                      + '&power=' + str(before['hvac']['power_on']).lower()
                      + '&driver_temp=' + str(before['hvac']['driver']['temp_c'])
                      + '&passenger_temp=' + str(before['hvac']['passenger']['temp_c']), 'PUT')),
                ('occupancy', lambda: self.api('/vehicle/seat?kind=occupied&area=passenger&value='
                      + str(before['seat']['occupied']['passenger']).lower(), 'PUT')),
            ]
            for name, action in actions:
                try: action()
                except Exception as error: cleanup['errors'].append({'operation': name, 'error': type(error).__name__})
            try:
                after = self.api('/vehicle/state')
                cleanup['after'] = after
                cleanup['verified'] = (not cleanup['errors']
                    and all(after['hvac'][k] == before['hvac'][k] for k in (
                        'power_on', 'auto_on', 'ac_on', 'dual_on', 'sync_on', 'recirc_on',
                        'front_defroster_on', 'rear_defroster_on', 'fan_speed',
                        'auto_blower_level', 'driver', 'passenger', 'row2'))
                    and after['gear']['actual']['value'] == 'Park'
                    and after['seat']['occupied']['passenger'] == before['seat']['occupied']['passenger']
                    and all(after['controls'][key] == before['controls'][key] == 0
                            for key in ('speed_kph', 'accelerator_pct', 'brake_pct', 'steering_deg')))
            except Exception as error:
                cleanup['errors'].append({'operation': 'readback', 'error': type(error).__name__})
            self.save('tester-cleanup.json', cleanup)
            self.save('results.json', self.results)
            if not cleanup['verified']:
                raise RuntimeError('Tester cleanup incomplete; inspect recorded state')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', action='store_true', required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--apk-sha256', required=True)
    p.add_argument('--version', required=True)
    p.add_argument('--code', type=int, required=True)
    p.add_argument('--serial', default='emulator-5554')
    p.add_argument('--adb', default='/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
    p.add_argument('--open-label', default='이전 실행 기록 보기')
    p.add_argument('--close-label', default='기록 닫기')
    args = p.parse_args()
    if not re.fullmatch('[0-9a-f]{64}', args.apk_sha256):
        p.error('exact candidate hash required')
    Probe(args).run()


if __name__ == '__main__':
    main()
