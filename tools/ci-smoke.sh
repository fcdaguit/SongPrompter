#!/usr/bin/env bash
# Launches the app on the CI emulator, taps through the main screens and prints any crash.
set +e
PKG=com.chordflow.prompter
mkdir -p shots
n=0
look() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1; python3 tools/tap.py ui.xml "$1"; }
shot() { n=$((n+1)); adb exec-out screencap -p > "shots/$(printf %02d $n)-$1.png"; }
alive() { echo "PID after $1: [$(adb shell pidof $PKG | tr -d '\r')]"; }
crash() { echo "===== CRASH LOG ($1) ====="; adb logcat -d -s AndroidRuntime:E | tail -60; adb logcat -c; }
start() { adb shell am force-stop $PKG; adb logcat -c; adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 8; }

python3 tools/catalog-probe.py
adb install -r app/build/outputs/apk/debug/app-debug.apk
start; look -; shot launch; alive launch; crash launch

look "new blank song"; sleep 6; look -; shot editor-new; alive new-song; crash new-song
adb shell input swipe 500 1500 500 300; sleep 2; look -; shot editor-preview
look "inline"; sleep 2; shot editor-inline; look "lyrics only"; sleep 2; shot editor-lyrics-only; alive editor-toggles; crash editor-toggles

start; look "download free songs"; sleep 15; look -; shot downloader; alive downloader; crash downloader
adb shell wm size; look "Cmpilato Public Domain"; sleep 8; look -; shot downloader-preview
look "save to offline library"; sleep 5; look -; shot downloader-saved; alive download-save; crash download-save
adb shell input keyevent 4; sleep 3; look -; shot library-after-download

start; look "amazing grace"; sleep 5; look -; shot prompter-lyrics; alive prompter
look "show chords"; sleep 3; shot prompter-above; look "⚙"; sleep 2; look "inline"; sleep 2; shot prompter-inline-settings; look "⚙"; sleep 2; shot prompter-inline
look "next"; sleep 2; look "next"; sleep 2; shot prompter-next; alive prompter-modes; crash prompter-modes
exit 0
