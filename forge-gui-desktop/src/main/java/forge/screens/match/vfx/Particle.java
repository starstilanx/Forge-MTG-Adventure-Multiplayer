package forge.screens.match.vfx;

/** Single particle — only accessed from the render thread after handoff. */
final class Particle {
    float x, y;
    float vx, vy;
    float r, g, b, a;
    float size;
    float life;
    float maxLife;
    float gravity;
    boolean dead;

    void update(final float dt) {
        vy += gravity * dt;
        x  += vx * dt;
        y  += vy * dt;
        life -= dt;
        if (life <= 0f) {
            dead = true;
        }
    }

    /** 1.0 when fresh, 0.0 when dead. */
    float lifeRatio() {
        return maxLife > 0f ? Math.max(0f, life / maxLife) : 0f;
    }
}
