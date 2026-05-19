package forge.gamemodes.net.server;

import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.adventure.AdventureNetEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gui.interfaces.IGuiGame;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side lobby for Adventure mode multiplayer.
 * Supports up to 4 human players (1 LOCAL host + 3 OPEN/REMOTE clients).
 */
public class ServerAdventureLobby extends GameLobby {

    private AdventureNetEvent worldSnapshot;
    private final float[] preBattleX = new float[4];
    private final float[] preBattleY = new float[4];
    private final ConcurrentHashMap<Integer, Integer> objectLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String[]> playerStates = new ConcurrentHashMap<>();
    private int moveSequence = 0;
    private final float[] allPositions = new float[8];
    private java.util.Timer heartbeatTimer;

    public ServerAdventureLobby() {
        super(true);
        addSlot(new LobbySlot(LobbySlotType.LOCAL,
                localName(), localAvatarIndices()[0], localSleeveIndices()[0],
                0, true, false, Collections.emptySet()));
        addSlot(new LobbySlot(LobbySlotType.OPEN, null, -1, -1,
                1, false, false, Collections.emptySet()));
    }

    public void setWorldSnapshot(final AdventureNetEvent snapshot) {
        this.worldSnapshot = snapshot;
    }

    public AdventureNetEvent getWorldSnapshot() {
        return worldSnapshot;
    }

    public synchronized int connectPlayer(final String name, final int avatarIndex, final int sleeveIndex) {
        System.out.println("[AdventureMP] connectPlayer(" + name + ") slots before=" + getNumberOfSlots());
        final int nSlots = getNumberOfSlots();
        for (int index = 0; index < nSlots; index++) {
            final LobbySlot slot = getSlot(index);
            if (slot.getType() == LobbySlotType.OPEN) {
                slot.setType(LobbySlotType.REMOTE);
                slot.setName(name);
                slot.setAvatarIndex(avatarIndex);
                slot.setSleeveIndex(sleeveIndex);

                updateView(false);

                // Notify existing players that a new player joined
                final float spawnX = allPositions[0];
                final float spawnY = allPositions[1];
                FServerManager.getInstance().broadcast(new AdventureNetEvent(
                        AdventureNetEvent.Type.PLAYER_JOIN,
                        new String[]{ String.valueOf(index), name, String.valueOf(spawnX), String.valueOf(spawnY) }));

                final forge.gamemodes.net.adventure.AdventureNetSession session =
                        forge.gamemodes.net.adventure.AdventureNetSession.getInstance();
                if (session.onPlayerJoinCallback != null) {
                    session.onPlayerJoinCallback.accept(index);
                }
                System.out.println("[AdventureMP] connectPlayer(" + name + ") assigned slot=" + index + " slots after=" + getNumberOfSlots());
                return index;
            }
        }
        System.err.println("[AdventureMP] connectPlayer(" + name + ") — no open slot found, slots=" + getNumberOfSlots());
        return -1;
    }

