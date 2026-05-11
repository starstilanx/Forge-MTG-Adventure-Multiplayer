package forge.screens.match.vfx;

import forge.card.ColorSet;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;

import java.util.Random;

/** Factory for predefined particle effects triggered by game events. */
final class VFXEffects {
    private static final Random RNG = new Random();

    private VFXEffects() { }

    // -------- effect presets --------

    static void spellCast(final ParticleSystem ps, final float x, final float y,
            final float r, final float g, final float b) {
        radialBurst(ps, x, y, r, g, b, 70, 150f, 450f, 6f, 18f, 0.5f, 1.0f, 0f);
    }

    static void spellResolve(final ParticleSystem ps, final float x, final float y,
            final float r, final float g, final float b) {
        radialBurst(ps, x, y, r, g, b, 100, 100f, 550f, 8f, 24f, 0.7f, 1.3f, 0f);
        ring(ps, x, y, r, g, b, 40, 140f, 0.9f, 1.3f);
    }

    static void cardDamage(final ParticleSystem ps, final float x, final float y) {
        radialBurst(ps, x, y, 1f, 0.15f, 0.05f, 50, 200f, 650f, 4f, 12f, 0.3f, 0.7f, 90f);
    }

    static void playerDamage(final ParticleSystem ps, final float x, final float y) {
        radialBurst(ps, x, y, 1f, 0.05f, 0.05f, 80, 150f, 500f, 6f, 20f, 0.5f, 1.1f, 60f);
    }

    static void cardEnterBattlefield(final ParticleSystem ps, final float x, final float y,
            final float r, final float g, final float b) {
        ring(ps, x, y, r, g, b, 50, 120f, 0.7f, 1.2f);
        radialBurst(ps, x, y, r, g, b, 30, 50f, 250f, 4f, 10f, 0.5f, 0.9f, -30f);
    }

    static void cardDestroyed(final ParticleSystem ps, final float x, final float y) {
        radialBurst(ps, x, y, 0.45f, 0.1f, 0.55f, 60, 80f, 380f, 3f, 11f, 0.5f, 1.0f, 180f);
    }

    static void tokenCreated(final ParticleSystem ps, final float x, final float y,
            final float r, final float g, final float b) {
        ring(ps, x, y, r, g, b, 36, 100f, 0.5f, 0.9f);
    }

    // -------- color helpers --------

    /** Returns an RGB triple (values 0..1) representing the card's mana color identity. */
    static float[] colorForCard(final CardView card) {
        if (card == null) { return new float[]{0.8f, 0.8f, 0.9f}; }
        final CardStateView state = card.getCurrentState();
        if (state == null) { return new float[]{0.8f, 0.8f, 0.9f}; }
        final ColorSet colors = state.getColors();
        if (colors == null || colors.isColorless()) { return new float[]{0.75f, 0.75f, 0.85f}; }
        if (colors.countColors() > 1)  { return new float[]{1.00f, 0.82f, 0.20f}; } // gold
        if (colors.hasWhite())         { return new float[]{1.00f, 0.95f, 0.80f}; }
        if (colors.hasBlue())          { return new float[]{0.25f, 0.65f, 1.00f}; }
        if (colors.hasBlack())         { return new float[]{0.60f, 0.15f, 0.75f}; }
        if (colors.hasRed())           { return new float[]{1.00f, 0.35f, 0.10f}; }
        if (colors.hasGreen())         { return new float[]{0.15f, 0.85f, 0.25f}; }
        return new float[]{0.80f, 0.80f, 0.90f};
    }

    // -------- internal emitters --------

    private static void radialBurst(final ParticleSystem ps,
            final float x, final float y,
            final float r, final float g, final float b,
            final int count,
            final float speedMin, final float speedMax,
            final float sizeMin,  final float sizeMax,
            final float lifeMin,  final float lifeMax,
            final float gravity) {
        for (int i = 0; i < count; i++) {
            final Particle p = new Particle();
            final float angle = RNG.nextFloat() * (float) (Math.PI * 2.0);
            final float speed = speedMin + RNG.nextFloat() * (speedMax - speedMin);
            p.x = x + (RNG.nextFloat() - 0.5f) * 20f;
            p.y = y + (RNG.nextFloat() - 0.5f) * 20f;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed;
            p.r = clamp(r + (RNG.nextFloat() - 0.5f) * 0.15f);
            p.g = clamp(g + (RNG.nextFloat() - 0.5f) * 0.15f);
            p.b = clamp(b + (RNG.nextFloat() - 0.5f) * 0.15f);
            p.a = 0.55f + RNG.nextFloat() * 0.45f;
            p.size = sizeMin + RNG.nextFloat() * (sizeMax - sizeMin);
            p.life = lifeMin + RNG.nextFloat() * (lifeMax - lifeMin);
            p.maxLife = p.life;
            p.gravity = gravity;
            ps.addParticle(p);
        }
    }

    private static void ring(final ParticleSystem ps,
            final float x, final float y,
            final float r, final float g, final float b,
            final int count, final float radius,
            final float lifeMin, final float lifeMax) {
        final float avgLife = (lifeMin + lifeMax) * 0.5f;
        final float speed   = radius / avgLife;
        for (int i = 0; i < count; i++) {
            final Particle p = new Particle();
            final float angle = (float) (Math.PI * 2.0 * i / count);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed;
            p.r = r;
            p.g = g;
            p.b = b;
            p.a = 0.85f + RNG.nextFloat() * 0.15f;
            p.size = 7f + RNG.nextFloat() * 7f;
            p.life = lifeMin + RNG.nextFloat() * (lifeMax - lifeMin);
            p.maxLife = p.life;
            p.gravity = 0f;
            ps.addParticle(p);
        }
    }

    private static float clamp(final float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
