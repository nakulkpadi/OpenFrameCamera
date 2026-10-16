package in.advancedcamera.sdk;

import android.content.Context;
import java.io.File;

/** Call periodically from host WorkManager or at startup; never from an active capture. */
public final class CameraCacheCleaner {
    private CameraCacheCleaner() { }
    public static void deleteAbandoned(Context ctx) {
        long now = System.currentTimeMillis();
        removeOld(new File(ctx.getCacheDir(), "advanced-camera/captures"), now - 24L*60*60*1000);
        // Give WebView/encrypted ingestion sufficient time before pruning finished captures.
        removeOld(new File(ctx.getCacheDir(), "advanced-camera/confirmed"), now - 48L*60*60*1000);
    }
    private static void removeOld(File dir, long cutoff) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) file.delete();
        }
    }
}
