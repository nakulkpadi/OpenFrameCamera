package in.advancedcamera.sdk;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An isolated, capability-checked Camera2 recording path for 8K/24.
 * Never changes the ordinary CameraX picture/video engine or SDK result contract.
 * No OEM libraries, hidden APIs or privileged permission requirements.
 *
 * A device's stock app may have OEM-only 8K profiles which are not public:
 * in that case probe() returns false and this recorder does not run.
 */
final class Camera2HighResRecorder {
    interface Listener {
        void onRecording();
        void onFinished(File output);
        void onFailure(String reason);
    }

    private static final String TAG = "OpenFrame8K";
    static final int WIDTH = 7680, HEIGHT = 4320, FPS = 24;

    static boolean canRecord8K(Context context, String id) {
        if (android.os.Build.VERSION.SDK_INT < 31 || id == null) return false;
        try {
            CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c=manager.getCameraCharacteristics(id);
            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            boolean sizeAvailable=false;
            if(map!=null) {
                Size[] sizes=map.getOutputSizes(MediaRecorder.class);
                if(sizes!=null)for(Size size:sizes) {
                    if(size.getWidth()==WIDTH && size.getHeight()==HEIGHT) {
                        sizeAvailable=true;break;
                    }
                }
            }
            if(!sizeAvailable) return false;
            Range<Integer>[] fps=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            boolean fpsAvailable=false;
            if(fps!=null)for(Range<Integer> range:fps) {
                if(range.getLower()<=FPS && range.getUpper()>=FPS) {
                    fpsAvailable=true;break;
                }
            }
            if(!fpsAvailable)return false;
            // Encoder and stream sizes must both be publicly advertised.
            MediaCodecList codecs=new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for(MediaCodecInfo codec:codecs.getCodecInfos()){
                if(!codec.isEncoder())continue;
                for(String type:codec.getSupportedTypes()){
                    if(!MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(type))continue;
                    try{
                        MediaCodecInfo.VideoCapabilities caps=codec.getCapabilitiesForType(type).getVideoCapabilities();
                        if(caps.areSizeAndRateSupported(WIDTH,HEIGHT,FPS))return true;
                    }catch(RuntimeException ignored){}
                }
            }
        }catch(Exception error){
            Log.w(TAG,"8K capability probe failed",error);
        }
        return false;
    }

    private final Activity activity;
    private final String cameraId;
    private final TextureView preview;
    private final File output;
    private final Listener listener;
    private final boolean microphone;
    private final Handler main=new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler worker;
    private CameraDevice device;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private Surface previewSurface;
    private Surface recordingSurface;
    private boolean started, finished, stopRequested;

    Camera2HighResRecorder(Activity activity, String cameraId,
                            TextureView preview, File output, boolean microphone,
                            Listener listener){
        this.activity=activity;
        this.cameraId=cameraId;
        this.preview=preview;
        this.output=output;
        this.microphone=microphone;
        this.listener=listener;
    }

