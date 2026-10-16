package in.advancedcamera.sdk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Bounded-memory output transform. Re-encoding deliberately strips EXIF GPS/device metadata. */
final class ImageFinalizer {
    static final class Output {
        final File file; final int width; final int height;
        Output(File f,int w,int h) { file=f; width=w; height=h; }
    }
    static Output finalizeJpeg(Context ctx, File input, CameraRequest request, int filter, float brightness, CameraRequest.AspectRatio aspect) throws IOException {
        if (input == null || !input.isFile() || input.length() == 0) throw new IOException("Missing captured JPEG");
        File captures = new File(ctx.getCacheDir(), "advanced-camera/captures").getCanonicalFile();
        if (!input.getCanonicalFile().getParentFile().equals(captures)) throw new IOException("Capture not in SDK private cache");
        File dstDir = new File(ctx.getCacheDir(), "advanced-camera/confirmed");
        if (!dstDir.exists() && !dstDir.mkdirs()) throw new IOException("Cannot create final directory");
        File output = File.createTempFile("final-", ".jpg", dstDir);
        Bitmap decoded = null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(input.getAbsolutePath(), probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) throw new IOException("Invalid JPEG image");
            int limitW = request.maxWidth > 0 ? request.maxWidth : 4096;
            int limitH = request.maxHeight > 0 ? request.maxHeight : 4096;
            int cap = Math.min(4096, Math.max(limitW, limitH));
            int sample = 1;
            while (probe.outWidth / sample > cap * 2 || probe.outHeight / sample > cap * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeFile(input.getAbsolutePath(), options);
            if (decoded == null) throw new IOException("Cannot decode JPEG");
            ExifInterface exif = new ExifInterface(input.getAbsolutePath());
            Matrix matrix = exifMatrix(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
            if (!matrix.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
                if (rotated != decoded) { decoded.recycle(); decoded = rotated; }
            }
            if (aspect != CameraRequest.AspectRatio.ORIGINAL) {
                // 16:9 follows the sensor/image orientation: 9:16 in portrait,
                // 16:9 in landscape. The actual JPEG is cropped, not just the UI.
                double ratio = aspect == CameraRequest.AspectRatio.SIXTEEN_NINE
                    ? (decoded.getWidth() < decoded.getHeight() ? 9.0/16.0 : 16.0/9.0)
                    : aspect == CameraRequest.AspectRatio.THREE_FOUR ? 0.75
                    : aspect == CameraRequest.AspectRatio.FOUR_THREE ? 4.0/3.0 : 1.0;
                int w = decoded.getWidth(), h = decoded.getHeight();
                int newW = w, newH = h;
                if ((double)w / h > ratio) newW = Math.max(1, (int)Math.round(h * ratio));
                else newH = Math.max(1, (int)Math.round(w / ratio));
                Bitmap cropped = Bitmap.createBitmap(decoded, (w-newW)/2, (h-newH)/2, newW, newH);
                if (cropped != decoded) { decoded.recycle(); decoded = cropped; }
            }
            double scale = Math.min(1.0, Math.min((double)limitW / decoded.getWidth(), (double)limitH / decoded.getHeight()));
            if (scale < 1) {
                Bitmap small = Bitmap.createScaledBitmap(decoded, Math.max(1,(int)(decoded.getWidth()*scale)), Math.max(1,(int)(decoded.getHeight()*scale)), true);
                if (small != decoded) { decoded.recycle(); decoded = small; }
            }
            if (filter != 0 || Math.abs(brightness) > 0.001f) {
                Bitmap adjusted = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                paint.setColorFilter(new ColorMatrixColorFilter(filterMatrix(filter, brightness)));
                new Canvas(adjusted).drawBitmap(decoded, 0, 0, paint);
                decoded.recycle(); decoded = adjusted;
            }
            final long maxSize = request.purpose == CameraRequest.Purpose.TENANT_PHOTO ? 2_000_000L : Long.MAX_VALUE;
            int quality = request.jpegQuality;
            for (int tries=0; tries<14; tries++) {
                try (FileOutputStream fos = new FileOutputStream(output, false)) {
                    if (!decoded.compress(Bitmap.CompressFormat.JPEG, quality, fos)) throw new IOException("JPEG compression failed");
                }
                if (output.length() <= maxSize) break;
                if (quality > 55) quality = Math.max(55, quality - 10);
                else {
                    Bitmap small = Bitmap.createScaledBitmap(decoded,
                        Math.max(1,(int)(decoded.getWidth()*0.85)), Math.max(1,(int)(decoded.getHeight()*0.85)), true);
                    decoded.recycle(); decoded = small;
                }
            }
            if (output.length() > maxSize) throw new IOException("Image exceeds 2MB tenant limit");
            return new Output(output, decoded.getWidth(), decoded.getHeight());
        } catch (Exception ex) {
            output.delete();
            if (ex instanceof IOException) throw (IOException)ex;
            throw new IOException("Image processing failed", ex);
        } finally {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            input.delete();
        }
    }
    /** Intentional original colour looks, not copied OEM LUTs. */
    static ColorMatrix filterMatrix(int filter, float brightness) {
        ColorMatrix m = new ColorMatrix();
        if (filter == 1) m.setSaturation(0f);
        else if (filter == 4) m.setSaturation(1.28f);
        else if (filter == 2 || filter == 3) {
            float r = filter == 2 ? 1.07f : 0.94f;
            float b = filter == 2 ? 0.92f : 1.08f;
            m.set(new float[]{r,0,0,0,4, 0,1,0,0,0, 0,0,b,0,0, 0,0,0,1,0});
        }
        brightness = Math.max(-1f, Math.min(1f, brightness));
        ColorMatrix adjust = new ColorMatrix(new float[]{1,0,0,0,brightness*68, 0,1,0,0,brightness*68, 0,0,1,0,brightness*68, 0,0,0,1,0});
        m.postConcat(adjust);
        return m;
    }
    static Matrix exifMatrix(int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1,1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1,-1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1,1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(-90); m.postScale(-1,1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            default: break;
        }
        return m;
    }
}
