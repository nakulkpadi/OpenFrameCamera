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
import android.view.Gravity;
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
    private static final String[] FILTERS = {"Natural", "Mono", "Warm", "Cool", "Vivid"};
    private CameraSession session;
    private ComponentActivity activity;
    private FrameLayout root;
    private PreviewView viewfinder;
    private ImageView imageReview;
    private VideoView videoReview;
    private View grid;
    private LinearLayout topBar, bottomPanel, modeBar, filterBar, reviewBar;
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
        p.setMargins(dp(3), 0, dp(3), 0); container.addView(v, p);
    }
    private void show(String message) { status.setText(message); }
    private void buildInterface() {
        root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF06080A);
        activity.setContentView(root);
        viewfinder = new PreviewView(activity);
        viewfinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(viewfinder, new FrameLayout.LayoutParams(-1, -1));
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
        root.addView(grid, new FrameLayout.LayoutParams(-1,-1));
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
            show("Focus locked • tap again to refocus");
            return true;
        });
        topBar = new LinearLayout(activity);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(10),dp(12),dp(10),dp(12));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topParams.topMargin = dp(30);
        root.addView(topBar, topParams);
        addChip(topBar, chip("✕", v -> session.cancel()));
        flashButton = chip("⚡ Auto", v -> cycleFlash()); addChip(topBar, flashButton);
        ratioButton = chip(aspectLabel(), v -> cycleAspect()); addChip(topBar, ratioButton);
        gridButton = chip("Grid", v -> {gridEnabled=!gridEnabled;grid.setVisibility(gridEnabled?View.VISIBLE:View.GONE);}); addChip(topBar, gridButton);
        timerButton = chip("0s", v -> {timerSeconds=timerSeconds==0?3:timerSeconds==3?10:0;timerButton.setText(timerSeconds+"s");}); addChip(topBar, timerButton);
        bottomPanel = new LinearLayout(activity);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(12),dp(12),dp(12),dp(24));
        bottomPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000,0xCC05070A,0xFF05070A}));
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(bottomPanel,bottom);
        status = text("OpenFrame • ready to capture",12,false);
        status.setOnClickListener(v->{if(!hasCameraPermission()) permissionLauncher.launch(Manifest.permission.CAMERA);});
        status.setTextColor(0xFFCED2D7);
        bottomPanel.addView(status,new LinearLayout.LayoutParams(-1,dp(30)));
        zoomLabel = text("1.0×",17,true);
        bottomPanel.addView(zoomLabel,new LinearLayout.LayoutParams(-1,dp(30)));
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
        bottomPanel.addView(zoomSeek,new LinearLayout.LayoutParams(-1,dp(32)));
        LinearLayout exposureRow = new LinearLayout(activity); exposureRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView expText = text("EV",12,true); exposureRow.addView(expText,new LinearLayout.LayoutParams(dp(35),dp(30)));
        exposureSeek = new SeekBar(activity); exposureSeek.setMax(100); exposureSeek.setProgress(50);
        exposureSeek.setOnSeekBarChangeListener(new SimpleSeekBar() {
            @Override public void onProgressChanged(SeekBar b,int n,boolean user) {
                if (!user || camera == null) return;
                androidx.camera.core.ExposureState exposure = camera.getCameraInfo().getExposureState();
                if (!exposure.isExposureCompensationSupported()) return;
                android.util.Range<Integer> range = exposure.getExposureCompensationRange();
                int index = range.getLower() + Math.round((range.getUpper()-range.getLower())*n/100f);
                camera.getCameraControl().setExposureCompensationIndex(index);
            }
        });
        exposureRow.addView(exposureSeek,new LinearLayout.LayoutParams(0,dp(32),1));
        bottomPanel.addView(exposureRow,new LinearLayout.LayoutParams(-1,dp(35)));
        modeBar = new LinearLayout(activity);modeBar.setGravity(Gravity.CENTER);
        bottomPanel.addView(modeBar,new LinearLayout.LayoutParams(-1,dp(48)));
        for (Mode m : Mode.values()) {
            if (m==Mode.VIDEO && session.request().purpose==CameraRequest.Purpose.TENANT_PHOTO) continue;
            TextView button = text(m.name(), m.name().length()>5?10:12, m==mode);
            button.setPadding(dp(6),dp(5),dp(6),dp(5));
            button.setOnClickListener(v -> setMode(m));
            modeBar.addView(button,new LinearLayout.LayoutParams(0,dp(40),1));
        }
        LinearLayout captureRow = new LinearLayout(activity); captureRow.setGravity(Gravity.CENTER);
        TextView flip = chip("⟳ Flip", v -> flipCamera());
        captureRow.addView(flip,new LinearLayout.LayoutParams(0,dp(52),1));
        TextView shutter = text("●",59,true);
        shutter.setTextColor(0xFFFFFFFF);
        shutter.setContentDescription("Capture photo or start and stop video recording");
        shutter.setBackground(background(0x55FFFFFF, 100, 0xFFFFFFFF));
        LinearLayout.LayoutParams shutterParams = new LinearLayout.LayoutParams(dp(86),dp(86));
        shutterParams.setMargins(dp(22),dp(6),dp(22),dp(6));
        captureRow.addView(shutter,shutterParams);
        shutter.setOnClickListener(v -> onShutter(shutter));
        TextView filters = chip("Filters", v -> {
            if (inReview) return;
            show("Filters are available after capture • natural preview remains unchanged");
        });
        captureRow.addView(filters,new LinearLayout.LayoutParams(0,dp(52),1));
        bottomPanel.addView(captureRow,new LinearLayout.LayoutParams(-1,dp(105)));
        // Editor is only visible after an actual capture; edits are applied at JPEG finalization.
        imageReview = new ImageView(activity); imageReview.setBackgroundColor(Color.BLACK);
        imageReview.setScaleType(ImageView.ScaleType.FIT_CENTER); imageReview.setVisibility(View.GONE);
        root.addView(imageReview,new FrameLayout.LayoutParams(-1,-1));
        videoReview = new VideoView(activity); videoReview.setVisibility(View.GONE);
        root.addView(videoReview,new FrameLayout.LayoutParams(-1,-1));
        reviewBar = new LinearLayout(activity); reviewBar.setOrientation(LinearLayout.VERTICAL);
        reviewBar.setPadding(dp(12),dp(14),dp(12),dp(24));
        reviewBar.setBackground(background(0xF0101216,18,0));
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
        confirm.setBackground(background(0xFFE4F2B5,27,0));confirm.setTextColor(Color.BLACK);
        actions.addView(retake,new LinearLayout.LayoutParams(0,dp(56),1));
        actions.addView(confirm,new LinearLayout.LayoutParams(0,dp(56),1));
        reviewBar.addView(actions,new LinearLayout.LayoutParams(-1,dp(60)));
        reviewBar.setVisibility(View.GONE);
        refreshModeChips();
    }
    private String aspectLabel() {
        switch (selectedAspect) {
            case THREE_FOUR: return "3:4";
            case FOUR_THREE: return "4:3";
            case SQUARE: return "1:1";
            default:return "Original";
        }
    }
    private void cycleAspect() {
        if (session.request().purpose==CameraRequest.Purpose.TENANT_PHOTO) {show("Resident photos use a fixed 3:4 crop");return;}
        switch(selectedAspect) {
            case THREE_FOUR:selectedAspect=CameraRequest.AspectRatio.FOUR_THREE;break;
            case FOUR_THREE:selectedAspect=CameraRequest.AspectRatio.SQUARE;break;
            case SQUARE:selectedAspect=CameraRequest.AspectRatio.ORIGINAL;break;
            default:selectedAspect=CameraRequest.AspectRatio.THREE_FOUR;
        }
        ratioButton.setText(aspectLabel());
        show("Crop applied to final photo • preview uses full sensor");
    }
    private void cycleFlash() {
        flashMode = flashMode==ImageCapture.FLASH_MODE_AUTO?ImageCapture.FLASH_MODE_ON
                :flashMode==ImageCapture.FLASH_MODE_ON?ImageCapture.FLASH_MODE_OFF:ImageCapture.FLASH_MODE_AUTO;
        flashButton.setText(flashMode==ImageCapture.FLASH_MODE_AUTO?"⚡ Auto":flashMode==ImageCapture.FLASH_MODE_ON?"⚡ On":"⚡ Off");
        if(imageCapture != null) imageCapture.setFlashMode(flashMode);
        if(mode==Mode.VIDEO && camera!=null) {
            torchEnabled = flashMode==ImageCapture.FLASH_MODE_ON;
            camera.getCameraControl().enableTorch(torchEnabled);
        }
    }
    private void flipCamera() {
        if (isRecording || inReview || busy) return;
        facing=facing==CameraSelector.LENS_FACING_BACK?CameraSelector.LENS_FACING_FRONT:CameraSelector.LENS_FACING_BACK;
        // OEM extensions often have separate per-facing support.
        if(mode!=Mode.PHOTO && mode!=Mode.VIDEO) mode=Mode.PHOTO;
        refreshModeChips();
        bindCamera();
    }
    private void setMode(Mode next) {
        if(busy || isRecording || inReview) return;
        if (next==Mode.VIDEO && session.request().purpose==CameraRequest.Purpose.TENANT_PHOTO) return;
        if(next!=Mode.PHOTO && next!=Mode.VIDEO) {
            int extension = extensionMode(next);
            CameraSelector selector = facing==CameraSelector.LENS_FACING_FRONT?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;
            if(extensions==null || !extensions.isExtensionAvailable(selector,extension)) {
                show(next.name()+" unavailable on this device / lens"); return;
            }
        }
        mode=next;
        refreshModeChips();
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
            chip.setTextColor(selected?0xFFE4F2B5:0xFFD7DBDF);
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
        if(destroyed || !hasCameraPermission() || provider!=null) return;
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(activity);
        future.addListener(()->{
            if(destroyed)return;
            try {
                provider=future.get();bindCamera();
                ListenableFuture<ExtensionsManager> extFuture=ExtensionsManager.getInstanceAsync(activity,provider);
                extFuture.addListener(()->{
                    if(destroyed)return;
                    try {extensions=extFuture.get(); if (mode!=Mode.PHOTO&&mode!=Mode.VIDEO)bindCamera();}
                    catch(Exception ex){ android.util.Log.w("OpenFrame","Extensions unavailable",ex); }
                },ContextCompat.getMainExecutor(activity));
            }catch(Exception ex){show("Camera unavailable: "+ex.getClass().getSimpleName());}
        },ContextCompat.getMainExecutor(activity));
    }
    private void bindCamera() {
        if(provider==null || destroyed || !hasCameraPermission())return;
        try {
            provider.unbindAll();
            CameraSelector selector=facing==CameraSelector.LENS_FACING_FRONT?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;
            if(mode!=Mode.VIDEO && mode!=Mode.PHOTO && extensions!=null && extensions.isExtensionAvailable(selector,extensionMode(mode)))
                selector=extensions.getExtensionEnabledCameraSelector(selector,extensionMode(mode));
            Preview preview=new Preview.Builder().build();
            preview.setSurfaceProvider(viewfinder.getSurfaceProvider());
            if(mode==Mode.VIDEO) {
                Recorder recorder=new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build();
                videoCapture=VideoCapture.withOutput(recorder);
                imageCapture=null;
                camera=provider.bindToLifecycle(activity,selector,preview,videoCapture);
            } else {
                imageCapture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode).build();
                videoCapture=null;
                camera=provider.bindToLifecycle(activity,selector,preview,imageCapture);
            }
            ZoomState z=camera.getCameraInfo().getZoomState().getValue();
            if(z!=null){zoomSeek.setEnabled(z.getMaxZoomRatio()>z.getMinZoomRatio());zoomLabel.setText(String.format(java.util.Locale.US,"%.1f×",z.getZoomRatio()));}
            exposureSeek.setEnabled(camera.getCameraInfo().getExposureState().isExposureCompensationSupported());
            show(mode.name()+" • tap anywhere to focus");
        }catch(Exception ex){
            android.util.Log.e("OpenFrame","Camera bind failed",ex);
            Mode failed = mode;
            mode=Mode.PHOTO;
            refreshModeChips();
            if(failed!=Mode.PHOTO) bindCamera();
            else show("Camera unavailable on this lens");
        }
    }
    private void onShutter(TextView button) {
        if (inReview || busy) return;
        if(mode==Mode.VIDEO) {
            if(isRecording) {if(recording!=null)recording.stop();button.setText("●");}
            else beginVideo(button);
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
        try {captureFile=session.createPrivateCaptureFile();}
        catch(IOException e){busy=false;show("Cannot create private capture file");return;}
        ImageCapture.OutputFileOptions options=new ImageCapture.OutputFileOptions.Builder(captureFile).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(activity),new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults result) {
                busy=false;if(!destroyed)openReview();
            }
            @Override public void onError(ImageCaptureException e) {
                busy=false;if(captureFile!=null)captureFile.delete();
                show("Photo failed: "+e.getMessage());
            }
        });
    }
    private void beginVideo(TextView button) {
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
                if(event instanceof VideoRecordEvent.Start){isRecording=true;button.setText("■");show("Recording • tap square to stop");}
                if(event instanceof VideoRecordEvent.Finalize){
                    isRecording=false;recording=null;button.setText("●");
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
        topBar.setVisibility(View.GONE);bottomPanel.setVisibility(View.GONE);
        reviewBar.setVisibility(View.VISIBLE);
        boolean video=captureFile.getName().endsWith(".mp4");
        boolean canFilter=!video && session.request().allowEditing && session.request().purpose!=CameraRequest.Purpose.TENANT_PHOTO;
        boolean canEnhance=!video && session.request().allowEnhancement;
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
            updateReviewFilter();
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
        inReview=false;filterIndex=0;brightness=0;
        reviewBar.setVisibility(View.GONE);topBar.setVisibility(View.VISIBLE);
        bottomPanel.setVisibility(View.VISIBLE);viewfinder.setVisibility(View.VISIBLE);
        grid.setVisibility(gridEnabled?View.VISIBLE:View.GONE);
        show("Retake • ready");
    }
    private void confirm() {
        if(!inReview||captureFile==null)return;
        reviewBar.setEnabled(false);show("Preparing private result…");
        if(captureFile.getName().endsWith(".mp4"))session.confirmVideo(captureFile);
        else session.confirmJpeg(captureFile,filterIndex,brightness,selectedAspect);
    }
    @Override public boolean onBackPressed() { if(isRecording)return true; if(inReview){retake();return true;}return false; }
    @Override public void onResume() { if(videoReview!=null&&inReview&&videoReview.getVisibility()==View.VISIBLE)videoReview.start(); }
    @Override public void onPause() { if(videoReview!=null)videoReview.pause();if(isRecording&&recording!=null)recording.stop(); }
    @Override public void onSaveInstanceState(Bundle out) {
        out.putString("of_mode",mode.name());out.putInt("of_facing",facing);
        out.putString("of_capture",captureFile==null?null:captureFile.getAbsolutePath());
        out.putBoolean("of_review",inReview);out.putInt("of_filter",filterIndex);
        out.putFloat("of_brightness",brightness);out.putInt("of_timer",timerSeconds);
        out.putString("of_aspect",selectedAspect.name());
    }
    @Override public void onDestroy() {
        destroyed=true;
        if(recording!=null){recording.stop();recording=null;}
        if(provider!=null)provider.unbindAll();
        if(videoReview!=null)videoReview.stopPlayback();
        if(reviewBitmap!=null){reviewBitmap.recycle();reviewBitmap=null;}
        // Only unconfirmed raw captures are cleaned up; confirmed URIs belong to host until release.
        if(!activity.isChangingConfigurations() && captureFile!=null && captureFile.exists())captureFile.delete();
    }
    private abstract static class SimpleSeekBar implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }
}
