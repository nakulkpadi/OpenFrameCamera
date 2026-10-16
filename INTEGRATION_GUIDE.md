# Android host integration (Sundar Wadi Rent)

### Host compatibility

The supplied camera library uses Groovy Gradle, Java 17, AGP 8.7.3, compile SDK 35, minimum SDK 26. It integrates with Java-based Android `ComponentActivity`. **Do not replace the host's existing WebView page, local encryption, storage, or Supabase sync.** The standalone and host APK both consume the same `:advanced-camera` library code.

## 1 — Add module without replacing the host

Copy folder `advanced-camera/` alongside the existing `android/app` folder, preserving any existing code. In `SundarWadiRent/android/settings.gradle` add:

```groovy
include ':advanced-camera'
```

In `SundarWadiRent/android/app/build.gradle` dependencies:

```groovy
implementation project(':advanced-camera')
```

Ensure the top-level plugin configuration can resolve AGP 8.7.3 and JDK 17. The library manifest merges non-exported `AdvancedCameraActivity`, `CAMERA`, `RECORD_AUDIO`, and a provider whose authority is `${applicationId}.advancedcamera.fileprovider` — e.g. `in.sundarwadi.rent.advancedcamera.fileprovider`. Check any host provider has a different authority. **Do not create a duplicate FileProvider.**

## 2 — MainActivity registration (Java)

Register callbacks early in `MainActivity.onCreate()` before the Activity becomes STARTED. The host must be a `ComponentActivity` or compatible subclass.

```java
private AdvancedCamera.Registration embeddedCamera;
private ValueCallback<Uri[]> pendingFileChooser;
private ActivityResultLauncher<Intent> documents;

@Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    embeddedCamera = AdvancedCamera.register(this, result -> {
        ValueCallback<Uri[]> cb = pendingFileChooser;
        pendingFileChooser = null;
        if (cb != null) cb.onReceiveValue(result.status == CameraResult.Status.SUCCESS
                ? new Uri[]{result.uri} : null);
        // WebView now owns a temporary URI reference; its existing form handles storage.
        // Copy image to encrypted storage BEFORE calling releaseTemporaryResult.
    });
    documents = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            ValueCallback<Uri[]> cb = pendingFileChooser;
            pendingFileChooser = null;
            if (cb != null) cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(
                    result.getResultCode(), result.getData()));
        });
    // Retain the EXISTING WebView initialization and set WebChromeClient there.
}
```

Use `integration-example/src/main/java/in/advancedcamera/example/MainActivity.java` for the complete `WebChromeClient.onShowFileChooser` implementation. Route only an image input with the HTML `capture` attribute to `CameraRequest.tenantPhoto()`; leave PDF/payment attachments with `params.createIntent()` / SAF. **Do not use a global intercept of all image inputs.** An example HTML input:

```html
<input type="file" accept="image/*" capture="user" id="resident-photo-1">
```

`input.click()` triggers the existing file chooser callback. Android returns the `content://` URI through the chooser; the same resident's input receives the file and other fields remain unchanged. Correct resident association belongs to the form (not the camera).

## 3 — General use, photo preset, and success/cancel

```java
embeddedCamera.launch(CameraRequest.tenantPhoto());
// or AdvancedCamera.launch(embeddedCamera, CameraRequest.builder(
//   CameraRequest.Purpose.GENERAL).allowEditing(true).build());
```

A `CameraResult` contains `.status`, `.uri`, `.mimeType`, `.width`, `.height`, `.errorCode`. On CANCELLED or FAILURE, pass `null` to the pending WebView chooser and leave the form intact. If your form saves edits across page reload, preserve that existing flow; **do not reload the page on capture**.

## 4 — Private URI lifecycle

- On success, the camera returns a private file via the SDK FileProvider; the WebView can read it because it belongs to the same host app.
- Host encrypts/imports through its existing storage logic and optionally syncs to Supabase. The SDK performs **no** network upload.
- Once the host has copied/ingested the file and no input is using it, call `AdvancedCamera.releaseTemporaryResult(context, uri)`.
- Cancellation never returns a media URI. Raw captures are cleaned on retake and normal Activity teardown. A periodic `CameraCacheCleaner.deleteAbandoned(context)` removes stale orphaned files.
- Never call `releaseTemporaryResult` before the WebView or offline-storage pipeline has finished reading the URI.

## 5 — Testing checklist

Check single and multiple residents, retake/replace, back/cancel, camera permission denial, photo EXIF rotation, image size below 2 MB, photo input restoration, video mode on standalone, document chooser unchanged, activity rotation while reviewing, device with no Bokeh/HDR/Night extension, low storage, low RAM, and unplugged network. Verify gallery is not automatically populated and no Supabase service key is packaged.

## Feature support boundaries

Present implementation only guarantees **request API shape at source level**. Native build and integration tests have not run here. JPEG is the only supported photo export format; PNG/HEIC request enums are reserved and return an explicit finalization error. Non-OEM computational HDR and complex color science are future work.