    public void disconnectPlayer(final int index) {
        final LobbySlot slot = getSlot(index);
        if (slot == null) return;
        slot.setType(LobbySlotType.OPEN);
        slot.setName(StringUtils.EMPTY);
        slot.setIsReady(false);
        updateView(false);
        FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.PLAYER_LEAVE, index));

        // Only stop heartbeat if no more remote players are connected
        boolean anyRemote = false;
        for (int i = 1; i < getNumberOfSlots(); i++) {
            if (getSlot(i).getType() == LobbySlotType.REMOTE) {
                anyRemote = true;
                break;
            }
        }
        if (!anyRemote) {
            stopHeartbeat();
        }
    }

    public void updateHostPosition(final float x, final float y) {
        allPositions[0] = x;
        allPositions[1] = y;
    }

    public void updateClientPosition(final int slot, final float x, final float y) {
        if (slot >= 1 && slot <= 3) {
            allPositions[slot * 2]     = x;
            allPositions[slot * 2 + 1] = y;
        }
    }

    public float[] getAllPositions() {
        return allPositions.clone();
    }

    public void broadcastAllPositions() {
        final AdventureNetEvent event = new AdventureNetEvent(
                AdventureNetEvent.Type.PLAYER_MOVE, allPositions.clone());
        event.sequenceNumber = ++moveSequence;
        FServerManager.getInstance().broadcast(event);
    }

    public void broadcastHostJoin(final String name, final float spawnX, final float spawnY) {
        FServerManager.getInstance().broadcast(new AdventureNetEvent(
                AdventureNetEvent.Type.PLAYER_JOIN,
                new String[]{ "0", name, String.valueOf(spawnX), String.valueOf(spawnY) }));
    }

    /**
     * Synchronize the current world state and existing player list to a newly connected client.
     * This ensures the client knows about the host and other peers already in the session.
     */
    public void syncExistingPlayersTo(final RemoteClient client) {
        // 1. Send world snapshot (MAP_SYNC) first
        if (worldSnapshot != null) {
            System.out.println("[AdventureMP] Sending world snapshot to client " + client.getIndex());
            client.send(worldSnapshot);
        }

        // 2. Send PLAYER_JOIN for all currently occupied slots (including the host at slot 0)
        for (int i = 0; i < getNumberOfSlots(); i++) {
            final LobbySlot slot = getSlot(i);
            if (slot.getType() != LobbySlotType.OPEN && i != client.getIndex()) {
                final float x = allPositions[i * 2];
                final float y = allPositions[i * 2 + 1];
                System.out.println("[AdventureMP] Syncing existing player slot " + i + " (" + slot.getName() + ") to new client");
                client.send(new AdventureNetEvent(
                        AdventureNetEvent.Type.PLAYER_JOIN,
                        new String[]{ String.valueOf(i), slot.getName(), String.valueOf(x), String.valueOf(y) }));

                // 3. Send cached PLAYER_STATE if available
                final String[] state = playerStates.get(i);
                if (state != null) {
                    System.out.println("[AdventureMP] Syncing cached state for slot " + i + " to new client");
                    client.send(new AdventureNetEvent(AdventureNetEvent.Type.PLAYER_STATE, state));
                }
            }
        }
    }

    /** Broadcast host sprite name and HP to all clients so they display the correct art. */
    public void broadcastHostState(final String spriteName, final String hp) {
        final String[] payload = new String[]{ "0", spriteName, hp };
        playerStates.put(0, payload);
        FServerManager.getInstance().broadcast(new AdventureNetEvent(
                AdventureNetEvent.Type.PLAYER_STATE, payload));
    }

    /** Update cached state for a client and broadcast to others. */
    public void onClientState(final int slot, final String spriteName, final String hp) {
        final String[] payload = new String[]{ String.valueOf(slot), spriteName, hp };
        playerStates.put(slot, payload);
        FServerManager.getInstance().broadcast(new AdventureNetEvent(
                AdventureNetEvent.Type.PLAYER_STATE, payload));
    }

    public void onClientPlayerState(final String[] data) {
        if (data == null || data.length < 3) return;
        final String name = data[0];
        int slotIndex = -1;
        for (int i = 0; i < getNumberOfSlots(); i++) {
            final LobbySlot s = getSlot(i);
            if (s != null && name.equals(s.getName())) {
                slotIndex = i;
                break;
            }
        }
        if (slotIndex < 0) return;
        playerStates.put(slotIndex, data);
        // Populate session map so the host's GameStage can look up remote sprites correctly.
        final forge.gamemodes.net.adventure.AdventureNetSession session =
                forge.gamemodes.net.adventure.AdventureNetSession.getInstance();
        session.remotePlayerStates.put(slotIndex, new String[]{ data[1], data[2] });
        final java.util.function.BiConsumer<Integer, String> cb = session.onRemotePlayerStateCallback;
        if (cb != null) cb.accept(slotIndex, data[1]);
        FServerManager.getInstance().broadcast(new AdventureNetEvent(
                AdventureNetEvent.Type.PLAYER_STATE,
                new String[]{ String.valueOf(slotIndex), data[1], data[2] }));
    }

    public String[] getPlayerState(final int slotIndex) {
        return playerStates.get(slotIndex);
    }

    public void onClientMove(final float[] data) {
        if (data.length == 3) {
            updateClientPosition((int) data[0], data[1], data[2]);
        }
    }

    public boolean tryLockObject(final int objectId, final int playerIndex) {
        if (objectLocks.putIfAbsent(objectId, playerIndex) == null) {
            FServerManager.getInstance().broadcast(new AdventureNetEvent(
                    AdventureNetEvent.Type.OBJECT_LOCK,
                    new int[]{ objectId, playerIndex }));
            return true;
        }
        return false;
    }

    public void unlockObject(final int objectId) {
        objectLocks.remove(objectId);
        FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.OBJECT_UNLOCK, objectId));
    }

    public void initiateBattle(final Serializable enemyDataPayload, final float[] currentPositions) {
        for (int i = 0; i < 4 && i * 2 + 1 < currentPositions.length; i++) {
            preBattleX[i] = currentPositions[i * 2];
            preBattleY[i] = currentPositions[i * 2 + 1];
        }
        FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.BATTLE_INIT, enemyDataPayload));
    }

    public void onBattleEnd(final boolean humansWon) {
        FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.BATTLE_END, humansWon));
    }

    public void broadcastPoiEntry(final String poiId) {
        FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.PLAYER_ENTER_POI, poiId));
    }

    public void broadcastPoiExit() {
        FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.PLAYER_EXIT_POI, null));
    }

    public float getPreBattleX(final int slotIndex) { return preBattleX[slotIndex]; }
    public float getPreBattleY(final int slotIndex) { return preBattleY[slotIndex]; }

    @Override public boolean hasControl()           { return true; }
    @Override public boolean mayEdit(final int i)   { final LobbySlot s = getSlot(i); return s != null && s.getType() != LobbySlotType.REMOTE && s.getType() != LobbySlotType.OPEN; }
    @Override public boolean mayControl(final int i){ final LobbySlot s = getSlot(i); return s != null && s.getType() != LobbySlotType.REMOTE; }
    @Override public boolean mayRemove(final int i) { return i >= 2; }
    @Override protected IGuiGame getGui(final int i){ return FServerManager.getInstance().getGui(i); }
    @Override protected void onGameStarted() { }

    @Override
    public Runnable startGame() {
        int active = 0;
        for (int i = 0; i < getNumberOfSlots(); i++) {
            final LobbySlot s = getSlot(i);
            if (s != null && s.getType() != LobbySlotType.OPEN) {
                active++;
            }
        }
        if (active < 2) {
            System.err.println("[AdventureMP] startGame: need at least 2 players, have " + active);
            return null;
        }

        // Require every connected client to have signalled readiness (ready checkbox ticked).
        // Clients auto-tick this once their lobby data finishes loading.
        for (int i = 0; i < getNumberOfSlots(); i++) {
            final LobbySlot s = getSlot(i);
            if (s != null && s.getType() == LobbySlotType.REMOTE && !s.isReady()) {
                System.err.println("[AdventureMP] startGame blocked: " + s.getName() + " (slot " + i + ") not ready");
                FServerManager.getInstance().broadcast(new MessageEvent(
                        "[Adventure] Waiting for " + s.getName() + " to finish loading before starting..."));
                return null;
            }
        }

        final forge.gamemodes.net.adventure.AdventureNetSession session =
                forge.gamemodes.net.adventure.AdventureNetSession.getInstance();
        byte[] saveBytes = null;
        if (session.worldSaveBytes != null) {
            try {
                saveBytes = session.worldSaveBytes.get();
            } catch (Exception e) {
                System.err.println("ServerAdventureLobby: failed to serialize world save: " + e.getMessage());
            }
        }

        FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.WORLD_START, saveBytes));

        startHeartbeat();

        return () -> {
            if (session.onWorldStart != null) {
                session.onWorldStart.run();
            }
        };
    }

    private synchronized void startHeartbeat() {
        if (heartbeatTimer != null) return;
        heartbeatTimer = new java.util.Timer("AdventureHeartbeat", true);
        heartbeatTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() {
                try {
                    broadcastAllPositions();
                } catch (Exception e) {
                    System.err.println("[AdventureMP] Heartbeat error: " + e.getMessage());
                }
            }
        }, 50, 50);
    }

    public synchronized void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    @Override
    protected void onMatchOver() {
        for (int i = 0; i < getNumberOfSlots(); i++) {
            final LobbySlot slot = getSlot(i);
            if (slot != null) slot.setIsReady(false);
        }
        super.onMatchOver();
        FServerManager.getInstance().clearPlayerGuis();
        FServerManager.getInstance().updateLobbyState();
        stopHeartbeat();
    }
}
