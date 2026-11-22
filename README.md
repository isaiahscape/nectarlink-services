# Nectarlink Services

**Nectarlink Services** (`sn.nectarlink.services`) is a privileged Android system service package for custom ROMs (similar in role to Microsoft's *Link to Windows Service*). It replaces NectarLink's legacy ADB / Wireless Debugging / Shizuku / Root (`Elevated`) helper by providing system-level capabilities directly through a bound AIDL Binder service installed in `/system_ext/priv-app` (or `/system/priv-app`).

## Why a Privileged System Service?

On stock Android, NectarLink achieves Elevated features by spawning `app_process` (`InputServer`) as `UID 2000` (`shell`) over Wireless Debugging, USB ADB, Shizuku, or `su`. On custom ROMs, bundling `NectarlinkServices` as a `priv-app`:

- Eliminates Wireless Debugging pairing, local ADB loopback scanning, Shizuku, and root requirements.
- Survives reboots, network switches, and mobile-data usage automatically.
- Exposes a clean, permission-gated AIDL Binder API (`INectarlinkService` + `IDisplaySession`) plus an optional `ParcelFileDescriptor` adapter compatible with NectarLink's `InputServer` line protocol.

## Repository Layout

| Path | Description |
|---|---|
| `client/` | `app.nectarlink.services.client` Android library containing the AIDL contracts (`INectarlinkService`, `IDisplaySession`, `INectarlinkServiceCallback`, `ServiceStatus`, `PointerScreenInfo`) and `NectarlinkServiceClient`. |
| `service/` | `sn.nectarlink.services` privileged APK implementing input injection, screen mirroring, per-app virtual displays, device/call audio streaming, background clipboard sync, physical display power control, and system toggles. |
| `Android.bp` | AOSP / LineageOS Soong build definition (`NectarlinkServices` + `nectarlink-services-client` + `system_ext` / `product` permissions & sysconfig). |
| `aosp/privapp-permissions-sn.nectarlink.services.xml` | Privileged permission allowlist for `/system_ext/etc/permissions/`. |
| `aosp/default-permissions-sn.nectarlink.services.xml` | Default permission grants for `sn.nectarlink.services`. |
| `aosp/sysconfig-sn.nectarlink.services.xml` | Doze/data-saver & hidden-API allowlist for `sn.nectarlink.services`. |
| `aosp/default-permissions-app.nectarlink.xml` | Optional default permission grants for `app.nectarlink` under `/product/etc/default-permissions/`. |
| `aosp/sysconfig-app.nectarlink.xml` | Optional Doze/data-saver allowlist for `app.nectarlink` under `/product/etc/sysconfig/`. |
| `aosp/fetch-client-apk.sh` | Helper to download & verify `thtbee/nectarlink` `stable`, `nightly`, or `canary` APKs into `aosp/prebuilt/` as a `Nectarlink` Soong `android_app_import` module. |
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

Because `app.nectarlink` is distributed and updated independently (e.g., via GitHub Releases) while `sn.nectarlink.services` is signed with the ROM's platform key:

1. `NectarlinkServiceClient` verifies that `sn.nectarlink.services` is installed on the system partition (`FLAG_SYSTEM` or `FLAG_UPDATED_SYSTEM_APP`).
2. `CallerVerifier` verifies `Binder.getCallingUid()` on every IPC call against:
   - Package allowlist (`allowed_client_packages` in `service/src/main/res/values/config.xml`: `app.nectarlink`, `app.nectarlink.dev`).
   - Single-signer SHA-256 certificate verification matching:
     - `trusted_client_cert_sha256` in `config.xml` (recommended for ROM maintainers shipping with official NectarLink release signing cert), **or**
     - `sn.nectarlink.services`'s own signing certificate, **or**
     - An explicit user-approved `(packageName, sha256)` entry toggled in **Nectarlink Services** settings (for local debug/custom builds).
3. Users can disable the service globally or toggle individual capabilities (input, display, audio, clipboard, system controls) in **Settings › Connected devices › Nectarlink Services**.

## Custom ROM Integration (AOSP / LineageOS)

1. Place this repository in your ROM tree, e.g. `packages/apps/NectarlinkServices`.
   - `config.xml` (`trusted_client_cert_sha256`) already trusts official `thtbee/nectarlink` builds (`c7c09a125ca5320b1670e710e798486de08b318127243b2790f3ff13511924f1`).
   - Upstream `thtbee/nectarlink` (`TRUSTED_SERVICE_CERT_SHA256`) pins `e055b7e6a622168da36e7b9f730369c8f140e5ba87df1e16f31fd754434eea0d` and `c7c09a125ca5320b1670e710e798486de08b318127243b2790f3ff13511924f1`. If your ROM builds `NectarlinkServices` from source with `certificate: "platform"`, official unmodified `thtbee/nectarlink` APKs will reject that platform key unless `NectarlinkServices` is signed with one of the pinned certs (or uses a matching custom client build).
2. **Option A1 (Recommended when bundling official `thtbee/nectarlink` APKs):** Fetch the presigned `thtbee/nectarlink` (`stable`, `nightly`, or `canary`) APK together with the release-signed `NectarlinkServices.apk` (`e055b7e6...`) into `aosp/prebuilt/`:
   ```sh
   ./aosp/fetch-client-apk.sh --channel stable --with-service
   # or for nightly / canary:
   # ./aosp/fetch-client-apk.sh --channel nightly --with-service
   # or provide a local release-signed service APK:
   # ./aosp/fetch-client-apk.sh --channel stable --service-apk /path/to/NectarlinkServices.apk
   ```
   Then add the prebuilt targets to `PRODUCT_PACKAGES`:
   ```makefile
   PRODUCT_PACKAGES += \
       NectarlinkServicesPrebuilt \
       Nectarlink
   ```
3. **Option A2 (Source build signed with release key, or custom client builds):** Fetch just the client APK if desired (`./aosp/fetch-client-apk.sh --channel stable`), set `certificate:` in `Android.bp` to your local release `.x509.pem`/`.pk8` keypair (converted from `nectarlink-release.jks`), or keep `certificate: "platform"` when pairing with a custom `nectarlink` build that trusts your platform cert:
   ```makefile
   PRODUCT_PACKAGES += \
       NectarlinkServices \
       Nectarlink
   ```
4. *(Optional)* Add rules from `aosp/sepolicy/nectarlink_services.te` to enable `/dev/uhid` hardware gamepad/mouse creation (without these, input injection and pointer flow still work via `INJECT_EVENTS` fallbacks).
5. Open **Nectarlink Services** on the device to verify `[OK]` next to all privileged permissions.

## Integrating `:client` into `nectarlink/android`

Add `:client` as a dependency in `nectarlink/android` and check `NectarlinkServiceClient.isAvailable(context)` at startup:

- When `isAvailable(context)` is `true`, connect via `NectarlinkServiceClient.connect()` and route `Elevated` operations (`handle`, `openScreen`, `openApp`, `openAudioStream`, `openCallAudio`, `setClipboard`, `setScreenOff`, `pointerStart`, `runCommand`, `PcAutoStart`) through `NectarlinkServiceClient` (or `openInputServerChannel()`).
- When `isAvailable(context)` is `false` (stock ROMs), keep using the existing `Elevated` (Wireless Debugging / USB ADB / Shizuku / Root) flow.
