package forge.gamemodes.net.client;

import forge.gamemodes.net.adventure.AdventureNetEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Client-side lobby for Adventure mode multiplayer.
 *
 * Receives AdventureNetEvents dispatched by AdventureProtocolHandler and routes
 * them to a listener registered by forge-gui-mobile code (MapStage / DuelScene).
 *
 * forge-gui cannot reference WorldData or EnemyData (forge-gui-mobile types) directly,
 * so the raw AdventureNetEvent is forwarded as-is; the listener casts the payload.
 */
public class ClientAdventureLobby extends ClientGameLobby {

    /**
     * Listener registered by MapStage (forge-gui-mobile) to handle incoming events.
     * Called on the Netty IO thread — implementations must post libGDX mutations
     * to the rendering thread via Gdx.app.postRunnable().
     */
    private Consumer<AdventureNetEvent> eventListener;

    /** Events buffered before the listener is registered (e.g. MAP_SYNC on fast connections). */
    private final List<AdventureNetEvent> pendingEvents = new ArrayList<>();

    public ClientAdventureLobby() {
        super();
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Register the event listener.  Called by MapStage after joining.
     * Replays any events buffered before the listener was available.
     */
    public void setEventListener(final Consumer<AdventureNetEvent> listener) {
        this.eventListener = listener;
        if (listener != null) {
            for (final AdventureNetEvent buffered : pendingEvents) {
                listener.accept(buffered);
            }
            pendingEvents.clear();
        }
    }

    /** Clears the event listener so the next subsystem (e.g. MapStage) can register. */
    public void clearEventListener() {
        this.eventListener = null;
    }

    /**
     * Explicitly forward an event to the pending queue.
     * Use this inside a listener that only handles some event types so that
     * unhandled events are not silently dropped before the next listener registers.
     */
    public void forwardToPending(final AdventureNetEvent event) {
        pendingEvents.add(event);
    }

    // -------------------------------------------------------------------------
    // Dispatch — called by AdventureProtocolHandler on the Netty IO thread
    // -------------------------------------------------------------------------

    public void dispatch(final AdventureNetEvent event) {
        if (eventListener != null) {
            eventListener.accept(event);
        } else {
            pendingEvents.add(event);
        }
    }

    public boolean hasEventListener() {
        return eventListener != null;
    }

    // -------------------------------------------------------------------------
    // GameLobby overrides
    // -------------------------------------------------------------------------

    @Override protected void onGameStarted()         { }
}
