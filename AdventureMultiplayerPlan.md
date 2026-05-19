# Adventure Mode Multiplayer Integration Plan

## Goal
Enable up to 4 players (1 host + 3 clients) to share a realtime Adventure map, move independently, interact with map objects, and fight AI enemies together in co-op. The host's save file is authoritative; clients are session-only participants.

---

## Verified Architectural Decisions

These were confirmed against the codebase before writing this plan. Do not re-litigate them during implementation.

| Decision | Verdict | Source |
|---|---|---|
| `GameType.Constructed` respects `teamNumber` for win/loss | ✅ Confirmed | `GameAction.java` `checkGameOverCondition()` checks surviving teams across all game types. Uses `GameEndReason.AllOpposingTeamsLost`. |
| `forge-gui-mobile` can access `forge-gui` classes | ✅ Confirmed | `forge-gui-mobile/pom.xml` lists `forge-gui` as a dependency. `AdventureNetSession` can live in `forge-gui`. |
| `WorldData` can be sent through existing Netty pipeline | ✅ Confirmed | `WorldData implements Serializable`. The existing `CompatibleObjectEncoder/Decoder` handles it without a custom serializer. |
| `WorldData` spawn point fields already exist | ✅ Confirmed | `WorldData.playerStartPosX` and `WorldData.playerStartPosY` are present. No new fields needed. |
| `MapStage.onActing(delta)` already runs every frame | ✅ Confirmed | The loop does not pause when the player is stationary. Pause is gated by `isPaused()`, `isDialogOnlyInput()`, and `Forge.advFreezePlayerControls`. A 20Hz broadcast tick requires only a delta accumulator, not a structural change. |
| `FServerManager` Netty pipeline | ✅ Confirmed | 9 handlers; `GameServerHandler` is last. `AdventureProtocolHandler` appends after it as handler #10. Same connection serves both map and duel phases — no reconnection needed at battle start. |

---

## Proposed Changes

### 1. `AdventureNetSession.java` — Transient Session Singleton

**Module:** `forge-gui`, package `forge.gamemodes.net.adventure`

Holds in-memory session state only. Never persisted to disk. Reset on Adventure exit to prevent stale state on next launch.

```java
public class AdventureNetSession {
    private static final AdventureNetSession INSTANCE = new AdventureNetSession();
    public static AdventureNetSession getInstance() { return INSTANCE; }

    public boolean isMultiplayer = false;
    public boolean isHost        = false;
    public ServerAdventureLobby serverLobby = null; // non-null on host
    public ClientAdventureLobby clientLobby = null; // non-null on client

    public void reset() {
        isMultiplayer = false;
        isHost        = false;
        serverLobby   = null;
        clientLobby   = null;
    }
}
```

---

### 2. `AdventureNetEvent.java` — Network Message Type

**Module:** `forge-gui`, package `forge.gamemodes.net.adventure`

A single serializable envelope for all adventure map events. Kept separate from `ProtocolMethod` / `GuiGameEvent` to avoid polluting match-game interfaces.

```java
public class AdventureNetEvent implements Serializable {
    public enum Type {
        MAP_SYNC,      // Server → All clients: full WorldData snapshot
        PLAYER_MOVE,   // Client → Server (intent) / Server → All (confirmed delta)
        PLAYER_JOIN,   // Server → All: new player appeared (includes spawn coords)
        PLAYER_LEAVE,  // Server → All: player disconnected
        BATTLE_INIT,   // Server → All: begin co-op duel
        BATTLE_END,    // Server → All: duel resolved, resume map
        OBJECT_LOCK,   // Server → All: map object claimed
        OBJECT_UNLOCK, // Server → All: map object released
    }

    public final Type type;
    public final Object payload;  // see payload table below
    public int sequenceNumber;    // incremented per broadcast; used for desync detection

    public AdventureNetEvent(Type type, Object payload) {
        this.type    = type;
        this.payload = payload;
    }
}
```

**Payload conventions:**

