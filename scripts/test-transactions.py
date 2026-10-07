#!/usr/bin/env python3
"""Test a public transaction screenshot through encoding, preview and Room (mock API by default)."""

import argparse
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
    parser.add_argument('--key-env', default='OPENROUTER_API_KEY', help='Variable name, never the credential value')
    parser.add_argument('--model', help='Free vision model ID, checked against the current provider catalog')
    options = parser.parse_args()
    if not options.image.is_file() or not options.expected_json.is_file():
        parser.error('--image and --expected-json must be existing files')
    expected = json.loads(options.expected_json.read_text())
    if not isinstance(expected.get('transactions'), list):
        parser.error('Expected JSON must contain a transactions array')
    if not options.skip_build:
        run([str(PROJECT / 'gradlew'), ':app:assembleDebug', ':app:assembleDebugAndroidTest', '--max-workers=2'], cwd=PROJECT)
    api_key = os.environ.get(options.key_env, '').strip() if options.live else ''
    if options.live and not api_key:
        raise RuntimeError(f'The environment variable {options.key_env} is not configured')
    model = runpy.run_path(str(PROJECT / 'scripts/test-openrouter.py'))['select_free_vision_model'](options.model) if options.live else 'test-vision'
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
            run(adb + ['shell', 'run-as', APP, 'sh', '-c', f"'mkdir -p no_backup && cat > no_backup/{name} && chmod 600 no_backup/{name}'"],
                input=data, capture_output=True)
        result = run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                    'com.storytellerf.summer.TransactionScreenshotImportTest#externalScreenshotThroughEncoderPreviewAndDatabase',
                    '-e', 'transaction_live', str(options.live).lower(), '-e', 'transaction_model', model,
                    APP + '.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True)
        print(result.stdout)
        if 'OK (1 test)' not in result.stdout or 'FAILURES' in result.stdout:
            raise RuntimeError('Transaction screenshot test failed')
        print('Live screenshot recognition passed' if options.live else 'Mock API screenshot integration passed (model accuracy was not tested)')
    finally:
        subprocess.run(adb + ['shell', 'run-as', APP, 'rm', '-f', *['no_backup/' + name for name in names]], capture_output=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
