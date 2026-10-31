#!/usr/bin/env bash
set -uo pipefail
adb shell settings put secure show_ime_with_hard_keyboard 1
ledger_test_status=0
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console=plain || ledger_test_status=$?
mkdir -p app/build/outputs/ui-screenshots
adb pull /sdcard/Android/data/cn.shopledger.offline/files/. app/build/outputs/ui-screenshots/ || true
exit "$ledger_test_status"
