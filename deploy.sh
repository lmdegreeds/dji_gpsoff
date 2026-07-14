#!/usr/bin/env bash
# Deploy QuickToggle.apk (com.djiquick) to the DJI RC 2: MTP push to internal storage,
# then pm install + grant the overlay appop over telnet (com.dpad.fuli system shell).
# adb is unusable on the RC.
#
#   ./deploy.sh            # push existing build/QuickToggle.apk + install
#   ./deploy.sh --build    # build first (build.ps1), then push + install
#
# Must run under Bash (Git Bash): PowerShell 5.1 mangles the Cyrillic internal-storage
# name; Bash + MSYS_NO_PATHCONV=1 passes it intact.
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJ/build/QuickToggle.apk"
RC2="c:/Users/lm/projects/dji/drone-plan/apps/server/native/rc2.exe"
DEV="DJI RC 2"
INTERNAL="Внутренний общий накопитель"      # RC internal storage == /sdcard in the shell

if [ "$1" = "--build" ]; then
  echo "[build] build.ps1"
  powershell -ExecutionPolicy Bypass -File "$PROJ/build.ps1"
fi

[ -f "$APK" ] || { echo "no APK at $APK — run with --build first"; exit 1; }

echo "[1/3] MTP push -> internal /sdcard/QuickToggle.apk"
MSYS_NO_PATHCONV=1 "$RC2" pushfile "$DEV" "$(cygpath -w "$APK")" "QuickToggle.apk" "$INTERNAL"

echo "[2/3] pm install over telnet"
python "$PROJ/tools/rc2sh.py" "pm install -r -g /sdcard/QuickToggle.apk"
# appops-грант overlay НЕ обязателен на этом пульте: дефолтный режим appop = allow —
# проверено на чистом пакете, оверлей и тумблеры работают без него. Оставлен как
# belt-and-suspenders (стоковые устройства) и намеренно НЕ фатален, чтобы не ронять деплой.
python "$PROJ/tools/rc2sh.py" "appops set com.djiquick SYSTEM_ALERT_WINDOW allow" || true

echo "[3/3] verify"
python "$PROJ/tools/rc2sh.py" "dumpsys package com.djiquick | grep versionName"
echo "DONE. Launch on the RC, or: python tools/rc2sh.py \"am start -n com.djiquick/.MainActivity\""
