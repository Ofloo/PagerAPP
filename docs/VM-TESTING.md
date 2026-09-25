# Testing PagerAPP on a VM (bhyve / Linux host)

This host has **no `/dev/kvm`**, so Android Studio AVDs, Cuttlefish and Genymotion do not run
here. Use one of the routes below to get a real device the agent can `adb` into.

## Route A — Android-x86 in bhyve (recommended)

A full Android OS in the VM, no KVM required. The release APK is multi-ABI
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), so it installs natively on an x86_64 image.

### Image

Use **`android-x86_64-9.0-r2.iso`** (965.7 MB, Android 9 / API 28 > our minSdk 26).
- Fall back to `android-x86_64-9.0-r2-k49.iso` only if the default kernel will not boot under
  bhyve (older 4.9 kernel, sometimes more forgiving on odd firmware).
- Do **not** use the `i686` images: 32-bit only, and native x86_64 code would not run.

### vm-bhyve setup

```
vm init pagerapp
vm install pagerapp /pad/android-x86_64-9.0-r2.iso
```

`/zroot/vm/pagerapp/pagerapp.conf`:

```
guest="linux"
loader="uefi"
cpu=4
memory="4G"
network0_type="virtio-net"
network0_switch="public"
disk0_type="ahci-hd"
disk0_name="disk0.img"
disk0_size="16G"
graphics="yes"
graphics_res="1280x800"
xhci_mouse="yes"
```

Install Android to the disk, reboot from disk, then set **Settings → Network** to get an IP.

### Make adb reachable

1. Settings → About → tap **Build number** 7× to unlock Developer options.
2. Developer options → enable **USB debugging** / **ADB over network**.
3. Find the VM IP (Settings → Network, or the VM console).
4. From the repo root:

   ```
   docker/adb.sh connect <vm-ip>:5555
   docker/adb.sh install -r dist/PagerAPP-release.apk
   docker/adb.sh shell am start -n io.ofloo.pagerapp/.ui.MainActivity
   docker/adb.sh logcat -d '*:E'
   ```

`docker/adb.sh` runs `adb` inside the Android SDK image with host networking; nothing is
installed on the Ubuntu host. If the VM network is NAT instead of bridged, connect from the host
running bhyve, or add a port forward for 5555.

### What works in a VM
- Polling, notifications, status rules, active hours, bypass/silence, ACK.
- No Google Play Services needed (notifications are local).

### What does not
- The QR scanner (no camera). Use **paste UUID** in setup instead.
- Android 9 does not prompt for `POST_NOTIFICATIONS` (that started at API 33); notifications
  post without a runtime permission there.

### Smoke test
1. `docker/adb.sh shell am start -n io.ofloo.pagerapp/.ui.MainActivity`
2. In the app: **Create a new mailbox** (or paste one).
3. From the host, page the app's UUID:
   `curl -X POST https://pager.ofloo.io/notify -H "Authorization: Bearer <uuid>" -H "Content-Type: application/json" -d '{"PROJECT":"test","JOB_ID":"smoke-1","status":2,"message":"hello"}'`
4. Within one poll interval a notification appears and the page shows in the Pages tab.
5. Regression check for the old crash: the app must survive being opened repeatedly, and no
   `FATAL EXCEPTION` in `logcat`.

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
