package forge.screens.match.vfx;

import forge.view.arcane.CardPanel;

import javax.swing.Timer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EDT-only singleton that drives per-card physical animations.
 * All methods must be called on the Event Dispatch Thread.
 *
 * Animations:
 *   ENTER        — card drops in and scales up with a slight overshoot bounce.
 *   ATTACK_LUNGE — card lunges toward the opponent then snaps back.
 *   DAMAGE_SHAKE — rapid decaying horizontal shake.
 *   BLOCK_SWELL  — brief scale pulse when a blocker is declared.
 *   DEATH_FADE   — card shrinks and fades out as it dies.
 */
public final class CardAnimator {

    public static final CardAnimator INSTANCE = new CardAnimator();

    public enum Type { ENTER, ATTACK_LUNGE, DAMAGE_SHAKE, BLOCK_SWELL, DEATH_FADE }

    // ---- private animation record ----

    private static final class Anim {
        final Type   type;
        final long   startNano;
        final long   durationNano;
        final float  param; // type-specific: ATTACK_LUNGE → lungeY pixels

        Anim(final Type type, final long durationMs, final float param) {
            this.type         = type;
            this.startNano    = System.nanoTime();
            this.durationNano = durationMs * 1_000_000L;
            this.param        = param;
        }

        CardAnimState compute(final float t) {
            switch (type) {
                case ENTER:        return computeEnter(t);
                case ATTACK_LUNGE: return computeLunge(t, param);
                case DAMAGE_SHAKE: return computeShake(t);
                case BLOCK_SWELL:  return computeSwell(t);
                case DEATH_FADE:   return computeDeath(t);
                default:           return CardAnimState.IDENTITY;
            }
        }
    }

    // ---- animation math ----

    private static CardAnimState computeEnter(final float t) {
        // scale: 0.55 → 1.0 with ease-out-back (slight overshoot)
        final float s = easeOutBack(t);
        final float scale = 0.55f + 0.45f * s;
        // drop-in: shift upward at start, land at 0
        final float dy = -28f * (1f - t) * (1f - t);
        // fade in
        final float alpha = Math.min(1f, t * 2.5f);
        return new CardAnimState(0f, dy, scale, alpha);
    }

    private static CardAnimState computeLunge(final float t, final float lungeY) {
        // lunge out over first 35%, return over remaining 65%
        final float lunge;
        if (t < 0.35f) {
            lunge = easeInQuad(t / 0.35f);
        } else {
            lunge = easeOutQuad(1f - (t - 0.35f) / 0.65f);
        }
        final float dy    = lungeY * lunge;
        final float scale = 1f + 0.06f * lunge;
        return new CardAnimState(0f, dy, scale, 1f);
    }

    private static CardAnimState computeShake(final float t) {
        final float decay = 1f - t;
        final float dx = 9f * (float) Math.sin(t * Math.PI * 6.5) * decay;
        return new CardAnimState(dx, 0f, 1f, 1f);
    }

    private static CardAnimState computeSwell(final float t) {
        final float s;
        if (t < 0.3f) {
            s = easeOutQuad(t / 0.3f) * 0.09f;
        } else {
            s = 0.09f * (1f - easeInQuad((t - 0.3f) / 0.7f));
        }
        return new CardAnimState(0f, 0f, 1f + s, 1f);
    }

    private static CardAnimState computeDeath(final float t) {
        final float alpha = 1f - easeInQuad(t);
        final float scale = 1f - 0.18f * t;
        return new CardAnimState(0f, 0f, scale, alpha);
    }

    // ---- easing functions (t in [0,1]) ----

    private static float easeInQuad(final float t)  { return t * t; }
    private static float easeOutQuad(final float t) { return 1f - (1f - t) * (1f - t); }

    private static float easeOutBack(final float t) {
        final float c1 = 1.70158f;
        final float c3 = c1 + 1f;
        final float u  = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    // ---- animation table and timer ----

    private final Map<CardPanel, Anim> active = new LinkedHashMap<>();
    private final Timer timer;

    private CardAnimator() {
        timer = new Timer(16, e -> tick());
        timer.setCoalesce(true);
        timer.setInitialDelay(0);
    }

    // ---- public API (EDT only) ----

    /**
     * Start an animation on the given panel, replacing any running animation of the same type.
     * @param panel    the CardPanel to animate
     * @param type     animation type
     * @param param    type-specific parameter (ATTACK_LUNGE: lunge dy in px; others: ignored)
     */
    public void play(final CardPanel panel, final Type type, final float param) {
        final long durationMs;
        switch (type) {
            case ENTER:        durationMs = 500; break;
            case ATTACK_LUNGE: durationMs = 420; break;
            case DAMAGE_SHAKE: durationMs = 360; break;
            case BLOCK_SWELL:  durationMs = 300; break;
            case DEATH_FADE:   durationMs = 550; break;
            default:           durationMs = 400; break;
        }
        active.put(panel, new Anim(type, durationMs, param));
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    /** Immediately clear any animation on this panel (e.g. when card is removed). */
    public void cancel(final CardPanel panel) {
        if (active.remove(panel) != null) {
            panel.setCardAnimState(null);
        }
        if (active.isEmpty()) {
            timer.stop();
        }
    }

    // ---- timer tick (EDT) ----

    private void tick() {
        final long now = System.nanoTime();
        final Iterator<Map.Entry<CardPanel, Anim>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<CardPanel, Anim> entry = it.next();
            final CardPanel panel = entry.getKey();
            final Anim      anim  = entry.getValue();
            final float     t     = Math.min(1f, (float)((now - anim.startNano) / (double) anim.durationNano));

            if (t >= 1f) {
                panel.setCardAnimState(null);
                panel.repaint();
                it.remove();
            } else {
                panel.setCardAnimState(anim.compute(t));
                panel.repaint();
            }
        }
        if (active.isEmpty()) {
            timer.stop();
        }
    }
}
