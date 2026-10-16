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
        if (!".jpg".equals(suffix) && !".mp4".equals(suffix)) throw new IOException("Invalid media suffix");
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
                if (request.format != CameraRequest.Format.JPEG) throw new IOException("UNSUPPORTED_OUTPUT_FORMAT");
                ImageFinalizer.Output output = ImageFinalizer.finalizeJpeg(activity, captured, request, filter, brightness, aspect);
                result = CameraResult.success(toUri(output.file), "image/jpeg", output.width, output.height);
            } catch (Exception ex) {
                android.util.Log.e("OpenFrame", "JPEG finalization failed", ex);
                result = CameraResult.failure("IMAGE_FINALIZATION_FAILED");
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
