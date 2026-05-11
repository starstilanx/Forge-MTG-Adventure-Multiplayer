# Forge MTG: Project Architecture Overview

## Overview
**Forge** is an open-source Rules Engine and simulator for Magic: The Gathering. This repository consists of a multi-module Java application that implements the game rules, AI opponents, and cross-platform frontends (Desktop, Android, iOS).

## Technology Stack
- **Language**: Java 17+
- **Build System**: Maven (`pom.xml`)
- **Desktop UI**: Java Swing (via `forge-gui-desktop`)
- **Mobile UI**: LibGDX / Custom framework wrappers for Android and iOS
- **AI**: Custom heuristic and rules-based AI engine

## Directory Structure (Maven Modules)

The project is split into several interconnected modules to cleanly separate concerns:

- `forge-core/`: Contains the base static models, data loaders, definitions for Cards, Decks, and configuration.
- `forge-game/`: The heart of the application. Implements the MTG rules engine, game state simulation, phases, abilities, and combat mechanics.
- `forge-ai/`: Contains logic for the computer opponents. It leverages `forge-game` abstractions to evaluate board states, assign blockers, and simulate decisions.
- `forge-gui/`: Common UI interfaces and shared GUI logic across platforms.
- `forge-gui-desktop/`: Desktop-specific UI implementations (Java Swing/AWT).
- `forge-gui-mobile/`, `forge-gui-android/`, `forge-gui-ios/`: Mobile-specific bindings and UI logic.
- `adventure-editor/`: Tooling for Forge's Adventure Mode.
- `forge-lda/`: Logic for Limited formats (Drafting/Sealed).

## Key Components & Classes

### 1. `forge-core` (Data & Foundations)
Responsible for static, immutable data definitions. This module reads and parses card data (which is stored in external ForgeScript `.txt` files) and structures it for the rest of the application.
- **`forge.StaticData`**: A foundational singleton-like class that orchestrates the loading of all card rules, editions (sets), and tokens via the `CardStorageReader`. It holds the global `CardDb`.
- **`forge.card.CardDb`**: The in-memory database of all parsed Magic cards. It handles lookups for specific printings and alternatives.
- **`forge.card.CardRules`**: Represents the raw parsed abilities, base stats, and types of a card as defined in its script file. This is the immutable template that `forge-game` uses to instantiate a `Card` in an active match.
- **`forge.card.CardEdition`**: Represents a specific Magic: The Gathering set (like "Alpha" or "Zendikar").
- **`forge.deck.Deck` & `DeckFormat`**: Models a player's collection of cards built into a playable deck and defines construction rules for various formats (Standard, Commander, Pioneer, etc.).

### 2. `forge-game` (The Rules Engine)
The most critical module for understanding gameplay modifications. It represents the active state of an MTG match and processes all rules, triggers, and state-based actions.
- **`forge.game.Game`**: The central class representing an active MTG game. It manages the `GameStage`, `GameLog`, the `Match` context, and the active `Player` entities.
- **`forge.game.GameAction`**: The gatekeeper for all game state mutations. Direct modification of objects is discouraged; instead, actions like drawing a card, dealing damage, or moving a card between zones are routed through `GameAction` methods. This ensures triggers and replacement effects can properly hook into the event loop.
- **`forge.game.spellability.SpellAbility`**: The core abstraction for spells being cast or abilities being activated/triggered. It contains resolution logic, targeting (`TargetRestrictions`, `TargetChoices`), and cost parameters.
- **`forge.game.zone.MagicStack`**: Implements the MTG stack. When players cast spells or activate/trigger abilities, they are placed here as `SpellAbilityStackInstance`s and resolved in LIFO order.
- **`forge.game.trigger.TriggerHandler` & `Trigger`**: The listener system. Triggers (e.g., "Whenever a creature enters the battlefield") monitor `GameAction` events and place the appropriate `SpellAbility` onto the `MagicStack`.
- **`forge.game.replacement.ReplacementHandler`**: Intercepts `GameAction` events *before* they happen, allowing cards to alter the outcome (e.g., "If you would draw a card, do X instead").
- **`forge.game.player.Player`**: Represents a participant (human or AI), maintaining life totals, mana pools, and zone references.
- **`forge.game.card.Card`**: Represents an *instantiated* card in an active game, maintaining real-time state like timestamps, tapped status, counters, and attachments.
- **Zones & Phases**: Handled via `ZoneType` and `PhaseType`, organizing the flow of turns and card locations.

### 3. `forge-ai` (Computer Player Logic)
Built on top of `forge-game` to evaluate actions. It operates primarily on heuristics rather than deep tree search.
- **`forge.ai.AiController`**: The primary brain for an AI player, making high-level decisions when prompted by the game engine (e.g., choosing a card to play, responding to spells).
- **`forge.ai.AiAttackController` & `AiBlockController`**: Dedicated classes for evaluating combat scenarios. They calculate predicted combat damage, determine favorable trades, and assign attackers/blockers.
- **`forge.ai.ComputerUtil*`**: A suite of massive utility classes containing heuristic logic:
  - `ComputerUtilMana`: Heuristics for tapping optimal lands to pay mana costs and reserving mana for future spells.
  - `ComputerUtilCombat`: Logic for combat tricks and determining lethal damage.
  - `ComputerUtilCost`: Evaluates alternative costs (like discarding cards or sacrificing creatures) to see if the AI is willing to pay them.
- **`forge.ai.ability.*`**: Contains specific AI handlers for almost every spell or ability effect type in the game (e.g., `DamageDealAi`, `DrawAi`, `PumpAi`, `TapAi`). Each handler class implements logic to evaluate whether the AI should use that ability and exactly what optimal targets it should select.

### 4. `forge-gui-desktop` (Desktop Frontend)
- **`forge.GuiDesktop`**: The desktop entry point implementation of `IGuiBase`, bridging the game engine's requests to Swing dialogs and graphics.

## Development Context for LLMs
- **Adding a new card**: Usually does not require Java code. Cards are defined in text files using a specialized scripting language (ForgeScript) which the engine parses.
- **Modifying Game Rules**: Look into `forge-game/src/main/java/forge/game/GameAction.java` or specific `SpellAbility` implementations in `forge-game/src/main/java/forge/game/ability/`.
- **Tweaking AI**: Most behavioral tweaks happen in `forge-ai` within the `ComputerUtil*.java` classes or specific `Ai*` classes.
- **State Changes**: Always use `GameAction` or the relevant `Game` wrapper methods to mutate state to ensure triggers and log events fire correctly.
