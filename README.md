# Nectarlink Services

**Nectarlink Services** (`app.nectarlink.services`) is a privileged Android system service package for custom ROMs (similar in role to Microsoft's *Link to Windows Service*). It replaces NectarLink's legacy ADB / Wireless Debugging / Shizuku / Root (`Elevated`) helper by providing system-level capabilities directly through a bound AIDL Binder service installed in `/system_ext/priv-app` (or `/system/priv-app`).

## Why a Privileged System Service?

On stock Android, NectarLink achieves Elevated features by spawning `app_process` (`InputServer`) as `UID 2000` (`shell`) over Wireless Debugging, USB ADB, Shizuku, or `su`. On custom ROMs, bundling `NectarlinkServices` as a `priv-app`:

- Eliminates Wireless Debugging pairing, local ADB loopback scanning, Shizuku, and root requirements.
- Survives reboots, network switches, and mobile-data usage automatically.
- Exposes a clean, permission-gated AIDL Binder API (`INectarlinkService` + `IDisplaySession`) plus an optional `ParcelFileDescriptor` adapter compatible with NectarLink's `InputServer` line protocol.

## Repository Layout

| Path | Description |
|---|---|
| `client/` | `app.nectarlink.services.client` Android library containing the AIDL contracts (`INectarlinkService`, `IDisplaySession`, `INectarlinkServiceCallback`, `ServiceStatus`, `PointerScreenInfo`) and `NectarlinkServiceClient`. |
| `service/` | `app.nectarlink.services` privileged APK implementing input injection, screen mirroring, per-app virtual displays, device/call audio streaming, background clipboard sync, physical display power control, and system toggles. |
| `Android.bp` | AOSP / LineageOS Soong build definition (`NectarlinkServices` + `nectarlink-services-client` + `privapp-permissions`). |
| `aosp/privapp-permissions-app.nectarlink.services.xml` | Privileged permission allowlist for `/system_ext/etc/permissions/`. |
| `aosp/sepolicy/nectarlink_services.te` | Optional SELinux policy rules for `/dev/uhid` virtual gamepad/mouse and `SurfaceControl` panel power. |

## Feature Parity with Elevated

| Capability | Elevated (Legacy `UID 2000`) | Nectarlink Services (`priv-app`) |
|---|---|---|
| Full-screen mirror without prompt | `app_process` + `PROJECT_MEDIA` | `DisplayManager.createVirtualDisplay` (`CAPTURE_VIDEO_OUTPUT`) via `IDisplaySession` |
| Per-app virtual displays | `AppDisplay` + `VirtualDevice` | `AppDisplaySession` + `VirtualDeviceOwner` (`CREATE_VIRTUAL_DEVICE`, `ADD_TRUSTED_DISPLAY`) |
| Real touch, scroll, keys, shortcuts | `InputManager` as `shell` | `InputController` (`INJECT_EVENTS`) |
| Virtual gamepad | `/dev/uhid` + `InputManager` fallback | `UhidBridge` (if sepolicy permits) + `InputManager` fallback |
| Cross-edge mouse (`PointerFlow`) | `/dev/uhid` + `dumpsys input` | `UhidBridge` (if sepolicy permits) + tracked `INJECT_EVENTS` `SOURCE_MOUSE` fallback |
| Physical screen power off | `requestDisplayPower` / `SurfaceControl` / brightness 0 | `DisplayPowerController` (`DEVICE_POWER` / `CONTROL_DISPLAY_POWER` / brightness 0) |
| Internal device audio | `REMOTE_SUBMIX` (`CAPTURE_AUDIO_OUTPUT`) | `DeviceAudioStreamer` (`CAPTURE_AUDIO_OUTPUT`) over `ParcelFileDescriptor` pipe |
| PSTN call audio (Android 13+) | `CallAudio` (`CALL_AUDIO_INTERCEPTION`) | `CallAudioStreamer` (`CALL_AUDIO_INTERCEPTION`) over socket pair |
| Background clipboard sync & quiet copy | `IClipboard` as `com.android.shell` | `ClipboardController` (`READ_CLIPBOARD_IN_BACKGROUND`) |
| Wi-Fi, Bluetooth, Hotspot, Brightness | `svc` / `cmd` / `settings` CLI | `SystemControlManager` (`NETWORK_SETTINGS`, `BLUETOOTH_PRIVILEGED`, `TETHER_PRIVILEGED`, `WRITE_SETTINGS`) |
| Silent app uninstall | `pm uninstall` | `PackageInstaller.uninstall` (`DELETE_PACKAGES`, never NectarLink itself) |
| Camera/mic auto-start (`PcStartActivity`) | `am start -n ...` | `Context.startActivity` (`START_ACTIVITIES_FROM_BACKGROUND`) |

## Caller Authentication & Security

Because `app.nectarlink` is distributed and updated independently (e.g., via GitHub Releases) while `app.nectarlink.services` is signed with the ROM's platform key:

1. `NectarlinkServiceClient` verifies that `app.nectarlink.services` is installed on the system partition (`FLAG_SYSTEM` or `FLAG_UPDATED_SYSTEM_APP`).
2. `CallerVerifier` verifies `Binder.getCallingUid()` on every IPC call against:
   - Package allowlist (`allowed_client_packages` in `service/src/main/res/values/config.xml`: `app.nectarlink`, `app.nectarlink.dev`).
   - Single-signer SHA-256 certificate verification matching:
     - `trusted_client_cert_sha256` in `config.xml` (recommended for ROM maintainers shipping with official NectarLink release signing cert), **or**
     - `app.nectarlink.services`'s own signing certificate, **or**
     - An explicit user-approved `(packageName, sha256)` entry toggled in **Nectarlink Services** settings (for local debug/custom builds).
3. Users can disable the service globally or toggle individual capabilities (input, display, audio, clipboard, system controls) in **Settings › Connected devices › Nectarlink Services**.

## Custom ROM Integration (AOSP / LineageOS)

1. Place this repository in your ROM tree, e.g. `packages/apps/NectarlinkServices`.
2. Add the official NectarLink release signing certificate SHA-256 digest to `service/src/main/res/values/config.xml` (`trusted_client_cert_sha256`).
3. Add `NectarlinkServices` to `PRODUCT_PACKAGES` in your device/product makefile:
   ```makefile
   PRODUCT_PACKAGES += \
       NectarlinkServices
   ```
4. *(Optional)* Add rules from `aosp/sepolicy/nectarlink_services.te` to enable `/dev/uhid` hardware gamepad/mouse creation (without these, input injection and pointer flow still work via `INJECT_EVENTS` fallbacks).
5. Open **Nectarlink Services** on the device to verify `[OK]` next to all privileged permissions.

## Integrating `:client` into `nectarlink/android`

Add `:client` as a dependency in `nectarlink/android` and check `NectarlinkServiceClient.isAvailable(context)` at startup:

- When `isAvailable(context)` is `true`, connect via `NectarlinkServiceClient.connect()` and route `Elevated` operations (`handle`, `openScreen`, `openApp`, `openAudioStream`, `openCallAudio`, `setClipboard`, `setScreenOff`, `pointerStart`, `runCommand`, `PcAutoStart`) through `NectarlinkServiceClient` (or `openInputServerChannel()`).
- When `isAvailable(context)` is `false` (stock ROMs), keep using the existing `Elevated` (Wireless Debugging / USB ADB / Shizuku / Root) flow.
