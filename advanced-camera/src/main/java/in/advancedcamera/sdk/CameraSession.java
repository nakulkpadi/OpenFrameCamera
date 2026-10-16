package in.advancedcamera.sdk;

import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.ComponentActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private, host-owned media handoff. Does not upload or publish to the public gallery. */
public final class CameraSession {
    private final AdvancedCameraActivity activity;
    private final CameraRequest request;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    CameraSession(AdvancedCameraActivity activity, CameraRequest request) {
        this.activity = activity; this.request = request;
    }
    public ComponentActivity activity() { return activity; }
    public CameraRequest request() { return request; }
    public File createPrivateCaptureFile() throws IOException { return createPrivateCaptureFile(".jpg"); }
    public File createPrivateCaptureFile(String suffix) throws IOException {
        if (!".jpg".equals(suffix) && !".mp4".equals(suffix) && !".dng".equals(suffix)) throw new IOException("Invalid media suffix");
        File dir = new File(activity.getCacheDir(), "advanced-camera/captures");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create capture directory");
        return File.createTempFile("capture-", suffix, dir);
    }
    public void confirmJpeg(File captured) { confirmJpeg(captured, 0, 0f); }
    /** filter 0=natural,1=mono,2=warm,3=cool,4=vivid; brightness -1..1. */
    public void confirmJpeg(File captured, int filter, float brightness) {
        confirmJpeg(captured, filter, brightness, request.aspectRatio);
    }
    public void confirmJpeg(File captured, int filter, float brightness, CameraRequest.AspectRatio aspect) {
        if (!finished.compareAndSet(false, true)) return;
        worker.execute(() -> {
            CameraResult result;
            try {
                if (request.format == CameraRequest.Format.HEIC || request.format == CameraRequest.Format.PNG)
                    throw new IOException("UNSUPPORTED_OUTPUT_FORMAT");
                ImageFinalizer.Output output = ImageFinalizer.finalizeJpeg(activity, captured, request, filter, brightness, aspect);
                result = CameraResult.success(toUri(output.file), "image/jpeg", output.width, output.height);
            } catch (Exception ex) {
                android.util.Log.e("OpenFrame", "JPEG finalization failed", ex);
                result = CameraResult.failure("IMAGE_FINALIZATION_FAILED");
            }
            deliver(result);
        });
    }
    /** Preserve an actual sensor DNG. No JPEG post-processing is applied to RAW data. */
    public void confirmRaw(File capturedDng, File previewJpeg) {
        if (!finished.compareAndSet(false, true)) return;
        worker.execute(() -> {
            CameraResult result;
            try {
                if (request.purpose == CameraRequest.Purpose.TENANT_PHOTO)
                    throw new IOException("RAW is disabled for identity photographs");
                File captures = new File(activity.getCacheDir(), "advanced-camera/captures").getCanonicalFile();
                if (capturedDng == null || !capturedDng.isFile()
                        || !capturedDng.getCanonicalFile().getParentFile().equals(captures)
                        || capturedDng.length() == 0) throw new IOException("RAW capture missing");
                int width = 0, height = 0;
                if (previewJpeg != null && previewJpeg.isFile()) {
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(previewJpeg.getAbsolutePath(), opts);
                    width = Math.max(0, opts.outWidth); height = Math.max(0, opts.outHeight);
                }
                File dest = new File(activity.getCacheDir(), "advanced-camera/confirmed");
                if (!dest.exists() && !dest.mkdirs()) throw new IOException("Cannot create confirmed dir");
                File finalFile = new File(dest, "final-" + UUID.randomUUID() + ".dng");
                Files.move(capturedDng.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (previewJpeg != null) previewJpeg.delete();
                result = CameraResult.success(toUri(finalFile), "image/x-adobe-dng", width, height);
            } catch (Exception ex) {
                android.util.Log.e("OpenFrame", "RAW finalization failed", ex);
                result = CameraResult.failure("RAW_FINALIZATION_FAILED");
            }
            deliver(result);
        });
    }
    /** Transfer Ultra HDR JPEG_R without JPEG re-encoding, which would discard its gain map. */
    public void confirmUltraHdr(File capturedJpeg) {
        if (!finished.compareAndSet(false, true)) return;
        worker.execute(() -> {
            CameraResult result;
            try {
                File captures = new File(activity.getCacheDir(), "advanced-camera/captures").getCanonicalFile();
                if (capturedJpeg == null || !capturedJpeg.isFile()
                    || !capturedJpeg.getCanonicalFile().getParentFile().equals(captures)
                    || capturedJpeg.length() == 0) throw new IOException("HDR capture missing");
                File dest = new File(activity.getCacheDir(), "advanced-camera/confirmed");
                if (!dest.exists() && !dest.mkdirs()) throw new IOException("Cannot create confirmed dir");
                File finalFile = new File(dest, "final-" + UUID.randomUUID() + ".jpg");
                Files.move(capturedJpeg.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(finalFile.getAbsolutePath(), o);
                result = CameraResult.success(toUri(finalFile), "image/jpeg",
                    Math.max(0, o.outWidth), Math.max(0, o.outHeight));
            } catch (Exception ex) {
                android.util.Log.e("OpenFrame", "Ultra HDR transfer failed", ex);
                result = CameraResult.failure("HDR_FINALIZATION_FAILED");
            }
            deliver(result);
        });
    }
    public void confirmVideo(File captured) {
        if (!finished.compareAndSet(false, true)) return;
        worker.execute(() -> {
            CameraResult result;
            try {
                File captures = new File(activity.getCacheDir(), "advanced-camera/captures").getCanonicalFile();
                if (!captured.getCanonicalFile().getParentFile().equals(captures) || captured.length() == 0)
                    throw new IOException("Invalid capture file");
                File confirmed = new File(activity.getCacheDir(), "advanced-camera/confirmed");
                if (!confirmed.exists() && !confirmed.mkdirs()) throw new IOException("Cannot create confirmed directory");
                File output = new File(confirmed, "final-" + UUID.randomUUID() + ".mp4");
                Files.move(captured.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                int width = 0, height = 0;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(output.getAbsolutePath());
                    String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (w != null) width = Integer.parseInt(w);
                    if (h != null) height = Integer.parseInt(h);
                } catch (RuntimeException ignored) { /* Dimensions are optional metadata. */ }
                finally { retriever.release(); }
                result = CameraResult.success(toUri(output), "video/mp4", width, height);
            } catch (Exception ex) {
                android.util.Log.e("OpenFrame", "Video finalization failed", ex);
                result = CameraResult.failure("VIDEO_FINALIZATION_FAILED");
            }
            deliver(result);
        });
    }
    private Uri toUri(File f) {
        return FileProvider.getUriForFile(activity, activity.getPackageName()+".advancedcamera.fileprovider", f);
    }
    private void deliver(CameraResult result) {
        main.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) activity.finishResult(result); });
        worker.shutdown();
    }
    public void cancel() { finish(CameraResult.cancelled()); }
    public void fail(String code) { finish(CameraResult.failure(code)); }
    private void finish(CameraResult result) {
        if (!finished.compareAndSet(false, true)) return;
        deliver(result);
    }
}
