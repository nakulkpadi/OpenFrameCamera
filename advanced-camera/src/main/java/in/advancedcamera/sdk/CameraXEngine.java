package in.advancedcamera.sdk;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.DynamicRange;
import android.graphics.ImageFormat;
import android.util.Range;
import java.util.concurrent.atomic.AtomicInteger;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Original Java/CameraX implementation. NO OnePlus bytecode, assets or system privileges. */
public final class CameraXEngine implements CameraEngine {
    private enum Mode { PHOTO, VIDEO, PORTRAIT, NIGHT, HDR }
    private enum PhotoFormat { JPEG, RAW }
    private static final String[] FILTERS = {"Natural", "Mono", "Warm", "Cool", "Vivid"};
    private CameraSession session;
    private ComponentActivity activity;
    private FrameLayout root, previewStage;
    private LinearLayout quickPanel, morePanel, detailControls, zoomPresetBar;
    private HorizontalScrollView modeScroller;
    private ImageView shutterControl;
    private TextView recordingStopIndicator, formatButton, videoSettingsButton;
    private LinearLayout videoSettingsPanel;
    private FocusRing focusRing;
    private PhotoFormat photoFormat = PhotoFormat.JPEG;
    private File rawCaptureFile;
    private boolean ultraHdrCapture, hdrUltraFallback, rawSupported;
    private Quality videoQuality = Quality.FHD;
    private int videoFps = 30;
    private boolean videoBindRecoveryAttempted;
    private static final int ACCENT = 0xFFFF941C;
    private static final int PANEL_BG = 0xF2181A1D;
    private PreviewView viewfinder;
    private ImageView imageReview;
    private VideoView videoReview;
    private View grid;
    private LinearLayout topBar, bottomPanel, modeBar, filterBar, reviewBar;
    private View topControls;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private int bindGeneration, recoveryAttempts;
    private boolean previewStreaming, paused, initializingCamera;
    private TextView status, flashButton, ratioButton, timerButton, gridButton, modeLabel, zoomLabel, filterLabel;
    private SeekBar zoomSeek, exposureSeek, brightnessSeek;
    private ProcessCameraProvider provider;
    private ExtensionsManager extensions;
    private Camera camera;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private File captureFile;
    private boolean isRecording, inReview, destroyed, gridEnabled, torchEnabled;
    private boolean busy, zoomUpdating, pinchInProgress;
    private TextView brightnessTitle;
    private Mode mode = Mode.PHOTO;
    private int facing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_AUTO;
    private int timerSeconds, filterIndex;
    private float brightness;
    private CameraRequest.AspectRatio selectedAspect;
    private Bitmap reviewBitmap;
    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String> microphonePermissionLauncher;

