package forge.screens.match.vfx;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Transparent Swing panel rendered on top of the match field.
 * The GL thread calls repaintFromGL() when a new frame is ready;
 * the EDT paints the BufferedImage plus any floating damage labels.
 *
 * Mouse events pass through because contains() always returns false.
 */
public final class VFXLayer extends JPanel {

    private static final long serialVersionUID = 1L;

    // ---- floating damage numbers ----

    private static final float LABEL_LIFE   = 1.2f;  // seconds
    private static final float LABEL_RISE   = 60f;   // px/s upward drift
    private static final Font  LABEL_FONT   = new Font("SansSerif", Font.BOLD, 22);
    private static final Color LABEL_SHADOW = new Color(0, 0, 0, 180);

    private static final class FloatingLabel {
        final String text;
        final Color  color;
        float x, y;
        float age; // seconds since spawn

        FloatingLabel(final String text, final Color color, final float x, final float y) {
            this.text  = text;
            this.color = color;
            this.x     = x;
            this.y     = y;
        }
    }

    private final ConcurrentLinkedQueue<FloatingLabel> incoming = new ConcurrentLinkedQueue<>();
    private final List<FloatingLabel>                  labels   = new ArrayList<>();

    // ---- card overlay glows ----

    public enum OverlayType { DAMAGE_FLASH, ENTER_GLOW, ATTACK_GLOW, BLOCK_GLOW }

    private static final class CardOverlay {
        final OverlayType        type;
        final Supplier<Rectangle> bounds; // screen→layer coords, polled each frame
        final Color               color;
        final float               life;   // total seconds
        float age;

        CardOverlay(final OverlayType type, final Supplier<Rectangle> bounds,
                    final Color color, final float life) {
            this.type   = type;
            this.bounds = bounds;
            this.color  = color;
            this.life   = life;
        }
    }

    private final ConcurrentLinkedQueue<CardOverlay> incomingOverlays = new ConcurrentLinkedQueue<>();
    private final List<CardOverlay>                  overlays         = new ArrayList<>();

    private volatile VFXRenderer renderer;
    private long lastLabelUpdate = System.nanoTime();

    // ---- construction ----

    public VFXLayer() {
        setOpaque(false);
        setFocusable(false);
    }

    // ---- public API ----

    public void setRenderer(final VFXRenderer r) {
        this.renderer = r;
    }

    /** Any thread: queue a floating damage/life number at screen coordinates. */
    void addFloatingLabel(final String text, final Color color, final float x, final float y) {
        incoming.offer(new FloatingLabel(text, color, x, y));
    }

    /**
     * Any thread: add a card overlay glow/flash.
     * @param bounds  Supplier polled on the EDT each paint frame to get layer-relative bounds.
     */
    public void addCardOverlay(final OverlayType type, final Supplier<Rectangle> bounds,
                               final Color color, final float lifeSecs) {
        incomingOverlays.offer(new CardOverlay(type, bounds, color, lifeSecs));
        SwingUtilities.invokeLater(this::repaint);
    }

    /** Called by the GL thread when a new particle frame is ready. */
    void repaintFromGL() {
        SwingUtilities.invokeLater(this::repaint);
    }

    // ---- Swing overrides ----

    /** Pass all mouse events through to whatever is below us. */
    @Override
    public boolean contains(final int x, final int y) {
        return false;
    }

    @Override
    protected void paintComponent(final Graphics g) {
        // deliberately do NOT call super.paintComponent — we are transparent

        final Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,           RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,       RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // draw particle frame
        final VFXRenderer r = renderer;
        if (r != null) {
            final BufferedImage frame = r.getDisplayImage();
            if (frame != null) {
                // additive-looking composite: use SRC_OVER — additive blend is done GPU-side
                g2.drawImage(frame, 0, 0, getWidth(), getHeight(), null);
            }
        }

        // compute dt once for both advance passes
        final long now = System.nanoTime();
        final float dt = Math.min((now - lastLabelUpdate) / 1_000_000_000f, 0.1f);
        lastLabelUpdate = now;

        // update and draw card overlays
        advanceOverlays(g2, dt);

        // update and draw floating labels
        advanceLabels(g2, dt);
    }

