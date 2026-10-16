package dev.openframe.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import in.advancedcamera.sdk.AdvancedCamera;
import in.advancedcamera.sdk.CameraCacheCleaner;
import in.advancedcamera.sdk.CameraRequest;
import in.advancedcamera.sdk.CameraResult;
import java.io.InputStream;
import java.io.OutputStream;

/** Standalone demonstration of the exact same library used by embedded integrations. */
public final class MainActivity extends ComponentActivity {
    private AdvancedCamera.Registration camera;
    private ActivityResultLauncher<String> documentExport;
    private Uri lastResult;
    private String lastMime;
    private TextView details;
    private ImageView image;
    private VideoView video;
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF080B0F);
        getWindow().setNavigationBarColor(0xFF080B0F);
        camera=AdvancedCamera.register(this,result->{
            if(result.status==CameraResult.Status.CANCELLED){details.setText("Capture cancelled • nothing was saved");return;}
            if(result.status==CameraResult.Status.FAILURE){details.setText("Camera error: "+result.errorCode);return;}
            lastResult=result.uri;lastMime=result.mimeType;
            details.setText("Captured • "+result.width+" × "+result.height+" • "+result.mimeType+"\nPrivate URI: "+result.uri);
            image.setVisibility(View.GONE);video.setVisibility(View.GONE);
            if(result.mimeType.startsWith("image/")){image.setVisibility(View.VISIBLE);image.setImageURI(result.uri);}
            else{video.setVisibility(View.VISIBLE);video.setVideoURI(result.uri);video.setOnPreparedListener(p->p.setLooping(true));video.start();}
        });
        documentExport=registerForActivityResult(new ActivityResultContracts.CreateDocument("*/*"),uri->{
            if(uri==null||lastResult==null)return;
            try(InputStream in=getContentResolver().openInputStream(lastResult);
                OutputStream out=getContentResolver().openOutputStream(uri)){
                if(in==null||out==null)throw new java.io.IOException("Cannot open URI");
                byte[] buf=new byte[64*1024];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                Toast.makeText(this,"Export saved",Toast.LENGTH_SHORT).show();
            }catch(Exception ex){Toast.makeText(this,"Export failed: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
        });
        CameraCacheCleaner.deleteAbandoned(this);
        buildUi();
    }
    private TextView label(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private void addGap(LinearLayout layout,int gap){View v=new View(this);layout.addView(v,new LinearLayout.LayoutParams(1,dp(gap)));}
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xFF080B0F);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(56),dp(24),dp(28));
        scroll.addView(content);setContentView(scroll);
        TextView tag=label("OPENFRAME  /  CAMERA SDK",13,0xFFDEEBAD);content.addView(tag);
        addGap(content,16);
        TextView hero=label("A camera that lives\ninside your apps.",33,Color.WHITE);
        hero.setTypeface(null,android.graphics.Typeface.BOLD);content.addView(hero);
        addGap(content,13);
        content.addView(label("Original open-source Android camera engine. Professional capture controls, private output, reusable Java integration.",15,0xFFC3C9D0));
        addGap(content,30);
        button(content,"Open full camera","Explore Photo, Video, device HDR, Portrait and Night",()->
            camera.launch(CameraRequest.builder(CameraRequest.Purpose.GENERAL)
                .allowEditing(true).allowEnhancement(true).maxDimensions(4096,4096).jpegQuality(92).build()));
        addGap(content,12);
        button(content,"Resident portrait preset","3:4 JPEG • below 2 MB • no location metadata",()->
            camera.launch(CameraRequest.tenantPhoto()));
        addGap(content,28);
        details=label("No capture yet. Photos and videos stay in private application storage until you export them.",13,0xFFCFD7DD);
        content.addView(details);
        addGap(content,18);
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(image,new LinearLayout.LayoutParams(-1,dp(290)));image.setVisibility(View.GONE);
        video=new VideoView(this);content.addView(video,new LinearLayout.LayoutParams(-1,dp(290)));video.setVisibility(View.GONE);
        addGap(content,10);
        button(content,"Export confirmed capture","Optional • user chooses where to save",()->{
            if(lastResult==null){Toast.makeText(this,"Capture something first",Toast.LENGTH_SHORT).show();return;}
            documentExport.launch("OpenFrame-"+System.currentTimeMillis()+("video/mp4".equals(lastMime)?".mp4":".jpg"));
        });
        addGap(content,14);
        content.addView(label("No automatic gallery publication. No network tracking. No OnePlus proprietary code or assets.",12,0xFF9299A4));
    }
    private void button(LinearLayout host,String title,String subtitle,Runnable onClick){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18),dp(16),dp(18),dp(16));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xFF1E242B);bg.setCornerRadius(dp(18));bg.setStroke(dp(1),0xFF343A40);
        card.setBackground(bg);
        TextView a=label(title+"  →",18,Color.WHITE);a.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(a);
        TextView b=label(subtitle,12,0xFFB4BDC6);card.addView(b);
        card.setOnClickListener(v->onClick.run());host.addView(card,new LinearLayout.LayoutParams(-1,-2));
    }
    @Override protected void onPause(){if(video!=null)video.pause();super.onPause();}
    @Override protected void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
