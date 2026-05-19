package forge.adventure.character;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

/**
 * Represents a remote player's avatar on the adventure map.
 *
 * Position is driven by AdventureNetEvent PLAYER_MOVE packets arriving from the server.
 * The sprite interpolates smoothly toward the last-known target position each frame so
 * that 20Hz network updates don't appear as jitter.
 *
 * All mutations to targetX/targetY arrive from the Netty IO thread and must be
 * routed through Gdx.app.postRunnable() by the caller (ClientAdventureLobby) before
 * reaching here — this class itself is not thread-safe.
 */
public class RemotePlayerSprite extends CharacterSprite {

    private static final float LERP_SPEED = 10f;

    private float targetX;
    private float targetY;

    /** Slot index in the lobby (0 = host, 1-3 = clients). */
    public final int playerIndex;

    /** Display name shown above the sprite (future HUD use). */
    public final String playerName;

    public RemotePlayerSprite(final int playerIndex, final String playerName,
                              final String spritePath, final float startX, final float startY) {
        super(playerIndex, spritePath);
        this.playerIndex = playerIndex;
        this.playerName  = playerName;
        this.targetX     = startX;
        this.targetY     = startY;
        setPosition(startX, startY);
    }

    /**
     * Update the target position from a PLAYER_MOVE packet.
     * Must be called on the libGDX rendering thread.
     */
    public void setTargetPosition(final float x, final float y) {
        this.targetX = x;
        this.targetY = y;
        // Transition to Walk animation when moving, Idle when arrived.
        setAnimation(getDistanceSq() > 0.25f
                ? AnimationTypes.Walk : AnimationTypes.Idle);
    }

    /** Reloads animations from a new sprite atlas path (called on PLAYER_STATE receipt). */
    public void updateSprite(final String newSpritePath) {
        load(newSpritePath);
    }

    /** Snap directly to a position without interpolation (used on MAP_SYNC / respawn). */
    public void snapToPosition(final float x, final float y) {
        this.targetX = x;
        this.targetY = y;
        setPosition(x, y);
    }

    @Override
    public void act(final float delta) {
        super.act(delta);
        final float curX = getX();
        final float curY = getY();
        if (getDistanceSq() > 0.01f) {
            final float newX = MathUtils.lerp(curX, targetX, Math.min(1f, delta * LERP_SPEED));
            final float newY = MathUtils.lerp(curY, targetY, Math.min(1f, delta * LERP_SPEED));
            setPosition(newX, newY);
        }
    }

    public Vector2 pos() {
        return new Vector2(getX(), getY());
    }

    private float getDistanceSq() {
        final float dx = targetX - getX();
        final float dy = targetY - getY();
        return dx * dx + dy * dy;
    }
}
