package in.advancedcamera.sdk;

import android.content.Intent;
import java.util.Objects;

/** Immutable request. The camera engine determines hardware-dependent support. */
public final class CameraRequest {
    public enum Purpose { TENANT_PHOTO, PAYMENT_PROOF, GENERAL }
    public enum Facing { FRONT, BACK }
    public enum AspectRatio { THREE_FOUR, FOUR_THREE, SQUARE, ORIGINAL }
    public enum Format { JPEG, RAW, PNG, HEIC }

    private static final String KEY = "in.advancedcamera.sdk.request.";
    public final Purpose purpose;
    public final Facing facing;
    public final AspectRatio aspectRatio;
    public final Format format;
    public final boolean allowEditing;
    public final boolean allowEnhancement;
    public final int maxWidth;
    public final int maxHeight;
    public final int jpegQuality;

    private CameraRequest(Builder b) {
        this.purpose = b.purpose;
        this.facing = b.facing;
        this.aspectRatio = b.aspectRatio;
        this.format = b.format;
        this.allowEditing = b.allowEditing;
        this.allowEnhancement = b.allowEnhancement;
        this.maxWidth = b.maxWidth;
        this.maxHeight = b.maxHeight;
        this.jpegQuality = b.jpegQuality;
    }

    public static Builder builder(Purpose purpose) { return new Builder(purpose); }
    public static CameraRequest tenantPhoto() {
        return builder(Purpose.TENANT_PHOTO).facing(Facing.BACK)
            .aspectRatio(AspectRatio.THREE_FOUR).format(Format.JPEG)
            .maxDimensions(1600, 2133).jpegQuality(85)
            .allowEditing(true).allowEnhancement(true).build();
    }

    void writeTo(Intent intent) {
        intent.putExtra(KEY + "purpose", purpose.name());
        intent.putExtra(KEY + "facing", facing.name());
        intent.putExtra(KEY + "aspect", aspectRatio.name());
        intent.putExtra(KEY + "format", format.name());
        intent.putExtra(KEY + "edit", allowEditing);
        intent.putExtra(KEY + "enhance", allowEnhancement);
        intent.putExtra(KEY + "width", maxWidth);
        intent.putExtra(KEY + "height", maxHeight);
        intent.putExtra(KEY + "quality", jpegQuality);
    }

    static CameraRequest readFrom(Intent intent) {
        if (intent == null || !intent.hasExtra(KEY + "purpose")) return null;
        try {
            return builder(Purpose.valueOf(intent.getStringExtra(KEY + "purpose")))
                .facing(Facing.valueOf(intent.getStringExtra(KEY + "facing")))
                .aspectRatio(AspectRatio.valueOf(intent.getStringExtra(KEY + "aspect")))
                .format(Format.valueOf(intent.getStringExtra(KEY + "format")))
                .allowEditing(intent.getBooleanExtra(KEY + "edit", false))
                .allowEnhancement(intent.getBooleanExtra(KEY + "enhance", false))
                .maxDimensions(intent.getIntExtra(KEY + "width", 0), intent.getIntExtra(KEY + "height", 0))
                .jpegQuality(intent.getIntExtra(KEY + "quality", 85)).build();
        } catch (RuntimeException ex) { return null; }
    }

    public static final class Builder {
        private final Purpose purpose;
        private Facing facing = Facing.BACK;
        private AspectRatio aspectRatio = AspectRatio.ORIGINAL;
        private Format format = Format.JPEG;
        private boolean allowEditing;
        private boolean allowEnhancement;
        private int maxWidth = 2048;
        private int maxHeight = 2048;
        private int jpegQuality = 85;

        private Builder(Purpose purpose) { this.purpose = Objects.requireNonNull(purpose); }
        public Builder facing(Facing v) { this.facing = Objects.requireNonNull(v); return this; }
        public Builder aspectRatio(AspectRatio v) { this.aspectRatio = Objects.requireNonNull(v); return this; }
        public Builder format(Format v) { this.format = Objects.requireNonNull(v); return this; }
        public Builder allowEditing(boolean v) { this.allowEditing = v; return this; }
        public Builder allowEnhancement(boolean v) { this.allowEnhancement = v; return this; }
        public Builder maxDimensions(int w, int h) {
            if (w < 0 || h < 0 || w > 16384 || h > 16384 || ((w == 0) != (h == 0)))
                throw new IllegalArgumentException("Both dimensions must be 0 (original), or between 1 and 16384");
            this.maxWidth = w; this.maxHeight = h; return this;
        }
        public Builder jpegQuality(int v) {
            if (v < 1 || v > 100) throw new IllegalArgumentException("JPEG quality must be 1..100");
            jpegQuality = v; return this;
        }
        public CameraRequest build() { return new CameraRequest(this); }
    }
}
