package forge.screens.match.vfx;

import com.google.common.eventbus.Subscribe;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.IGameEventVisitor;
import forge.game.zone.ZoneType;
import forge.game.card.CardView;
import forge.screens.match.CMatchUI;
import forge.screens.match.views.VField;
import forge.view.arcane.CardPanel;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Point;
import java.util.List;

/**
 * Subscribes to game events (via Guava EventBus) and fires particle effects.
 * Must be registered with game.subscribeToEvents(this) on the game thread.
 *
 * Position lookups always happen on the EDT since Swing components are not
 * thread-safe.
 */
public final class VFXController extends IGameEventVisitor.Base<Void> {

    private final CMatchUI matchUI;
    private final ParticleSystem particles;
    private final VFXLayer layer;

    private static final Color COLOR_DAMAGE_CREATURE = new Color(255, 90,  20);
    private static final Color COLOR_DAMAGE_PLAYER   = new Color(255, 40,  40);

    public VFXController(final CMatchUI matchUI,
                         final ParticleSystem particles,
                         final VFXLayer layer) {
        this.matchUI  = matchUI;
        this.particles = particles;
        this.layer    = layer;
    }

    // ---- Guava EventBus entry point ----

    @Subscribe
    public void receiveGameEvent(final GameEvent ev) {
        ev.visit(this);
    }

    // ---- visitor implementations ----

    @Override
    public Void visit(final GameEventSpellAbilityCast event) {
        if (event.sa() == null) { return null; }
        final CardView card = event.sa().getHostCard();
        if (card == null) { return null; }
        SwingUtilities.invokeLater(() -> {
            final Point p = cardCenter(card);
            if (p == null) { return; }
            final float[] rgb = VFXEffects.colorForCard(card);
            VFXEffects.spellCast(particles, p.x, p.y, rgb[0], rgb[1], rgb[2]);
        });
        return null;
    }

    @Override
    public Void visit(final GameEventSpellResolved event) {
        if (event.spell() == null || event.hasFizzled()) { return null; }
        final CardView card = event.spell().getHostCard();
        if (card == null) { return null; }
        SwingUtilities.invokeLater(() -> {
            final Point p = cardCenter(card);
            if (p == null) { return; }
            final float[] rgb = VFXEffects.colorForCard(card);
            VFXEffects.spellResolve(particles, p.x, p.y, rgb[0], rgb[1], rgb[2]);
        });
        return null;
    }

    @Override
    public Void visit(final GameEventCardDamaged event) {
        if (event.card() == null) { return null; }
        final CardView card = event.card();
        final int amount = event.amount();
        SwingUtilities.invokeLater(() -> {
            final Point p = cardCenter(card);
            if (p == null) { return; }
            VFXEffects.cardDamage(particles, p.x, p.y);
            layer.addFloatingLabel("-" + amount, COLOR_DAMAGE_CREATURE, p.x, p.y - 20);
        });
        return null;
    }

    @Override
    public Void visit(final GameEventPlayerDamaged event) {
        if (event.target() == null) { return null; }
        final forge.game.player.PlayerView player = event.target();
        final int amount = event.amount();
        SwingUtilities.invokeLater(() -> {
            final Point p = playerAvatarCenter(player);
            if (p == null) { return; }
            VFXEffects.playerDamage(particles, p.x, p.y);
            layer.addFloatingLabel("-" + amount, COLOR_DAMAGE_PLAYER, p.x, p.y - 30);
        });
        return null;
    }

    @Override
    public Void visit(final GameEventCardChangeZone event) {
        if (event.card() == null || event.to() == null) { return null; }
        final ZoneType toZone = event.to().zoneType();
        final CardView card = event.card();

        if (toZone == ZoneType.Battlefield) {
            SwingUtilities.invokeLater(() -> {
                final Point p = cardCenter(card);
                if (p == null) { return; }
                final float[] rgb = VFXEffects.colorForCard(card);
                if (card.isToken()) {
                    VFXEffects.tokenCreated(particles, p.x, p.y, rgb[0], rgb[1], rgb[2]);
                } else {
                    VFXEffects.cardEnterBattlefield(particles, p.x, p.y, rgb[0], rgb[1], rgb[2]);
                }
            });
        } else if (toZone == ZoneType.Graveyard) {
            final ZoneType fromZone = event.from() != null ? event.from().zoneType() : null;
            if (fromZone == ZoneType.Battlefield) {
                SwingUtilities.invokeLater(() -> {
                    // card is gone from battlefield; fire at last known layer center
                    final Point p = layerCenter();
                    VFXEffects.cardDestroyed(particles, p.x, p.y);
                });
            }
        }
        return null;
    }

    // ---- coordinate helpers (EDT only) ----

    /**
     * Returns the center of the given card's panel in VFXLayer-local coordinates,
     * or null if the card panel cannot be found or is not on screen.
     */
    private Point cardCenter(final CardView card) {
        final List<VField> fields = matchUI.getFieldViews();
        if (fields == null) { return null; }
        for (final VField field : fields) {
            final CardPanel cp = field.getTabletop().getCardPanel(card.getId());
            if (cp != null) {
                try {
                    final Point screen = cp.getCardLocationOnScreen();
                    screen.x += cp.getCardWidth()  / 2;
                    screen.y += cp.getCardHeight() / 2;
                    return screenToLayer(screen);
                } catch (final java.awt.IllegalComponentStateException ignored) {
                    // panel not yet visible on screen
                }
            }
        }
        return null;
    }

    /**
     * Returns the avatar panel center for the given player in VFXLayer-local
     * coordinates, or null if not found.
     */
    private Point playerAvatarCenter(final forge.game.player.PlayerView player) {
        final VField field = matchUI.getFieldViewFor(player);
        if (field == null) { return null; }
        final java.awt.Component avatar = field.getAvatarArea();
        try {
            final Point screen = avatar.getLocationOnScreen();
            screen.x += avatar.getWidth()  / 2;
            screen.y += avatar.getHeight() / 2;
            return screenToLayer(screen);
        } catch (final java.awt.IllegalComponentStateException ignored) {
            return null;
        }
    }

    /** Converts an absolute screen point to VFXLayer-local coordinates. */
    private Point screenToLayer(final Point screen) {
        try {
            final Point layerScreen = layer.getLocationOnScreen();
            return new Point(screen.x - layerScreen.x, screen.y - layerScreen.y);
        } catch (final java.awt.IllegalComponentStateException ignored) {
            return screen; // layer not yet on screen — use raw screen coords as fallback
        }
    }

    /** Fallback: the center of the VFXLayer panel itself. */
    private Point layerCenter() {
        return new Point(layer.getWidth() / 2, layer.getHeight() / 2);
    }
}
