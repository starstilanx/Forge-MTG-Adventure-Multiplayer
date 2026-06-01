package forge.adventure.stage;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.Timer;
import com.badlogic.gdx.utils.viewport.ScalingViewport;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingAdapter;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.Graphics;
import forge.adventure.character.CharacterSprite;
import forge.adventure.character.MapActor;
import forge.adventure.character.PlayerSprite;
import forge.adventure.data.DialogData;
import forge.adventure.data.EffectData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.data.EnemyData;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.Scene;
import forge.adventure.scene.StartScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.util.Reward;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.KeyBinding;
import forge.adventure.util.MapDialog;
import forge.adventure.util.Paths;
import forge.adventure.world.WorldSave;
import forge.assets.FBufferedImage;
import forge.assets.FImageComplex;
import forge.assets.FSkinImage;
import forge.card.CardRenderer;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.game.GameType;
import forge.adventure.character.RemotePlayerSprite;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.adventure.AdventureNetEvent;
import forge.gamemodes.net.adventure.AdventureNetSession;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.screens.CoverScreen;
import forge.util.MyRandom;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Base class to render a player sprite on a map
 * used for the over world and dungeons
 */
public abstract class GameStage extends Stage {


    private final OrthographicCamera camera;
    Group backgroundSprites;
    SpriteGroup foregroundSprites;
    PlayerSprite player;
    private float touchX = -1;
    private float touchY = -1;
    private final float timer = 0;
    private float animationTimeout = 0;
    public static float maximumScrollDistance = 1.5f;
    public static float minimumScrollDistance = 0.3f;

    private String extraAnnouncement = "";

    protected final Dialog dialog;
    protected Stage dialogStage;
    protected boolean dialogOnlyInput;
    protected final Array<TextraButton> dialogButtonMap = new Array<>();
    TextraButton selectedKey;

    public Dialog getDialog() {
        return dialog;
    }

    public boolean isDialogOnlyInput() {
        return dialogOnlyInput;
    }


    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    public void showDialog() {
        if (dialogStage == null) {
            setDialogStage(GameHUD.getInstance());
        }
        GameHUD.getInstance().playerIdle();
        dialogButtonMap.clear();
        for (int i = 0; i < dialog.getButtonTable().getCells().size; i++) {
            dialogButtonMap.add((TextraButton) dialog.getButtonTable().getCells().get(i).getActor());
        }
        dialog.show(dialogStage, Actions.show());
        dialog.setPosition((dialogStage.getWidth() - dialog.getWidth()) / 2, (dialogStage.getHeight() - dialog.getHeight()) / 2);
        dialogOnlyInput = true;

        if (Forge.hasExternalInput() && !dialogButtonMap.isEmpty())
            dialogStage.setKeyboardFocus(dialogButtonMap.first());
    }

    public void hideDialog() {
        dialog.hide(Actions.sequence(Actions.sizeTo(dialog.getOriginX(), dialog.getOriginY(), 0.3f), Actions.hide()));
        dialogOnlyInput = false;
        selectedKey = null;
        dialog.clearListeners();
    }

    /**
     * Triggered when the hud is showing a dialog, which is tracked separately
     *
     * @param isShowing Whether a dialog is currently showing
     */
    public void hudIsShowingDialog(boolean isShowing) {
        dialogOnlyInput = isShowing;
    }

