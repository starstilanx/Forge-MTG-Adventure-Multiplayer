# Adventure Multiplayer Update
**Date**: May 25, 2026

## Overview
This update fixes a cluster of multiplayer regressions and adds an initial multiplayer-draft pipeline. The bugs: the client's commander section and gear/blessing effects were silently dropped on every world-map re-entry; client rewards never appeared after a co-op win in Commander mode; any third or fourth player who tried to join broke the session entirely; a client-initiated combat encounter wouldn't pull the host into the duel; a race between the game-state thread and the EDT in `Tracker` crashed the host with a `ConcurrentModificationException` during co-op matches. The feature: hosts can now run a draft event with up to three other players, with each player drafting independently against AI from a shared seed and joining the host for co-op match rounds.

## Key Changes

### 1. Client Commander + Gear Effects Re-Dropped on World Re-Entry (`GameStage.java`, `WorldStage.java`)
*   **Issue**: After the May 24 work, the client's commander still failed to appear and equipped items / active blessings still produced no effect — even though the data was being sent. The state survived the initial PLAYER_JOIN but disappeared again every time the client returned to the world map.
*   **Root Cause**: Two places sent PLAYER_STATE from the client:
    1.  `GameStage.PLAYER_JOIN` handler (own join) sent the full 10-field payload with commander + aggregated item/blessing effects.
    2.  `WorldStage.enter()` sent only a 4-field payload (slot, sprite, hp, deck) — missing the commander section and all effect fields.
    The server's `onClientPlayerState()` overwrites the cached payload on every receipt, so the short payload from `WorldStage.enter()` clobbered the full payload from PLAYER_JOIN whenever the client re-entered the world map (initial entry after join, after leaving a POI, after a duel). When the host then started a battle, `buildRemoteDeck()` skipped commander (`state.length >= 5` failed) and `applyRemotePlayerEffects()` early-returned (`state.length < 10` failed).
*   **Fix**:
    *   **`GameStage.java`** — Extracted `buildClientPlayerStatePayload(int slotIndex)` as a static helper that produces the canonical 10-field payload (slot, sprite, hp, deck, commander, lifeMod, handMod, shards, bfCards, cmdCards). The PLAYER_JOIN "isMe" handler now calls this helper instead of inlining the build.
    *   **`WorldStage.java`** — `enter()` now calls the same helper, sending the full payload instead of the truncated 4-field version. Both senders are now identical, so re-entering the world map cannot overwrite a previously-cached full state with a partial one.

### 2. Client Receives No Rewards on Co-op Commander Win (`ViewWinLose.java`)
*   **Issue**: After a co-op win in Commander mode, the client never saw a RewardScene — they were left at the win/lose screen until they backed out manually to the world map empty-handed.
*   **Root Cause**: `DuelScene.enter()` sets `mainGameType = GameType.Commander` when in commander mode. The `ViewWinLose` switch only maps `Adventure` and `AdventureEvent` to `AdventureWinLose` — `Commander` (and any other unlisted type) falls through to the default `ControlWinLose`, whose `actionOnQuit` just hides the dialog and never calls `DuelScene.GameEnd()`. Both halves of the reward flow broke as a result:
    *   Host side: `GameEnd()` never fired, so `afterGameEnd()` never fired, so `serverLobby.onBattleEnd()` never broadcast `BATTLE_END` to the client.
    *   Client side: `GameEnd()` never fired, so `networkClientExited` stayed false; even if `BATTLE_END` did arrive, the late-arrival path in `setPendingClientRewards` (which requires `networkClientExited=true`) couldn't switch to RewardScene.
*   **Fix**:
    *   **`ViewWinLose.java`** — When `Forge.isMobileAdventureMode` is true and the switch would otherwise pick the default `ControlWinLose`, route to `AdventureWinLose` instead. This makes "Back to Adventure" fire `DuelScene.GameEnd()` for any game type that Adventure may use (Commander, Constructed, etc.), restoring both the host's BATTLE_END broadcast and the client's networkClientExited flag.
    *   This also incidentally fixes single-player Commander Adventure rewards if `disableWinLose=false`, which had the same root cause.

