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

adb install -r CloudPhotos.apk 2>&1 | tee $OUT/install.txt
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

adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d | grep -E "AndroidRuntime|$PKG|FATAL|ANR" | tail -300 > $OUT/logcat.txt
if adb shell pidof $PKG > /dev/null; then echo "alive" > $OUT/result.txt; else echo "NOT RUNNING" > $OUT/result.txt; fi
if [ -s $OUT/crash.txt ]; then echo "CRASH" >> $OUT/result.txt; fi
cat $OUT/result.txt
exit 0
