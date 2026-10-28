# Cloud Photos

An unofficial Android app for your **iCloud Photos** library — a native app modelled on the
iPhone Photos app, instead of the icloud.com website.

> Not made by or affiliated with Apple. The app talks only to Apple's own iCloud servers,
> using the same sign-in and data APIs as icloud.com.

## Install

Download **CloudPhotos.apk** from the [latest release](../../releases/latest) on your Android
device (Android 11 or newer) and open it. New builds install straight over the old one.

## What it does

- **Sign in with your Apple Account** — Apple's SRP sign-in (your password itself is never sent),
  two-factor codes from your devices or by text, and Advanced Data Protection approval.
  The session is kept encrypted with the Android keystore, so you stay signed in.
- **Library** — every photo and video, oldest at the top and opening on your newest at the bottom,
  pinch to change grid size, date header, fast-scroll handle, tap the tab again to jump to newest.
- **Albums** — Recents, Favourites, your own albums, Media Types (Videos, Live Photos, Panoramas,
  Slo-mo, Time-lapse, Screenshots, Bursts), Hidden and Recently Deleted (locked behind your
  fingerprint / PIN, like Face ID on iPhone). Personal / Shared Library switch.
- **Viewer** — swipe between items, pinch / double-tap zoom on full-resolution originals,
  swipe down to close, tap for full screen, video playback with controls, Live Photo playback
  (tap LIVE or long-press), info sheet.
- **Actions** — favourite, delete (to Recently Deleted), recover, hide / unhide, share,
  save to the device gallery (Pictures/Movies › Cloud Photos), upload from the device, multi-select.
- **Optional app lock.**

## Building

GitHub Actions builds and publishes the APK on every push to `main`
(`.github/workflows/build.yml`). Locally: JDK 17 + Android SDK, then `gradle :app:assembleRelease`.

Sign-in and Photos protocol details follow the open-source
[icloudpd](https://github.com/icloud-photos-downloader/icloud_photos_downloader) and
[pyicloud](https://github.com/timlaing/pyicloud) projects; the SRP code is unit-tested
against the same reference implementation they use.
