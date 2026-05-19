package forge.gamemodes.net.adventure;

import forge.gamemodes.net.client.ClientAdventureLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.server.ServerAdventureLobby;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Transient singleton holding the active Adventure multiplayer session state.
 *
 * Never persisted to disk — always reset between sessions so that a crash or
 * abnormal exit cannot leave the game stuck in multiplayer mode on next launch.
 *
 * Accessible from both forge-gui (lobby/networking code) and forge-gui-mobile
 * (MapStage, DuelScene) since forge-gui-mobile depends on forge-gui.
 */
public final class AdventureNetSession {

    private static final AdventureNetSession INSTANCE = new AdventureNetSession();

    private AdventureNetSession() { }

    public static AdventureNetSession getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Session state — all transient, never written to disk
    // -------------------------------------------------------------------------

    public boolean isMultiplayer = false;
    public boolean isHost        = false;

    /** Non-null when this instance is hosting. */
    public ServerAdventureLobby serverLobby = null;

    /** Non-null when this instance has joined as a client. */
    public ClientAdventureLobby clientLobby = null;

    /** The active FGameClient connection (client side only). Used to send events to the host. */
    public FGameClient client = null;

    /**
     * Slot index → { spriteName, hp } received via PLAYER_STATE broadcasts.
     * Populated on both host and client; used by GameStage (sprite update) and DuelScene (HP).
     */
    public final ConcurrentHashMap<Integer, String[]> remotePlayerStates = new ConcurrentHashMap<>();

    /**
     * Remote player names by slot index — accumulated from PLAYER_JOIN events on the client.
     * Used by GameStage.enter() to recreate sprites after stage transitions.
     */
    public final ConcurrentHashMap<Integer, String> remotePlayerNames = new ConcurrentHashMap<>();

    /** This client's assigned slot index (set when own PLAYER_JOIN is received). -1 until known. */
    public int mySlotIndex = -1;

    /**
     * Slot indices currently inside a POI (town/dungeon).
     * On the world map, PLAYER_MOVE updates are skipped for these slots because the
     * sender is broadcasting local map coordinates, not world coordinates.
     */
    public final java.util.Set<Integer> slotsInPoi =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Host-side callback: fired on the Netty IO thread when a new client connects.
     * Integer argument is the assigned slot index.  Callee must post GDX mutations via
     * Gdx.app.postRunnable().  Set by GameStage.enter() on the host; cleared on reset().
     */
    public java.util.function.Consumer<Integer> onPlayerJoinCallback = null;

    /**
     * Host-side callback: fired on the Netty IO thread when a PLAYER_STATE is received from a client.
     * Arguments: (slotIndex, spriteName).  Callee must post GDX mutations via Gdx.app.postRunnable().
     * Set by GameStage.enter() on the host; cleared on reset().
     */
    public java.util.function.BiConsumer<Integer, String> onRemotePlayerStateCallback = null;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Called when Adventure mode exits to ensure clean state on next launch. */
    public void reset() {
        isMultiplayer = false;
        isHost        = false;
        serverLobby   = null;
        clientLobby   = null;
        client        = null;
        onWorldStart  = null;
        worldSaveBytes = null;
        mySlotIndex   = -1;
        onPlayerJoinCallback = null;
        onRemotePlayerStateCallback = null;
        remotePlayerStates.clear();
        slotsInPoi.clear();
        remotePlayerNames.clear();
    }

    /**
     * Callback registered by forge-gui-mobile (LobbyScene) that transitions the
     * host into the adventure world when the lobby's Start button is clicked.
     * Called on the EDT by ServerAdventureLobby.startGame().
     */
    public Runnable onWorldStart = null;

    /**
     * Supplier registered by forge-gui-mobile that returns the current world save
     * serialized as a byte array.  Used by ServerAdventureLobby to include the
     * save data in the WORLD_START payload so clients can load the host's world.
     * May be null (clients then stay in their own world).
     */
    public java.util.function.Supplier<byte[]> worldSaveBytes = null;

    /** Convenience: true when this instance is an active multiplayer host. */
    public boolean isActiveHost() {
        return isMultiplayer && isHost && serverLobby != null;
    }

    /** Convenience: true when this instance is an active multiplayer client. */
    public boolean isActiveClient() {
        return isMultiplayer && !isHost && clientLobby != null;
    }
}
