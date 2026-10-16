package in.advancedcamera.sdk;
import android.content.Intent;
import android.net.Uri;
public class SmokeTest {
 private static void require(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  CameraRequest src=CameraRequest.tenantPhoto();
  Intent i=new Intent();src.writeTo(i);
  CameraRequest dst=CameraRequest.readFrom(i);
  require(dst!=null && dst.purpose==CameraRequest.Purpose.TENANT_PHOTO && dst.jpegQuality==85 && dst.maxWidth==1600);
  require(dst.facing==CameraRequest.Facing.BACK && dst.aspectRatio==CameraRequest.AspectRatio.THREE_FOUR);
  CameraResult good=CameraResult.success(Uri.parse("content://in.sundarwadi.rent.advancedcamera.fileprovider/confirmed_camera/final-a.jpg"), "image/jpeg", 1500, 2000);
  Intent out=new Intent();good.writeTo(out);
  CameraResult read=CameraResult.readFrom(out, android.app.Activity.RESULT_OK);
  require(read.status==CameraResult.Status.SUCCESS && read.width==1500 && read.mimeType.equals("image/jpeg"));
  require(CameraResult.readFrom(null,android.app.Activity.RESULT_CANCELED).status==CameraResult.Status.CANCELLED);
  try {CameraResult.success(Uri.parse("file:///sdcard/tmp.jpg"),"image/jpeg",1,1);throw new AssertionError();}
  catch(IllegalArgumentException expected){}
  try {CameraRequest.builder(CameraRequest.Purpose.GENERAL).jpegQuality(150);throw new AssertionError();}
  catch(IllegalArgumentException expected){}
  System.out.println("PASS: Request serialization; typed tenant preset; success/cancel result; rejects file://; JPEG bounds");
 }
}