| Type | Payload class | Contents |
|---|---|---|
| `MAP_SYNC` | `WorldData` | Full save state; sent on join and on resync |
| `PLAYER_MOVE` | `float[]` `{playerIndex, x, y}` | Client sends intent; server rebroadcasts validated position |
| `PLAYER_JOIN` | `String[]` `{name, spawnX, spawnY}` | `spawnX/Y` from `WorldData.playerStartPosX/Y` |
| `PLAYER_LEAVE` | `Integer` — player index | |
| `BATTLE_INIT` | `EnemyData` | Clients use this to configure their side of `DuelScene` |
| `BATTLE_END` | `Boolean` — `true` = humans won | |
| `OBJECT_LOCK` | `int[]` `{objectId, playerIndex}` | |
| `OBJECT_UNLOCK` | `Integer` — objectId | |

---

### 3. `AdventureProtocolHandler.java` — Netty Handler

**Module:** `forge-gui`, package `forge.gamemodes.net.adventure`

Intercepts `AdventureNetEvent` messages and dispatches them to registered listeners. Passes everything else down the pipeline unchanged.

```java
public class AdventureProtocolHandler extends ChannelInboundHandlerAdapter {

    private final AdventureEventDispatcher dispatcher;

    public AdventureProtocolHandler(AdventureEventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof AdventureNetEvent)) {
            ctx.fireChannelRead(msg); // not our message — pass through
            return;
        }
        dispatcher.dispatch((AdventureNetEvent) msg, ctx);
    }
}
```

**Pipeline registration — two places:**

1. **`FServerManager.java`** (lines 148–158): append `new AdventureProtocolHandler(serverDispatcher)` after `GameServerHandler`.
2. **`FGameClient.java`**: append `new AdventureProtocolHandler(clientDispatcher)` in the client channel initializer.

**`GameServerHandler` passthrough:** Verify that `GameServerHandler.channelRead()` calls `ctx.fireChannelRead(msg)` for any message that is not a `GuiGameEvent`. If it currently drops unknowns silently, patch it to pass them through before this handler is added.

---

### 4. `ServerAdventureLobby.java`

**Module:** `forge-gui`, package `forge.gamemodes.net.server`  
**Extends:** `GameLobby` (mirrors `ServerGameLobby`)

Initializes with 1 LOCAL slot + 3 OPEN slots. Key differences from `ServerGameLobby`:
- `onGameStarted()` sends `MAP_SYNC` to each connecting client instead of launching a match.
- Maintains server-authoritative object locks.
- Exposes `broadcastPositions()` for the host's 20Hz tick.

```java
public class ServerAdventureLobby extends GameLobby {

    // objectId → playerIndex; -1 = unlocked
    private final Map<Integer, Integer> objectLocks = new ConcurrentHashMap<>();

    // Per-player pre-battle position for restoration after duel
    private final float[] preBattleX = new float[4];
    private final float[] preBattleY = new float[4];

    @Override
    public synchronized int connectPlayer(String name, int avatarIndex, int sleeveIndex) {
        int index = super.connectPlayer(name, avatarIndex, sleeveIndex);
        // 1. Send full MAP_SYNC to the newly connected client
        FServerManager.getInstance().send(index,
            new AdventureNetEvent(AdventureNetEvent.Type.MAP_SYNC, getCurrentWorldData()));
        // 2. Notify all other clients of the new player
        FServerManager.getInstance().broadcast(
            new AdventureNetEvent(AdventureNetEvent.Type.PLAYER_JOIN,
                new String[]{ name,
                    String.valueOf(getCurrentWorldData().playerStartPosX),
                    String.valueOf(getCurrentWorldData().playerStartPosY) }));
        return index;
    }

    @Override
    public void disconnectPlayer(int index) {
        super.disconnectPlayer(index);
        FServerManager.getInstance().broadcast(
            new AdventureNetEvent(AdventureNetEvent.Type.PLAYER_LEAVE, index));
    }

    /** Called from MapStage tick at 20Hz. */
    public void broadcastPositions(float[] positions) {
        FServerManager.getInstance().broadcast(
            new AdventureNetEvent(AdventureNetEvent.Type.PLAYER_MOVE, positions));
    }

    /** Returns true if lock was granted. */
    public boolean tryLockObject(int objectId, int playerIndex) {
        if (objectLocks.putIfAbsent(objectId, playerIndex) == null) {
            FServerManager.getInstance().broadcast(
                new AdventureNetEvent(AdventureNetEvent.Type.OBJECT_LOCK,
                    new int[]{ objectId, playerIndex }));
            return true;
        }
        return false;
    }

    public void unlockObject(int objectId) {
        objectLocks.remove(objectId);
        FServerManager.getInstance().broadcast(
            new AdventureNetEvent(AdventureNetEvent.Type.OBJECT_UNLOCK, objectId));
    }

    /**
     * Called when any player collides with an enemy.
     * Saves all positions, freezes the map, kicks off the duel on the host,
     * and notifies clients to transition to DuelScene.
     */
    public void initiateBattle(EnemyData enemy, float[] currentPositions) {
        System.arraycopy(currentPositions, 0, preBattleX, 0, 4); // save positions
        FServerManager.getInstance().broadcast(
            new AdventureNetEvent(AdventureNetEvent.Type.BATTLE_INIT, enemy));
        // Host-side DuelScene entry is triggered by the caller immediately after this.
    }

    /** Called by DuelScene after match resolves. Unpauses map and restores positions. */
    public void onBattleEnd(boolean humansWon) {
        FServerManager.getInstance().broadcast(
            new AdventureNetEvent(AdventureNetEvent.Type.BATTLE_END, humansWon));
        // MapStage unfreeze is handled by the receiver of BATTLE_END on all sides.
    }

    @Override public boolean hasControl()            { return true; }
    @Override public boolean mayEdit(int index)      { return index < getPlayerCount(); }
    @Override public boolean mayControl(int index)   { return index == 0; }
    @Override public boolean mayRemove(int index)    { return index >= 2; }
    @Override protected IGuiGame getGui(int index)   { return FServerManager.getInstance().getGui(index); }
    @Override protected void onGameStarted()         { /* map sync already done in connectPlayer */ }
}
```