    @Override public void onCreate(CameraSession s, Bundle state) {
        session = s;
        activity = s.activity();
        selectedAspect = s.request().aspectRatio;
        if (s.request().format == CameraRequest.Format.RAW && s.request().purpose != CameraRequest.Purpose.TENANT_PHOTO)
            photoFormat = PhotoFormat.RAW;
        facing = s.request().facing == CameraRequest.Facing.FRONT ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        if (state != null) {
            try {
                mode = Mode.valueOf(state.getString("of_mode", "PHOTO"));
                String path = state.getString("of_capture");
                if (path != null) captureFile = new File(path);
            } catch (IllegalArgumentException ignored) { mode = Mode.PHOTO; }
            facing = state.getInt("of_facing", facing);
            inReview = state.getBoolean("of_review", false) && captureFile != null && captureFile.exists();
            filterIndex = state.getInt("of_filter", 0);
            brightness = state.getFloat("of_brightness", 0f);
            try { photoFormat = PhotoFormat.valueOf(state.getString("of_photoformat", photoFormat.name())); }
            catch (IllegalArgumentException ignored) {photoFormat = PhotoFormat.JPEG;}
            rawCaptureFile = state.getString("of_rawcapture") == null ? null : new File(state.getString("of_rawcapture"));
            videoFps = state.getInt("of_video_fps", 30);
            String quality = state.getString("of_video_quality", "FHD");
            videoQuality = "UHD".equals(quality) ? Quality.UHD : "HD".equals(quality) ? Quality.HD : Quality.FHD;
            timerSeconds = state.getInt("of_timer", 0);
            try { selectedAspect = CameraRequest.AspectRatio.valueOf(state.getString("of_aspect", selectedAspect.name())); }
            catch (IllegalArgumentException ignored) { }
        }
        permissionLauncher = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), allowed -> {
            if (allowed) initializeCamera();
            else status.setText("Camera permission denied • Allow permission to take photos");
        });
        microphonePermissionLauncher = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            show(granted ? "Microphone enabled for video" : "Microphone denied • video will record silently");
            if (!destroyed && mode == Mode.VIDEO && provider != null && !inReview) bindCamera();
        });
        buildInterface();
        if (inReview) openReview();
        if (hasCameraPermission()) initializeCamera();
        else permissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    private int dp(float v) { return (int)(v * activity.getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable background(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private TextView text(String label, int size, boolean bold) {
        TextView v = new TextView(activity);
        v.setText(label); v.setTextColor(Color.WHITE); v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }
    private TextView chip(String label, View.OnClickListener callback) {
        TextView v = text(label, 13, false);
        v.setBackground(background(0x7709090C, 28, 0x45FFFFFF));
        v.setPadding(dp(14), dp(12), dp(14), dp(12));
        v.setOnClickListener(callback); return v;
    }
    private void addChip(LinearLayout container, TextView v) {
        v.setMinWidth(dp(52));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p.setMargins(dp(3), 0, dp(3), 0); container.addView(v, p);
    }
    private void show(String message) { status.setText(message); }
    private void buildInterface() {
        root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF06080A);
        activity.setContentView(root);
        viewfinder = new PreviewView(activity);
        viewfinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewStage = new FrameLayout(activity);
        FrameLayout.LayoutParams stageParams = new FrameLayout.LayoutParams(-1, -1);
        stageParams.topMargin = dp(82); stageParams.bottomMargin = dp(190);
        root.addView(previewStage, stageParams);
        previewStage.addView(viewfinder, new FrameLayout.LayoutParams(-1, -1));
        viewfinder.getPreviewStreamState().observe(activity, streamState -> {
            previewStreaming = streamState == PreviewView.StreamState.STREAMING;
            if (previewStreaming && !destroyed && !inReview) {
                recoveryAttempts = 0;
                show(mode.name() + " • preview streaming");
            }
        });
        grid = new View(activity) {
            final Paint p = new Paint(3);
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c); p.setStrokeWidth(dp(0.8f)); p.setColor(0x80FFFFFF);
                for(int i=1;i<3;i++) {
                    c.drawLine(getWidth()*i/3f, 0, getWidth()*i/3f, getHeight(), p);
                    c.drawLine(0, getHeight()*i/3f, getWidth(), getHeight()*i/3f, p);
                }
            }
        };
        grid.setVisibility(gridEnabled ? View.VISIBLE : View.GONE);
        previewStage.addView(grid, new FrameLayout.LayoutParams(-1,-1));
        focusRing = new FocusRing(activity);
        previewStage.addView(focusRing, new FrameLayout.LayoutParams(-1,-1));
        ScaleGestureDetector pinch = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinchInProgress=true; return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if(camera==null)return false;
                ZoomState state=camera.getCameraInfo().getZoomState().getValue();
                if(state==null)return false;
                float next=Math.max(state.getMinZoomRatio(),Math.min(state.getMaxZoomRatio(),
                    state.getZoomRatio()*detector.getScaleFactor()));
                camera.getCameraControl().setZoomRatio(next);
                zoomLabel.setText(String.format(java.util.Locale.US,"%.1f×",next));
                zoomUpdating=true;
                int progress=Math.round((next-state.getMinZoomRatio())*1000f/
                    Math.max(0.001f,state.getMaxZoomRatio()-state.getMinZoomRatio()));
                zoomSeek.setProgress(progress);
                zoomUpdating=false;
                return true;
            }
        });
        viewfinder.setOnTouchListener((v, event) -> {
            pinch.onTouchEvent(event);
            if (event.getPointerCount()>1 || event.getAction()==MotionEvent.ACTION_POINTER_UP) {
                pinchInProgress=true; return true;
            }
            if (event.getAction()!=MotionEvent.ACTION_UP) return true;
            if (pinchInProgress) { pinchInProgress=false; return true; }
            if (camera==null || inReview) return true;
            androidx.camera.core.MeteringPoint p = viewfinder.getMeteringPointFactory().createPoint(event.getX(), event.getY());
            camera.getCameraControl().startFocusAndMetering(new androidx.camera.core.FocusMeteringAction.Builder(p).build());
            focusRing.focusAt(event.getX(), event.getY());
            show("Tap to focus • exposure metering");
            return true;
        });

        // OpenFrame original premium camera skin. The preview and camera engine
        // remain independent of these controls, so embedded integrations match.
        topBar = new LinearLayout(activity);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(12), dp(5), dp(12), dp(5));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP);
        topParams.topMargin = dp(9);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(topBar, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(scroll, topParams);
        topControls = scroll;
        addChip(topBar, toolbarAction("✕", "Close camera", v -> session.cancel()));
        flashButton = toolbarAction("⚡", "Choose flash mode", v -> showFlashOptions());
        addChip(topBar, flashButton);
        // Format follows flash, so RAW/JPG and video quality are visible
        // without scrolling through less-frequently used controls.
        formatButton = toolbarAction("JPG", "Choose JPEG or DNG RAW photo", v -> showPhotoFormats());
        addChip(topBar, formatButton);
        videoSettingsButton = toolbarAction("1080p·30", "Video resolution and frame rate", v -> showVideoSettings());
        addChip(topBar, videoSettingsButton);
        ratioButton = toolbarAction(aspectLabel(), "Choose photo aspect ratio", v -> showAspectOptions());
        addChip(topBar, ratioButton);
        addChip(topBar, toolbarAction("EV", "Open exposure and zoom sliders", v -> toggleDetailControls()));
        gridButton = toolbarAction(gridEnabled ? "▦" : "▤", "Toggle composition grid", v -> {
            gridEnabled = !gridEnabled;
            grid.setVisibility(gridEnabled ? View.VISIBLE : View.GONE);
            gridButton.setText(gridEnabled ? "▦" : "▤");
            show(gridEnabled ? "Grid enabled" : "Grid disabled");
        });
        addChip(topBar, gridButton);
        timerButton = toolbarAction(timerSeconds + "s", "Choose shutter timer", v -> showTimerOptions());
        addChip(topBar, timerButton);
        TextView menu = toolbarAction("•••", "Open camera controls", v -> toggleMorePanel());
        addChip(topBar, menu);

        quickPanel = new LinearLayout(activity);
        quickPanel.setOrientation(LinearLayout.HORIZONTAL);
        quickPanel.setGravity(Gravity.CENTER);
        quickPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        quickPanel.setBackground(background(PANEL_BG, 20, 0x665A5C60));
        FrameLayout.LayoutParams optionsParams = new FrameLayout.LayoutParams(-1, dp(62), Gravity.TOP);
        optionsParams.setMargins(dp(15), dp(75), dp(15), 0);
        root.addView(quickPanel, optionsParams);
        quickPanel.setVisibility(View.GONE);

        bottomPanel = new LinearLayout(activity);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomPanel.setPadding(dp(12), dp(4), dp(12), dp(15));
        bottomPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00000000, 0xEB050506, 0xFF050506}));
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottomPanel, bottom);

        status = text("Opening camera…", 11, false);
        status.setTextColor(0xFFCDD0D5);
        status.setGravity(Gravity.CENTER);
        status.setOnClickListener(v -> {
            if (!hasCameraPermission()) permissionLauncher.launch(Manifest.permission.CAMERA);
            else { recoveryAttempts = 0; if (provider == null) initializeCamera(); else bindCamera(); }
        });
        bottomPanel.addView(status, new LinearLayout.LayoutParams(-1, dp(23)));

        zoomPresetBar = new LinearLayout(activity);
        zoomPresetBar.setGravity(Gravity.CENTER);
        bottomPanel.addView(zoomPresetBar, new LinearLayout.LayoutParams(-1, dp(37)));
        zoomLabel = text("1.0×", 15, true);
        zoomLabel.setTextColor(ACCENT);
        zoomLabel.setContentDescription("Toggle manual zoom and exposure adjustments");
        zoomLabel.setOnClickListener(v -> {
            if(camera==null)return;
            ZoomState zs=camera.getCameraInfo().getZoomState().getValue();
            if(zs==null)return;
            float one=Math.max(zs.getMinZoomRatio(),Math.min(1f,zs.getMaxZoomRatio()));
            camera.getCameraControl().setZoomRatio(one);
            zoomLabel.setText(String.format(java.util.Locale.US,"%.1f×",one));
            zoomUpdating=true;
            zoomSeek.setProgress(Math.round(1000f*(one-zs.getMinZoomRatio())/
                Math.max(0.001f,zs.getMaxZoomRatio()-zs.getMinZoomRatio())));
            zoomUpdating=false;
        });
        zoomLabel.setOnLongClickListener(v->{toggleDetailControls();return true;});
        zoomPresetBar.addView(zoomLabel, new LinearLayout.LayoutParams(dp(64), dp(35)));
        addZoomPreset("2×", 2f);
        addZoomPreset("3×", 3f);

        detailControls = new LinearLayout(activity);
        detailControls.setOrientation(LinearLayout.VERTICAL);
        detailControls.setBackground(background(0xF01C1D20, 18, 0));
        detailControls.setPadding(dp(12), dp(7), dp(12), dp(7));
        detailControls.setVisibility(View.GONE);
        zoomSeek = new SeekBar(activity);
        zoomSeek.setMax(1000); zoomSeek.setProgress(0);
        zoomSeek.setOnSeekBarChangeListener(new SimpleSeekBar() {
            @Override public void onProgressChanged(SeekBar b,int progress,boolean user) {
                if (!user || camera == null || zoomUpdating) return;
                ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                if (state != null) {
                    float ratio = state.getMinZoomRatio()+(state.getMaxZoomRatio()-state.getMinZoomRatio())*progress/1000f;
                    camera.getCameraControl().setZoomRatio(ratio);
                    zoomLabel.setText(String.format(java.util.Locale.US,"%.1f×",ratio));
                }
            }
        });
        detailControls.addView(text("ZOOM", 11, false), new LinearLayout.LayoutParams(-1, dp(18)));
        detailControls.addView(zoomSeek, new LinearLayout.LayoutParams(-1, dp(36)));
        LinearLayout exposureRow = new LinearLayout(activity);
        exposureRow.setGravity(Gravity.CENTER_VERTICAL);
        exposureRow.addView(text("EV", 12, true), new LinearLayout.LayoutParams(dp(40), dp(32)));
        exposureSeek = new SeekBar(activity); exposureSeek.setMax(100); exposureSeek.setProgress(50);
        exposureSeek.setOnSeekBarChangeListener(new SimpleSeekBar() {
            @Override public void onProgressChanged(SeekBar b,int n,boolean user) {
                if (!user || camera == null) return;
                androidx.camera.core.ExposureState exposure = camera.getCameraInfo().getExposureState();
                if (!exposure.isExposureCompensationSupported()) return;
                android.util.Range<Integer> range = exposure.getExposureCompensationRange();
                int index = range.getLower() + Math.round((range.getUpper()-range.getLower())*n/100f);
                camera.getCameraControl().setExposureCompensationIndex(index);
                show("Exposure • " + index + " steps");
            }
        });
        exposureRow.addView(exposureSeek, new LinearLayout.LayoutParams(0, dp(36), 1f));
        detailControls.addView(exposureRow, new LinearLayout.LayoutParams(-1, dp(40)));
        bottomPanel.addView(detailControls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout captureRow = new LinearLayout(activity);
        captureRow.setGravity(Gravity.CENTER_VERTICAL);
        captureRow.setPadding(dp(10), dp(7), dp(10), dp(3));
        TextView filters = roundAction("✦", "Filters available during photo review",
            v -> show("Shoot first • choose filters and brightness in review"));
        captureRow.addView(filters,new LinearLayout.LayoutParams(0,dp(58),1f));

        FrameLayout shutterFrame = new FrameLayout(activity);
        shutterControl = new ImageView(activity);
        shutterControl.setImageResource(R.drawable.btn_capture_photo);
        shutterControl.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shutterControl.setBackgroundColor(Color.TRANSPARENT);
        shutterControl.setContentDescription("Take photo");
        shutterFrame.addView(shutterControl,new FrameLayout.LayoutParams(-1,-1));
        recordingStopIndicator = text("■",29,true);
        recordingStopIndicator.setTextColor(Color.WHITE);
        recordingStopIndicator.setVisibility(View.GONE);
        shutterFrame.addView(recordingStopIndicator,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams shutterParams = new LinearLayout.LayoutParams(dp(91),dp(91));
        shutterParams.setMargins(dp(13),0,dp(13),0);
        captureRow.addView(shutterFrame,shutterParams);
        shutterFrame.setContentDescription("Capture photo or start/stop recording");
        shutterFrame.setOnClickListener(v -> {
            closePopups();
            onShutter();
        });

        ImageView flip = new ImageView(activity);
        flip.setImageResource(R.drawable.btn_switch_camera);
        flip.setScaleType(ImageView.ScaleType.FIT_CENTER);
        flip.setBackgroundColor(Color.TRANSPARENT);
        flip.setContentDescription("Switch front and rear camera");
        flip.setOnClickListener(v -> {closePopups();flipCamera();});
        captureRow.addView(flip,new LinearLayout.LayoutParams(0,dp(58),1f));
        bottomPanel.addView(captureRow, new LinearLayout.LayoutParams(-1, dp(103)));

        modeScroller = new HorizontalScrollView(activity);
        modeScroller.setHorizontalScrollBarEnabled(false);
        modeScroller.setFillViewport(true);
        modeBar = new LinearLayout(activity);
        modeBar.setGravity(Gravity.CENTER);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBar.setPadding(dp(10), 0, dp(10), 0);
        modeScroller.addView(modeBar, new HorizontalScrollView.LayoutParams(-2, dp(48)));
        bottomPanel.addView(modeScroller, new LinearLayout.LayoutParams(-1, dp(53)));
        for (Mode m : new Mode[]{Mode.VIDEO, Mode.PHOTO, Mode.PORTRAIT, Mode.NIGHT, Mode.HDR}) {
            if (m == Mode.VIDEO && session.request().purpose == CameraRequest.Purpose.TENANT_PHOTO) continue;
            TextView tab = text(m.name(), 14, false);
            tab.setPadding(dp(16), dp(10), dp(16), dp(10));
            tab.setOnClickListener(v -> setMode(m));
            modeBar.addView(tab, new LinearLayout.LayoutParams(-2, dp(45)));
        }
        TextView moreTab = text("MORE", 14, false);
        moreTab.setPadding(dp(16), dp(10), dp(16), dp(10));
        moreTab.setOnClickListener(v -> toggleMorePanel());
        modeBar.addView(moreTab, new LinearLayout.LayoutParams(-2, dp(45)));

        morePanel = new LinearLayout(activity);
        morePanel.setOrientation(LinearLayout.VERTICAL);
        morePanel.setPadding(dp(16), dp(16), dp(16), dp(18));
        morePanel.setBackground(background(0xF0202225, 24, 0x664B4C50));
        FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        moreParams.setMargins(dp(15), 0, dp(15), dp(160));
        root.addView(morePanel, moreParams);
        morePanel.setVisibility(View.GONE);
        TextView moreTitle = text("CAMERA TOOLS   •   OpenFrame", 12, true);
        moreTitle.setTextColor(ACCENT);
        morePanel.addView(moreTitle, new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout toolsRow = new LinearLayout(activity);
        toolsRow.setGravity(Gravity.CENTER);
        TextView aspectTool = roundAction("▣  Ratio", "Choose crop framing", v -> { morePanel.setVisibility(View.GONE); showAspectOptions(); });
        TextView flashTool = roundAction("⚡  Flash", "Choose flash option", v -> {morePanel.setVisibility(View.GONE); showFlashOptions();});
        TextView gridTool = roundAction("▦  Grid", "Toggle composition grid", v -> {
            morePanel.setVisibility(View.GONE);
            gridEnabled = !gridEnabled; grid.setVisibility(gridEnabled ? View.VISIBLE : View.GONE);
            gridButton.setText(gridEnabled ? "▦" : "▤");
        });
        toolsRow.addView(aspectTool, new LinearLayout.LayoutParams(0, dp(56), 1f));
        toolsRow.addView(flashTool, new LinearLayout.LayoutParams(0, dp(56), 1f));
        toolsRow.addView(gridTool, new LinearLayout.LayoutParams(0, dp(56), 1f));
        morePanel.addView(toolsRow);
        TextView videoTools=roundAction("▣   Video resolution / frame rate",
            "Open video recording settings",v->{morePanel.setVisibility(View.GONE);showVideoSettings();});
        morePanel.addView(videoTools,new LinearLayout.LayoutParams(-1,dp(50)));
        TextView extensionsText = text("DEVICE CAMERA EXTENSIONS", 11, true);
        extensionsText.setTextColor(0xFFAEB4BA);
        morePanel.addView(extensionsText, new LinearLayout.LayoutParams(-1, dp(35)));
        LinearLayout extRow = new LinearLayout(activity);
        extRow.setGravity(Gravity.CENTER);
        for (Mode ext : new Mode[]{Mode.NIGHT, Mode.PORTRAIT, Mode.HDR}) {
            TextView extButton = roundAction(ext.name(), ext.name() + " camera extension", v -> {
                morePanel.setVisibility(View.GONE); setMode(ext);
            });
            extRow.addView(extButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        morePanel.addView(extRow);
        TextView disclaimer = text("Pro RAW • Panorama • Time-lapse • 4K/60: planned, not yet implemented", 11, false);
        disclaimer.setTextColor(0xFFB3B8BD);
        disclaimer.setPadding(dp(6),dp(10),dp(6),0);
        morePanel.addView(disclaimer, new LinearLayout.LayoutParams(-1, dp(54)));
                // Editor is only visible after an actual capture; edits are applied at JPEG finalization.
        imageReview = new ImageView(activity); imageReview.setBackgroundColor(Color.BLACK);
        imageReview.setScaleType(ImageView.ScaleType.FIT_CENTER); imageReview.setVisibility(View.GONE);
        root.addView(imageReview,new FrameLayout.LayoutParams(-1,-1));
        videoReview = new VideoView(activity); videoReview.setVisibility(View.GONE);
        root.addView(videoReview,new FrameLayout.LayoutParams(-1,-1));
        reviewBar = new LinearLayout(activity); reviewBar.setOrientation(LinearLayout.VERTICAL);
        reviewBar.setPadding(dp(12),dp(14),dp(12),dp(24));
        reviewBar.setBackground(background(PANEL_BG, 18, 0));
        FrameLayout.LayoutParams reviewParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(reviewBar,reviewParams);
        filterLabel = text("Natural • non-destructive preview",13,true);
        reviewBar.addView(filterLabel,new LinearLayout.LayoutParams(-1,dp(30)));
        filterBar = new LinearLayout(activity);filterBar.setGravity(Gravity.CENTER);
        reviewBar.addView(filterBar,new LinearLayout.LayoutParams(-1,dp(52)));
        for(int i=0;i<FILTERS.length;i++) {
            final int selected=i;
            TextView item=chip(FILTERS[i],v->{filterIndex=selected;updateReviewFilter();});
            LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,dp(44),1);
            cell.setMargins(dp(2),0,dp(2),0);
            filterBar.addView(item,cell);
        }
        brightnessSeek = new SeekBar(activity); brightnessSeek.setMax(200);brightnessSeek.setProgress(100);
        brightnessSeek.setOnSeekBarChangeListener(new SimpleSeekBar() {
            @Override public void onProgressChanged(SeekBar b,int n,boolean user) {
                if (!user) return;
                brightness=(n-100)/100f;updateReviewFilter();
            }
        });
        brightnessTitle = text("Brightness",12,false);
        reviewBar.addView(brightnessTitle,new LinearLayout.LayoutParams(-1,dp(27)));
        reviewBar.addView(brightnessSeek,new LinearLayout.LayoutParams(-1,dp(32)));
        LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.CENTER);
        TextView retake=chip("↶  Retake",v->retake());
        TextView confirm=chip("✓  Use photo / video",v->confirm());
        confirm.setBackground(background(ACCENT,27,0));confirm.setTextColor(Color.BLACK);
        actions.addView(retake,new LinearLayout.LayoutParams(0,dp(56),1));
        actions.addView(confirm,new LinearLayout.LayoutParams(0,dp(56),1));
        reviewBar.addView(actions,new LinearLayout.LayoutParams(-1,dp(60)));
        reviewBar.setVisibility(View.GONE);
        refreshModeChips();
        updateShutterStyle();
        updateAspectUi();
        updateFlashUi();
    }

    private TextView toolbarAction(String label, String desc, View.OnClickListener click) {
        TextView t=text(label,15,true);t.setContentDescription(desc);t.setPadding(dp(9),0,dp(9),0);
        t.setBackground(background(0xEE24262A,27,0x44595B60));t.setOnClickListener(click);return t;
    }
    private TextView roundAction(String label, String desc, View.OnClickListener click) {
        TextView t=text(label,13,false);t.setContentDescription(desc);t.setPadding(dp(5),dp(4),dp(5),dp(4));
        t.setBackground(background(0xDA33363C,23,0x224F5157));t.setOnClickListener(click);return t;
    }
    private void showOptions(String[] choices, java.util.function.Consumer<String> action) {
        if (inReview || quickPanel==null) return;
        if(morePanel!=null)morePanel.setVisibility(View.GONE);
        quickPanel.removeAllViews();
        for(String choice:choices) {
            TextView item=text(choice,13,false);item.setBackground(background(0x77232528,18,0));
            item.setOnClickListener(v->{quickPanel.setVisibility(View.GONE);action.accept(choice);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);
            p.setMargins(dp(2),0,dp(2),0);quickPanel.addView(item,p);
        }
        quickPanel.setAlpha(0);quickPanel.setVisibility(View.VISIBLE);
        quickPanel.animate().alpha(1f).setDuration(150).start();
    }
    private void showFlashOptions() {
        if(camera==null){show("Camera is not ready");return;}
        if(!camera.getCameraInfo().hasFlashUnit()){show("Flash unavailable on this lens");return;}
        if(mode==Mode.VIDEO) {
            showOptions(new String[]{"Torch Off","Torch On"},choice->{
                torchEnabled=choice.equals("Torch On");
                camera.getCameraControl().enableTorch(torchEnabled);
                updateFlashUi();show(choice);
            });
        } else {
            showOptions(new String[]{"Off","Auto","On","Always on"},choice->{
                torchEnabled=choice.equals("Always on");
                flashMode=choice.equals("On")?ImageCapture.FLASH_MODE_ON:
                    choice.equals("Auto")?ImageCapture.FLASH_MODE_AUTO:ImageCapture.FLASH_MODE_OFF;
                if(imageCapture!=null)imageCapture.setFlashMode(flashMode);
                camera.getCameraControl().enableTorch(torchEnabled);
                updateFlashUi();show("Flash • "+choice);
            });
        }
    }
    private void showAspectOptions() {
        if(mode==Mode.VIDEO){show("Video uses HD 16:9");return;}
        if(session.request().purpose==CameraRequest.Purpose.TENANT_PHOTO) {
            show("Resident portrait requires 3:4");return;
        }
        showOptions(new String[]{"3:4","4:3","1:1","Original"},choice->{
            selectedAspect=choice.equals("3:4")?CameraRequest.AspectRatio.THREE_FOUR:
                choice.equals("4:3")?CameraRequest.AspectRatio.FOUR_THREE:
                choice.equals("1:1")?CameraRequest.AspectRatio.SQUARE:CameraRequest.AspectRatio.ORIGINAL;
            updateAspectUi();show("Photo crop • "+aspectLabel());
        });
    }
    private void showTimerOptions() {
        showOptions(new String[]{"Off","3 sec","10 sec"},choice->{
            timerSeconds=choice.equals("3 sec")?3:choice.equals("10 sec")?10:0;
            timerButton.setText(timerSeconds+"s");
            show(timerSeconds==0?"Timer off":"Timer • "+timerSeconds+" seconds");
        });
    }
    private void toggleMorePanel() {
        if(morePanel==null||inReview)return;
        quickPanel.setVisibility(View.GONE);
        if(morePanel.getVisibility()==View.VISIBLE)morePanel.setVisibility(View.GONE);
        else {morePanel.setAlpha(0);morePanel.setVisibility(View.VISIBLE);
            morePanel.animate().alpha(1f).setDuration(180).start();}
    }
    private void toggleDetailControls() {
        if(detailControls==null||inReview)return;
        if(morePanel!=null)morePanel.setVisibility(View.GONE);
        detailControls.setVisibility(detailControls.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
    }
    private void addZoomPreset(String label,float zoom) {
        TextView item=roundAction(label,"Zoom "+label,v->{
            if(camera==null){show("Camera not ready");return;}
            ZoomState state=camera.getCameraInfo().getZoomState().getValue();
            if(state==null)return;
            if(zoom<state.getMinZoomRatio()||zoom>state.getMaxZoomRatio()) {
                show(label+" zoom not supported by this lens");return;
            }
            camera.getCameraControl().setZoomRatio(zoom);
            zoomLabel.setText(String.format(java.util.Locale.US,"%.1f×",zoom));
            zoomUpdating=true;
            zoomSeek.setProgress(Math.round(1000f*(zoom-state.getMinZoomRatio())/
                Math.max(0.001f,state.getMaxZoomRatio()-state.getMinZoomRatio())));
            zoomUpdating=false;
        });
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(48),dp(34));
        p.setMargins(dp(3),0,dp(3),0);zoomPresetBar.addView(item,p);
    }
    private void updateShutterStyle() {
        if (shutterControl == null) return;
        shutterControl.setImageResource(mode == Mode.VIDEO ?
            R.drawable.btn_capture_video : R.drawable.btn_capture_photo);
        shutterControl.setContentDescription(mode == Mode.VIDEO ?
            (isRecording ? "Stop video recording" : "Start video recording") : "Take photo");
        if (recordingStopIndicator != null)
            recordingStopIndicator.setVisibility(mode == Mode.VIDEO && isRecording ?
                View.VISIBLE : View.GONE);
    }

    private String qualityName() {
        return videoQuality == Quality.UHD ? "4K" : videoQuality == Quality.HD ? "720p" : "1080p";
    }
    private CameraSelector baseSelector() {
        return facing == CameraSelector.LENS_FACING_FRONT ?
            CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
    }
    private boolean supportsImageFormat(int outputFormat) {
        if(provider==null)return false;
        try {
            return ImageCapture.getImageCaptureCapabilities(provider.getCameraInfo(baseSelector()))
                .getSupportedOutputFormats().contains(outputFormat);
        } catch(RuntimeException ex) {android.util.Log.w("OpenFrame","Image format query failed",ex);return false;}
    }
    private boolean supportsVideoQuality(Quality quality) {
        if(provider==null)return false;
        try {
            return Recorder.getVideoCapabilities(provider.getCameraInfo(baseSelector()))
                .getSupportedQualities(DynamicRange.SDR).contains(quality);
        } catch(RuntimeException ex) {return false;}
    }
    private boolean supportsFps(int rate) {
        if(provider==null)return false;
        try {
            for(Range<Integer> r:provider.getCameraInfo(baseSelector()).getSupportedFrameRateRanges()) {
                if(r.getLower()<=rate && r.getUpper()>=rate)return true;
            }
        } catch(RuntimeException ignored){}
        return false;
    }
    private void showVideoSettings() {
        if(mode!=Mode.VIDEO){show("Video settings available in Video mode");return;}
        if(isRecording||busy){show("Stop recording to change video settings");return;}
        showOptions(new String[]{"720p","1080p","4K","30fps","60fps"},choice->{
            if(choice.endsWith("fps")){
                int requested="60fps".equals(choice)?60:30;
                if(!supportsFps(requested)){
                    show(requested+"fps unavailable on this lens");return;
                }
                videoFps=requested;
            } else {
                Quality q="4K".equals(choice)?Quality.UHD:
                    "720p".equals(choice)?Quality.HD:Quality.FHD;
                if(!supportsVideoQuality(q)){
                    show(choice+" recording unsupported on selected camera");return;
                }
                videoQuality=q;
            }
            videoBindRecoveryAttempted=false;
            updateAspectUi();bindCamera();
            show("Video target: "+qualityName()+" @ "+videoFps+"fps (actual depends on device)");
        });
    }
    private void showPhotoFormats() {
        if(mode!=Mode.PHOTO){show("JPEG is used by this camera mode");return;}
        if(session.request().purpose==CameraRequest.Purpose.TENANT_PHOTO){
            show("Tenant photographs must remain JPEG");return;
        }
        showOptions(new String[]{"JPG","RAW DNG"},choice->{
            if("RAW DNG".equals(choice)&&!supportsImageFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)){
                show("RAW DNG is not available on selected lens");return;
            }
            photoFormat="RAW DNG".equals(choice)?PhotoFormat.RAW:PhotoFormat.JPEG;
            bindCamera();updateAspectUi();
            show(photoFormat==PhotoFormat.RAW?"RAW DNG • original sensor data, editing disabled":"JPEG • filters and edits supported");
        });
    }
    private String aspectLabel() {
        switch (selectedAspect) {
            case THREE_FOUR: return "3:4";
            case FOUR_THREE: return "4:3";
            case SQUARE: return "1:1";
            default:return "Original";
        }
    }
    /**
     * Display the same centered aspect region that ImageFinalizer crops from the
     * JPEG. Video remains HD 16:9; still-image crops never silently affect it.
     */
    private void updateAspectUi() {
        if (ratioButton != null) ratioButton.setText(mode == Mode.VIDEO ? "16:9" : aspectLabel());
        if (formatButton != null) {
            formatButton.setVisibility(mode == Mode.VIDEO || session.request().purpose == CameraRequest.Purpose.TENANT_PHOTO ? View.GONE : View.VISIBLE);
            formatButton.setText(photoFormat == PhotoFormat.RAW && mode == Mode.PHOTO ? "RAW" : "JPG");
        }
        if (videoSettingsButton != null) {
            videoSettingsButton.setVisibility(mode == Mode.VIDEO ? View.VISIBLE : View.GONE);
            videoSettingsButton.setText(qualityName() + "·" + videoFps);
        }
        if (previewStage == null || viewfinder == null || grid == null) return;
        previewStage.post(() -> {
            if (destroyed || previewStage.getWidth() == 0 || previewStage.getHeight() == 0) return;
            int width = previewStage.getWidth(), height = previewStage.getHeight();
            if (mode != Mode.VIDEO && selectedAspect != CameraRequest.AspectRatio.ORIGINAL) {
                double aspect = selectedAspect == CameraRequest.AspectRatio.THREE_FOUR ? 0.75
                        : selectedAspect == CameraRequest.AspectRatio.FOUR_THREE ? (4.0 / 3.0) : 1.0;
                if (width / (double) height > aspect) width = Math.max(1, (int) Math.round(height * aspect));
                else height = Math.max(1, (int) Math.round(width / aspect));
            }
            FrameLayout.LayoutParams frame = new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
            viewfinder.setLayoutParams(frame);
            grid.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
            if (focusRing != null) focusRing.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        });
    }
    private void cycleAspect() {
        if (mode == Mode.VIDEO) { show("HD video is 16:9 • photo ratio does not affect recording"); return; }
        if (session.request().purpose == CameraRequest.Purpose.TENANT_PHOTO) {
            show("Resident ID photos use a fixed 3:4 crop"); return;
        }
        switch (selectedAspect) {
            case THREE_FOUR: selectedAspect = CameraRequest.AspectRatio.FOUR_THREE; break;
            case FOUR_THREE: selectedAspect = CameraRequest.AspectRatio.SQUARE; break;
            case SQUARE: selectedAspect = CameraRequest.AspectRatio.ORIGINAL; break;
            default: selectedAspect = CameraRequest.AspectRatio.THREE_FOUR;
        }
        updateAspectUi();
        show(aspectLabel() + " photo framing • centered crop saved");
    }
    private void updateFlashUi() {
        if (flashButton == null) return;
        boolean hasFlash = camera == null || camera.getCameraInfo().hasFlashUnit();
        flashButton.setAlpha(hasFlash ? 1f : 0.55f);
        if (mode == Mode.VIDEO) flashButton.setText(torchEnabled ? "☀" : "⚡");
        else flashButton.setText(torchEnabled ? "☀" : flashMode == ImageCapture.FLASH_MODE_ON ? "⚡" : flashMode == ImageCapture.FLASH_MODE_AUTO ? "⚡A" : "⚡̸");
    }
    private void cycleFlash() {
        if (camera == null) { show("Camera not ready • tap status to retry"); return; }
        if (!camera.getCameraInfo().hasFlashUnit()) {
            show("Flash / torch is not available on this camera"); return;
        }
        if (mode == Mode.VIDEO) {
            torchEnabled = !torchEnabled;
            camera.getCameraControl().enableTorch(torchEnabled);
            updateFlashUi();
            show(torchEnabled ? "Video torch enabled" : "Video torch disabled");
            return;
        }
        flashMode = flashMode == ImageCapture.FLASH_MODE_AUTO ? ImageCapture.FLASH_MODE_ON
                : flashMode == ImageCapture.FLASH_MODE_ON ? ImageCapture.FLASH_MODE_OFF : ImageCapture.FLASH_MODE_AUTO;
        if (imageCapture != null) imageCapture.setFlashMode(flashMode);
        updateFlashUi();
        show(flashMode == ImageCapture.FLASH_MODE_OFF ? "Photo flash off"
                : "Photo flash fires only when taking a picture");
    }
    private void flipCamera() {
        if (isRecording || inReview || busy) return;
        facing=facing==CameraSelector.LENS_FACING_BACK?CameraSelector.LENS_FACING_FRONT:CameraSelector.LENS_FACING_BACK;
        // OEM extensions often have separate per-facing support.
        if(mode!=Mode.PHOTO && mode!=Mode.VIDEO) mode=Mode.PHOTO;
        refreshModeChips();
        updateAspectUi();
        bindCamera();
    }
    private void setMode(Mode next) {
        if(busy || isRecording || inReview) return;
        if (next==Mode.VIDEO && session.request().purpose==CameraRequest.Purpose.TENANT_PHOTO) return;
        if(next!=Mode.PHOTO && next!=Mode.VIDEO) {
            if(next==Mode.HDR && (extensions==null ||
               !extensions.isExtensionAvailable(baseSelector(), ExtensionMode.HDR)) &&
               supportsImageFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)){
                hdrUltraFallback=true;
            } else {
                hdrUltraFallback=false;
            }
            int extension = extensionMode(next);
            CameraSelector selector = facing==CameraSelector.LENS_FACING_FRONT?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;
            if(!hdrUltraFallback && (extensions==null || !extensions.isExtensionAvailable(selector,extension))) {
                show(next.name()+" unavailable on this device / lens"); return;
            }
        }
        if (quickPanel != null) quickPanel.setVisibility(View.GONE);
        if (morePanel != null) morePanel.setVisibility(View.GONE);
        if (detailControls != null) detailControls.setVisibility(View.GONE);
        if (mode == Mode.VIDEO && next != Mode.VIDEO) torchEnabled = false;
        if (next == Mode.VIDEO && mode != Mode.VIDEO) videoBindRecoveryAttempted=false;
        if (next != Mode.HDR) { hdrUltraFallback=false; ultraHdrCapture=false; }
        mode=next;
        if(mode!=Mode.PHOTO && session.request().format!=CameraRequest.Format.RAW) photoFormat=PhotoFormat.JPEG;
        refreshModeChips();
        updateAspectUi();
        updateFlashUi();
        updateShutterStyle();
        if(next==Mode.VIDEO && ContextCompat.checkSelfPermission(activity,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        show(next==Mode.VIDEO?"Video • tap to record":next.name()+" • ready");
        bindCamera();
    }
    private void refreshModeChips() {
        if(modeBar==null)return;
        for(int i=0;i<modeBar.getChildCount();i++){
            TextView chip=(TextView)modeBar.getChildAt(i);
            boolean selected=mode.name().equals(chip.getText().toString());
            chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            chip.setTextColor(selected?ACCENT:0xFFD7DBDF);
            chip.setBackground(selected ? background(0xDD28201B, 24, 0x66FF941C) : background(0x00000000,24,0));
            if (selected && modeScroller != null) {
                final int left = chip.getLeft();
                modeScroller.post(() -> modeScroller.smoothScrollTo(Math.max(0, left - modeScroller.getWidth()/2 + chip.getWidth()/2), 0));
            }
        }
    }
    private int extensionMode(Mode m) {
        switch(m) {
            case PORTRAIT:return ExtensionMode.BOKEH;
            case NIGHT:return ExtensionMode.NIGHT;
            case HDR:return ExtensionMode.HDR;
            default:return ExtensionMode.NONE;
        }
    }
    private void initializeCamera() {
        if (destroyed || !hasCameraPermission() || provider != null || initializingCamera) return;
        initializingCamera = true;
        show("Opening camera…");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            initializingCamera = false;
            if (destroyed) return;
            try {
                provider = future.get();
                bindCamera();
                ListenableFuture<ExtensionsManager> extFuture = ExtensionsManager.getInstanceAsync(activity, provider);
                extFuture.addListener(() -> {
                    if (destroyed) return;
                    try {
                        extensions = extFuture.get();
                        if (mode != Mode.PHOTO && mode != Mode.VIDEO) bindCamera();
                    } catch (Exception ex) {
                        android.util.Log.w("OpenFrame", "Extensions unavailable", ex);
                        if (mode != Mode.PHOTO && mode != Mode.VIDEO) {
                            mode = Mode.PHOTO;
                            refreshModeChips();
                            bindCamera();
                        }
                    }
                }, ContextCompat.getMainExecutor(activity));
            } catch (Exception ex) {
                android.util.Log.e("OpenFrame", "Camera provider initialization failed", ex);
                show("Camera unavailable • " + ex.getClass().getSimpleName() + " • tap to retry");
            }
        }, ContextCompat.getMainExecutor(activity));
    }
    private void checkPreview(int generation) {
        previewHandler.postDelayed(() -> {
            if (destroyed || paused || inReview || generation != bindGeneration || previewStreaming) return;
            if (mode != Mode.PHOTO && mode != Mode.VIDEO) {
                android.util.Log.w("OpenFrame", "Extension preview failed to stream; reverting to Photo");
                mode = Mode.PHOTO;
                refreshModeChips();
                show("Device extension failed • switching to standard Photo");
                bindCamera();
                return;
            }
            if (recoveryAttempts < 1) {
                recoveryAttempts++;
                android.util.Log.w("OpenFrame", "No camera frames; retrying regular preview");
                show("Preview stalled • retrying camera");
                bindCamera();
            } else {
                show("No live preview • tap here to retry / check camera access");
            }
        }, mode == Mode.PORTRAIT || mode == Mode.NIGHT || mode == Mode.HDR ? 9000L : 6500L);
    }
    private void bindCamera() {
        if (provider == null || destroyed || !hasCameraPermission() || inReview) return;
        final int generation = ++bindGeneration;
        previewStreaming = false;
        try {
            if (camera != null && torchEnabled) camera.getCameraControl().enableTorch(false);
            provider.unbindAll();
            camera = null;
            imageCapture = null;
            videoCapture = null;
            ultraHdrCapture=false;
            CameraSelector selector=baseSelector();
            if(mode!=Mode.VIDEO && mode!=Mode.PHOTO && !(mode==Mode.HDR && hdrUltraFallback)){
                if(extensions==null || !extensions.isExtensionAvailable(selector,extensionMode(mode)))
                    throw new IllegalStateException("Requested device extension unavailable");
                selector=extensions.getExtensionEnabledCameraSelector(selector,extensionMode(mode));
            }
            Preview preview=new Preview.Builder().build();
            preview.setSurfaceProvider(viewfinder.getSurfaceProvider());
            if(mode==Mode.VIDEO){
                Quality q=supportsVideoQuality(videoQuality)?videoQuality:Quality.HD;
                if(q!=videoQuality){videoQuality=q;show("Selected video quality unavailable; using 720p");}
                Recorder recorder=new Recorder.Builder().setQualitySelector(
                    QualitySelector.from(q,FallbackStrategy.lowerQualityOrHigherThan(q))).build();
                VideoCapture.Builder<Recorder> vb=new VideoCapture.Builder<>(recorder);
                if(supportsFps(videoFps))
                    vb.setTargetFrameRate(new Range<>(videoFps,videoFps));
                videoCapture=vb.build();
                camera=provider.bindToLifecycle(activity,selector,preview,videoCapture);
            }else{
                int desiredFormat=ImageCapture.OUTPUT_FORMAT_JPEG;
                if(mode==Mode.PHOTO && photoFormat==PhotoFormat.RAW){
                    if(supportsImageFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)){
                        desiredFormat=ImageCapture.OUTPUT_FORMAT_RAW_JPEG;
                    }else{
                        photoFormat=PhotoFormat.JPEG;
                        show("RAW not supported on selected lens • using JPEG");
                    }
                }
                if(mode==Mode.HDR && hdrUltraFallback){
                    if(!supportsImageFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR))
                        throw new IllegalStateException("Ultra HDR not supported by this lens");
                    desiredFormat=ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR;
                    ultraHdrCapture=true;
                }
                ImageCapture.Builder ib=new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .setOutputFormat(desiredFormat);
                imageCapture=ib.build();
                camera=provider.bindToLifecycle(activity,selector,preview,imageCapture);
            }
            if (torchEnabled && camera.getCameraInfo().hasFlashUnit())
                camera.getCameraControl().enableTorch(true);
            ZoomState zoom = camera.getCameraInfo().getZoomState().getValue();
            if (zoom != null) {
                zoomSeek.setEnabled(zoom.getMaxZoomRatio() > zoom.getMinZoomRatio());
                zoomLabel.setText(String.format(java.util.Locale.US, "%.1f×", zoom.getZoomRatio()));
            }
            exposureSeek.setEnabled(camera.getCameraInfo().getExposureState().isExposureCompensationSupported());
            camera.getCameraInfo().getCameraState().observe(activity, state -> {
                if (generation != bindGeneration || destroyed || inReview) return;
                if (state.getError() != null) {
                    android.util.Log.e("OpenFrame", "Camera state error " + state.getError().getCode());
                    show("Camera error " + state.getError().getCode() + " • tap here to retry");
                }
            });
            updateAspectUi();
            updateFlashUi();
            show("Opening " + mode.name() + (ultraHdrCapture ? " • Ultra HDR JPEG_R" : "") + " preview…");
            checkPreview(generation);
        } catch (Exception ex) {
            android.util.Log.e("OpenFrame", "Camera bind failed", ex);
            Mode failed = mode;
            if(failed == Mode.VIDEO && !videoBindRecoveryAttempted &&
                (videoFps!=30 || videoQuality!=Quality.HD)){
                videoBindRecoveryAttempted=true;
                videoFps=30;videoQuality=Quality.HD;
                show("Video combination unsupported • retrying 720p/30");
                bindCamera();return;
            }
            if (failed != Mode.PHOTO) {
                mode = Mode.PHOTO;
                hdrUltraFallback = false;
                ultraHdrCapture = false;
                photoFormat = PhotoFormat.JPEG;
                torchEnabled = false;
                refreshModeChips();
                bindCamera();
            } else show("Camera unavailable • " + ex.getClass().getSimpleName() + " • tap to retry");
        }
    }
    private void onShutter() {
        if (inReview || busy) return;
        if(mode==Mode.VIDEO) {
            if(isRecording) {if(recording!=null)recording.stop();updateShutterStyle();}
            else beginVideo();
            return;
        }
        if(imageCapture==null){show("Camera not ready");return;}
        busy=true;
        if(timerSeconds>0) {
            show("Timer • "+timerSeconds+" seconds");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
                if(!destroyed && !inReview)takePhoto();else busy=false;
            },timerSeconds*1000L);
        }else takePhoto();
    }
    private void takePhoto() {
        if(imageCapture==null){busy=false;return;}
        try {
            captureFile=session.createPrivateCaptureFile();
            if(mode==Mode.PHOTO && photoFormat==PhotoFormat.RAW)
                rawCaptureFile=session.createPrivateCaptureFile(".dng");
            else rawCaptureFile=null;
        }catch(IOException e){
            busy=false;
            if(captureFile!=null)captureFile.delete();
            show("Cannot create private capture file");
            return;
        }
        ImageCapture.OutputFileOptions jpgOptions=
            new ImageCapture.OutputFileOptions.Builder(captureFile).build();
        if(rawCaptureFile!=null){
            ImageCapture.OutputFileOptions dngOptions=
                new ImageCapture.OutputFileOptions.Builder(rawCaptureFile).build();
            final AtomicInteger saved=new AtomicInteger();
            final java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                imageCapture.takePicture(dngOptions,jpgOptions,ContextCompat.getMainExecutor(activity),
                    new ImageCapture.OnImageSavedCallback(){
                        @Override public void onImageSaved(ImageCapture.OutputFileResults result){
                            if(!failed.get() && saved.incrementAndGet()==2) {
                                busy=false;
                                if(!destroyed)openReview();
                            }
                        }
                        @Override public void onError(ImageCaptureException e){
                            failed.set(true);
                            busy=false;
                            if(captureFile!=null)captureFile.delete();
                            if(rawCaptureFile!=null)rawCaptureFile.delete();
                            rawCaptureFile=null;
                            if(!destroyed)show("RAW capture failed: "+e.getMessage());
                        }
                    });
            }catch(Exception ex){
                busy=false;
                captureFile.delete();rawCaptureFile.delete();rawCaptureFile=null;
                show("RAW capture unavailable: "+ex.getMessage());
            }
            return;
        }
        imageCapture.takePicture(jpgOptions,ContextCompat.getMainExecutor(activity),
            new ImageCapture.OnImageSavedCallback(){
                @Override public void onImageSaved(ImageCapture.OutputFileResults result){
                    busy=false;if(!destroyed)openReview();
                }
                @Override public void onError(ImageCaptureException e){
                    busy=false;if(captureFile!=null)captureFile.delete();
                    if(!destroyed)show("Photo failed: "+e.getMessage());
                }
            });
    }
    private void beginVideo() {
        if(videoCapture==null){show("Video mode not ready");return;}
        try {captureFile=session.createPrivateCaptureFile(".mp4");}
        catch(IOException e){show("Cannot create video file");return;}
        try {
            PendingRecording pending=videoCapture.getOutput().prepareRecording(activity,
                new FileOutputOptions.Builder(captureFile).build());
            if(ContextCompat.checkSelfPermission(activity,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                pending=pending.withAudioEnabled();
            else show("Recording without microphone audio");
            recording=pending.start(ContextCompat.getMainExecutor(activity),event->{
                if(event instanceof VideoRecordEvent.Start){isRecording=true;updateShutterStyle();show("Recording • tap square to stop");}
                if(event instanceof VideoRecordEvent.Finalize){
                    isRecording=false;recording=null;updateShutterStyle();
                    VideoRecordEvent.Finalize end=(VideoRecordEvent.Finalize)event;
                    if(!end.hasError() && !destroyed)openReview();
                    else {if(captureFile!=null)captureFile.delete();if(!destroyed)show("Recording failed: "+end.getError());}
                }
            });
        }catch(Exception ex){if(captureFile!=null)captureFile.delete();show("Recording unavailable: "+ex.getMessage());}
    }
    private void openReview() {
        if(captureFile==null || !captureFile.exists())return;
        inReview=true;
        viewfinder.setVisibility(View.INVISIBLE);grid.setVisibility(View.GONE);
        topControls.setVisibility(View.GONE);bottomPanel.setVisibility(View.GONE);
        reviewBar.setVisibility(View.VISIBLE);
        boolean video=captureFile.getName().endsWith(".mp4");
        boolean preserveSensorData=rawCaptureFile!=null || ultraHdrCapture;
        boolean canFilter=!video && !preserveSensorData && session.request().allowEditing && session.request().purpose!=CameraRequest.Purpose.TENANT_PHOTO;
        boolean canEnhance=!video && !preserveSensorData && session.request().allowEnhancement;
        filterBar.setVisibility(canFilter?View.VISIBLE:View.GONE);
        brightnessSeek.setVisibility(canEnhance?View.VISIBLE:View.GONE);
        brightnessTitle.setVisibility(canEnhance?View.VISIBLE:View.GONE);
        if(video){
            videoReview.setVisibility(View.VISIBLE);videoReview.setVideoPath(captureFile.getAbsolutePath());
            videoReview.setOnPreparedListener(mp->mp.setLooping(true));videoReview.start();
            filterLabel.setText("Video review • original colour");
        }else{
            imageReview.setVisibility(View.VISIBLE);
            BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(captureFile.getAbsolutePath(),b);
            b.inJustDecodeBounds=false;b.inSampleSize=1;
            while(b.outWidth/b.inSampleSize>1920||b.outHeight/b.inSampleSize>1920)b.inSampleSize*=2;
            if(reviewBitmap!=null){reviewBitmap.recycle();reviewBitmap=null;}
            reviewBitmap=BitmapFactory.decodeFile(captureFile.getAbsolutePath(),b);
            if(reviewBitmap==null){show("Cannot preview captured image");return;}
            try {
                ExifInterface exif=new ExifInterface(captureFile.getAbsolutePath());
                Matrix rotate=ImageFinalizer.exifMatrix(exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL));
                if(!rotate.isIdentity()){
                    Bitmap corrected=Bitmap.createBitmap(reviewBitmap,0,0,
                        reviewBitmap.getWidth(),reviewBitmap.getHeight(),rotate,true);
                    if(corrected!=reviewBitmap){reviewBitmap.recycle();reviewBitmap=corrected;}
                }
            }catch(IOException ex){android.util.Log.w("OpenFrame","Preview orientation unavailable",ex);}
            imageReview.setImageBitmap(reviewBitmap);
            if(rawCaptureFile!=null) filterLabel.setText("RAW DNG • JPEG preview • original sensor data will be exported");
            else if(ultraHdrCapture) filterLabel.setText("Ultra HDR JPEG_R • original HDR gain map preserved");
            else updateReviewFilter();
        }
    }
    private void updateReviewFilter() {
        if(imageReview==null || !inReview || captureFile==null || captureFile.getName().endsWith(".mp4"))return;
        ColorMatrix matrix=new ColorMatrix();
        if(filterIndex==1)matrix.setSaturation(0);
        else if(filterIndex==4)matrix.setSaturation(1.28f);
        else if(filterIndex==2||filterIndex==3)matrix.set(new float[]{filterIndex==2?1.07f:0.94f,0,0,0,4,
            0,1,0,0,0,0,0,filterIndex==2?0.92f:1.08f,0,0,0,0,0,1,0});
        matrix.postConcat(new ColorMatrix(new float[]{1,0,0,0,brightness*68,0,1,0,0,brightness*68,
            0,0,1,0,brightness*68,0,0,0,1,0}));
        imageReview.setColorFilter(new ColorMatrixColorFilter(matrix));
        filterLabel.setText(FILTERS[Math.max(0,Math.min(4,filterIndex))]+" • brightness "+Math.round(brightness*100));
    }
    private void retake() {
        videoReview.stopPlayback();videoReview.setVisibility(View.GONE);
        imageReview.setVisibility(View.GONE);imageReview.clearColorFilter();
        if(reviewBitmap!=null){reviewBitmap.recycle();reviewBitmap=null;}
        if(captureFile!=null)captureFile.delete();captureFile=null;
        if(rawCaptureFile!=null)rawCaptureFile.delete();rawCaptureFile=null;
        inReview=false;filterIndex=0;brightness=0;
        reviewBar.setVisibility(View.GONE);topControls.setVisibility(View.VISIBLE);
        bottomPanel.setVisibility(View.VISIBLE);viewfinder.setVisibility(View.VISIBLE);
        grid.setVisibility(gridEnabled?View.VISIBLE:View.GONE);
        show("Retake • ready");
    }
    private void confirm() {
        if(!inReview||captureFile==null)return;
        reviewBar.setEnabled(false);show("Preparing private result…");
        if(captureFile.getName().endsWith(".mp4"))session.confirmVideo(captureFile);
        else if(rawCaptureFile!=null)session.confirmRaw(rawCaptureFile,captureFile);
        else if(ultraHdrCapture)session.confirmUltraHdr(captureFile);
        else session.confirmJpeg(captureFile,filterIndex,brightness,selectedAspect);
    }
    @Override public boolean onBackPressed() {
        if(isRecording)return true;
        if(morePanel!=null&&morePanel.getVisibility()==View.VISIBLE){morePanel.setVisibility(View.GONE);return true;}
        if(quickPanel!=null&&quickPanel.getVisibility()==View.VISIBLE){quickPanel.setVisibility(View.GONE);return true;}
        if(inReview){retake();return true;}
        return false;
    }
    @Override public void onResume() {
        paused = false;
        if (videoReview != null && inReview && videoReview.getVisibility() == View.VISIBLE) videoReview.start();
        if (!inReview && hasCameraPermission()) {
            if (provider == null) initializeCamera();
            else if (!previewStreaming && bindGeneration > 0) checkPreview(bindGeneration);
        }
    }
    @Override public void onPause() {
        paused = true;
        if (videoReview != null) videoReview.pause();
        if (isRecording && recording != null) recording.stop();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        out.putString("of_mode",mode.name());out.putInt("of_facing",facing);
        out.putString("of_capture",captureFile==null?null:captureFile.getAbsolutePath());
        out.putBoolean("of_review",inReview);out.putInt("of_filter",filterIndex);
        out.putFloat("of_brightness",brightness);out.putInt("of_timer",timerSeconds);
        out.putString("of_aspect",selectedAspect.name());
        out.putString("of_photoformat", photoFormat.name());
        out.putString("of_rawcapture", rawCaptureFile==null?null:rawCaptureFile.getAbsolutePath());
        out.putString("of_video_quality", videoQuality == Quality.UHD ? "UHD" : videoQuality == Quality.HD ? "HD" : "FHD");
        out.putInt("of_video_fps", videoFps);
    }
    @Override public void onDestroy() {
        destroyed=true;
        previewHandler.removeCallbacksAndMessages(null);
        if(recording!=null){recording.stop();recording=null;}
        if(provider!=null)provider.unbindAll();
        if(videoReview!=null)videoReview.stopPlayback();
        if(reviewBitmap!=null){reviewBitmap.recycle();reviewBitmap=null;}
        // Only unconfirmed raw captures are cleaned up; confirmed URIs belong to host until release.
        if(!activity.isChangingConfigurations() && captureFile!=null && captureFile.exists())captureFile.delete();
        if(!activity.isChangingConfigurations() && rawCaptureFile!=null && rawCaptureFile.exists())rawCaptureFile.delete();
    }

    /** One-shot focus reticle matching the actual tap location, not a fake permanent lock. */
    private final class FocusRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float touchX, touchY;
        private boolean visible;
        FocusRing(android.content.Context context) { super(context); setClickable(false); }
        void focusAt(float x,float y) {
            touchX=x;touchY=y;visible=true;
            animate().cancel();setAlpha(1f);invalidate();
            animate().alpha(0f).setStartDelay(1100).setDuration(330).start();
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (!visible) return;
            paint.setColor(0xFFF3F4F5);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);
            float size=dp(45), corner=dp(13), x=touchX, y=touchY;
            float left=x-size,right=x+size,top=y-size,bottom=y+size;
            for(int ix=0;ix<2;ix++)for(int iy=0;iy<2;iy++){
                float cx=ix==0?left:right,cy=iy==0?top:bottom;
                float dx=ix==0?corner:-corner,dy=iy==0?corner:-corner;
                c.drawLine(cx,cy,cx+dx,cy,paint);c.drawLine(cx,cy,cx,cy+dy,paint);
            }
            paint.setColor(ACCENT);paint.setStrokeWidth(dp(1.4f));
            c.drawLine(x+size+dp(10),y-dp(18),x+size+dp(10),y+dp(18),paint);
            c.drawCircle(x+size+dp(10),y,dp(6),paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
    private abstract static class SimpleSeekBar implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }
}
