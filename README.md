# OpenFrame Camera — Android camera app + embeddable SDK

**Status: `0.2.4-beta` — GitHub CI builds run; 60fps is now selectable as a guarded live test. Actual MP4 FPS must still be measured.** OpenFrame is an **original**, independent Android camera implementation inspired by useful interaction patterns seen in premium smartphone cameras. No OEM proprietary code, binaries, shaders, art, or models are included. The original reference APK is deliberately excluded from this repository.

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
| 720p, 1080p, 4K recording | Conditional | CameraX device capabilities determine quality availability |
| 4K/60 and 8K/24 recording | Conditional | CameraX 1.5 uses a session-verified 60fps combination where supported. A separate Camera2/MediaRecorder HEVC path can attempt real 4K60 or 8K24 only if the public camera stream, frame rate and encoder all advertise that exact mode. The OnePlus stock app may access private OEM capabilities not visible to OpenFrame |
| Pinch zoom + slider | Yes | CameraX zoom limits depend on device |
| Tap-to-focus with animated focus reticle; auto-exposure compensation | Yes | Actual focus result and EV range depend on lens |
| 0 / 3 / 10 second timer, thirds grid | Yes | Timer does not persist through activity stop |
| Flash Auto / On / Off (photo) | Yes | Flash availability depends on device |
| Photo review, retake, confirm | Yes | Cancel does not return media |
| Review filters: Natural / Mono / Warm / Cool / Vivid | Yes | Output processing, not live preview LUTs |
| Brightness adjustment | Yes | Output processing; no face reshaping |
| Crops: 3:4, 4:3, square, original | Yes | Center crop at confirmation; preview remains uncropped |
| OEM Night / Portrait | Conditional | CameraX Extensions only when provider exposes them; not OnePlus algorithms |
| HDR stills | Conditional | Uses OEM HDR extension where exposed; otherwise Ultra HDR JPEG_R when supported. JPEG_R is returned unmodified to preserve gain map; editing/crop is disabled in that mode |
| Tenant 3:4 JPEG preset, up to 2 MB, GPS-free | Yes | Image recompressed, orientation normalized |
| `content://` URI result, cancel/error statuses | Yes | Host ingests into its own encrypted storage |
| JPEG / RAW DNG | Conditional | RAW uses CameraX 1.5 RAW+JPEG on supported lenses, returns original DNG, and uses a temporary JPEG only for review; unavailable modes show an explanatory message |
| Manual ISO/shutter, advanced EIS, multi-frame fusion, face beauty/segmentation, AI editing | **No** | Planned; do not represent these as supported |
| Compiled APK / AAR | **Yes** | GitHub CI produces both; physical-device capability and image-quality testing is still required |

## Build

Requirements: **Android Studio**, JDK 17, Android SDK Platform 35, Android Gradle Plugin 8.7.3, Gradle 8.9, internet access to resolve Maven artifacts.

1. Open this repo as an Android Studio project; allow sync to finish.
2. Run Gradle tasks `:advanced-camera:assembleRelease`, `:camera-app:assembleDebug`, and `:integration-example:assembleDebug`.
3. Install the camera-app debug APK on an Android 8+ phone and test the listed flows.
4. Build and run `:integration-example` to verify WebView file input capture + gallery input.

No wrapper binary is included in this early source package; Android Studio can use an installed Gradle distribution. Android Studio can use a locally installed Gradle 8.9 distribution; alternatively install Gradle and generate a wrapper using `gradle wrapper --gradle-version 8.9`. CI installs Gradle automatically.

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

**Build honesty:** GitHub Actions compiles the standalone APK, SDK AAR and Java example. A successful build is not proof of device-dependent RAW, HDR or 60 fps recording support. Test on physical devices before claiming feature equivalence.


## Premium OpenFrame UI (design update)

The standalone APK and embeddable library share one original camera user interface
inspired by premium mobile camera workflows (not a reproduction of an OEM skin).
This release introduces a dedicated preview stage with black letterboxing, compact
top controls, orange-highlighted mode tabs, a horizontal mode strip, zoom presets,
a bottom shutter deck, animated options, and a camera tools sheet.

**Working controls:** Photo and Video; device-supported Portrait, Night, HDR
extensions; flash and torch when available; real 3:4, 4:3, and 1:1 post-capture
crop framing; shutter timers, zoom and exposure slider, front/back camera switch,
review filters, brightness, recapture and private handoff.

