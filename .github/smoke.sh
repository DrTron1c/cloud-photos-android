#!/bin/bash
# Installs the APK on an emulator, opens it and records screenshots + any crash.
set -u
PKG=io.github.drtron1c.cloudphotos
OUT=smoke
mkdir -p $OUT

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

# Tap the centre of the first on-screen element whose text contains $1.
tap_text() {
  adb exec-out uiautomator dump /dev/tty 2>/dev/null > $OUT/ui.xml
  python3 - "$1" <<'PY'
import re, sys, subprocess
xml = open("smoke/ui.xml", encoding="utf-8", errors="ignore").read()
needle = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    d = re.search(r' content-desc="([^"]*)"', n)
    if (t and needle in t.group(1)) or (d and needle in d.group(1)):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        x = (int(b.group(1)) + int(b.group(3))) // 2
        y = (int(b.group(2)) + int(b.group(4))) // 2
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
        print("tapped", needle, x, y)
        break
else:
    print("not found:", needle)
PY
}

# The emulator is x86_64; the release APK is arm64-only, so the identical releaseTest build is used.
APPT=app/build/outputs/apk/releaseTest/app-releaseTest.apk
# Key rotation: old key → rotated key installs; the retired key is then refused.
if [ -f rotation/1-old.apk ]; then
  {
    last() { grep -v '^[[:space:]]*$' | tail -1; }
    echo "1 old:      $(adb install rotation/1-old.apk 2>&1 | last)"
    echo "2 rotated:  $(adb install -r rotation/2-rotated.apk 2>&1 | last)"
    echo "3 old key:  $(adb install -r rotation/3-old-again.apk 2>&1 | last)"
    echo "signer now: $(adb shell dumpsys package io.github.drtron1c.cloudphotos.launcher | grep -iE 'signatures|pastSigs' | head -3 | tr -s ' ' | tr '\n' ' ')"
  } > $OUT/rotation.txt
  adb uninstall io.github.drtron1c.cloudphotos.launcher > /dev/null 2>&1
  cat $OUT/rotation.txt
fi

adb install -r "$APPT" 2>&1 | tee $OUT/install.txt
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 20
shot 1-login
# Scroll to the bottom of the sign-in screen, then open the network privacy options.
adb shell input swipe 500 1600 500 400 300; sleep 2
tap_text "Network privacy"; sleep 3; shot 2-dns-dialog
tap_text "Quad9"; sleep 3; shot 3-after-dns
adb shell cmd uimode night yes; sleep 6; shot 4-login-dark
adb shell cmd uimode night no; sleep 3
# Rotate to landscape and back (configuration change survival).
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 5; shot 5-landscape
adb shell settings put system user_rotation 0; sleep 3
# Background and return.
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 6; shot 6-reopened

# The map pictures load without any API key (status, type, size).
curl -s -o /tmp/tile -w "esri %{http_code} %{content_type} %{size_download}\n" "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/6/24/36" > $OUT/map-tiles.txt 2>&1 || true
cp /tmp/tile $OUT/map-tile-sample 2>/dev/null || true

# Long-press app shortcuts are published; the Quick Settings tile opens the app; keyboard keys don't crash.
adb shell dumpsys shortcut 2>/dev/null | grep -A40 "Package: $PKG" | grep -oE "id=(search|favourites|memories|upload)" | sort -u > $OUT/shortcuts.txt
adb shell cmd statusbar add-tile $PKG/.vpn.VpnTileService > $OUT/tile.txt 2>&1
adb shell cmd statusbar click-tile $PKG/.vpn.VpnTileService >> $OUT/tile.txt 2>&1; sleep 4
adb shell cmd statusbar collapse > /dev/null 2>&1; sleep 2
adb shell dumpsys activity activities | grep -E "topResumedActivity" | head -2 >> $OUT/tile.txt
adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_SLASH > /dev/null 2>&1; sleep 1
adb shell input keyevent KEYCODE_TAB KEYCODE_DPAD_RIGHT KEYCODE_ESCAPE > /dev/null 2>&1; sleep 2
shot 6b-after-tile-and-keys
# Tablet-size screen: the sign-in screen at iPad-like size.
adb shell wm size 1600x2560; adb shell wm density 320; sleep 5; shot 6c-tablet
adb shell wm size reset; adb shell wm density reset; sleep 4

# Companion app made from the template (name "AAA My Photos", teal picture): install, open, and
# check it shows in the app drawer and opens Cloud Photos.
COMP=app/build/launcher-test/CustomLauncher.apk
if [ -f "$COMP" ]; then
  adb install -r "$COMP" 2>&1 | tee $OUT/companion-install.txt
  adb shell dumpsys package io.github.drtron1c.cloudphotos.launcher | grep -iE "versionCode|signatures|launcher" | head -5 > $OUT/companion-package.txt
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell monkey -p io.github.drtron1c.cloudphotos.launcher -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 6
  adb shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity" | head -3 > $OUT/companion-opened.txt
  shot 7-opened-from-companion
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell input swipe 540 2000 540 600 300; sleep 3
  shot 8-app-drawer
  adb shell input keyevent KEYCODE_HOME
fi

# Same thing made on the device itself (Android Keystore signing), via the on-device test.
APPT=app/build/outputs/apk/releaseTest/app-releaseTest.apk
TEST=app/build/outputs/apk/androidTest/releaseTest/app-releaseTest-androidTest.apk
if [ -f "$TEST" ]; then
  adb install -r "$APPT" 2>&1 | tee $OUT/test-app-install.txt
  adb install -r "$TEST" 2>&1 | tee $OUT/test-install.txt
  adb logcat -d -b crash > $OUT/crash-before-device-test.txt
  # The on-device test installs its own companion (signed with the device key) in its place.
  adb uninstall io.github.drtron1c.cloudphotos.launcher > /dev/null 2>&1
  adb shell appops set io.github.drtron1c.cloudphotos ACTIVATE_VPN allow
  adb logcat -c
  timeout 1100 adb shell am instrument -r -w \
    io.github.drtron1c.cloudphotos.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee $OUT/device-test.txt
  echo "exit=$?" >> $OUT/device-test.txt
  adb logcat -d | grep -iE "CompanionTest|TestRunner|AndroidRuntime|apksig|keystore|keymint|keymaster|cloudphotos|Instrumentation|System.err|WireGuard|GoBackend|onnx|Transformer|heif" | tail -400 > $OUT/device-test-logcat.txt
  for f in glass-light.png glass-dark.png video-fit-portrait-in-landscape.png video-fit-landscape-in-portrait.png video-fit-rotated-phone-on-tablet.png video-fit-landscape-on-tablet.png video-fit-large.png video-fit-downscale-landscape.png video-fit-downscale-rotated.png viewer-fit-rotated-phone-landscape.png viewer-fit-rotated-phone-bigger.png viewer-fit-landscape-portrait.png viewer-fit-landscape-bigger.png viewer-fit-gif-portrait.png gif-frame.png; do adb pull /sdcard/Download/$f $OUT/$f 2>&1 | tail -1; done
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell input swipe 540 2000 540 600 300; sleep 3
  shot 9-app-drawer-device-made
  adb shell input keyevent KEYCODE_HOME
fi

adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d | grep -E "AndroidRuntime|$PKG|FATAL|ANR" | tail -300 > $OUT/logcat.txt
if adb shell pidof $PKG > /dev/null; then echo "alive" > $OUT/result.txt; else echo "NOT RUNNING" > $OUT/result.txt; fi
if [ -s $OUT/crash.txt ]; then echo "CRASH" >> $OUT/result.txt; fi
cat $OUT/result.txt
exit 0