---

### 5. `ClientAdventureLobby.java`

**Module:** `forge-gui`, package `forge.gamemodes.net.client`  
**Extends:** `GameLobby` (mirrors `ClientGameLobby`)

Routes incoming `AdventureNetEvent` messages to the client's `MapStage` and `DuelScene`. All `MapStage` mutations are posted to the libGDX rendering thread.

```java
public class ClientAdventureLobby extends GameLobby {

    private int localPlayerIndex = -1;

    public void setLocalPlayer(int index) { this.localPlayerIndex = index; }

    public void onMapSync(WorldData snapshot) {
        Gdx.app.postRunnable(() -> MapStage.instance().initFromNetworkSnapshot(snapshot));
    }

    public void onPlayerMove(int playerIndex, float x, float y) {
        if (playerIndex == localPlayerIndex) return; // ignore echo of our own position
        Gdx.app.postRunnable(() -> MapStage.instance().updateRemotePlayer(playerIndex, x, y));
    }

    public void onPlayerJoin(String name, float spawnX, float spawnY) {
        Gdx.app.postRunnable(() -> MapStage.instance().addRemotePlayer(name, spawnX, spawnY));
    }

    public void onPlayerLeave(int playerIndex) {
        Gdx.app.postRunnable(() -> MapStage.instance().removeRemotePlayer(playerIndex));
    }

    public void onBattleInit(EnemyData enemy) {
        Gdx.app.postRunnable(() -> DuelScene.instance().enterAsNetworkClient(enemy));
    }

    public void onBattleEnd(boolean humansWon) {
        Gdx.app.postRunnable(() -> MapStage.instance().resumeAfterBattle());
    }

    @Override public boolean hasControl()          { return false; }
    @Override public boolean mayEdit(int index)    { return index == localPlayerIndex; }
    @Override public boolean mayControl(int index) { return false; }
    @Override public boolean mayRemove(int index)  { return false; }
    @Override protected IGuiGame getGui(int index) { return null; }
    @Override protected void onGameStarted()       { }
}
```

---

### 6. `NetConnectUtil.java` Modifications

In both `host()` and `join(url, ...)`, check `AdventureNetSession` at the top and branch:

```java
// In host():
if (AdventureNetSession.getInstance().isMultiplayer) {
    ServerAdventureLobby lobby = new ServerAdventureLobby();
    AdventureNetSession.getInstance().serverLobby = lobby;
    FServerManager.getInstance().startServer(port, lobby);
    return; // skip standard ServerGameLobby setup
}

// In join():
if (AdventureNetSession.getInstance().isMultiplayer) {
    ClientAdventureLobby lobby = new ClientAdventureLobby();
    AdventureNetSession.getInstance().clientLobby = lobby;
    FGameClient client = new FGameClient(playerName, gui, hostname, port);
    client.setLobby(lobby);
    client.connect();
    return;
}
```

---