### 3. Multiplayer Draft Support — Shared Seed, Parallel Drafts (Feature)
*   **Goal**: Let up to 4 players (host + 3 clients) run an Adventure draft event together — each player drafts independently locally against AI, then everyone plays the co-op match rounds against the host's event opponents.
*   **Design**: "Option B" — shared seed, parallel drafts. The host's `AdventureEventData` (which already contains `eventSeed`, `format`, `cardBlockName`, `packConfiguration`) is sent verbatim to clients. Each client constructs the same draft pool from the shared seed, picks their own cards, and commits a deck. Once every client signals ready, the host's "Play round N" click triggers the existing co-op battle multiplayer flow.
*   **Changes**:
    *   **`AdventureNetEvent.java`** — New `EVENT_INIT` (server → all clients, payload is `AdventureEventData`) and `EVENT_READY` (client → server, payload is `Integer slotIndex`) event types.
    *   **`AdventureNetSession.java`** — Three new fields cleared on `reset()`:
        *   `activeEventData` (Object, holds the current event)
        *   `activeEventDeck` (Object, holds the client's drafted Deck — used to override the player's main deck in `buildClientPlayerStatePayload`)
        *   `eventReadyClients` (concurrent set of slot indices; host-side tracker for the ready barrier)
    *   **`EventScene.java`**:
        *   Host's entry-fee callbacks now call `broadcastEventInitIfHost()` after setting status to `Entered`, which serializes `currentEvent` and broadcasts `EVENT_INIT`.
        *   `refresh()`'s `Ready` and `Started` cases call `publishMultiplayerReadyState()` which stashes the drafted deck as `activeEventDeck`, sends a fresh `PLAYER_STATE` (so the host's `getPlayerState(slot)` sees the drafted deck before `DuelScene.enter()` reads it), and sends `EVENT_READY`.
        *   Client's `Started` case now shows "Waiting for host..." and disables the advance button; `startRound()` early-returns on client.
        *   Host's `startRound()` now gates on `allRemoteClientsEventReady()` (shows a "Waiting for other players to finish building their decks" dialog if any client is still drafting), then mirrors `MapStage.beginDuel()` by calling `serverLobby.initiateBattle()` to broadcast `BATTLE_INIT` so all clients enter the same co-op match.
    *   **`GameStage.java`**:
        *   New `EVENT_INIT` case in `registerClientListener()` deserializes the event data and switches the client to the same `EventScene`.
        *   `buildClientPlayerStatePayload()` prefers `AdventureNetSession.activeEventDeck` over `Current.player().getSelectedDeck()` when present, so the drafted deck (held in `AdventureEventData.registeredDeck`, not in the player's main deck slot) reaches the host.
    *   **`FServerManager.java`** — Server's `AdventureProtocolHandler` now recognizes `EVENT_READY` and adds the slot to `eventReadyClients`.
*   **Known rough edges**:
    *   Standings are host-driven; client-local records are cosmetic.
    *   The match opponent is always the host's `currentEvent.nextOpponent` — clients don't run their own tournament bracket.
    *   Nothing currently prevents a client from navigating away from the EventScene mid-draft; they can return via the world map if needed.

### 4. 3rd/4th Player Connection Broke Everything in Combat (`ServerAdventureLobby.java`)
*   **Issue**: With 3+ players, combat broke completely — the host couldn't even initiate a duel.
*   **Root Cause**: The lobby constructor allocated only **1 LOCAL + 1 OPEN slot** (total 2). The 2nd player to connect claimed the OPEN slot and turned it REMOTE. When the 3rd or 4th player connected, `connectPlayer()` iterated 2 slots, found no OPEN slot, and returned -1. That player never got a `mySlotIndex`, so every slot-keyed flow (sprite sync, PLAYER_STATE, DuelScene player-list build, EVENT_READY tracking) silently skipped them. The rest of the multiplayer code already assumed slots 0..3 (the `allPositions[8]` array, all the `getNumberOfSlots()` loops filtering by REMOTE/OPEN type) — the only thing missing was the slot allocation itself.
*   **First attempt (regressed)**: Pre-allocated **1 LOCAL + 3 OPEN** slots in the constructor. This broke the lobby UI: the host's lobby screen ships with a `cbPlayerCount` dropdown that defaults to 2, but `getNumPlayers()` reads the actual lobby slot count (now 4), so the UI rendered 4 player panels with two empty OPEN slots showing as awkward partial widgets. Worse, the 3 pre-allocated OPEN slots had `team=1,2,3` (one slot per team) which confused the lobby's team-grouping logic.
*   **Final fix**:
    *   **`ServerAdventureLobby.java`** — Constructor reverted to **1 LOCAL + 1 OPEN** (the original lobby UI behavior). Instead, `connectPlayer()` now grows the slot list on demand: when a connecting client finds no OPEN slot AND the lobby has fewer than `MAX_PLAYERS` (= 4) slots, it appends a fresh OPEN slot and immediately claims it as REMOTE. The 2nd, 3rd, and 4th clients each get a unique `mySlotIndex` without forcing the lobby UI to display empty player panels at startup. Extracted `findOpenSlot()` as a small helper.

### 5. Client-Initiated Combat Didn't Pull Host into the Duel (`MapStage.java`, `GameStage.java`, networking)
*   **Issue**: When the client collided with an enemy, only the client transitioned to a (broken) solo `DuelScene` — the host stayed on the map and was never drawn into the co-op battle.
*   **Root Cause**: `MapStage.beginDuel(mob)` was guarded as "host broadcasts BATTLE_INIT, then everyone enters DuelScene". The client side had no parallel path — when called by a client it skipped the broadcast and fell through into the local transition, starting a duel locally that the host knew nothing about.
*   **Fix**:
    *   **`AdventureNetEvent.java`** — New `BATTLE_REQUEST` event type (client → server, payload is the Serializable EnemyData).
    *   **`AdventureNetSession.java`** — New `onBattleRequestCallback` field; cleared in `reset()`. Held as `Consumer<Serializable>` so forge-gui doesn't depend on forge-gui-mobile's EnemyData.
    *   **`FServerManager.java`** — Server's `AdventureProtocolHandler` now dispatches `BATTLE_REQUEST` to `session.onBattleRequestCallback`.
    *   **`MapStage.beginDuel`** — Client branch added: sends `BATTLE_REQUEST` with the mob's EnemyData to the server and returns *without* transitioning locally. The host's BATTLE_INIT broadcast will be the trigger that pulls this client into `DuelScene.enterAsNetworkClient`.
    *   **`GameStage.enter` (host)** — Registers `session.onBattleRequestCallback`: deserializes the EnemyData, posts a runnable on the GDX thread that reconstructs an `EnemySprite` from it and calls `MapStage.getInstance().beginDuel(mob)`. That path runs the host's existing flow which broadcasts `BATTLE_INIT` to everyone (including the requester).

### 6. Client Doesn't See Host's Sprite in Dungeon — Spawn-Point Fallback + Diagnostics (`GameStage.java`)
*   **Issue**: When the client entered a dungeon, the host's sprite did not appear on the client's screen even though the host could see the client move correctly.
*   **Suspected cause**: `GameStage.enter()`'s client branch spawned remote sprites at `(0, 0)` — the bottom-left corner of the tile map, almost always off-screen. The sprite stayed invisible until a PLAYER_MOVE packet lerped it inward, and at lerp-speed 10/sec across a map that's hundreds of units wide that could take several seconds — long enough that it looked like the sprite was simply absent.
*   **Mitigation (deployed)**:
    *   **`GameStage.java`** — Client `enter()` now spawns remote sprites at the client's own player position rather than `(0, 0)`. The sprite is on-screen immediately; the next PLAYER_MOVE packet then lerps it to the actual remote location.
    *   Added diagnostic `System.out.println` lines on client `enter()` (`remotePlayerNames`, `mySlotIndex`, `slotsInPoi`, spawn coords) and on `PLAYER_MOVE` (`System.err.println` warning when a move for host slot 0 arrives in MapStage but no sprite exists). If the sprite is still missing after this change, those logs will pinpoint whether the issue is missing `remotePlayerNames` data or an unrelated rendering problem — pending user retest.

### 7. Host Crashed with ConcurrentModificationException During Co-op Matches (`Tracker.java`)
*   **Issue**: During multiplayer match progression the host crashed on the EDT with:
    ```
    java.util.ConcurrentModificationException
        at java.util.ArrayList$Itr.checkForComodification
        at forge.trackable.Tracker.getDelayedPropsFor(Tracker.java:79)
        at forge.gamemodes.net.server.DeltaSyncManager.mergeDelayedProps(DeltaSyncManager.java:306)
        ...
        at forge.gamemodes.net.server.RemoteClientGuiGame.showWaitingTimer
    ```
*   **Root Cause**: `Tracker.delayedPropChanges` is a plain `ArrayList` accessed from two threads — the game-state thread (calls `addDelayedPropChange` / `clearDelayed` / `unfreeze`) and the EDT (calls `getDelayedPropsFor` from `DeltaSyncManager.collectDeltas`). Single-player and the simpler multiplayer-match flows rarely lined up the threads, but the adventure co-op host fires `DeltaSyncManager` from `showWaitingTimer` while the game thread is still mutating the queue.
*   **Fix**:
    *   **`Tracker.java`** — All four touch points (`addDelayedPropChange`, `clearDelayed`, `unfreeze`, `getDelayedPropsFor`) now synchronize on `delayedPropChanges`. `unfreeze` and `getDelayedPropsFor` drain/copy to a local array under lock before iterating, so the actual work (`change.object.set(...)` and `result.put(...)`) happens outside the critical section.

## Files Modified

| File | Change |
| :--- | :--- |
| `forge-gui-mobile/src/forge/adventure/stage/GameStage.java` | Extracted `buildClientPlayerStatePayload` helper (full 10-field payload); PLAYER_JOIN handler delegates to it; added EVENT_INIT case in client listener; `buildClientPlayerStatePayload` prefers `AdventureNetSession.activeEventDeck` over the player's main deck; client `enter()` spawns remote sprites at the client's own player position (not `(0, 0)`); host `enter()` registers `onBattleRequestCallback` that runs `MapStage.beginDuel` on receipt; diagnostic logging added on client `enter()` and PLAYER_MOVE |
| `forge-gui-mobile/src/forge/adventure/stage/WorldStage.java` | `enter()` now calls `GameStage.buildClientPlayerStatePayload` instead of inlining a truncated 4-field payload |
| `forge-gui-mobile/src/forge/adventure/stage/MapStage.java` | `beginDuel()` client branch sends `BATTLE_REQUEST` to the host instead of starting a solo duel; returns without transitioning locally so the host's BATTLE_INIT broadcast is the trigger |
| `forge-gui-mobile/src/forge/screens/match/winlose/ViewWinLose.java` | Routes to `AdventureWinLose` for any game type when `Forge.isMobileAdventureMode` is true |
| `forge-gui-mobile/src/forge/adventure/scene/EventScene.java` | Host broadcasts EVENT_INIT after entry fee; client publishes EVENT_READY + fresh PLAYER_STATE on Ready/Started; host's `startRound` gates on all-ready and broadcasts BATTLE_INIT; client's "Play round N" disabled with "Waiting for host..." text |
| `forge-game/src/main/java/forge/trackable/Tracker.java` | All four `delayedPropChanges` touch points now synchronize on the list; `unfreeze` and `getDelayedPropsFor` drain/copy to a local array before iterating |
| `forge-gui/src/main/java/forge/gamemodes/net/adventure/AdventureNetEvent.java` | Added EVENT_INIT, EVENT_READY, and BATTLE_REQUEST event types |
| `forge-gui/src/main/java/forge/gamemodes/net/adventure/AdventureNetSession.java` | Added `activeEventData`, `activeEventDeck`, `eventReadyClients`, `onBattleRequestCallback`; all cleared in `reset()` |
| `forge-gui/src/main/java/forge/gamemodes/net/server/FServerManager.java` | `AdventureProtocolHandler` now recognizes EVENT_READY (updates `eventReadyClients`) and BATTLE_REQUEST (dispatches to `onBattleRequestCallback`) |
| `forge-gui/src/main/java/forge/gamemodes/net/server/ServerAdventureLobby.java` | Constructor kept at 1 LOCAL + 1 OPEN to preserve original lobby UI; `connectPlayer()` grows the slot list on demand up to `MAX_PLAYERS=4`; extracted `findOpenSlot()` helper |

## Deployment Details
*   **Target**: `D:/Games/ForgeMTG`
*   **Build Command**: `apache-maven-3.9.15/bin/mvn.cmd clean install -pl forge-gui-mobile-dev -am -DskipTests -Dcheckstyle.skip=true`
*   **Artifact Replaced**: `forge-gui-mobile-dev-2.0.13-SNAPSHOT-jar-with-dependencies.jar`

---
*Generated by Claude Code*
