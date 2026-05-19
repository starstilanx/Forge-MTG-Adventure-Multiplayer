package forge.gamemodes.net.adventure;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.server.RemoteClient;

import java.io.Serializable;

/**
 * Envelope for all Adventure map network events.
 * Kept separate from ProtocolMethod/GuiGameEvent to avoid polluting match interfaces.
 * The payload type for each event type is documented on the Type enum.
 */
public class AdventureNetEvent implements NetEvent {

    private static final long serialVersionUID = 1L;

    public enum Type {
        /** Server → All clients. Payload: WorldData (full save snapshot). */
        MAP_SYNC,
        /** Client → Server (intent), then Server → All (confirmed delta).
         *  Payload: float[] { playerIndex, x, y } */
        PLAYER_MOVE,
        /** Server → All. Payload: String[] { name, spawnX, spawnY } */
        PLAYER_JOIN,
        /** Server → All. Payload: Integer playerIndex */
        PLAYER_LEAVE,
        /** Server → All. Payload: Serializable EnemyData — clients cast on receipt. */
        BATTLE_INIT,
        /** Server → All. Payload: Boolean — true if humans won. */
        BATTLE_END,
        /** Server → All. Payload: int[] { objectId, playerIndex } */
        OBJECT_LOCK,
        /** Server → All. Payload: Integer objectId */
        OBJECT_UNLOCK,
        /** Server → All. No payload. Signals all clients to close the lobby and enter the adventure world. */
        WORLD_START,
        /**
         * Client → Server. Payload: String[] { name, spriteName, hpStr, deckCardList }.
         * Server stores per-slot, then re-broadcasts to all as String[] { slotIndexStr, spriteName, hpStr }.
         */
        PLAYER_STATE,
        /** Server → All. Payload: String poiId — host entered this POI; clients should follow. */
        PLAYER_ENTER_POI,
        /** Server → All. No payload — host returned to the world map; clients should follow. */
        PLAYER_EXIT_POI,
    }

    public final Type type;
    public final Serializable payload;
    public int sequenceNumber;

    public AdventureNetEvent(final Type type, final Serializable payload) {
        this.type    = type;
        this.payload = payload;
    }

    @Override
    public void updateForClient(final RemoteClient client) {
        // Same event broadcast to all clients; no per-client mutation needed.
    }

    @Override
    public String toString() {
        return "AdventureNetEvent{" + type + ", seq=" + sequenceNumber + "}";
    }
}
