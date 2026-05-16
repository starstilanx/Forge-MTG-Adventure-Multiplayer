# Adventure Mode Gemini Integration Research

## 1. Adventure Mode Preferences System

**Configuration File and Data Model**
*   Class Name: `Config` (`forge-gui-mobile/src/forge/adventure/util/Config.java`)
*   Data Model: `SettingData` (`forge-gui-mobile/src/forge/adventure/data/SettingData.java`)

**How to Add a Preference**
Preferences are declared as public boolean fields in `SettingData.java`.
```java
// In SettingData.java:
public boolean enableGeminiAi;
```

**How it is read at runtime**
```java
boolean useGemini = Config.instance().getSettingData().enableGeminiAi;
```

**How it is written/saved**
```java
Config.instance().getSettingData().enableGeminiAi = true;
Config.instance().saveSettings();
```

## 2. Adventure Settings Screen (UI)

**File Path and Class Name**
`SettingsScene` (`forge-gui-mobile/src/forge/adventure/scene/SettingsScene.java`)

**How a New Toggle is Added**
Use the `addSettingField(String name, boolean value, ChangeListener change)` helper method inside the `SettingsScene` constructor.

```java
// Adding a boolean toggle to SettingsScene.java
addSettingField("Enable Gemini AI", Config.instance().getSettingData().enableGeminiAi, new ChangeListener() {
    @Override
    public void changed(ChangeEvent event, Actor actor) {
        Config.instance().getSettingData().enableGeminiAi = ((CheckBox) actor).isChecked();
        Config.instance().saveSettings();
    }
});
```

## 3. Full EnemyData Field List

Below is the complete list of public fields in `forge-gui-mobile/src/forge/adventure/data/EnemyData.java`.

*   `name` (String) — The raw internal name. Use `.getName()` to retrieve, which checks `nameOverride`, then `name`, and falls back to `"(Unnamed Enemy)"`.
*   `nameOverride` (String) — Replaces the default name for display if specified.
*   `sprite` (String) — The image/texture key used to represent the enemy on the map.
*   `deck` (String[]) — A pool of deck names or paths the enemy is allowed to pick from.
*   `copyPlayerDeck` (boolean) — If true, the enemy abandons its deck list and plays a copy of the human player's deck.
*   `ai` (String) — Determines the AI personality. Passed into `selectAI(String ai)` mapping keys like `"reckless"`, `"cautious"`.
*   `boss` (boolean) — Flags whether the enemy should be treated as a boss encounter.
*   `flying` (boolean) — Controls visual hovering behavior for the sprite on the map.
*   `randomizeDeck` (boolean) — If true, picks a random deck from the `deck` array instead of scaling it sequentially.
*   `spawnRate` (float) — The frequency multiplier for how often this enemy generates in biomes.
*   `difficulty` (float) — Numeric value used for scaling difficulty metrics.
*   `speed` (float) — How quickly the enemy roams around the adventure map.
*   `scale` (float) — Visual scale multiplier for the enemy's map sprite (default 1.0f).
*   `life` (int) — Base starting life points.
*   `rewards` (RewardData[]) — The loot granted to the player upon defeating this enemy.
*   `equipment` (String[]) — Keys of items/equipment the enemy holds.
*   `colors` (String) — Color affinity string (e.g., "WUBRG").
*   `nextEnemy` (EnemyData) — Used to chain enemies together for team battles or multi-stage fights.
*   `teamNumber` (int) — Team ID alignment in the match (-1 default).
*   `questTags` (String[]) — Used for pattern-matching enemies to specific adventure quests.
*   `lifetime` (float) — Duration the enemy is allowed to exist on the map before despawning.
*   `gamesPerMatch` (int) — Sets how many games are played against this enemy (usually 1).
*   `bossInsult` (String) — Dialogue text shown if the player loses the match.
*   `bossIntro` (String) — Dialogue text shown right before the match begins.

## 4. DuelScene enter() Method

**Location:** `forge-gui-mobile/src/forge/adventure/scene/DuelScene.java` (Lines 323–544)