**Not implemented yet:** manual Pro ISO/shutter, ultra-wide physical camera routing,
panorama, long-exposure stacking, action stabilization, XPAN and dual video.
RAW DNG is implemented for supported CameraX sensors. The optional 8K/24 and
4K/60 recording modes use the public Camera2/HEVC recorder and must be verified
on the device. They appear as clearly identified future features only, not as selectable
or falsely working camera modes. Do not confuse zoom presets with physical lenses.

The camera launcher uses a resized version of the user-provided OpenFrame
neon-blue lens icon. The original high-resolution source remains with the designer
and is not copied from OnePlus. Build this repository's `main` branch through
GitHub Actions to get the current standalone APK and reusable AAR.


## Direct launch and beta testing (0.2.0)

The standalone app opens the camera Activity immediately, with no developer demonstration landing page. A separate capture-result screen appears only after confirmation so users can export media. Embedded hosts still use `AdvancedCamera.register(...)` and do not open any standalone screen.

Use the upper **JPG/RAW** chip in Photo mode to request Adobe DNG capture on lenses exposing RAW+JPEG via CameraX. DNG keeps original sensor data and may contain camera metadata: inspect it before sharing. Sundar Wadi Rent's tenant preset remains JPEG-only. Ultra HDR JPEG_R is saved without recompression; the 3:4/1:1 crop and filters are intentionally unavailable in HDR-pass-through mode. Photo/Video/Portrait switching and 720p/1080p/4K video quality depend on camera capabilities; 30/60 fps is a target, not a verified output frame rate.

**Debug install:** GitHub-hosted CI debug APKs may be signed by different temporary keys per workflow run. If Android rejects an update due to a signature mismatch, back up any files first and uninstall the previous debug version. For distributable releases, use a persistent protected signing key and CI secrets; never commit the release signing key.

## High-resolution video (0.2.3 beta)

The OnePlus 12 stock camera advertises **4K/60** and **8K/24**, but these are not guarantees about the capabilities exposed to third-party apps. OpenFrame checks the public selected-camera capabilities:

- CameraX 1.5 session-specific frame-rate ranges for the actual Preview + VideoCapture combination and uses `SessionConfig.Builder.setFrameRateRange()` for supported 60fps sessions (rather than the old best-effort target-rate hint).
- A separate Camera2/HEVC recording backend for 4K/60 or 8K/24 is activated only if the selected camera exposes the correct MediaRecorder output size, AE frame-rate range, and HEVC encoder capability.
- If the public APIs do not expose the combination, its control is disabled. An OEM camera may use hidden profiles, private IDs or privileged pipelines that open-source third-party apps cannot safely assume are available.
- The video resolution and frame rate must be verified using a **real recorded MP4**. A successful APK build does not prove that either 60fps or 8K works on a particular phone.

The native high-resolution recorder stores output in OpenFrame's private cache, has no cloud upload, and closes its camera/session on failure, cancellation and activity shutdown. This is an experimental on-device beta feature, not an assertion of parity with OnePlus stock recording.

## Video device diagnostics and 60fps test (0.2.4-beta)

Open **Video > Video resolution/frame rate > 4K > 60fps**. The control no longer remains greyed out solely because an Android capability probe returned false. It attempts a CameraX 60fps session; if that session rejects the requested mode, OpenFrame falls back to **4K/30** without falsely displaying 60. When a public Camera2 + HEVC 4K/60 candidate is present, that backend can be used instead. A selectable frame rate is *not* proof that the encoder really produced that frame rate.

Use **ⓘ Camera capabilities • Tap to copy report** at the bottom of the video settings popup. It displays and copies the public camera's session-specific 60fps range result, recorder stream sizes, Camera2 AE frame ranges, and Camera2 candidate detection without collecting personal information. Paste this report into the bug report if 4K/60 remains unavailable. When you capture a clip, the video review displays its actual file resolution and approximate FPS measured from video sample timestamps.

The user's latest camera-switch icon attachment was a uniformly black RGB image. OpenFrame therefore uses a crisp, resolution-independent, clearly visible white circular flip glyph as a fallback. Replacing it with the user's exact icon requires a PNG with visible artwork and transparency.
