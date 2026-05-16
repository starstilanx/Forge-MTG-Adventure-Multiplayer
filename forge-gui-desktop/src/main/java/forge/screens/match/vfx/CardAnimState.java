package forge.screens.match.vfx;

/** Immutable snapshot of a card's current animation transform — written by CardAnimator, read by CardPanel. */
public final class CardAnimState {
    public final float dx;    // horizontal translation, pixels
    public final float dy;    // vertical translation, pixels
    public final float scale; // uniform scale factor (1.0 = normal)
    public final float alpha; // opacity (1.0 = fully opaque)

    public static final CardAnimState IDENTITY = new CardAnimState(0f, 0f, 1f, 1f);

    public CardAnimState(final float dx, final float dy, final float scale, final float alpha) {
        this.dx    = dx;
        this.dy    = dy;
        this.scale = scale;
        this.alpha = alpha;
    }

    public boolean isIdentity() {
        return dx == 0f && dy == 0f && scale == 1f && alpha == 1f;
    }
}
