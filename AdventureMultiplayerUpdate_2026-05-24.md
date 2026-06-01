# Adventure Multiplayer Bug Fix Update
**Date**: May 24, 2026

## Overview
This update resolves three follow-on bugs from the May 23 client-deck sync work. When a multiplayer client entered a battle, they had no commander (commander section was dropped from the network payload), no inventory buffs (item and blessing effects were never serialized), and no rewards on win (the `BATTLE_END` handler raced against `DuelScene.GameEnd()` and tried to switch scenes while the match screen was still up).

## Key Changes

### 1. Client Commander Section Missing in Multiplayer Battles (`GameStage.java`, `DuelScene.java`)
*   **Issue**: When a client entered a duel in Commander mode, their deck always fell back to the host's commander (or got the placeholder `Atogatog` injected by `applyAdventureCommandZoneRules`). The client's actual commander pick never made it across the wire.
*   **Root Causes**:
    1.  `GameStage.PLAYER_JOIN` handler serialized only `myDeck.getMain().toCardList(...)` into `PLAYER_STATE` — the `DeckSection.Commander` pool was never included.
    2.  `DuelScene.buildRemoteDeck()` only reconstructed `DeckSection.Main` from the received state, so even if commander cards had been sent they would have been discarded.
*   **Fixes**:
    *   **`GameStage.java`** — `PLAYER_JOIN` handler now also reads `myDeck.get(DeckSection.Commander)` and serializes its `toCardList("\n")` output as `state[4]` (empty string if the deck has no commander section).
    *   **`DuelScene.java`** — `buildRemoteDeck()` checks for `state[4]` and, when present, parses it via `CardPool.fromCardList(...)` and installs it as `DeckSection.Commander` on the reconstructed `Deck`. The existing `applyAdventureCommandZoneRules` pass then runs on the correctly-populated deck.

### 2. Client Inventory Buffs Not Applied (`GameStage.java`, `DuelScene.java`)
*   **Issue**: Equipped items and the active blessing produced no effect for the client during a multiplayer battle — no life modifier, no extra starting hand, no extra mana shards, no start-of-battle cards on the battlefield or in the command zone.
*   **Root Cause**: The host built the client's `RegisteredPlayer` slot in `DuelScene.enter()` with just `deck`, `name`, and `startingLife`. The `addEffects()` aggregation that applies items/blessings was only ever invoked for `humanPlayer` (the host's own slot). The client had no channel to communicate its inventory state to the host.
*   **Fixes**:
    *   **`GameStage.java`** — The client's `PLAYER_STATE` send was extended with five new fields covering the aggregate of all equipped items plus the active blessing:
        *   `state[5]` = total `lifeModifier` (int)
        *   `state[6]` = total `changeStartCards` (int)
        *   `state[7]` = total mana shards (`advPlayer.getShards()` + all `extraManaShards`)
        *   `state[8]` = newline-separated card names that should start on the battlefield
        *   `state[9]` = newline-separated card names that should start in the command zone
    *   **`DuelScene.java`** — Added `applyRemotePlayerEffects(RegisteredPlayer, String[])`, called immediately after `remotePlayer.setStartingLife(...)` in the host's remote-player setup loop. The helper:
        1.  Adjusts `startingLife` by `lifeMod` (with `Math.max(1, ...)` floor matching `addEffects`).
        2.  Adjusts `startingHand` by `handMod`.
        3.  Calls `setManaShards(shards)` with the pre-aggregated total.
        4.  Resolves each card name in `state[8]` / `state[9]` via `FModel.getMagicDb().getCommonCards().getCard(name)` (and falls back to `getAllTokens().getToken(name)`) — same lookup path `EffectData.startBattleWithCards()` uses, so tokens from items work correctly.
        5.  Calls `addExtraCardsOnBattlefield(...)` / `addExtraCardsInCommandZone(...)` and sets `setEnableETBCountersEffect(true)` if any effect was applied.