### 7. UI Entry Point

**In the Adventure main menu** (add two buttons alongside existing start options):

- **"Host Multiplayer"**: Sets `AdventureNetSession.isMultiplayer = true` and `isHost = true`, calls `NetConnectUtil.host()`, then loads the Adventure map normally. The host plays while clients join.
- **"Join Adventure"**: Opens an IP/port prompt. On confirm, sets `AdventureNetSession.isMultiplayer = true`, `isHost = false`, calls `NetConnectUtil.join(url, ...)`. On successful connect, the client receives `MAP_SYNC` and transitions directly to the `AdventureMapScene` without loading a local save.

**Spawn point:** Clients spawn at `WorldData.playerStartPosX` / `playerStartPosY` (fields confirmed present). These are included in the `PLAYER_JOIN` event broadcast to all existing clients when a new player connects.

---

### 8. `MapStage.java` Modifications

#### 8a. Host-side 20Hz broadcast tick

`onActing(delta)` already runs every frame — no structural change needed. Add a broadcast accumulator at the bottom of the method:

```java
// Fields:
private float netTickAccumulator = 0f;
private static final float NET_TICK_RATE = 0.05f; // 20Hz

// At the end of onActing(float delta):
if (AdventureNetSession.getInstance().isHost) {
    netTickAccumulator += delta;
    if (netTickAccumulator >= NET_TICK_RATE) {
        netTickAccumulator -= NET_TICK_RATE;
        AdventureNetSession.getInstance().serverLobby
            .broadcastPositions(collectPlayerPositions());
    }
}
```

`collectPlayerPositions()` returns a flat `float[]` of `{p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y}`.

#### 8b. `RemotePlayerSprite`

Add `List<RemotePlayerSprite> remotePlayers` (max 3 entries) to `MapStage`. Each entry:
- Holds `targetX`, `targetY` and an interpolated current position.
- In `onActing()`, lerps toward target: `curX += (targetX - curX) * delta * LERP_SPEED`.
- All mutations (create, update, destroy) must be called from `Gdx.app.postRunnable()` since network events arrive on a Netty IO thread.

New methods driven by `ClientAdventureLobby`:
- `addRemotePlayer(String name, float x, float y)` — create sprite at spawn.
- `updateRemotePlayer(int index, float x, float y)` — set target position.
- `removeRemotePlayer(int index)` — destroy sprite.
- `initFromNetworkSnapshot(WorldData snapshot)` — replace local state with received snapshot, bypassing save loading.

#### 8c. Map pause during battle

When `BATTLE_INIT` is received (host broadcasts, all sides handle), call `Forge.advFreezePlayerControls = true`. On `BATTLE_END`, call `Forge.advFreezePlayerControls = false` and restore positions. This reuses the existing freeze flag rather than introducing a new one.

---

### 9. Map Object Interaction

Before entering any interactive object (shop, dungeon, event node):

1. The client/host sends an `OBJECT_LOCK` request to `ServerAdventureLobby.tryLockObject()`.
2. If granted: `OBJECT_LOCK` broadcasts to all; the object shows as "occupied" to other players.
3. If denied: the requesting player sees "Occupied by [name]". Entry is blocked.
4. On exit: `OBJECT_UNLOCK` is sent; server clears the lock and broadcasts to all.

Lock state lives entirely in `ServerAdventureLobby.objectLocks` (server-authoritative). Clients only mirror the visual state.

---

### 10. `DuelScene.java` Modifications

#### Host path (`enter()`)

```java
if (AdventureNetSession.getInstance().isMultiplayer) {
    // Build players from all lobby slots instead of single humanPlayer
    List<RegisteredPlayer> players = new ArrayList<>();
    for (LobbySlot slot : AdventureNetSession.getInstance().serverLobby.getData().slots) {
        RegisteredPlayer rp = RegisteredPlayer.forVariants(slot.getDeck(), appliedVariants);
        rp.setTeamNumber(0); // all humans on Team 0
        // local slot uses GuiPlayer; remote slots use LobbyPlayerRemote
        rp.setPlayer(slot.isLocal() ? GamePlayerUtil.getGuiPlayer()
                                    : new LobbyPlayerRemote(slot.getIndex()));
        players.add(rp);
    }
    // Add AI enemy/enemies as Team 1 (existing logic unchanged)
    // ... existing enemy setup loop, with setTeamNumber(1) added ...

    // Save pre-battle positions before freezing
    float[] positions = collectPlayerPositions();
    AdventureNetSession.getInstance().serverLobby.initiateBattle(currentEnemy, positions);

    hostedMatch = MatchController.hostMatch();
    hostedMatch.startMatch(rules, appliedVariants, players, guiMap, musicPlaylist);
    // After match ends, onMatchOver() calls serverLobby.onBattleEnd()
}
```

