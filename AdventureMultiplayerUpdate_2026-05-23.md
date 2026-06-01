# Adventure Multiplayer Bug Fix Update
**Date**: May 23, 2026

## Overview
This update resolves two critical bugs affecting multiplayer battle flow: clients entering duels with the wrong deck (host's deck instead of their own), and clients receiving no rewards after winning a battle.

## Key Changes

### 1. Client Deck Import in Multiplayer Battles (`ServerAdventureLobby.java`, `WorldStage.java`, `GameStage.java`)
*   **Issue**: When the host initiated a multiplayer battle, the `DuelScene` would build `RegisteredPlayer` entries for all slots but had no way to retrieve the client's actual deck. It fell back to the host's deck for every slot.
*   **Root Causes**:
    1.  `onClientPlayerState()` was looking up the client's lobby slot by Adventure character name, which does not match the Forge network username used to register the slot — causing the lookup to fail silently and the deck to never be cached.
    2.  `WorldStage.enter()` attempted to send `PLAYER_STATE` (including the deck) immediately on entry, but `mySlotIndex` was still `-1` at that point because pending `PLAYER_JOIN` events had not yet been processed by `GameStage.act()`.
*   **Fixes**:
    *   **`ServerAdventureLobby.onClientPlayerState()`**: Replaced name-based slot lookup with a direct parse of `data[0]` as the slot index. The client now sends its assigned slot index (e.g. `"1"`) as the first element of the `PLAYER_STATE` payload, eliminating the name mismatch entirely.
    *   **`WorldStage.enter()`**: Added a guard (`mySlotIndex >= 0`) around the `PLAYER_STATE` send. If the index is not yet known, the send is skipped — the fallback path handles it.
    *   **`GameStage.registerClientListener()` — `PLAYER_JOIN` handler**: Added a reliable second path. Once the `PLAYER_JOIN` "isMe" branch fires and `mySlotIndex` is set, the client immediately sends its full `PLAYER_STATE` (slot index, sprite name, HP, deck card list). This fires even if `WorldStage.enter()` was too early.

### 2. Client Reward Generation on Battle Win (`DuelScene.java`, `GameStage.java`)
*   **Issue**: After a multiplayer battle ended, the server broadcast `BATTLE_END` with the win/loss result. The client's handler only called `resumeAfterBattle()` — no rewards were generated or shown.
*   **Root Causes**:
    1.  `DuelScene.GameEnd()` on the network-client path exited immediately without any reward logic.
    2.  The `BATTLE_END` handler in `GameStage` had no access to the `EnemyData` needed to generate rewards (enemy card list, reward tables).
*   **Fixes**:
    *   **`DuelScene.java`**: Added a `private EnemyData networkEnemyData` field. `enterAsNetworkClient(Object payload)` now casts and stores the incoming `BATTLE_INIT` payload. A public getter `getNetworkEnemyData()` exposes it for use in `GameStage`.
    *   **`GameStage.registerClientListener()` — `BATTLE_END` handler**: On a win (`humanWon = true`), the handler now retrieves the stored `EnemyData` from `DuelScene.instance()`, iterates `enemyData.rewards`, calls `RewardData.generate(false, null, true)` on each entry to produce `Reward` objects, increments the win counter via `Current.player().win()`, and switches to `RewardScene` to display the loot — matching the host's reward flow.

### 3. Inn Sell Menu — Back Button Returns to Lobby Instead of Inn (`ShopScene.java`, `Forge.java`)
*   **Issue**: In multiplayer, pressing `<` (back) from the Inn's sell screen kicked the host to the multiplayer lobby instead of returning to the Inn menu. The client would then be left stuck with no way to exit.
*   **Root Cause**: `ShopScene` is a `ForgeScene` that uses Forge's `Dscreens` stack for back navigation. When the sell screen opened, the multiplayer lobby's `FScreen` was sitting behind it in `Dscreens`. Pressing back popped the sell screen and landed on the lobby `FScreen`, which Forge then rendered as the active screen.
*   **Investigation**: Two earlier approaches were tried and rejected:
    *   `replaceBackScreen=true` in `Forge.openScreen()` — silently skipped when `currentScreen` is null at call time, leaving the lobby in `Dscreens`.
    *   Overriding `onClose()` to call `Forge.switchToLast()` directly — navigated correctly but left `currentScreen` pointing at the sell screen, causing the display to freeze on the sell UI while the Inn was already active underneath. A follow-up patch to clear `currentScreen` inside `switchToLast()` fixed the freeze but introduced random flickering in unrelated menus.
*   **Fix**:
    *   **`Forge.java`**: Added `Forge.retainOnlyCurrentScreen()` — a targeted helper that clears `Dscreens` and re-adds only the current `FScreen`. This is safe to call immediately after a `ForgeScene` opens its screen.
    *   **`ShopScene.enter()`**: Calls `Forge.retainOnlyCurrentScreen()` after `super.enter()`. This ensures `Dscreens` contains only the sell screen's `FScreen`. Pressing back then empties `Dscreens` → `setCurrentScreen(null)` → Forge's existing adventure-mode handler calls `switchToLast()` → InnScene is correctly restored with no flicker.

## Files Modified

| File | Change |
| :--- | :--- |
| `forge-gui/src/main/java/forge/gamemodes/net/server/ServerAdventureLobby.java` | `onClientPlayerState()` now parses slot index from `data[0]` instead of matching by name |
| `forge-gui-mobile/src/forge/adventure/stage/WorldStage.java` | `PLAYER_STATE` send in `enter()` guarded by `mySlotIndex >= 0` |
| `forge-gui-mobile/src/forge/adventure/stage/GameStage.java` | `PLAYER_JOIN` handler sends `PLAYER_STATE` once slot is known; `BATTLE_END` handler generates and shows rewards on win |
| `forge-gui-mobile/src/forge/adventure/scene/DuelScene.java` | Added `networkEnemyData` field, getter, and storage in `enterAsNetworkClient()` |
| `forge-gui-mobile/src/forge/adventure/scene/ShopScene.java` | Calls `Forge.retainOnlyCurrentScreen()` after opening sell screen to evict stale lobby FScreen from Dscreens |
| `forge-gui-mobile/src/forge/Forge.java` | Added `retainOnlyCurrentScreen()` helper to trim Dscreens to only the active FScreen |

## Deployment Details
*   **Target**: `D:/Games/ForgeMTG`
*   **Build Status**: Successfully compiled with Maven (`-DskipTests`).
*   **Artifacts Replaced**:
    *   `forge-gui-mobile-dev-2.0.13-SNAPSHOT-jar-with-dependencies.jar`
    *   `forge-adventure.jar`

---
*Generated by Antigravity AI Coding Assistant*
