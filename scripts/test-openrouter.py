#!/usr/bin/env python3
"""Run the opt-in Koog vision test without putting credentials in APKs, argv, or logs."""

import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import urllib.request

PROJECT = Path(__file__).resolve().parents[1]
APP = "com.storytellerf.summer.debug"


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def select_free_vision_model(requested):
    with urllib.request.urlopen("https://openrouter.ai/api/v1/models", timeout=30) as response:
        models = json.load(response)["data"]
    eligible = {model["id"]: model for model in models
                if "image" in model.get("architecture", {}).get("input_modalities", [])
                and model["pricing"].get("prompt") == "0" and model["pricing"].get("completion") == "0"
                and model["id"].endswith(":free")}
    if requested:
        if requested not in eligible:
            raise RuntimeError("The requested model is not currently listed as free with image input.")
        return requested
    for preferred in ("qwen/qwen3.8-27b:free", "google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free"):
        if preferred in eligible:
            return preferred
    raise RuntimeError("No preferred free vision model is currently available. Select another with --model.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    parser.add_argument("--key-env", default="OPENROUTER_API_KEY", help="Environment variable name; never the key value")
    parser.add_argument("--model", help="Explicit free model ID, verified against the live OpenRouter catalog")
    parser.add_argument("--image", type=Path, help="Local screenshot to recognize instead of the generated fixture")
    parser.add_argument("--expected-balance", type=float, help="Manually verified balance; required with --image")
    options = parser.parse_args()
    if options.image and (not options.image.is_file() or options.expected_balance is None):
        parser.error("--image requires an existing file and --expected-balance")
    if options.expected_balance is not None and options.image is None:
        parser.error("--expected-balance requires --image")
    if options.expected_balance is not None and not math.isfinite(options.expected_balance):
        parser.error("--expected-balance must be finite")
    api_key = os.environ.get(options.key_env, "").strip()
    if not api_key:
        raise RuntimeError(f"The environment variable {options.key_env} is not available to this process.")
    model = select_free_vision_model(options.model)
    print(f"Testing Koog with free vision model: {model}", flush=True)
    run([str(PROJECT / "gradlew"), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--max-workers=2"], cwd=PROJECT)
    adb = ["adb", "-s", options.serial]
    run(adb + ["get-state"], capture_output=True)
    run(adb + ["install", "-r", str(PROJECT / "app/build/outputs/apk/debug/app-debug.apk")])
    run(adb + ["install", "-r", str(PROJECT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")])
    try:
        fixture = {"apiKey": api_key, "model": model}
        if options.image:
            run(adb + ["shell", "run-as", APP, "sh", "-c",
                       "'mkdir -p no_backup && cat > no_backup/koog-live-image'"],
                input=options.image.read_bytes(), capture_output=True)
            fixture.update({"image": "koog-live-image", "expectedBalance": options.expected_balance})
        run(adb + ["shell", "run-as", APP, "sh", "-c",
                   "'mkdir -p no_backup && cat > no_backup/koog-live-test.json && chmod 600 no_backup/koog-live-test.json'"],
            input=json.dumps(fixture).encode(), capture_output=True)
        result = run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                            "com.storytellerf.summer.KoogOpenRouterLiveTest#screenshotBalance_isRecognizedByKoogAndSavedFromTheForm",
                            APP + ".test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True)
        print(result.stdout)
        if "OK (1 test)" not in result.stdout or "FAILURES" in result.stdout:
            raise RuntimeError("OpenRouter live recognition failed. See the sanitized test result above.")
    finally:
        subprocess.run(adb + ["shell", "run-as", APP, "rm", "-f", "no_backup/koog-live-test.json", "no_backup/koog-live-image"], capture_output=True)

if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        # Commands never contain the API key. Do not print stdout/stderr from credential provisioning.
        raise SystemExit(str(error)) from None
