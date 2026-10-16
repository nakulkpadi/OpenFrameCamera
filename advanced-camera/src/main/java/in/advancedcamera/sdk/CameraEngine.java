package in.advancedcamera.sdk;

import android.os.Bundle;
import androidx.annotation.Nullable;

/** Adapter contract for the owner's EXISTING camera implementation.
 *  Move existing engine into this library and implement this interface.
 *  Existing UI/feature implementations belong in the engine, NOT this adapter.
 */
public interface CameraEngine {
    void onCreate(CameraSession session, @Nullable Bundle savedInstanceState);
    default void onResume() { }
    default void onPause() { }
    default void onDestroy() { }
    default void onSaveInstanceState(Bundle outState) { }
    /** Return true if the engine consumed Back (e.g., closing editor or mode panel). */
    default boolean onBackPressed() { return false; }
}
