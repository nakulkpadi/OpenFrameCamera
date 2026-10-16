# OpenFrame Camera — Validation report (2026-10-16)

## Actual executed checks

| Check | Result | Scope |
|---|---|---|
| Java parser via `javax.tools.JavaCompiler` | **PASS** | Parsed 13 module Java sources with no Java syntax errors; does **not** resolve Android symbols or type-check dependencies |
| Standalone Java serialization smoke test | **PASS** | Compiled CameraRequest/CameraResult with minimal Android stubs; checked tenant preset, roundtrip request/result, cancelled URI, rejection of file://, JPEG quality bounds |
| XML parser | **PASS** | All 4 XML files, including manifests and FileProvider paths |
| Module structure | **PASS** | advanced-camera library, camera-app and integration-example each have Gradle module |
| Private provider configuration | **PASS static** | Non-exported provider with `${applicationId}`-scoped authority; confirmed cache paths only |
| Java Android full type-check / Gradle build | **NOT RUN** | No Android SDK/platform or Gradle installed in authoring environment |
| APK/AAR produced | **NO** | Must build in Android Studio / Gradle or GitHub Actions |
| Runtime/UI preview/photo/video on physical device | **NOT RUN** | Device unavailable |
| Sundar Wadi actual integration | **NOT RUN** | Host Android source not attached |
| Supabase/database changed | **NO** | No network/database mutation and no credentials used |
| Proprietary Oplus resources/code in deliverable | **NONE FOUND** | No OplusCamera.apk, `.so`, DEX or OEM assets bundled in OpenFrame project |

## Known risks requiring first Android build/device test

1. CameraX API/dependency compatibility still needs Gradle compilation; JDK syntax checks are **not** proof of a working Android app.
2. Fullscreen UI overlay spacing must be tested on devices with display cutouts, gesture navigation and different aspect ratios.
3. OEM Extensions availability, switching and behavior are device-specific.
4. Photo and video file finalization requires real hardware tests; slow devices, capture failure and activity recreation need dedicated instrumentation tests.
5. Image preview reuses CameraX-produced EXIF orientation but app-specific gallery output codecs / lens characteristics vary.
6. Video audio permission is requested upon entering Video mode, and denied permission yields silent recording.
7. Unconfirmed capture cancellation with process termination is cleaned by startup TTL; confirmed files are deleted only after host ingestion or older-cache prune.
8. Camera mode uses CameraX presets and extensions rather than OnePlus image science; picture quality is **not** claimed equal to OEM.
9. Advanced manual camera controls, RAW, 4K/FPS and advanced denoise/fusion have not yet been implemented.

## Reproduce offline checks

From project root:

```bash
bash tests/contracts/run.sh
```

For a real Android build, install JDK 17, Android SDK 35 and Gradle 8.9, then run:

```bash
gradle :advanced-camera:assembleRelease :camera-app:assembleDebug :integration-example:assembleDebug --stacktrace
```

GitHub Actions workflow `.github/workflows/android.yml` performs those builds on every push / pull request after the source is published. CI has **not** run as of this report.
