package in.advancedcamera.sdk;

import android.content.Context;
import android.net.Uri;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import java.io.File;
import java.util.Objects;

/** Register in host onCreate BEFORE STARTED, then launch on demand. */
public final class AdvancedCamera {
    private AdvancedCamera() { }
    @FunctionalInterface public interface Callback { void onResult(CameraResult result); }
    public static final class Registration {
        private final ActivityResultLauncher<CameraRequest> launcher;
        private Registration(ActivityResultLauncher<CameraRequest> launcher) { this.launcher=launcher; }
        public void launch(CameraRequest request) { launcher.launch(Objects.requireNonNull(request)); }
    }
    public static Registration register(ComponentActivity activity, Callback callback) {
        Objects.requireNonNull(activity);
        Objects.requireNonNull(callback);
        return new Registration(activity.registerForActivityResult(
            new AdvancedCameraContract(), callback::onResult));
    }
    public static void launch(Registration registration, CameraRequest request) {
        registration.launch(request);
    }
    /** Call AFTER the host has ingested the photo into its own encrypted store. */
    public static boolean releaseTemporaryResult(Context context, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) ||
            !(context.getPackageName()+".advancedcamera.fileprovider").equals(uri.getAuthority())) return false;
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("final-[A-Za-z0-9_-]+\\.(jpg|mp4)")) return false;
        File dir = new File(context.getCacheDir(), "advanced-camera/confirmed");
        File target = new File(dir, name);
        try {
            if (!target.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) return false;
            return target.delete();
        } catch (java.io.IOException ex) { return false; }
    }
}