    void start(){
        if(!canRecord8K(activity,cameraId)){
            main.post(()->listener.onFailure("Android does not expose a compatible 8K/24 Camera2 stream and HEVC encoder"));
            return;
        }
        thread=new HandlerThread("openframe-camera2-8k");
        thread.start();
        worker=new Handler(thread.getLooper());
        if(preview.isAvailable())worker.post(this::startWithSurface);
        else preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){
                preview.setSurfaceTextureListener(null);
                if(worker!=null)worker.post(()->startWithSurface());
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){abort();return true;}
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        });
    }

    @SuppressWarnings("MissingPermission")
    private void startWithSurface(){
        if(finished)return;
        try{
            SurfaceTexture texture=preview.getSurfaceTexture();
            if(texture==null)throw new IllegalStateException("Video preview surface unavailable");
            texture.setDefaultBufferSize(1920,1080);
            previewSurface=new Surface(texture);
            recorder=new MediaRecorder(activity);
            if(microphone && ContextCompat.checkSelfPermission(activity,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
            recorder.setVideoSize(WIDTH,HEIGHT);
            recorder.setVideoFrameRate(FPS);
            recorder.setVideoEncodingBitRate(90_000_000);
            if(microphone && ContextCompat.checkSelfPermission(activity,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioChannels(1);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
            }
            recorder.setOutputFile(output.getAbsolutePath());
            recorder.setOrientationHint(orientationHint());
            recorder.prepare();
            recordingSurface=recorder.getSurface();
            CameraManager manager=(CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
            if(ContextCompat.checkSelfPermission(activity,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Camera permission revoked");
            manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice opened){
                    if(finished){opened.close();return;}
                    device=opened;
                    configure();
                }
                @Override public void onDisconnected(CameraDevice opened){
                    opened.close();fail("Camera disconnected");
                }
                @Override public void onError(CameraDevice opened,int code){
                    opened.close();fail("Camera2 open error "+code);
                }
            },worker);
        }catch(Exception ex){
            Log.e(TAG,"8K setup failed",ex);
            fail("8K session could not start: "+ex.getClass().getSimpleName());
        }
    }

    private int orientationHint(){
        try{
            CameraManager manager=(CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
            Integer sensor=manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION);
            int display=activity.getWindowManager().getDefaultDisplay().getRotation()*90;
            return ((sensor==null?90:sensor)-display+360)%360;
        }catch(Exception ignored){return 90;}
    }

    private void configure(){
        try{
            if(device==null || previewSurface==null || recordingSurface==null)throw new IllegalStateException("No recording surface");
            List<Surface> surfaces=Arrays.asList(previewSurface,recordingSurface);
            device.createCaptureSession(surfaces,new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession configured){
                    if(finished){configured.close();return;}
                    session=configured;
                    try{
                        CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        request.addTarget(previewSurface);
                        request.addTarget(recordingSurface);
                        request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
                        request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        // An AE interval that includes 24fps is safer than forcing [24,24]
                        // when the device advertises only [15,30] or [24,30].
                        Range<Integer>[] ranges=((CameraManager)activity.getSystemService(Context.CAMERA_SERVICE))
                            .getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        if(ranges!=null){
                            Range<Integer> chosen=null;
                            for(Range<Integer> range:ranges){
                                if(range.getLower()<=FPS&&range.getUpper()>=FPS){
                                    if(chosen==null || (range.getUpper()-range.getLower()) <
                                            (chosen.getUpper()-chosen.getLower())) chosen=range;
                                }
                            }
                            if(chosen!=null)request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,chosen);
                        }
                        configured.setRepeatingRequest(request.build(),null,worker);
                        recorder.start();
                        started=true;
                        main.post(()->listener.onRecording());
                        if(stopRequested)stop();
                    }catch(Exception ex){
                        Log.e(TAG,"8K capture start failed",ex);
                        fail("8K encode/start failed: "+ex.getClass().getSimpleName());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured){
                    configured.close();fail("Camera rejected 8K preview + record streams");
                }
            },worker);
        }catch(Exception ex){Log.e(TAG,"Session creation failed",ex);fail("Cannot create 8K session");}
    }

    void stop(){
        stopRequested=true;
        if(worker==null)return;
        worker.post(()->{
            if(finished || !started)return;
            try{
                recorder.stop();
                finished=true;
                closeCamera();
                File result=output.isFile()&&output.length()>1024?output:null;
                main.post(()->{
                    if(result!=null)listener.onFinished(result);
                    else listener.onFailure("8K output is empty");
                });
                if(result==null)output.delete();
            }catch(RuntimeException ex){
                Log.w(TAG,"Stopping 8K recorder failed",ex);
                fail("8K recording stopped without a valid video");
            }
        });
    }
    void abort(){
        if(worker!=null)worker.post(()->{
            if(finished)return;
            finished=true;
            if(started)try{recorder.stop();}catch(RuntimeException ignored){}
            closeCamera();
            output.delete();
        });
        else output.delete();
    }
    private void fail(String reason){
        if(finished)return;
        finished=true;
        closeCamera();
        output.delete();
        main.post(()->listener.onFailure(reason));
    }
    private void closeCamera(){
        if(session!=null){try{session.stopRepeating();}catch(Exception ignored){}session.close();session=null;}
        if(device!=null){device.close();device=null;}
        if(recordingSurface!=null){recordingSurface.release();recordingSurface=null;}
        if(previewSurface!=null){previewSurface.release();previewSurface=null;}
        if(recorder!=null){recorder.reset();recorder.release();recorder=null;}
        if(thread!=null){thread.quitSafely();thread=null;worker=null;}
    }
}