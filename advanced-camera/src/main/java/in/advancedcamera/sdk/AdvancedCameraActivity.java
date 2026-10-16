package in.advancedcamera.sdk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;

/** Fullscreen internal Activity. No external camera app is launched. */
public final class AdvancedCameraActivity extends ComponentActivity {
    private CameraEngine engine;
    private CameraSession session;
    private boolean done;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        CameraRequest request = CameraRequest.readFrom(getIntent());
        if (request == null) { finishResult(CameraResult.failure("BAD_REQUEST")); return; }
        try {
            engine = new CameraXEngine();
            session = new CameraSession(this, request);
            engine.onCreate(session, savedInstanceState);
        } catch (Exception ex) {
            Log.e("AdvancedCamera", "Engine startup error", ex);
            finishResult(CameraResult.failure("ENGINE_STARTUP_FAILED"));
        }
    }
    @Override protected void onResume() { super.onResume(); if (engine != null && !done) engine.onResume(); }
    @Override protected void onPause() { if (engine != null) engine.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (engine != null) engine.onDestroy(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (engine != null) engine.onSaveInstanceState(out);
        super.onSaveInstanceState(out);
    }
    void finishResult(CameraResult result) {
        if (done) return;
        done = true;
        Intent data = new Intent();
        result.writeTo(data);
        if (result.uri != null) {
            data.setDataAndType(result.uri, result.mimeType);
            data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        setResult(result.status == CameraResult.Status.SUCCESS ? Activity.RESULT_OK : Activity.RESULT_CANCELED, data);
        finish();
    }
    @Override public void onBackPressed() {
        if (engine != null && !done && engine.onBackPressed()) return;
        if (!done) finishResult(CameraResult.cancelled()); else super.onBackPressed();
    }
}
