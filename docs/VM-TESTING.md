# Testing PagerAPP on a VM (bhyve / Linux host)

This host has **no `/dev/kvm`**, so Android Studio AVDs, Cuttlefish and Genymotion do not run
here. Use one of the routes below to get a real device the agent can `adb` into.

## Route A — Android-x86 / BlissOS in bhyve (recommended)

A full Android OS in the VM, no KVM required. The release APK is multi-ABI
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), so it installs on an x86_64 image.

1. Install a UEFI Android image (BlissOS 14/15 or Android-x86 9.0-r2) with `vm-bhyve`.
2. Give the VM bridged networking (`virtio-net` on a public switch).
3. Boot, enable **Developer Options → ADB** (network ADB), note the VM IP.
4. From the repo root:

   ```
   docker/adb.sh connect <vm-ip>:5555
   docker/adb.sh install -r dist/PagerAPP-release.apk
   docker/adb.sh logcat -d '*:E'
   ```

`docker/adb.sh` runs `adb` inside the Android SDK image with host networking; nothing is
installed on the Ubuntu host.

### What works in a VM
- Polling, notifications, status rules, active hours, bypass/silence, ACK.
- No Google Play Services needed (notifications are local).

### What does not
- The QR scanner (no camera). Use **paste UUID** in setup instead.

## Route B — redroid in a Linux bhyve guest (headless / scriptable)

Android in Docker **without KVM**, but it needs binder:

1. Linux guest with a kernel that has `CONFIG_ANDROID_BINDER_IPC` and the `binder_linux` module
   (redroid publishes modules for common kernels; otherwise rebuild the kernel).
2. `docker run -d --privileged redroid/redroid:13.0.0-latest`
3. `adb connect <guest-ip>:5555`

More setup, best for automated smoke tests.

## Route C — cloud (no VM at all)

Upload the APK to **Firebase Test Lab** or **Genymotion Cloud** and run on real devices.

## Notes
- `dist/` is git-ignored; copy the APK from
  `android/app/build/outputs/apk/release/app-release.apk` after `docker/build-android.sh assembleRelease`.
- The debug APK is debug-key signed; some OEM security layers (e.g. OnePlus/OxygenOS Auto Blocker)
  flag that more aggressively. Prefer the release APK for device testing.
