# Release Status — 2026-10-16

- **Version:** 0.1.0 source candidate; first independent codebase.
- **No original existing Advanced Camera app source was supplied**; previous source uploads were decompiled classes from the Oplus system APK. New OpenFrame implementation built independently rather than copying the OEM app.
- **No APK or AAR compiled:** Android SDK, installed Gradle, and emulator/physical phone are unavailable in this workspace.
- **Static checks:** see `VALIDATION_REPORT.md`.
- **Behavior implemented but NOT physically tested:** CameraX preview, JPEG photo, HD video, tap-to-focus, zoom, EV, flash, timer, third-grid, image review/retake, brightness and 5 post-capture filters, OEM extensions when available, private URI result.
- **Not in 0.1:** live LUT filters, manual ISO/shutter focus, camera ultrawide switching, 4K high-FPS, RAW, DNG, algorithmic night HDR, ML portrait/beauty, HEIC/PNG exports.
- **Host not modified:** Sundar Wadi Rent source was not provided. `integration-example` includes a Java WebView demo of the file chooser handoff. No Supabase credentials or database changes.
- **Publishing:** no public GitHub repository has yet been created; review code/security/IP/licensing and choose owner before publication.
