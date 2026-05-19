package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.adventure.AdventureNetEvent;
import forge.gamemodes.net.adventure.AdventureNetSession;
import forge.screens.FScreen;
import forge.screens.online.OnlineMenu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * ForgeScene wrapper for OnlineLobbyScreen used during Adventure multiplayer matchmaking.
 *
 * World sync strategy:
 *   Sending the full world save (~3MB compressed, ~100MB+ decompressed) causes OOM crashes on
 *   the client.  Instead we send a compact "WorldSyncData" containing just the world seed and
 *   the host player's spawn position.  The client calls World.generateNew(seed) to regenerate
 *   the identical deterministic map locally (fast, already parallelised), then places the
 *   player at the host's spawn position.
 *
 *   Player-specific state (deck, gold, items) intentionally uses the client's own save so each
 *   player retains their own character progression.
 */
public class LobbyScene extends ForgeScene {

    private static LobbyScene object;

    public static LobbyScene getInstance() {
        if (object == null)
            object = new LobbyScene();
        return object;
    }

    private LobbyScene() {}

    @Override
    public FScreen getScreen() {
        return OnlineMenu.OnlineScreen.Lobby.getScreen();
    }

    @Override
    public void enter() {
        super.enter();

        final AdventureNetSession session = AdventureNetSession.getInstance();
        System.out.println("[AdventureMP] LobbyScene.enter() isHost=" + session.isHost
                + " isMultiplayer=" + session.isMultiplayer);

        if (session.isHost) {
            session.mySlotIndex = 0; // Host is always slot 0.
            // Pre-compute on the rendering thread now so startGame()'s background thread
            // never touches LibGDX-owned objects (WorldSave, Config).
            final byte[] syncBytes = buildSyncPayload();
            session.worldSaveBytes = () -> syncBytes;
            // onWorldStart is invoked inside LoadingOverlay → Gdx.app.postRunnable,
            // so we're already on the rendering thread — no extra postRunnable needed.
            session.onWorldStart = () -> Forge.switchScene(GameScene.instance());
        } else {
            System.out.println("[AdventureMP] Client: scheduling listener, clientLobby="
                    + session.clientLobby);
            scheduleClientListenerRegistration(session);
        }
    }

    // -------------------------------------------------------------------------
    // Compact sync payload — seed + spawn position only
    // -------------------------------------------------------------------------

    /**
     * Builds a tiny sync payload containing only the world seed and the host
     * player's current world-space position.  The client regenerates the identical
     * world from the same seed (deterministic) instead of receiving a 100MB+ map.
     */
    public static byte[] buildSyncPayload() {
        System.out.println("[AdventureMP] buildSyncPayload() starting...");
        try {
            final WorldSave save = WorldSave.getCurrentSave();
            if (save == null || save.getWorld() == null || save.getWorld().getData() == null) {
                System.err.println("[AdventureMP] buildSyncPayload: no world data loaded");
                return null;
            }

            final long   seed  = save.getWorld().getSeed();
            final float  posX  = save.getPlayer().getWorldPosX();
            final float  posY  = save.getPlayer().getWorldPosY();
            final String plane = forge.adventure.util.Config.instance().getPlane();

            System.out.println("[AdventureMP] seed=" + seed + " posX=" + posX + " posY=" + posY
                    + " plane=" + plane);

            final ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeLong(seed);
                oos.writeFloat(posX);
                oos.writeFloat(posY);
                oos.writeUTF(plane != null ? plane : "");
            }
            byte[] result = bos.toByteArray();
            System.out.println("[AdventureMP] buildSyncPayload() produced " + result.length + " bytes");
            return result;
        } catch (Exception e) {
            System.err.println("[AdventureMP] buildSyncPayload failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Applies the sync payload on the client: regenerates the world from the host's
     * seed, then positions the player at the host's spawn point.
     * Must be called on the GDX rendering thread (world generation touches LibGDX APIs).
     */
    public static void applySyncPayload(final byte[] bytes) {
        System.out.println("[AdventureMP] applySyncPayload() bytes=" + bytes.length);
        try {
            final long  seed;
            final float posX, posY;
            final String plane;

            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                 ObjectInputStream ois = new ObjectInputStream(bis)) {
                seed  = ois.readLong();
                posX  = ois.readFloat();
                posY  = ois.readFloat();
                plane = ois.readUTF();
            }

            System.out.println("[AdventureMP] applying seed=" + seed + " posX=" + posX
                    + " posY=" + posY + " plane=" + plane);

            final WorldSave save = WorldSave.getCurrentSave();

            final boolean generated = save.getWorld().generateNew(seed);
            save.getPlayer().setWorldPosX(posX);
            save.getPlayer().setWorldPosY(posY);

            if (!generated) {
                System.err.println("[AdventureMP] applySyncPayload: generateNew() failed — world may not match host!");
            } else {
                System.out.println("[AdventureMP] applySyncPayload SUCCESS");
            }
        } catch (Exception e) {
            System.err.println("[AdventureMP] applySyncPayload failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Client listener registration
    // -------------------------------------------------------------------------

    private void scheduleClientListenerRegistration(final AdventureNetSession session) {
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (session.clientLobby != null) {
                    System.out.println("[AdventureMP] Registering WORLD_START listener");
                    session.clientLobby.setEventListener(event -> {
                        System.out.println("[AdventureMP] Client received event: " + event.type
                                + " payload=" + (event.payload == null ? "null"
                                : (event.payload instanceof byte[]
                                        ? ((byte[]) event.payload).length + " bytes"
                                        : event.payload.getClass().getSimpleName())));
                        if (event.type == AdventureNetEvent.Type.WORLD_START) {
                            handleClientWorldStart(event);
                        } else {
                            // Forward unhandled events to pending so they survive the listener handoff
                            // to the GameStage listener registered once WorldStage is active.
                            session.clientLobby.forwardToPending(event);
                        }
                    });
                } else {
                    Gdx.app.postRunnable(this);
                }
            }
        });
    }

    private static void handleClientWorldStart(final AdventureNetEvent event) {
        System.out.println("[AdventureMP] handleClientWorldStart called");
        final byte[] syncBytes = (event.payload instanceof byte[]) ? (byte[]) event.payload : null;

        // applySyncPayload uses LibGDX Pixmap (world generation) — must be on render thread.
        Gdx.app.postRunnable(() -> {
            if (syncBytes != null && syncBytes.length > 0) {
                applySyncPayload(syncBytes);
            } else {
                System.out.println("[AdventureMP] No sync payload — using own world");
            }
            // Clear the LobbyScene listener so MapStage.registerClientListener() can take over
            // on the next act() tick to handle PLAYER_MOVE, PLAYER_JOIN, BATTLE_INIT, etc.
            final AdventureNetSession session = AdventureNetSession.getInstance();
            if (session.clientLobby != null) {
                session.clientLobby.clearEventListener();
            }
            Forge.switchScene(GameScene.instance());
        });
    }
}