`GameType.Constructed` is used (no variant change needed). Team-aware win conditions are handled by `GameAction.checkGameOverCondition()` — confirmed to fire `AllOpposingTeamsLost` when all of Team 1 is eliminated.

#### Client path (`enterAsNetworkClient(EnemyData enemy)`)

The client does **not** call `MatchController.hostMatch()`. Instead:
1. Show the `DuelScene` UI.
2. The existing `FGameClient` connection (already open from map sync phase) begins receiving `GuiGameEvent` messages from the host's match.
3. The client's `IGameController` (already wired through the lobby) handles all player decisions and sends them back to the host via the same connection.

No new connection is opened for the battle. The single TCP connection established at join time serves both map sync (via `AdventureProtocolHandler`) and match events (via `GameServerHandler`) throughout the session.

---

### 11. Host-Based Save State

- Host writes `WorldData` to disk normally — no changes to save logic.
- On client connect: `ServerAdventureLobby.connectPlayer()` wraps the live `WorldData` instance in a `MAP_SYNC` event and sends it via Netty. Since `WorldData implements Serializable` and both sides run `forge-gui-mobile`, the existing `CompatibleObjectEncoder/Decoder` handles serialization transparently.
- Client receives `MAP_SYNC`, calls `MapStage.initFromNetworkSnapshot(worldData)` on the libGDX thread. No local file is written.
- On reconnect: server re-sends current `MAP_SYNC`. Sequence number gaps in `PLAYER_MOVE` packets trigger an automatic `MAP_SYNC` resend.

---

## Verification Plan

### Automated Tests
- Unit test `ServerAdventureLobby.connectPlayer()` / `disconnectPlayer()` slot state transitions.
- Unit test `tryLockObject()`: concurrent lock requests from two threads — only one should succeed.
- Unit test `AdventureProtocolHandler`: `AdventureNetEvent` dispatched to handler; non-adventure message passed through via `ctx.fireChannelRead()`.
- Packet loss simulation: drop every 5th `PLAYER_MOVE`; verify sequence number detection triggers `MAP_SYNC` resend and client recovers without crash.

### Manual Verification Checklist
- [x] **Lobby & Handshake:** Client enters IP, connects, receives `MAP_SYNC`, and spawns at host's current location. (Implemented 2026-05-13)
- [x] **Realtime Movement (2P):** Both players move simultaneously; replicated smoothly on both screens at ~20Hz via authoritative server heartbeat. (Implemented 2026-05-14)
- [x] **Initial State Sync:** Late-joining clients receive host and peer data immediately upon connection. (Implemented 2026-05-15)
- [x] **Heartbeat Stability:** Movement sync persists even if individual clients disconnect or host enters menus. (Implemented 2026-05-15)
- [ ] **Realtime Movement (4P):** All 4 players move concurrently; no significant frame drop or desync.
- [ ] **Map Interaction Lock:** Player A enters a shop; Player B sees it as occupied and is blocked. After Player A exits, Player B can enter.
- [ ] **Battle Trigger:** Any player collides with an enemy; all players receive `BATTLE_INIT` and map freezes via `Forge.advFreezePlayerControls`.
- [ ] **DuelScene Transition:** All players successfully enter `DuelScene`. Host runs the match; clients are driven by the host over the existing connection.
- [ ] **Co-op Win:** All AI enemies eliminated → `AllOpposingTeamsLost` fires → all human players win collectively.
- [ ] **Co-op Loss:** All human players reach 0 life → game ends with AI team winning.
- [ ] **Return to Map:** All players exit `DuelScene` and restore to their saved pre-battle positions.
- [ ] **Late Join:** Client joins while host is mid-session; spawns at world default position with current `WorldData` snapshot state.
- [ ] **Reconnect / Resync:** Client disconnects and reconnects; receives updated `MAP_SYNC`; resumes without visible desync.
- [ ] **Save Authority:** After session ends, only the host's save file is updated. No local save exists on client machines.