```java
    @Override
    public void enter() {
        SoundSystem.instance.stopBackgroundMusic();
        GameType mainGameType;
        boolean isDeckMissing = false;
        String isDeckMissingMsg = "";
        if (eventData != null && eventData.eventRules != null) {
            mainGameType = eventData.eventRules.gameType;
        } else if (AdventurePlayer.current().isCommanderMode()){
            mainGameType = GameType.Commander;
        } else {
            mainGameType = GameType.Adventure;
        }
        Set<GameType> appliedVariants = EnumSet.of(mainGameType);

        AdventurePlayer advPlayer = Current.player();

        List<RegisteredPlayer> players = new ArrayList<>();

        applyAdventureDeckRules(mainGameType.getDeckFormat());
        int playerCount = 1;
        EnemyData currentEnemy = enemy.getData();
        for (int i = 0; i < 8 && currentEnemy != null; i++) {
            playerCount++;
            currentEnemy = currentEnemy.nextEnemy;
        }

        humanPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, playerDeck, null, false, null, null);
        LobbyPlayer playerObject = GamePlayerUtil.getGuiPlayer();
        FSkin.getAvatars().put(playerAvatarKey, advPlayer.avatar());
        playerObject.setAvatarIndex(playerAvatarKey);
        humanPlayer.setPlayer(playerObject);
        humanPlayer.setTeamNumber(0);
        humanPlayer.setStartingLife(eventData != null ? eventData.eventRules.startingLife : advPlayer.getLife());
        if (eventData == null || eventData.eventRules.allowsShards)
            humanPlayer.setManaShards(advPlayer.getShards());

        Array<EffectData> playerEffects = new Array<>();
        Array<EffectData> oppEffects = new Array<>();

        Map<DeckProxy, Pair<List<String>, List<String>>> deckProxyMapMap = null;
        DeckProxy deckProxy = null;
        if (chaosBattle) {
            deckProxyMapMap = DeckProxy.getAllQuestChallenges();
            deckProxy = Aggregates.random(deckProxyMapMap.keySet());
            //playerextras
            List<IPaperCard> playerCards = new ArrayList<>();
            for (String s : deckProxyMapMap.get(deckProxy).getLeft()) {
                playerCards.add(QuestUtil.readExtraCard(s));
            }
            humanPlayer.addExtraCardsOnBattlefield(playerCards);
        }

        if (eventData == null || eventData.eventRules.allowsItems) {
            //Collect and add items effects first.
            for (Long id : advPlayer.getEquippedItems()) {
                ItemData item = Current.player().getEquippedItem(id);
                if (item != null && item.effect != null) {
                    playerEffects.add(item.effect);
                    if (item.effect.opponent != null) oppEffects.add(item.effect.opponent);
                } else {
                    System.err.printf("Item %s not found.", id);
                }
            }
        }
        if (eventData == null || eventData.eventRules.allowsBlessings) {
            //Collect and add player blessings.
            if (advPlayer.getBlessing() != null) {
                playerEffects.add(advPlayer.getBlessing());
                if (advPlayer.getBlessing().opponent != null) oppEffects.add(advPlayer.getBlessing().opponent);
            }

            //Collect and add enemy effects (same as blessings but for individual enemies).
            if (enemy.effect != null) {
                oppEffects.add(enemy.effect);
                if (enemy.effect.opponent != null)
                    playerEffects.add(enemy.effect.opponent);
            }
        }
        //Collect and add dungeon-wide effects.
        if (dungeonEffect != null) {
            oppEffects.add(dungeonEffect);
            if (dungeonEffect.opponent != null)
                playerEffects.add(dungeonEffect.opponent);
        }

        addEffects(humanPlayer, playerEffects);

        currentEnemy = enemy.getData();
        boolean bossBattle = currentEnemy.boss;
        for (int i = 0; i < playerCount && currentEnemy != null; i++) {
            Deck deck;

            if (this.chaosBattle) { //random challenge for chaos mode
                if (deckProxyMapMap == null)
                    continue;
                //aiextras
                List<IPaperCard> aiCards = new ArrayList<>();
                for (String s : deckProxyMapMap.get(deckProxy).getRight()) {
                    aiCards.add(QuestUtil.readExtraCard(s));
                }
                this.AIExtras = aiCards;
                deck = deckProxy.getDeck();
            } else if (this.arenaBattleChallenge) {
                if (Config.instance().getConfigData().enableGeneticAI) {
                    deck = Aggregates.random(DeckProxy.getAllGeneticAIDecks()).getDeck();
                } else {
                    deck = currentEnemy.generateDeck(Current.player().isFantasyMode(), false);
                }
            } else if (this.eventData != null) {
                deck = eventData.nextOpponent.getDeck();
            } else {
                boolean useGeneticAI = Config.instance().getConfigData().enableGeneticAI && (Current.player().isUsingCustomDeck() || Current.player().isHardorInsaneDifficulty());
                deck = currentEnemy.copyPlayerDeck ? this.playerDeck : currentEnemy.generateDeck(Current.player().isFantasyMode(), useGeneticAI);
            }
            if (deck == null) {
                isDeckMissing = true;
                boolean canUseGeneticAI = Config.instance().getConfigData().enableGeneticAI;
                isDeckMissingMsg = "Deck for " + currentEnemy.getName() + " is missing! " + (this.eventData == null ? (canUseGeneticAI ? "Genetic AI deck will be used." : "Player deck will be used.") : "Player deck will be used.");
                System.err.println(isDeckMissingMsg);
                deck = this.eventData == null && canUseGeneticAI ? Aggregates.random(DeckProxy.getAllGeneticAIDecks()).getDeck() : this.playerDeck;
            }
            RegisteredPlayer aiPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, deck, null, false, null, null);

            LobbyPlayer enemyPlayer = GamePlayerUtil.createAiPlayer(currentEnemy.getName(), selectAI(currentEnemy.ai));
            enemyPlayer.setName(enemy.getName()); //Override name if defined in the map.(only supported for 1 enemy atm)
            TextureRegion enemyAvatar = enemy.getAvatar(i);
            enemyAvatar.flip(true, false); //flip facing left
            FSkin.getAvatars().put(enemyAvatarKey + i, enemyAvatar);
            enemyPlayer.setAvatarIndex(enemyAvatarKey + i);
            aiPlayer.setPlayer(enemyPlayer);
            aiPlayer.setTeamNumber(currentEnemy.teamNumber);
            aiPlayer.setStartingLife(eventData != null ? eventData.eventRules.startingLife : Math.round((float) currentEnemy.life * advPlayer.getDifficulty().enemyLifeFactor));

            Array<EffectData> equipmentEffects = new Array<>();
            if (eventData != null && eventData.eventRules.allowsItems) {
                if (currentEnemy.equipment != null) {
                    for (String oppItem : currentEnemy.equipment) {
                        ItemData item = ItemListData.getItem(oppItem);
                        if (item == null)
                            continue;
                        equipmentEffects.add(item.effect);
                        if (item.effect.opponent != null)
                            playerEffects.add(item.effect.opponent);
                    }
                }
            }
            addEffects(aiPlayer, oppEffects);
            addEffects(aiPlayer, equipmentEffects);

            //add extra cards for challenger mode
            if (chaosBattle) {
                aiPlayer.addExtraCardsOnBattlefield(AIExtras);
            }

            players.add(aiPlayer);

            if (eventData == null) {
                Current.setLatestDeck(deck);
            }

            currentEnemy = currentEnemy.nextEnemy;
        }

        players.add(humanPlayer);

        if(eventData != null && eventData.draft != null) {
            for(RegisteredPlayer p : players)
                p.assignConspiracies();
        }

        final Map<RegisteredPlayer, IGuiGame> guiMap = new HashMap<>();
        guiMap.put(humanPlayer, MatchController.instance);

        hostedMatch = MatchController.hostMatch();

        GameRules rules;

        if (eventData != null) {
            rules = new GameRules(eventData.eventRules.gameType);
            rules.setGamesPerMatch(eventData.eventRules.gamesPerMatch);
            bossBattle = false;
        } else {
            rules = new GameRules(GameType.Adventure);
            rules.setGamesPerMatch(enemy.getData().gamesPerMatch);
        }
        rules.setPlayForAnte(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE));
        rules.setMatchAnteRarity(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_MATCH_RARITY));
        rules.setAnteIncludeBasicLands(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_INCLUDE_BASIC_LANDS));
        rules.setManaBurn(false);
        rules.setWarnAboutAICards(false);

        //hostedMatch.setEndGameHook(() -> DuelScene.this.GameEnd());
        hostedMatch.startMatch(rules, appliedVariants, players, guiMap, bossBattle ? MusicPlaylist.BOSS : MusicPlaylist.MATCH);
        MatchController.instance.setGameView(hostedMatch.getGameView());
        boolean showMessages = enemy.getData().boss || (enemy.getData().copyPlayerDeck && Current.player().isUsingCustomDeck());
        LoadingOverlay matchOverlay;
        if (chaosBattle || showMessages || isDeckMissing) {
            final FBufferedImage fb = getFBEnemyAvatar();
            String Intro = enemy.getBossIntro();
            if (Intro != null){
                bossDialogue = createFOption((Intro), enemy.getName(), fb, fb::dispose);
                }
                else {
                bossDialogue = createFOption(isDeckMissing ? isDeckMissingMsg : Forge.getLocalizer().getMessage("AdvBossIntro" + Aggregates.randomInt(1, 35)),
                enemy.getName(), fb, fb::dispose);
                }
            matchOverlay = new LoadingOverlay(() -> FThreads.delayInEDT(300, () -> FThreads.invokeInEdtNowOrLater(() ->
            bossDialogue.show())), false, true);
        } else {
            matchOverlay = new LoadingOverlay(null);
        }
        for (final Player p : hostedMatch.getGame().getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman) {
                final PlayerControllerHuman humanController = (PlayerControllerHuman) p.getController();
                humanController.setGui(MatchController.instance);
                MatchController.instance.setOriginalGameController(p.getView(), humanController);
                MatchController.instance.openView(new TrackableCollection<>(p.getView()));
            }
        }
        super.enter();
        matchOverlay.show();
    }
```
