#!/usr/bin/env bash
set -uo pipefail
adb shell settings put secure show_ime_with_hard_keyboard 1
ledger_test_status=0
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console=plain || ledger_test_status=$?
mkdir -p app/build/outputs/ui-screenshots
adb pull /sdcard/Android/data/cn.shopledger.offline/files/. app/build/outputs/ui-screenshots/ || true
if [ "$ledger_test_status" -eq 0 ] && [ -f releases/xiaodian-ledger-native-v1.3.0.apk ]; then
  python3 ci/upgrade-check.py || ledger_test_status=$?
fi
exit "$ledger_test_status"
