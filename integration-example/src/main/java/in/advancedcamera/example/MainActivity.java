package in.advancedcamera.example;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import in.advancedcamera.sdk.AdvancedCamera;
import in.advancedcamera.sdk.CameraRequest;
import in.advancedcamera.sdk.CameraResult;

/** Demonstration only — copy necessary changes into the EXISTING Rent MainActivity. */
public final class MainActivity extends ComponentActivity {
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private AdvancedCamera.Registration camera;
    private ActivityResultLauncher<Intent> documentChooser;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register Activity Result launchers BEFORE Activity.STARTED.
        camera = AdvancedCamera.register(this, result -> {
            if (result.status == CameraResult.Status.SUCCESS) {
                completeWebChooser(new Uri[]{ result.uri });
            } else {
                completeWebChooser(null); // Cancel must not mutate the existing form.
                if (result.status == CameraResult.Status.FAILURE)
                    Toast.makeText(this, "Camera unavailable: " + result.errorCode,
                        Toast.LENGTH_LONG).show();
            }
        });
        documentChooser = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result ->
                completeWebChooser(WebChromeClient.FileChooserParams.parseResult(
                    result.getResultCode(), result.getData())));
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true); // Demo uses only bundled trusted HTML.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                // Explicit capture attribute from the resident photo input is required.
                // PAYMENT_PROOF and general attachment inputs keep native document/gallery chooser.
                boolean imageRequested = false;
                for (String type : params.getAcceptTypes()) {
                    if (type != null && (type.contains("image/") || type.equals(".jpg") || type.equals(".jpeg")))
                        imageRequested = true;
                }
                if (params.isCaptureEnabled() && imageRequested) {
                    try { camera.launch(CameraRequest.tenantPhoto()); }
                    catch (RuntimeException ex) { completeWebChooser(null); return true; }
                    return true;
                }
                try { documentChooser.launch(params.createIntent()); }
                catch (ActivityNotFoundException | SecurityException ex) {
                    completeWebChooser(null);
                }
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/demo.html");
    }
    private void completeWebChooser(Uri[] uris) {
        ValueCallback<Uri[]> callback = fileChooserCallback;
        fileChooserCallback = null;
        if (callback != null) callback.onReceiveValue(uris);
    }
    @Override protected void onDestroy() {
        if (fileChooserCallback != null) completeWebChooser(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
