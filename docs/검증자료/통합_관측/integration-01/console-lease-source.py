#!/usr/bin/env python3
"""Explicit child process; owns only its browser and any newly created 8088 forward."""
import argparse
import json
import signal
import subprocess
from pathlib import Path

ADB = '/opt/homebrew/share/android-commandlinetools/platform-tools/adb'
SERIAL = 'emulator-5554'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', type=Path, required=True)
    args = parser.parse_args()
    if args.state.exists():
        parser.error('fresh lease state required')
    stop = False
    state = {'ready': False, 'closed': False, 'ownForward': False, 'errors': []}

    def save():
        temp = args.state.with_suffix('.tmp')
        temp.write_text(json.dumps(state, indent=2)+'\n')
        temp.replace(args.state)

    def finish(*_):
        nonlocal stop
        stop = True

    def adb(*parts):
        return subprocess.check_output([ADB, '-s', SERIAL, *parts], timeout=10, text=True)

    signal.signal(signal.SIGTERM, finish)
    signal.signal(signal.SIGINT, finish)
    save()
    try:
        matches = [r.split() for r in adb('forward', '--list').splitlines()
                   if len(r.split()) == 3 and r.split()[1] == 'tcp:8088']
        if matches and matches != [[SERIAL, 'tcp:8088', 'tcp:8088']]:
            raise RuntimeError('foreign 8088 endpoint')
        state['ownForward'] = not matches
        save()  # Persist ownership before the mutation; cleanup is scoped by exact readback.
        if state['ownForward']:
            adb('forward', 'tcp:8088', 'tcp:8088')
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            try:
                page = browser.new_page(viewport={'width': 1440, 'height': 1000})
                page.goto('http://127.0.0.1:8088/', wait_until='domcontentloaded', timeout=15000)
                page.wait_for_timeout(3000)
                state['ready'] = True
                save()
                while not stop:
                    page.wait_for_timeout(250)
            finally:
                browser.close()
    except BaseException as error:
        state['errors'].append(type(error).__name__)
        raise
    finally:
        if state['ownForward']:
            matches = [r.split() for r in adb('forward', '--list').splitlines()
                       if len(r.split()) == 3 and r.split()[1] == 'tcp:8088']
            if matches == [[SERIAL, 'tcp:8088', 'tcp:8088']]:
                adb('forward', '--remove', 'tcp:8088')
            elif matches:
                state['errors'].append('forward ownership changed; left untouched')
        state['closed'] = True
        save()


if __name__ == '__main__':
    main()
