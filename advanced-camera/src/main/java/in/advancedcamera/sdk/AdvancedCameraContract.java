package in.advancedcamera.sdk;

import android.content.Context;
import android.content.Intent;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AdvancedCameraContract extends ActivityResultContract<CameraRequest, CameraResult> {
    @NonNull @Override public Intent createIntent(@NonNull Context context, @NonNull CameraRequest request) {
        Intent intent = new Intent(context, AdvancedCameraActivity.class);
        request.writeTo(intent);
        return intent;
    }
    @Override public CameraResult parseResult(int resultCode, @Nullable Intent data) {
        return CameraResult.readFrom(data, resultCode);
    }
}
