#!/usr/bin/env bash
# Upgrades from the previous build, then taps through playlists and the second screen.
set +e
PKG=com.chordflow.prompter
look() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1; python3 tools/tap.py ui.xml "$1"; }
alive() { echo "PID after $1: [$(adb shell pidof $PKG | tr -d '\r')]"; }
crash() { echo "===== CRASH LOG ($1) ====="; adb logcat -d -s AndroidRuntime:E | grep -v "^-----" | head -40; adb logcat -c; }
start() { adb shell am force-stop $PKG; adb logcat -c; adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 8; }
step() { echo; echo "##### $1"; }

step "OLD BUILD: make a setlist with a song in it"
adb install -r old.apk
start; look -
look "+ setlist"; sleep 2; look "setlist name"; sleep 2; adb shell input text Sunday; sleep 2; look "create"; sleep 3; look -
look "library"; sleep 2; look "⋮"; sleep 2; look -; look "sunday"; sleep 3; look -; alive old; crash old

step "UPGRADE: install new build over it"
adb install -r app/build/outputs/apk/debug/app-debug.apk
start; look -; alive upgrade; crash upgrade
look "sunday"; sleep 2; look -
look "library"; sleep 2; look -

step "ADD TO PLAYLIST from song menu, creating a new playlist"
look "⋮"; sleep 2; look -; look "add to playlist"; sleep 2; look -
look "new playlist name"; sleep 2; adb shell input text Evening; sleep 2; look -
look "create & add"; sleep 3; look -; look "done"; sleep 2; look -; alive add-menu; crash add-menu

step "ADD TO PLAYLIST from teleprompter"
look "holy, holy"; sleep 5; look -; look "add to playlist"; sleep 2; look -
look "[ ] evening"; sleep 3; look -; look "done"; sleep 2; look -
look "show chords"; sleep 2; look -
adb shell input keyevent 4; sleep 3; alive add-prompter; crash add-prompter

step "PLAYLIST VIEW and reorder"
look "evening"; sleep 2; look -
look "⋮"; sleep 2; look "move down"; sleep 2; look "close"; sleep 2; look -; alive reorder; crash reorder

step "SECOND SCREEN"
adb shell settings put global overlay_display_devices 1280x720/213; sleep 6
adb shell dumpsys display | grep -c "mDisplayId=" 
look "holy, holy, holy"; sleep 6; look -
echo "DISPLAYS: $(adb shell dumpsys display | grep -c 'DisplayDeviceInfo{')"
echo "SECOND SCREEN WINDOWS: $(adb shell dumpsys window windows | grep -c ChordFlowSecondScreen)"
look "next"; sleep 2; look "next"; sleep 2; echo "SECOND SCREEN WINDOWS AFTER NEXT: $(adb shell dumpsys window windows | grep -c ChordFlowSecondScreen)"; alive second-screen; crash second-screen
adb shell settings put global overlay_display_devices null; sleep 5; look -; echo "SECOND SCREEN WINDOWS AFTER UNPLUG: $(adb shell dumpsys window windows | grep -c ChordFlowSecondScreen)"; alive display-removed; crash display-removed
adb shell input keyevent 4; sleep 2; start; look -; alive relaunch; crash relaunch
exit 0
