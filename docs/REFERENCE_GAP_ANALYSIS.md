# Reference research: OplusCamera.apk → independent OpenFrame feature map

The user supplied `OplusCamera.apk` (OnePlus 12 system application) for feature research. **Only the name/role of features, UI workflow inspiration and high-level architecture were used.** No OEM source, decompiled classes, binaries, assets, shaders, ML models or trademarks are incorporated into this project's distributable modules. See `OnePlus12_Camera_APK_Analysis_and_Universal_SDK_Plan.md` from the prior conversation for fuller private research notes (not redistributed here).

| Observed/reference category | OpenFrame independent strategy | Release 0.1 |
|---|---|---|
| Photo/Video mode chooser | Original bottom mode strip, CameraX capture cases | Implemented in source |
| Portrait, HDR, Night | AndroidX Camera Extensions capability checks and standard selectors | Device conditional, not tested |
| Ultrawide, main, telephoto angles | CameraX zoom state; multi-camera ID strategy later | Zoom slider only |
| Pro controls | Camera2 interop API and capability profiles | EV only |
| Timer, grid, exposure, flash | New Java UI and public CameraControl APIs | Implemented in source |
| Live realtime filters | Original GLES 3 color pipeline + adjustable LUT designs | Not implemented; post-capture filters only |
| Beauty/makeup/skin adjustment | Consent-based segmentation; face shape must not be changed in tenant mode | Not implemented |
| AI super-resolution/noise/HDR | Original, testable multi-frame pipeline, or public OEM Extensions | Proprietary algorithms NOT copied |
| Raw DNG/HEIC formats | Camera2 hardware capability checks | Not implemented |
| Image editing / enhancement | Center crop, saturation/temperature, brightness, EXIF-normalized JPEG | Implemented in source |
| Video recording | CameraX Recorder HD default | Implemented, device test pending |
| Stabilization / 4K/120fps | Per-hardware advertised capabilities | Not implemented |
| Privacy-oriented ID photos | Private cache URI, no location EXIF, bounded JPEG, review | Implemented in source |

## Planned acceptance criteria for next versions

- 0.1: Android build in CI, app launches on OnePlus 12, photo and video functional, photo output usable in WebView file chooser.
- 0.2: original preview LUT shaders, ISO/shutter/focus controls on Camera2-supported devices, portrait crop assistance, gestures.
- 0.3: hardware capability matrix across OnePlus, Samsung, Pixel and low-end device; RAW/DNG and high-FPS conditional options.
- 1.0: stable published Maven AAR and documentation, tests, localized UI, privacy and accessibility review.

The goal is to **learn from the experience of a premium OEM camera, not clone the OEM APK**. UI and source remain independently authored.
