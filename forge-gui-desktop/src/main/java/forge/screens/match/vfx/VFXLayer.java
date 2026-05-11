package forge.screens.match.vfx;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    private volatile VFXRenderer renderer;
    private long lastLabelUpdate = System.nanoTime();

    // ---- construction ----

    public VFXLayer() {
        setOpaque(false);
        setFocusable(false);
    }

    // ---- public API ----

    void setRenderer(final VFXRenderer r) {
        this.renderer = r;
    }

    /** Any thread: queue a floating damage/life number at screen coordinates. */
    void addFloatingLabel(final String text, final Color color, final float x, final float y) {
        incoming.offer(new FloatingLabel(text, color, x, y));
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

        // update and draw floating labels
        advanceLabels(g2);
    }

    // ---- private helpers ----

    private void advanceLabels(final Graphics2D g2) {
        final long now = System.nanoTime();
        final float dt = Math.min((now - lastLabelUpdate) / 1_000_000_000f, 0.1f);
        lastLabelUpdate = now;

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