    public void effectDialog(EffectData effectData) {
        dialog.getButtonTable().clear();
        dialog.getContentTable().clear();
        dialog.clearListeners();
        TextraButton ok = Controls.newTextButton("OK", this::hideDialog);
        ok.setVisible(false);
        TypingLabel L = Controls.newTypingLabel("{GRADIENT=CYAN;WHITE;1;1}" +
                Forge.getLocalizer().getMessage("lblEffectDialogDescription") + "{ENDGRADIENT}\n" +
                Forge.getLocalizer().getMessage("lblEffectDataHeader") + "\n" + effectData.getDescription());
        L.setWrap(true);
        L.setTypingListener(new TypingAdapter() {
            @Override
            public void end() {
                ok.setVisible(true);
            }
        });
        dialog.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                L.skipToTheEnd();
                super.clicked(event, x, y);
            }
        });
        dialog.getButtonTable().add(ok).width(240f);
        dialog.getContentTable().add(L).width(250f);
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    public void showImageDialog(String message, FBufferedImage fb, Runnable runnable) {
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        if (fb != null && fb.getTexture() != null) {
            TextureRegion tr = new TextureRegion(fb.getTexture());
            tr.flip(true, true);
            Image image = new Image(tr);
            image.setScaling(Scaling.fit);
            dialog.getContentTable().add(image).height(100);
            dialog.getContentTable().add().row();
        }
        TypingLabel L = Controls.newTypingLabel(message);
        L.setWrap(true);
        L.skipToTheEnd();
        dialog.getContentTable().add(L).width(250f);
        dialog.getButtonTable().add(Controls.newTextButton("OK", () -> {
            hideDialog();
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    if (fb != null)
                        fb.dispose();
                }
            }, 0.5f);
            if (runnable != null) {
                runnable.run();
            }
        })).width(240f);
        dialog.setKeepWithinStage(true);
        setDialogStage(GameHUD.getInstance());
        showDialog();
    }

    public void showDeckAwardDialog(String message, Deck deck, Runnable runnable) {
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        DeckProxy dp = new DeckProxy(deck, "Constructed", GameType.Constructed, null);
        FImageComplex cardArt = CardRenderer.getCardArt(dp.getHighestCMCCard());
        if (cardArt != null) {
            Image art = new Image(cardArt.getTextureRegion());
            art.setWidth(58);
            art.setHeight(46);
            art.setPosition(25, 43);
            Image image = new Image(FSkinImage.ADV_DECKBOX.getTextureRegion());
            image.setWidth(60);
            image.setHeight(80);
            image.setPosition(24, 10);
            ColorSet colorSet = DeckProxy.getColorIdentity(deck);
            TypingLabel deckColors = Controls.newTypingLabel(Controls.colorIdToTypingString(colorSet, true).toUpperCase());
            deckColors.skipToTheEnd();
            deckColors.setAlignment(Align.center);
            deckColors.setPosition(14, 44);
            TextraLabel deckname = Controls.newTextraLabel(deck.getName());
            deckname.setAlignment(Align.center);
            deckname.setWrap(true);
            deckname.setWidth(80);
            deckname.setPosition(14, 28);
            Group group = new Group();
            group.addActor(art);
            group.addActor(image);
            group.addActor(deckColors);
            group.addActor(deckname);
            dialog.getContentTable().add(group).height(100).width(100).center();
            dialog.getContentTable().add().row();
        } else {
            TypingLabel label = Controls.newTypingLabel("[%120]" + Controls.colorIdToTypingString(DeckProxy.getColorIdentity(deck)).toUpperCase() + "\n[%]" + deck.getName());
            label.skipToTheEnd();
            label.setAlignment(Align.center);
            dialog.getContentTable().add(label).align(Align.center);
            dialog.getContentTable().add().row();
        }

        TypingLabel L = Controls.newTypingLabel(message);
        L.setWrap(true);
        L.skipToTheEnd();

        dialog.getContentTable().add(L).width(250);
        dialog.getButtonTable().add(Controls.newTextButton("OK", () -> {
            hideDialog();
            if (runnable != null)
                runnable.run();
        })).width(240);
        dialog.setKeepWithinStage(true);
        setDialogStage(GameHUD.getInstance());
        showDialog();
    }


    public boolean axisMoved(Controller controller, int axisIndex, float value) {

        if (MapStage.getInstance().isDialogOnlyInput() || isPaused()) {
            return true;
        }
        player.getMovementDirection().x = controller.getAxis(0);
        player.getMovementDirection().y = -controller.getAxis(1);
        if (player.getMovementDirection().len() < 0.2) {
            player.stop();
        }
        return true;
    }

    enum PlayerModification {
        Sprint,
        Hide,
        Fly

    }


    HashMap<PlayerModification, Float> currentModifications = new HashMap<>();

    // -------------------------------------------------------------------------
    // Multiplayer — remote player sprites and position sync
    // -------------------------------------------------------------------------

    private static final float NET_TICK_RATE = 0.05f; // 20 Hz
    private float netTickAccumulator = 0f;
    /** Slot index → remote sprite for every other player in this stage. */
    protected final Map<Integer, RemotePlayerSprite> remotePlayers = new HashMap<>();

    /** Create a remote player sprite and add it to this stage's scene graph. */
    public void addRemotePlayer(final int slotIndex, final String name, final String spritePath,
                                final float spawnX, final float spawnY) {
        if (remotePlayers.containsKey(slotIndex)) return;
        final RemotePlayerSprite sprite = new RemotePlayerSprite(
                slotIndex, name, spritePath, spawnX, spawnY);
        remotePlayers.put(slotIndex, sprite);
        foregroundSprites.addActor(sprite);
    }

    /** Update the target position of a remote player's sprite (called on PLAYER_MOVE). */
    public void updateRemotePlayer(final int slotIndex, final float x, final float y) {
        final RemotePlayerSprite sprite = remotePlayers.get(slotIndex);
        if (sprite != null) sprite.setTargetPosition(x, y);
    }

    /** Swap the sprite atlas for a remote player (called on PLAYER_STATE). */
    public void applyRemotePlayerSprite(final int slotIndex, final String spriteName) {
        final RemotePlayerSprite sprite = remotePlayers.get(slotIndex);
        if (sprite != null) sprite.updateSprite(spriteName);
    }

    /** Remove and dispose a remote player's sprite (called on PLAYER_LEAVE). */
    public void removeRemotePlayer(final int slotIndex) {
        final RemotePlayerSprite sprite = remotePlayers.remove(slotIndex);
        if (sprite != null) sprite.remove();
    }

    /** Collect all player positions as a flat array { p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y }. */
    public float[] collectPlayerPositions() {
        final float[] out = new float[8];
        if (player != null) {
            out[0] = player.pos().x;
            out[1] = player.pos().y;
        }
        for (final Entry<Integer, RemotePlayerSprite> entry : remotePlayers.entrySet()) {
            final int slot = entry.getKey();
            if (slot >= 1 && slot <= 3) {
                out[slot * 2]     = entry.getValue().pos().x;
                out[slot * 2 + 1] = entry.getValue().pos().y;
            }
        }
        return out;
    }

    /**
     * Build the full 10-field PLAYER_STATE payload that the host needs in order to
     * reconstruct this client's RegisteredPlayer at battle start: slot, sprite, hp,
     * main deck, commander section, and aggregated item/blessing battle effects.
     *
     * Used by both the PLAYER_JOIN "isMe" handler (initial send) and WorldStage.enter()
     * (re-send after returning to the world map).  Both call sites must use the same
     * 10-field shape — sending a shorter array overwrites the cached state on the
     * server and causes the host to start the next battle without the client's
     * commander or gear effects.
     */
    public static String[] buildClientPlayerStatePayload(final int slotIndex) {
        // Prefer an active event-draft deck (held on AdventureNetSession.activeEventDeck) so the
        // host's RegisteredPlayer for this slot is built from the cards the client actually picked
        // during the shared-seed draft — not the player's pre-event main deck.
        final Object activeEventDeckObj = AdventureNetSession.getInstance().activeEventDeck;
        final Deck myDeck = (activeEventDeckObj instanceof Deck)
                ? (Deck) activeEventDeckObj
                : Current.player().getSelectedDeck();
        final String ds = myDeck != null ? myDeck.getMain().toCardList("\n") : "";
        final forge.deck.CardPool cmdPool = (myDeck != null)
                ? myDeck.get(forge.deck.DeckSection.Commander) : null;
        final String cs = (cmdPool != null && !cmdPool.isEmpty())
                ? cmdPool.toCardList("\n") : "";
        int effectLifeMod = 0, effectHandMod = 0;
        int effectShards = Current.player().getShards();
        final StringBuilder effectBfCards  = new StringBuilder();
        final StringBuilder effectCmdCards = new StringBuilder();
        for (Long itemId : Current.player().getEquippedItems()) {
            final forge.adventure.data.ItemData itm = Current.player().getEquippedItem(itemId);
            if (itm == null || itm.effect == null) continue;
            effectLifeMod += itm.effect.lifeModifier;
            effectHandMod += itm.effect.changeStartCards;
            effectShards  += itm.effect.extraManaShards;
            if (itm.effect.startBattleWithCard != null)
                for (String c : itm.effect.startBattleWithCard) {
                    if (effectBfCards.length() > 0) effectBfCards.append('\n');
                    effectBfCards.append(c);
                }
            if (itm.effect.startBattleWithCardInCommandZone != null)
                for (String c : itm.effect.startBattleWithCardInCommandZone) {
                    if (effectCmdCards.length() > 0) effectCmdCards.append('\n');
                    effectCmdCards.append(c);
                }
        }
        final forge.adventure.data.EffectData blessing = Current.player().getBlessing();
        if (blessing != null) {
            effectLifeMod += blessing.lifeModifier;
            effectHandMod += blessing.changeStartCards;
            effectShards  += blessing.extraManaShards;
            if (blessing.startBattleWithCard != null)
                for (String c : blessing.startBattleWithCard) {
                    if (effectBfCards.length() > 0) effectBfCards.append('\n');
                    effectBfCards.append(c);
                }
            if (blessing.startBattleWithCardInCommandZone != null)
                for (String c : blessing.startBattleWithCardInCommandZone) {
                    if (effectCmdCards.length() > 0) effectCmdCards.append('\n');
                    effectCmdCards.append(c);
                }
        }
        return new String[]{
                String.valueOf(slotIndex),
                Current.player().spriteName(),
                String.valueOf(Current.player().getLife()),
                ds, cs,
                String.valueOf(effectLifeMod),
                String.valueOf(effectHandMod),
                String.valueOf(effectShards),
                effectBfCards.toString(),
                effectCmdCards.toString()
        };
    }

    /** Unfreeze the map and restore pre-battle positions (called on BATTLE_END). */
    public void resumeAfterBattle() {
        Forge.advFreezePlayerControls = false;
        final AdventureNetSession netSession = AdventureNetSession.getInstance();
        if (netSession.isActiveHost()) {
            if (player != null) {
                player.setPosition(
                        netSession.serverLobby.getPreBattleX(0),
                        netSession.serverLobby.getPreBattleY(0));
            }
            for (final Entry<Integer, RemotePlayerSprite> entry : remotePlayers.entrySet()) {
                final int slot = entry.getKey();
                entry.getValue().snapToPosition(
                        netSession.serverLobby.getPreBattleX(slot),
                        netSession.serverLobby.getPreBattleY(slot));
            }
        }
    }

    /**
     * Register this stage as the event listener on the ClientAdventureLobby.
     * The listener is an instance method so all callbacks reference {@code this} stage,
     * ensuring that remote sprites are added to the correct scene graph when stages change.
     */
    public void registerClientListener() {
        final AdventureNetSession session = AdventureNetSession.getInstance();
        if (!session.isActiveClient()) return;
        final GameStage self = this;
        session.clientLobby.setEventListener(event -> {
            switch (event.type) {
                case MAP_SYNC:
                    if (event.payload instanceof byte[]) {
                        final byte[] syncBytes = (byte[]) event.payload;
                        Gdx.app.postRunnable(() -> {
                            forge.adventure.scene.LobbyScene.applySyncPayload(syncBytes);
                        });
                    }
                    break;
                case PLAYER_MOVE:
                    if (event.payload instanceof float[]) {
                        final float[] pos = (float[]) event.payload;
                        Gdx.app.postRunnable(() -> {
                            for (int slot = 0; slot < 4 && slot * 2 + 1 < pos.length; slot++) {
                                if (slot == session.mySlotIndex) continue;
                                // Skip world-map updates for players inside a POI: their
                                // PLAYER_MOVE carries local map coordinates, not world coords.
                                // When both players are in the same MapStage the coordinates
                                // match and updates should be applied normally.
                                if (session.slotsInPoi.contains(slot) && !(self instanceof MapStage)) continue;
                                // Diagnostic: warn once if PLAYER_MOVE arrives for a slot we have no sprite for.
                                if (self.remotePlayers.get(slot) == null && slot == 0
                                        && self instanceof MapStage) {
                                    System.err.println("[AdventureMP] WARN: PLAYER_MOVE for host slot 0 in MapStage "
                                            + "but no sprite exists. remotePlayerNames="
                                            + session.remotePlayerNames + " known sprites="
                                            + self.remotePlayers.keySet());
                                }
                                self.updateRemotePlayer(slot, pos[slot * 2], pos[slot * 2 + 1]);
                            }
                        });
                    }
                    break;
                case PLAYER_JOIN:
                    if (event.payload instanceof String[]) {
                        final String[] data = (String[]) event.payload;
                        if (data.length >= 4) {
                            final int slot = Integer.parseInt(data[0]);
                            final String joinName = data[1];
                            final float sx = Float.parseFloat(data[2]);
                            final float sy = Float.parseFloat(data[3]);
                            session.remotePlayerNames.put(slot, joinName);
                            // Use slot index only — name comparison incorrectly matches when both
                            // players happen to share the same character name.
                            final boolean isMe = session.clientLobby != null
                                    && session.clientLobby.getLocalPlayer() == slot;
                            if (isMe) {
                                session.mySlotIndex = slot;
                                // WorldStage.enter() skipped PLAYER_STATE because mySlotIndex
                                // was -1 at that time (pending events not yet processed).
                                // Now that we know our slot, send the full state with deck.
                                if (session.client != null) {
                                    try {
                                        session.client.send(new AdventureNetEvent(
                                                AdventureNetEvent.Type.PLAYER_STATE,
                                                buildClientPlayerStatePayload(slot)));
                                    } catch (final Exception ex) {
                                        System.err.println("[AdventureMP] Failed to send PLAYER_STATE: " + ex.getMessage());
                                    }
                                }
                                break; // don't create a sprite for ourselves
                            }
                            final String[] state = session.remotePlayerStates.get(slot);
                            final String spritePath = (state != null && state.length > 0)
                                    ? state[0] : Current.player().spriteName();
                            Gdx.app.postRunnable(() -> self.addRemotePlayer(slot, joinName, spritePath, sx, sy));
                        }
                    }
                    break;
                case PLAYER_LEAVE:
                    if (event.payload instanceof Integer) {
                        final int idx = (Integer) event.payload;
                        session.remotePlayerNames.remove(idx);
                        Gdx.app.postRunnable(() -> self.removeRemotePlayer(idx));
                    }
                    break;
                case BATTLE_INIT:
                    Gdx.app.postRunnable(() -> {
                        Forge.advFreezePlayerControls = true;
                        forge.adventure.scene.DuelScene.instance().enterAsNetworkClient(event.payload);
                    });
                    break;
                case BATTLE_END: {
                    // Payload is String[] where [0]="true"/"false" (winner) and [1..] are
                    // serialized reward descriptors generated by the host.  The old Boolean
                    // payload is accepted as a legacy fallback (no rewards in that case).
                    final String[] battlePayload = (event.payload instanceof String[])
                            ? (String[]) event.payload : null;
                    final boolean humanWon = battlePayload != null
                            ? Boolean.parseBoolean(battlePayload[0])
                            : Boolean.TRUE.equals(event.payload);
                    Gdx.app.postRunnable(() -> {
                        self.resumeAfterBattle();
                        if (humanWon) {
                            // Increment win counter unconditionally — rewards are optional.
                            Current.player().win();
                            if (battlePayload != null && battlePayload.length > 1) {
                                final Array<Reward> rewards = new Array<>();
                                for (int i = 1; i < battlePayload.length; i++) {
                                    final Reward r = DuelScene.deserializeReward(battlePayload[i]);
                                    if (r != null) rewards.add(r);
                                }
                                // Hand rewards to DuelScene — it will show RewardScene
                                // either immediately (if GameEnd hasn't fired yet) or
                                // via a fallback postRunnable (if GameEnd already ran).
                                DuelScene.instance().setPendingClientRewards(rewards);
                            }
                        }
                    });
                    break;
                }
                case PLAYER_ENTER_POI:
                    // Host entered a POI: their subsequent PLAYER_MOVE packets carry local
                    // map coordinates, not world coordinates. Freeze the world-map sprite so
                    // it stays at the POI entrance rather than jumping to a wrong position.
                    // If this client is already in MapStage (same town), updates still apply.
                    session.slotsInPoi.add(0);
                    break;
                case PLAYER_EXIT_POI:
                    session.slotsInPoi.remove(0);
                    break;
                case PLAYER_STATE:
                    if (event.payload instanceof String[]) {
                        final String[] stateData = (String[]) event.payload;
                        if (stateData.length >= 3) {
                            final int slotIdx = Integer.parseInt(stateData[0]);
                            final String spriteName = stateData[1];
                            final String hp = stateData[2];
                            session.remotePlayerStates.put(slotIdx, new String[]{ spriteName, hp });
                            Gdx.app.postRunnable(() -> self.applyRemotePlayerSprite(slotIdx, spriteName));
                        }
                    }
                    break;
                case EVENT_INIT:
                    if (event.payload instanceof forge.adventure.data.AdventureEventData) {
                        final forge.adventure.data.AdventureEventData remoteEvent =
                                (forge.adventure.data.AdventureEventData) event.payload;
                        session.activeEventData = remoteEvent;
                        session.activeEventDeck = null; // reset until client commits a drafted deck
                        Gdx.app.postRunnable(() -> {
                            // Open the same EventScene as the host with the deserialized event.
                            // Status arrives as Entered (host paid the entry fee) so we go straight
                            // to the draft / deck-build UI; no entry-fee dialog on the client side.
                            Forge.switchScene(forge.adventure.scene.EventScene.instance(
                                    forge.adventure.scene.GameScene.instance(), remoteEvent, null));
                        });
                    }
                    break;
                default:
                    break;
            }
        });
    }

    public void modifyPlayer(PlayerModification mod, float value) {
        currentModifications.merge(mod, value, Float::sum);
    }

    public void flyFor(float value) {
        modifyPlayer(PlayerModification.Fly, value);
        player.playEffect(Paths.EFFECT_FLY);
    }

    public void hideFor(float value) {
        modifyPlayer(PlayerModification.Hide, value);
        player.setColor(player.getColor().r, player.getColor().g, player.getColor().b, 0.5f);
        player.playEffect(Paths.EFFECT_HIDE);
    }

    public void sprintFor(float value) {
        modifyPlayer(PlayerModification.Sprint, value);
        player.playEffect(Paths.EFFECT_SPRINT);
    }

    public void startPause(float i) {
        startPause(i, null);
    }

    public void startPause(float i, Runnable runnable) {
        onEndAction = runnable;
        animationTimeout = i;
        player.setMovementDirection(Vector2.Zero);
    }

    public boolean isPaused() {
        return animationTimeout > 0;
    }

    public GameStage() {
        super(new ScalingViewport(Scaling.stretch, Scene.getIntendedWidth(), Scene.getIntendedHeight(), new OrthographicCamera()));
        WorldSave.getCurrentSave().onLoad(() -> {
            if (player == null)
                return;
            foregroundSprites.removeActor(player);
            player = null;
            GameStage.this.getPlayerSprite();
        });
        camera = (OrthographicCamera) getCamera();

        backgroundSprites = new Group();
        foregroundSprites = new SpriteGroup();


        addActor(backgroundSprites);
        addActor(foregroundSprites);

        dialog = Controls.newDialog("");
    }

    public void setWinner(boolean b, boolean a) {
    }

    public void setBounds(float width, float height) {
        getViewport().setWorldSize(width, height);
    }

    public PlayerSprite getPlayerSprite() {
        if (player == null) {
            player = new PlayerSprite(this);
            foregroundSprites.addActor(player);
        }
        return player;
    }


    public SpriteGroup getSpriteGroup() {
        return foregroundSprites;
    }

    public Group getBackgroundSprites() {
        return backgroundSprites;
    }


    Runnable onEndAction;

    @Override
    public final void act(float delta) {
        super.act(delta);

        if (animationTimeout >= 0) {
            animationTimeout -= delta;
            return;
        }
        Array<PlayerModification> modsToRemove = new Array<>();
        for (Map.Entry<PlayerModification, Float> mod : currentModifications.entrySet()) {
            mod.setValue(mod.getValue() - delta);
            if (mod.getValue() < 0)
                modsToRemove.add(mod.getKey());
        }
        for (PlayerModification mod : modsToRemove) {
            currentModifications.remove(mod);
            onRemoveEffect(mod);
        }

        if (isPaused()) {
            return;
        }


        if (onEndAction != null) {

            onEndAction.run();
            onEndAction = null;
        }

        if (touchX >= 0) {
            Vector2 target = this.screenToStageCoordinates(new Vector2(touchX, touchY));
            target.x -= player.getWidth() / 2f;
            Vector2 diff = target.sub(player.pos());

            if (diff.len() < 2) {
                diff.setZero();
                player.stop();
            }
            player.setMovementDirection(diff);
        }
        camera.position.x = Math.min(Math.max(Scene.getIntendedWidth() / 2f, player.pos().x), getViewport().getWorldWidth() - Scene.getIntendedWidth() / 2f);
        camera.position.y = Math.min(Math.max(Scene.getIntendedHeight() / 2f, player.pos().y), getViewport().getWorldHeight() - Scene.getIntendedHeight() / 2f);


        onActing(delta);

        // ---- Multiplayer network tick ----
        if (player != null) {
            final AdventureNetSession netSession = AdventureNetSession.getInstance();
            if (netSession.isActiveHost()) {
                netSession.serverLobby.updateHostPosition(player.pos().x, player.pos().y);
                netTickAccumulator += delta;
                if (netTickAccumulator >= NET_TICK_RATE) {
                    netTickAccumulator -= NET_TICK_RATE;
                    // Refresh host-side remote sprites from accumulated client positions.
                    final float[] allPos = netSession.serverLobby.getAllPositions();
                    for (final Entry<Integer, RemotePlayerSprite> entry : remotePlayers.entrySet()) {
                        final int slot = entry.getKey();
                        if (slot * 2 + 1 < allPos.length) {
                            entry.getValue().setTargetPosition(allPos[slot * 2], allPos[slot * 2 + 1]);
                        }
                    }
                    netSession.serverLobby.broadcastAllPositions();
                }
            } else if (netSession.isActiveClient() && netSession.clientLobby != null) {
                if (!netSession.clientLobby.hasEventListener()) {
                    registerClientListener();
                } else {
                    // Send own position to server 20 Hz.
                    netTickAccumulator += delta;
                    if (netTickAccumulator >= NET_TICK_RATE && netSession.mySlotIndex >= 0
                            && netSession.client != null) {
                        netTickAccumulator -= NET_TICK_RATE;
                        try {
                            netSession.client.send(new AdventureNetEvent(
                                    AdventureNetEvent.Type.PLAYER_MOVE,
                                    new float[]{ (float) netSession.mySlotIndex,
                                            player.pos().x, player.pos().y }));
                        } catch (final Exception ignored) { }
                    }
                }
            }
        }
    }

    private void onRemoveEffect(PlayerModification mod) {
        switch (mod) {
            case Hide:
                player.setColor(player.getColor().r, player.getColor().g, player.getColor().b, 1f);
                break;
            case Fly:
                player.removeEffect(Paths.EFFECT_FLY);
                break;
            case Sprint:
                player.removeEffect(Paths.EFFECT_SPRINT);
                break;
        }
    }

    abstract protected void onActing(float delta);


    @Override
    public boolean keyDown(int keycode) {
        super.keyDown(keycode);
        if (isPaused())
            return true;
        if (KeyBinding.Left.isPressed(keycode)) {
            player.getMovementDirection().x = -1;
        }
        if (KeyBinding.Right.isPressed(keycode)) {
            player.getMovementDirection().x = +1;
        }
        if (KeyBinding.Up.isPressed(keycode)) {
            player.getMovementDirection().y = +1;
        }
        if (KeyBinding.Down.isPressed(keycode)) {
            player.getMovementDirection().y = -1;
        }
        if (keycode == Input.Keys.F5)//todo config
        {
            if (TileMapScene.instance().currentMap().isInMap()) {
                DialogData noQuicksave = new DialogData();
                DialogData noQuicksaveOK = new DialogData();
                noQuicksave.text = "Game not saved. Quicksave is only available on the world map.";
                noQuicksaveOK.name = "OK";
                noQuicksave.options = new DialogData[]{noQuicksaveOK};
                MapDialog noQuicksaveDialog = new MapDialog(noQuicksave, MapStage.getInstance(), -1, null);
                showDialog();
                noQuicksaveDialog.activate();
            } else {
                getPlayerSprite().storePos();
                WorldSave.getCurrentSave().header.createPreview();
                WorldSave.getCurrentSave().quickSave();
            }
        }
        if (keycode == Input.Keys.F8)//todo config
        {
            if (!TileMapScene.instance().currentMap().isInMap()) {
                WorldSave.getCurrentSave().quickLoad();
                enter();
            }
        }
        if (keycode == Input.Keys.F11) {
            debugCollision(false);

        }
        if (keycode == Input.Keys.F12) {
            debugCollision(true);

        }
        if (keycode == Input.Keys.F2) {
            // prevent going to Debug Zone by accident if Debug Map isn't enabled..
            if (GameHUD.getInstance().isDebugMap()) {
                TileMapScene S = TileMapScene.instance();
                PointOfInterestData P = PointOfInterestData.getPointOfInterest("DEBUGZONE");
                if (P != null) {
                    PointOfInterest PoI = new PointOfInterest(P, new Vector2(0, 0), MyRandom.getRandom());
                    S.load(PoI);
                    Forge.switchScene(S);
                }
            } else {
                System.out.println("Enable Debug Map for Debug Zone.");
            }
        }
        if (keycode == Input.Keys.F11) {
            debugCollision(false);
            for (Actor actor : foregroundSprites.getChildren()) {
                if (actor instanceof MapActor) {
                    ((MapActor) actor).setBoundDebug(false);
                }
            }
            player.setBoundDebug(false);
            setDebugAll(false);
        }
        return true;
    }

    public void debugCollision(boolean b) {
        for (Actor actor : foregroundSprites.getChildren()) {
            if (actor instanceof MapActor) {
                ((MapActor) actor).setBoundDebug(b);
            }
        }
        setDebugAll(b);
        player.setBoundDebug(b);
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (isPaused())
            return true;
        camera.zoom += (amountY * 0.03);
        if (camera.zoom < minimumScrollDistance)
            camera.zoom = minimumScrollDistance;
        if (camera.zoom > maximumScrollDistance)
            camera.zoom = maximumScrollDistance;
        return super.scrolled(amountX, amountY);
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (isPaused())
            return true;
        if (!GuiBase.isAndroid()) {
            touchX = screenX;
            touchY = screenY;
        }

        return true;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (isPaused())
            return true;
        if (!GuiBase.isAndroid()) {
            touchX = screenX;
            touchY = screenY;
        }


        return true;
    }

    public void stop() {
        WorldStage.getInstance().getPlayerSprite().setMovementDirection(Vector2.Zero);
        MapStage.getInstance().getPlayerSprite().setMovementDirection(Vector2.Zero);
        touchX = -1;
        touchY = -1;
        player.stop();
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        stop();
        return true;
    }

    @Override
    public boolean keyUp(int keycode) {
        if (isPaused())
            return true;
        if (KeyBinding.Left.isPressed(keycode) || KeyBinding.Right.isPressed(keycode)) {
            player.getMovementDirection().x = 0;
            if (!player.isMoving())
                stop();
        }
        if (KeyBinding.Down.isPressed(keycode) || KeyBinding.Up.isPressed(keycode)) {
            player.getMovementDirection().y = 0;
            if (!player.isMoving())
                stop();
        }
        return false;
    }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        stop();
        return super.touchCancelled(screenX, screenY, pointer, button);
    }

    public void openMenu() {
        if (Forge.advFreezePlayerControls)
            return;
        WorldSave.getCurrentSave().header.createPreview();
        Forge.switchScene(StartScene.instance());
    }

    public void enter() {
        stop();
        netTickAccumulator = 0f;

        // --- Multiplayer: rebuild remote sprites for this stage's scene graph ---
        for (final RemotePlayerSprite s : remotePlayers.values()) s.remove();
        remotePlayers.clear();

        final AdventureNetSession session = AdventureNetSession.getInstance();
        if (session.isMultiplayer) {
            if (session.isActiveClient() && session.clientLobby != null) {
                // Force re-registration so registerClientListener() captures `this` stage.
                session.clientLobby.clearEventListener();
                // Spawn remote sprites at our own player position rather than (0, 0).  In a dungeon
                // (0, 0) is the bottom-left corner of the tile map and is usually off-screen, so a
                // newly-created remote sprite stayed invisible until PLAYER_MOVE lerped it inward.
                // Spawning at our position keeps the sprite on-screen immediately; the next
                // PLAYER_MOVE packet will move it to the actual remote location.
                final float spawnX = (player != null) ? player.pos().x : 0f;
                final float spawnY = (player != null) ? player.pos().y : 0f;
                System.out.println("[AdventureMP] client " + getClass().getSimpleName()
                        + ".enter: remotePlayerNames=" + session.remotePlayerNames
                        + " mySlot=" + session.mySlotIndex
                        + " slotsInPoi=" + session.slotsInPoi
                        + " spawn=(" + spawnX + "," + spawnY + ")");
                for (final Entry<Integer, String> entry : session.remotePlayerNames.entrySet()) {
                    final int slot = entry.getKey();
                    if (slot == session.mySlotIndex) continue;
                    final String[] state = session.remotePlayerStates.get(slot);
                    final String spritePath = (state != null && state.length > 0)
                            ? state[0] : Current.player().spriteName();
                    System.out.println("[AdventureMP] client adding remote sprite slot=" + slot
                            + " name=" + entry.getValue() + " sprite=" + spritePath);
                    addRemotePlayer(slot, entry.getValue(), spritePath, spawnX, spawnY);
                }
            } else if (session.isActiveHost() && session.serverLobby != null) {
                // Recreate sprites for all currently connected remote clients.
                if (session.clientLobby != null && session.clientLobby.getLocalPlayer() >= 0) {
                    session.mySlotIndex = session.clientLobby.getLocalPlayer();
                }
                for (int i = 1; i < session.serverLobby.getNumberOfSlots(); i++) {
                    final LobbySlot s = session.serverLobby.getSlot(i);
                    if (s != null && s.getType() == LobbySlotType.REMOTE && s.getName() != null) {
                        final float[] allPos = session.serverLobby.getAllPositions();
                        // Fall back to host position if client position not yet received.
                        final float sx = allPos[i * 2] != 0f ? allPos[i * 2] : allPos[0];
                        final float sy = allPos[i * 2 + 1] != 0f ? allPos[i * 2 + 1] : allPos[1];
                        final String[] state = session.remotePlayerStates.get(i);
                        final String spritePath = (state != null && state.length > 0)
                                ? state[0] : Current.player().spriteName();
                        addRemotePlayer(i, s.getName(), spritePath, sx, sy);
                    }
                }
                // Callbacks so the host stage stays in sync for both new joins and state updates.
                final GameStage self = this;
                session.onPlayerJoinCallback = slotIndex -> {
                    final LobbySlot s2 = session.serverLobby.getSlot(slotIndex);
                    Gdx.app.postRunnable(() -> {
                        if (s2 != null && s2.getName() != null) {
                            final float[] allPos = session.serverLobby.getAllPositions();
                            // Use client's known position; fall back to host position if unknown.
                            final float sx = allPos[slotIndex * 2] != 0f ? allPos[slotIndex * 2] : allPos[0];
                            final float sy = allPos[slotIndex * 2 + 1] != 0f ? allPos[slotIndex * 2 + 1] : allPos[1];
                            final String[] state = session.remotePlayerStates.get(slotIndex);
                            final String spritePath = (state != null && state.length > 0)
                                    ? state[0] : Current.player().spriteName();
                            self.addRemotePlayer(slotIndex, s2.getName(), spritePath, sx, sy);
                        }
                    });
                };
                session.onRemotePlayerStateCallback = (slotIndex, spriteName) ->
                        Gdx.app.postRunnable(() -> self.applyRemotePlayerSprite(slotIndex, spriteName));
                // BATTLE_REQUEST: a client asked us to start a co-op battle.  Run the host's
                // beginDuel path on the GDX thread so it broadcasts BATTLE_INIT to everyone.
                // We synthesize a transient EnemySprite from the EnemyData payload — it's only
                // used to satisfy the existing beginDuel signature and feeds DuelScene.initDuels.
                session.onBattleRequestCallback = (payload) -> {
                    if (!(payload instanceof forge.adventure.data.EnemyData)) return;
                    final forge.adventure.data.EnemyData enemyData =
                            (forge.adventure.data.EnemyData) payload;
                    Gdx.app.postRunnable(() -> {
                        final forge.adventure.character.EnemySprite mob =
                                new forge.adventure.character.EnemySprite(enemyData);
                        MapStage.getInstance().beginDuel(mob);
                    });
                };
            }
        }

        if (!extraAnnouncement.isEmpty()) {
            showImageDialog(extraAnnouncement, null, this::clearExtraAnnouncement);
        }
    }

    public void leave() {
        stop();
    }

    public boolean isColliding(Rectangle boundingRect) {
        return false;
    }

    public void prepareCollision(Vector2 pos, Vector2 direction, Rectangle boundingRect) {
    }

    public Vector2 adjustMovement(Vector2 direction, Rectangle boundingRect) {
        Vector2 adjDirX = direction.cpy();
        Vector2 adjDirY = direction.cpy();
        boolean foundX = false;
        boolean foundY = false;
        while (true) {

            if (!isColliding(new Rectangle(boundingRect.x + adjDirX.x, boundingRect.y + adjDirX.y, boundingRect.width, boundingRect.height))) {
                foundX = true;
                break;
            }
            if (adjDirX.x == 0)
                break;

            if (adjDirX.x >= 0)
                adjDirX.x = Math.max(0, adjDirX.x - 0.2f);
            else
                adjDirX.x = Math.min(0, adjDirX.x + 0.2f);
        }
        while (true) {
            if (!isColliding(new Rectangle(boundingRect.x + adjDirY.x, boundingRect.y + adjDirY.y, boundingRect.width, boundingRect.height))) {
                foundY = true;
                break;
            }
            if (adjDirY.y == 0)
                break;

            if (adjDirY.y >= 0)
                adjDirY.y = (Math.max(0, adjDirY.y - 0.2f));
            else
                adjDirY.y = (Math.min(0, adjDirY.y + 0.2f));
        }
        if (foundY && foundX)
            return adjDirX.len() > adjDirY.len() ? adjDirX : adjDirY;
        else if (foundY)
            return adjDirY;
        else if (foundX)
            return adjDirX;
        return Vector2.Zero.cpy();
    }

    protected void teleported(Vector2 position) {

    }

    public void setPosition(Vector2 position) {
        getPlayerSprite().setPosition(position);
        teleported(position);
    }

    public void resetPlayerLocation() {
        PointOfInterest poi = Current.world().findPointsOfInterest("Spawn");
        if (poi != null) {
            Forge.advFreezePlayerControls = true;
            getPlayerSprite().setAnimation(CharacterSprite.AnimationTypes.Death);
            getPlayerSprite().playEffect(Paths.EFFECT_BLOOD, 0.5f);
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                showImageDialog(Current.generateDefeatMessage(true), getDefeatBadge(),
                    () -> FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new CoverScreen(() -> {
                        Forge.advFreezePlayerControls = false;
                        WorldStage.getInstance().setPosition(new Vector2(poi.getPosition().x - 16f, poi.getPosition().y + 16f));
                        WorldStage.getInstance().loadPOI(poi);
                        WorldSave.getCurrentSave().autoSave();
                        Forge.clearTransitionScreen();
                    }, Forge.takeScreenshot()))));
                }
            }, 1f);
        }//Spawn shouldn't be null
    }

    public void defeatedFromBoss() {
        if (!Current.player().hasEquippedItem())
            return;
        Forge.advFreezePlayerControls = true;
        getPlayerSprite().setAnimation(CharacterSprite.AnimationTypes.Hit);
        getPlayerSprite().playEffect(Paths.EFFECT_BLOOD, 0.5f);
        Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                showImageDialog(Current.generateDefeatMessage(false), getDefeatBadge(), () -> Forge.advFreezePlayerControls = false);
            }
        }, 1f);
    }

    private FBufferedImage getDefeatBadge() {
        FileHandle defeat = Config.instance().getFile("ui/defeat.png");
        if (defeat.exists()) {
            TextureRegion tr = new TextureRegion(Forge.getAssets().getTexture(defeat, true, false));
            tr.flip(true, false);
            return new FBufferedImage(176, 200) {
                @Override
                protected void draw(Graphics g, float w, float h) {
                    g.drawImage(tr, 0, 0, 176, 200);
                }
            };
        }
        return null;
    }

    public void setExtraAnnouncement(String message) {
        extraAnnouncement = message;
    }

    public void clearExtraAnnouncement() {
        extraAnnouncement = "";
    }
}
