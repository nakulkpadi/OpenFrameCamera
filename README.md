# OpenFrame Camera — Android camera app + embeddable SDK

**Status: source release candidate `0.1.0`, NOT device-tested.** OpenFrame is an **original**, independent Android camera implementation inspired by useful interaction patterns seen in premium smartphone cameras. No OEM proprietary code, binaries, shaders, art, or models are included. The original reference APK is deliberately excluded from this repository.

## What you get

- `:advanced-camera` — Java 17 native Android library. Implements its own fullscreen `CameraXEngine`; it is **not** an external camera `Intent` wrapper. Hosts need no second app.
- `:camera-app` — standalone OpenFrame Camera app using **the same library**.
- `:integration-example` — Java WebView host with a working `<input type=file capture=user>` callback and a separate document/gallery chooser.
- `INTEGRATION_GUIDE.md` — including integration requirements for `in.sundarwadi.rent`.
- `docs/REFERENCE_GAP_ANALYSIS.md` — OnePlus-inspired feature research and objective readiness labels.
- `.github/workflows/android.yml` — Gradle/Android CI for public forks.

## Functional implementation (subject to on-device verification)

| Feature | Code present | Constraints |
|---|---|---|
| Fullscreen live preview, back/front switch | Yes | Requires runtime Camera permission + CameraX device support |
| JPEG photo capture | Yes | Private cache; reviewed before returning |
| HD video recording | Yes | Microphone audio only if RECORD_AUDIO already granted; otherwise silent |
| Pinch zoom + slider | Yes | CameraX zoom limits depend on device |
| Tap-to-focus, auto-exposure compensation slider | Yes | Device-dependent |
| 0 / 3 / 10 second timer, thirds grid | Yes | Timer does not persist through activity stop |
| Flash Auto / On / Off (photo) | Yes | Flash availability depends on device |
| Photo review, retake, confirm | Yes | Cancel does not return media |
| Review filters: Natural / Mono / Warm / Cool / Vivid | Yes | Output processing, not live preview LUTs |
| Brightness adjustment | Yes | Output processing; no face reshaping |
| Crops: 3:4, 4:3, square, original | Yes | Center crop at confirmation; preview remains uncropped |
| OEM Night / HDR / Bokeh | Conditional | CameraX Extensions only when provider exposes them; **not** OnePlus algorithms |
| Tenant 3:4 JPEG preset, up to 2 MB, GPS-free | Yes | Image recompressed, orientation normalized |
| `content://` URI result, cancel/error statuses | Yes | Host ingests into its own encrypted storage |
| 4K, manual ISO/shutter, RAW/DNG, advanced EIS, multi-frame HDR, face beauty/segmentation, AI editing | **No** | Planned; do not represent these as supported |
| Working APK / AAR or device compatibility confirmed | **No** | Requires Gradle build and physical-device testing |

## Build

Requirements: **Android Studio**, JDK 17, Android SDK Platform 35, Android Gradle Plugin 8.7.3, Gradle 8.9, internet access to resolve Maven artifacts.

1. Open this repo as an Android Studio project; allow sync to finish.
2. Run Gradle tasks `:advanced-camera:assembleRelease`, `:camera-app:assembleDebug`, and `:integration-example:assembleDebug`.
3. Install the camera-app debug APK on an Android 8+ phone and test the listed flows.
4. Build and run `:integration-example` to verify WebView file input capture + gallery input.

No wrapper binary is included because this environment lacks a verified Gradle wrapper JAR. Android Studio can use a locally installed Gradle 8.9 distribution; alternatively install Gradle and generate a wrapper using `gradle wrapper --gradle-version 8.9`. CI installs Gradle automatically.

## Java API

```java
private AdvancedCamera.Registration camera;
@Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    camera = AdvancedCamera.register(this, result -> {
        if (result.status == CameraResult.Status.SUCCESS) {
            Uri captured = result.uri; // Secure content:// URI in app-private cache
            // Copy bytes into your own storage, then releaseTemporaryResult(...).
        }
    });
}
void takePortrait() { camera.launch(CameraRequest.tenantPhoto()); }
```

## Privacy & permissions

- Source contains no upload SDK, no Supabase secret, no ad or analytics library, and no network call.
- `CAMERA` requested only when camera opens. `RECORD_AUDIO` is requested only when the user enters Video mode; if denied, recording continues without audio.
- Output stays in internal app cache and is delivered via a non-exported FileProvider authority `${applicationId}.advancedcamera.fileprovider`.
- Abandoned raw files are cleaned immediately on retake and on non-configuration activity teardown; older cache can be pruned with `CameraCacheCleaner`. Confirmed captures remain until host cleanup or TTL expiry.
- No photo is published to system gallery unless the standalone user explicitly exports through Android's document picker.

## License and contribution

MIT license for **new OpenFrame source**, not the Oplus reference APK, its binaries, or its assets. Read `LICENSE`, `CONTRIBUTING.md`, and `SECURITY.md`. Before publishing, review all code and choose a repository owner. CameraX/AndroidX remain subject to their own licenses.

**Build honesty:** This archive was validated via static checks and Java contract tests where available; an Android build / device run was not performed on the authoring machine because no Android SDK/Gradle installation was found.
