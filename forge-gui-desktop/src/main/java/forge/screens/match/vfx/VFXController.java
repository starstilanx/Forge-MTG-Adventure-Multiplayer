package forge.screens.match.vfx;

import com.google.common.eventbus.Subscribe;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
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
import java.awt.Rectangle;
import java.util.ArrayList;
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
    private static final Color COLOR_ATTACK_GLOW     = new Color(255, 60,  0);
    private static final Color COLOR_BLOCK_GLOW      = new Color(50,  180, 255);
    private static final Color COLOR_DAMAGE_FLASH    = new Color(255, 50,  0, 80);

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
            // card animation + overlay flash
            final CardPanel cp = findCardPanel(card);
            if (cp != null) {
                CardAnimator.INSTANCE.play(cp, CardAnimator.Type.DAMAGE_SHAKE, 0f);
                layer.addCardOverlay(VFXLayer.OverlayType.DAMAGE_FLASH,
                        cardBoundsInLayer(cp), COLOR_DAMAGE_FLASH, 0.45f);
            }
        });
        return null;
    }

    @Override
    public Void visit(final GameEventAttackersDeclared event) {
        if (event.attackersMap() == null) { return null; }
        final List<CardView> attackers = new ArrayList<>(event.attackersMap().values());
        SwingUtilities.invokeLater(() -> {
            for (final CardView card : attackers) {
                final CardPanel cp = findCardPanel(card);
                if (cp == null) { continue; }
                // lunge toward opponent: negative Y = upward on screen
                CardAnimator.INSTANCE.play(cp, CardAnimator.Type.ATTACK_LUNGE, -40f);
                final Point p = cardCenter(card);
                if (p != null) {
                    VFXEffects.cardDamage(particles, p.x, p.y); // brief spark trail
                }
                layer.addCardOverlay(VFXLayer.OverlayType.ATTACK_GLOW,
                        cardBoundsInLayer(cp), COLOR_ATTACK_GLOW, 0.7f);
            }
        });
        return null;
    }

    @Override
    public Void visit(final GameEventBlockersDeclared event) {
        if (event.blockers() == null) { return null; }
        // collect all blocker cards from the nested map
        final List<CardView> blockerCards = new ArrayList<>();
        for (final com.google.common.collect.Multimap<CardView, CardView> inner : event.blockers().values()) {
            blockerCards.addAll(inner.keySet());
        }
        SwingUtilities.invokeLater(() -> {
            for (final CardView card : blockerCards) {
                final CardPanel cp = findCardPanel(card);
                if (cp == null) { continue; }
                CardAnimator.INSTANCE.play(cp, CardAnimator.Type.BLOCK_SWELL, 0f);
                layer.addCardOverlay(VFXLayer.OverlayType.BLOCK_GLOW,
                        cardBoundsInLayer(cp), COLOR_BLOCK_GLOW, 0.6f);
            }
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
                final float[] rgb = VFXEffects.colorForCard(card);
                if (p != null) {
                    if (card.isToken()) {
                        VFXEffects.tokenCreated(particles, p.x, p.y, rgb[0], rgb[1], rgb[2]);
                    } else {
                        VFXEffects.cardEnterBattlefield(particles, p.x, p.y, rgb[0], rgb[1], rgb[2]);
                    }
                }
                final CardPanel cp = findCardPanel(card);
                if (cp != null) {
                    CardAnimator.INSTANCE.play(cp, CardAnimator.Type.ENTER, 0f);
                    final Color glowColor = new Color(
                            Math.min(1f, rgb[0] * 0.8f + 0.2f),
                            Math.min(1f, rgb[1] * 0.8f + 0.2f),
                            Math.min(1f, rgb[2] * 0.8f + 0.2f));
                    layer.addCardOverlay(VFXLayer.OverlayType.ENTER_GLOW,
                            cardBoundsInLayer(cp), glowColor, 0.6f);
                }
            });
        } else if (toZone == ZoneType.Graveyard) {
            final ZoneType fromZone = event.from() != null ? event.from().zoneType() : null;
            if (fromZone == ZoneType.Battlefield) {
                // Capture panel before invokeLater — it may be gone by the time EDT runs.
                // We look it up now on the game thread; the worst case is null (panel already removed).
                SwingUtilities.invokeLater(() -> {
                    final CardPanel cp = findCardPanel(card);
                    final Point p;
                    if (cp != null) {
                        CardAnimator.INSTANCE.play(cp, CardAnimator.Type.DEATH_FADE, 0f);
                        p = cardCenter(card);
                    } else {
                        p = layerCenter();
                    }
                    if (p != null) {
                        VFXEffects.cardDestroyed(particles, p.x, p.y);
                    }
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

    /**
     * Finds the CardPanel for the given CardView across all field views.
     * Must be called on the EDT.
     */
    private CardPanel findCardPanel(final CardView card) {
        final List<VField> fields = matchUI.getFieldViews();
        if (fields == null) { return null; }
        for (final VField field : fields) {
            final CardPanel cp = field.getTabletop().getCardPanel(card.getId());
            if (cp != null) { return cp; }
        }
        return null;
    }

    /**
     * Returns a Supplier that, when called on the EDT, converts the CardPanel's
     * current screen bounds into VFXLayer-local coordinates.
     */
    private java.util.function.Supplier<Rectangle> cardBoundsInLayer(final CardPanel cp) {
        return () -> {
            try {
                final Point screen = cp.getCardLocationOnScreen();
                final Point layerScreen = layer.getLocationOnScreen();
                return new Rectangle(
                        screen.x - layerScreen.x,
                        screen.y - layerScreen.y,
                        cp.getCardWidth(),
                        cp.getCardHeight());
            } catch (final java.awt.IllegalComponentStateException ignored) {
                return null;
            }
        };
    }
}