    // ---- private helpers ----

    private void advanceOverlays(final Graphics2D g2, final float dt) {
        CardOverlay co;
        while ((co = incomingOverlays.poll()) != null) {
            overlays.add(co);
        }
        if (overlays.isEmpty()) { return; }

        final Composite origComp = g2.getComposite();
        final Stroke    origStroke = g2.getStroke();

        final Iterator<CardOverlay> it = overlays.iterator();
        while (it.hasNext()) {
            co = it.next();
            co.age += dt;
            if (co.age >= co.life) { it.remove(); continue; }

            final Rectangle r = co.bounds.get();
            if (r == null || r.width <= 0 || r.height <= 0) { continue; }

            final float t = co.age / co.life;
            final float alpha = computeOverlayAlpha(co.type, t);
            if (alpha <= 0f) { continue; }

            final int arc = Math.max(6, r.width / 10);

            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    Math.max(0f, Math.min(1f, alpha))));

            switch (co.type) {
                case DAMAGE_FLASH: {
                    // solid fill with the glow color
                    g2.setColor(co.color);
                    g2.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);
                    break;
                }
                case ENTER_GLOW:
                case ATTACK_GLOW:
                case BLOCK_GLOW: {
                    // layered border glow: draw three concentric borders shrinking inward
                    g2.setStroke(new BasicStroke(5f));
                    g2.setColor(co.color.darker());
                    g2.drawRoundRect(r.x - 3, r.y - 3, r.width + 6, r.height + 6, arc + 4, arc + 4);
                    g2.setStroke(new BasicStroke(3f));
                    g2.setColor(co.color);
                    g2.drawRoundRect(r.x, r.y, r.width, r.height, arc, arc);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.setColor(co.color.brighter());
                    g2.drawRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, arc - 2, arc - 2);
                    break;
                }
                default: break;
            }
        }

        g2.setComposite(origComp);
        g2.setStroke(origStroke);
    }

    private static float computeOverlayAlpha(final OverlayType type, final float t) {
        switch (type) {
            case DAMAGE_FLASH:
                // flash in fast, decay
                return t < 0.15f ? t / 0.15f * 0.7f : 0.7f * (1f - (t - 0.15f) / 0.85f);
            case ENTER_GLOW:
                // pulse: 0→peak→0
                return (float) Math.sin(t * Math.PI) * 0.85f;
            case ATTACK_GLOW:
                // hold bright, then fade
                return t < 0.6f ? 0.9f : 0.9f * (1f - (t - 0.6f) / 0.4f);
            case BLOCK_GLOW:
                // quick flash in, slow fade
                return t < 0.2f ? t / 0.2f * 0.8f : 0.8f * (1f - (t - 0.2f) / 0.8f);
            default:
                return 0f;
        }
    }

    private void advanceLabels(final Graphics2D g2, final float dt) {
        // drain incoming
        FloatingLabel fl;
        while ((fl = incoming.poll()) != null) {
            labels.add(fl);
        }

        if (labels.isEmpty()) { return; }

        g2.setFont(LABEL_FONT);
        final FontMetrics fm = g2.getFontMetrics();

        final Iterator<FloatingLabel> it = labels.iterator();
        while (it.hasNext()) {
            fl = it.next();
            fl.age += dt;
            if (fl.age >= LABEL_LIFE) { it.remove(); continue; }

            fl.y -= LABEL_RISE * dt;

            final float ratio = fl.age / LABEL_LIFE;
            // fade out in last 40% of life
            final float alpha = ratio < 0.6f ? 1f : 1f - ((ratio - 0.6f) / 0.4f);

            final int px = (int) fl.x - fm.stringWidth(fl.text) / 2;
            final int py = (int) fl.y;

            final Composite orig = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));

            // drop shadow
            g2.setColor(LABEL_SHADOW);
            g2.drawString(fl.text, px + 2, py + 2);

            // colored text
            g2.setColor(fl.color);
            g2.drawString(fl.text, px, py);

            g2.setComposite(orig);
        }
    }
}