### 3. Client Receives No Rewards on Battle Win (`GameStage.java`, `DuelScene.java`)
*   **Issue**: When the client won a multiplayer battle, no `RewardScene` appeared — the client transitioned straight back to the world map empty-handed.
*   **Root Causes**:
    1.  The client's `DuelScene.GameEnd()` path always built an `endRunnable` that went to `GameScene.instance().enter()`, with no awareness of pending rewards.
    2.  The `BATTLE_END` handler tried to `Forge.switchScene(RewardScene.instance())` directly, but the match screen (or the `AdventureWinLose` overlay) was still showing — the switch either no-op'd or got immediately overwritten by the `GameEnd()` transition that fired right after via `MatchController.finishGame()` (driven by the Forge `afterGameEnd` protocol message).
    3.  Race condition: `BATTLE_END` (adventure protocol) and `afterGameEnd` (Forge game protocol) arrive in non-deterministic order. With `disableWinLose=false`, `BATTLE_END` can arrive *long* after the client has already returned to `GameScene`, because the host doesn't broadcast it until the host player dismisses their own win/lose screen.
*   **Fixes**:
    *   **`DuelScene.java`** — Two new fields: `pendingClientRewards` (the loot generated by `BATTLE_END`) and `networkClientExited` (set true when `GameEnd()`'s client path fires). Both are reset in `enterAsNetworkClient()` so state doesn't bleed between encounters.
    *   **`DuelScene.GameEnd()` client path** — Consumes `pendingClientRewards`. If rewards are present, the `endRunnable` builds out via `RewardScene.instance().loadRewards(...)` + `Forge.switchScene(RewardScene.instance())`. If not, it falls back to the original `GameScene.instance().enter()` behavior.
    *   **`DuelScene.setPendingClientRewards(Array<Reward>)`** — Public entry point called from the `BATTLE_END` handler. Stores the rewards. If `networkClientExited` is already true (rewards arrived after `GameEnd()` ran — the late-arrival case), it queues a double-nested `Gdx.app.postRunnable` to switch to `RewardScene` after the `GameScene.enter()` transition completes.
    *   **`GameStage.BATTLE_END` handler** — Stripped of its direct scene-switching. Now just calls `resumeAfterBattle()` for the host position restore, generates rewards via `RewardData.generate(...)`, calls `Current.player().win()`, and hands the array to `DuelScene.instance().setPendingClientRewards(rewards)`. Both race-condition cases are now handled centrally in `DuelScene`.

## Files Modified

| File | Change |
| :--- | :--- |
| `forge-gui-mobile/src/forge/adventure/stage/GameStage.java` | `PLAYER_JOIN` handler serializes commander cards + aggregated item/blessing effects into `PLAYER_STATE` (state[4..9]); `BATTLE_END` handler delegates reward display to `DuelScene.setPendingClientRewards` instead of switching scenes directly |
| `forge-gui-mobile/src/forge/adventure/scene/DuelScene.java` | `buildRemoteDeck` restores `DeckSection.Commander` from state[4]; new `applyRemotePlayerEffects` applies state[5..9] to the remote `RegisteredPlayer`; `GameEnd` client path now consumes `pendingClientRewards` and routes to `RewardScene`; new `setPendingClientRewards` handles both race-condition orderings; `enterAsNetworkClient` resets the new fields |

## Deployment Details
*   **Target**: `D:/Games/ForgeMTG`
*   **Build Command**: `apache-maven-3.9.15/bin/mvn.cmd clean install -pl forge-gui-mobile-dev -am -DskipTests -Dcheckstyle.skip=true` (total reactor time ~50s)
*   **Artifacts Replaced**:
    *   `forge-gui-mobile-dev-2.0.13-SNAPSHOT-jar-with-dependencies.jar` (the fat JAR all four launchers — `.cmd`, `.sh`, `.command`, and the Launch4j `.exe` — invoke)

---
*Generated by Claude Code*
