#!/usr/bin/env python3
"""Test a public transaction screenshot through encoding, preview and Room (mock API by default)."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import time

PROJECT = Path(__file__).resolve().parents[1]
APP = 'com.storytellerf.summer.debug'


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--expected-json', type=Path, required=True,
                        help='Manually checked JSON with a transactions array; [] expects recognition rejection')
    parser.add_argument('--skip-build', action='store_true', help='Use existing APKs instead of rebuilding')
    parser.add_argument('--live', action='store_true', help='Use real OpenRouter recognition instead of a mock response')
    parser.add_argument('--llmd', choices=['release', 'alpha', 'debug'], help='Use an installed, authorized LLMD build instead of Koog')
    parser.add_argument('--key-env', default='OPENROUTER_API_KEY', help='Variable name, never the credential value')
    parser.add_argument('--model', help='Model ID, checked against the current catalog; paid models require --allow-paid-model')
    parser.add_argument('--allow-paid-model', action='store_true', help='Allow the explicitly selected live model to incur API charges')
    parser.add_argument('--currency', default='CNY', help='ISO currency of the test account')
    parser.add_argument('--proxy-port', type=int, help='Optional device-local HTTP CONNECT proxy port')
    options = parser.parse_args()
    if options.live and options.llmd:
        parser.error('--live and --llmd are mutually exclusive')
    if options.allow_paid_model and not options.live:
        parser.error('--allow-paid-model requires --live')
    if not options.image.is_file() or not options.expected_json.is_file():
        parser.error('--image and --expected-json must be existing files')
    expected = json.loads(options.expected_json.read_text(encoding='utf-8'))
    if not isinstance(expected.get('transactions'), list):
        parser.error('Expected JSON must contain a transactions array')
    required = {'timestamp', 'amount', 'note', 'transactionId', 'currency'}
    if any(not isinstance(row, dict) or set(row) != required for row in expected['transactions']):
        parser.error('Each expected row must contain timestamp, amount, note, transactionId and currency (ISO code or null)')
    if not options.skip_build:
        wrapper = 'gradlew.bat' if os.name == 'nt' else 'gradlew'
        run([str(PROJECT / wrapper), ':app:assembleDebug', ':app:assembleDebugAndroidTest', '--max-workers=2'], cwd=PROJECT)
    api_key = os.environ.get(options.key_env, '').strip() if options.live else ''
    if options.live and not api_key:
        raise RuntimeError(f'The environment variable {options.key_env} is not configured')
    model = runpy.run_path(str(PROJECT / 'scripts/test-openrouter.py'))['select_vision_model'](options.model, options.allow_paid_model) if options.live else 'test-vision'
    adb = ['adb', '-s', options.serial]
    run(adb + ['get-state'], capture_output=True)
    deadline = time.monotonic() + 60
    while run(adb + ['shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True).stdout.strip() != '1':
        if time.monotonic() >= deadline:
            raise RuntimeError('The emulator has not finished booting')
        time.sleep(1)
    for package, apk in [
        (APP, PROJECT / 'app/build/outputs/apk/debug/app-debug.apk'),
        (APP + '.test', PROJECT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),
    ]:
        installed = subprocess.run(adb + ['shell', 'pm', 'path', package], capture_output=True, text=True)
        paths = [line.removeprefix('package:') for line in installed.stdout.splitlines() if line.startswith('package:')]
        checksum = subprocess.run(adb + ['shell', 'sha256sum', paths[0]], capture_output=True, text=True).stdout.split() if paths else []
        if not checksum or checksum[0] != hashlib.sha256(apk.read_bytes()).hexdigest():
            run(adb + ['install', '-r', str(apk)])
    names = ['transaction-test-image', 'transaction-test-expected.json', 'transaction-test-key']
    try:
        for name, data in zip(names, [options.image.read_bytes(), options.expected_json.read_bytes(), api_key.encode()]):
            run(adb + ['shell', 'run-as', APP, 'sh', '-c', f"'mkdir -p no_backup && base64 -d > no_backup/{name} && chmod 600 no_backup/{name}'"],
                input=base64.b64encode(data), capture_output=True)
        checksum = run(adb + ['shell', 'run-as', APP, 'sha256sum', 'no_backup/transaction-test-image'], capture_output=True, text=True).stdout.split()[0]
        if checksum != hashlib.sha256(options.image.read_bytes()).hexdigest():
            raise RuntimeError('The screenshot transfer checksum does not match')
        result = run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                    'com.storytellerf.summer.TransactionScreenshotImportTest#externalScreenshotThroughEncoderPreviewAndDatabase',
                    '-e', 'transaction_live', str(options.live).lower(), '-e', 'transaction_model', model,
                    '-e', 'transaction_currency', options.currency,
                    *(['-e', 'transaction_llmd', options.llmd] if options.llmd else []),
                    *(['-e', 'recognition_proxy_port', str(options.proxy_port)] if options.proxy_port else []),
                    APP + '.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True)
        print(result.stdout)
        if 'OK (1 test)' not in result.stdout or 'FAILURES' in result.stdout:
            raise RuntimeError('Transaction screenshot test failed')
        print('LLMD screenshot recognition passed' if options.llmd else
              'Live screenshot recognition passed' if options.live else 'Mock API screenshot integration passed (model accuracy was not tested)')
    finally:
        subprocess.run(adb + ['shell', 'run-as', APP, 'rm', '-f', *['no_backup/' + name for name in names]], capture_output=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
