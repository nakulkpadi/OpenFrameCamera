package in.advancedcamera.sdk;

import android.content.Intent;
import android.net.Uri;

public final class CameraResult {
    public enum Status { SUCCESS, CANCELLED, FAILURE }
    static final String PREFIX = "in.advancedcamera.sdk.result.";
    public final Status status;
    public final Uri uri;
    public final String mimeType;
    public final int width;
    public final int height;
    public final String errorCode;

    private CameraResult(Status status, Uri uri, String mime, int w, int h, String error) {
        this.status = status; this.uri = uri; this.mimeType = mime;
        this.width = w; this.height = h; this.errorCode = error;
    }
    public static CameraResult success(Uri uri, String mime, int width, int height) {
        if (uri == null || !"content".equals(uri.getScheme()))
            throw new IllegalArgumentException("Only content:// URIs may be returned");
        return new CameraResult(Status.SUCCESS, uri, mime, width, height, null);
    }
    public static CameraResult cancelled() {
        return new CameraResult(Status.CANCELLED, null, null, 0, 0, null);
    }
    public static CameraResult failure(String code) {
        return new CameraResult(Status.FAILURE, null, null, 0, 0, code);
    }
    void writeTo(Intent intent) {
        intent.putExtra(PREFIX+"status", status.name());
        if (uri != null) intent.putExtra(PREFIX+"uri", uri.toString());
        if (mimeType != null) intent.putExtra(PREFIX+"mime", mimeType);
        intent.putExtra(PREFIX+"width", width);
        intent.putExtra(PREFIX+"height", height);
        if (errorCode != null) intent.putExtra(PREFIX+"error", errorCode);
    }
    static CameraResult readFrom(Intent data, int resultCode) {
        if (data == null) return resultCode == android.app.Activity.RESULT_CANCELED
                ? cancelled() : failure("MISSING_RESULT");
        String raw = data.getStringExtra(PREFIX+"status");
        if ("CANCELLED".equals(raw)) return cancelled();
        if ("FAILURE".equals(raw)) return failure(data.getStringExtra(PREFIX+"error"));
        if (!"SUCCESS".equals(raw) || resultCode != android.app.Activity.RESULT_OK)
            return failure("INVALID_RESULT");
        String uriString = data.getStringExtra(PREFIX+"uri");
        if (uriString == null) return failure("MISSING_URI");
        try {
            return success(Uri.parse(uriString), data.getStringExtra(PREFIX+"mime"),
                data.getIntExtra(PREFIX+"width", 0), data.getIntExtra(PREFIX+"height", 0));
        } catch (RuntimeException ex) { return failure("INVALID_URI"); }
    }
}
