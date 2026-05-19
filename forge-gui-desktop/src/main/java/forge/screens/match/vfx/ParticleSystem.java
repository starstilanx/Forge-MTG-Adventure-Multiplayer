package forge.screens.match.vfx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages a pool of active particles.
 * Thread model: any thread may call addParticle(); only the render thread calls update/getParticles/clear.
 */
public final class ParticleSystem {
    static final int MAX_PARTICLES = 4000;

    private final List<Particle> active = new ArrayList<>(512);
    private final ConcurrentLinkedQueue<Particle> incoming = new ConcurrentLinkedQueue<>();

    void addParticle(final Particle p) {
        incoming.offer(p);
    }

    /** Called by render thread each frame. */
    void update(final float dt) {
        Particle p;
        while ((p = incoming.poll()) != null) {
            if (active.size() < MAX_PARTICLES) {
                active.add(p);
            }
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            final Particle particle = active.get(i);
            particle.update(dt);
            if (particle.dead) {
                active.remove(i);
            }
        }
    }

    /** Render thread only. */
    List<Particle> getParticles() {
        return active;
    }

    boolean hasParticles() {
        return !active.isEmpty() || !incoming.isEmpty();
    }

    public void clear() {
        active.clear();
        incoming.clear();
    }
}
