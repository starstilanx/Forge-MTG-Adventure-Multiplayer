# Forge AI Class Definitions

## Tier 1 — Blocking

AiController (concrete)
  extends/implements: None
  method: public boolean usesSimulation() — Check if the AI is using the GameSimulator
  method: public void setUseSimulation(boolean value) — Set whether the AI should use the GameSimulator
  method: public SpellAbilityPicker getSimulationPicker() — Returns the SpellAbilityPicker for sim
  method: public Game getGame() — Get the current Game instance
  method: public Player getPlayer() — Get the Player instance associated with this controller
  method: public AiCardMemory getCardMemory() — Get the AI's short-term memory of card behaviors
  method: public Combat getPredictedCombat() — Evaluate declaring attackers for the AI
  method: public Combat getPredictedCombatNextTurn() — Evaluate declaring attackers for next turn
  method: public SpellAbility predictSpellToCastInMain2(ApiType exceptSA) — Predicts a spell to cast post-combat
  method: public boolean reserveManaSources(SpellAbility sa) — Sets aside mana sources for casting a spell later
  // How it's instantiated: Created by `PlayerControllerAi` which acts as a bridge between the game engine and the AI logic. It is associated with a Player object during instantiation: `new AiController(p, game)`.

AiAttackController (concrete)
  extends/implements: None
  method: public static Player choosePreferredDefenderPlayer(Player ai) — Chooses the optimal opponent to attack
  method: public static List<Card> sortAttackers(final List<Card> in) — Sorts the list of potential attackers
  method: public final boolean isEffectiveAttacker(final Player ai, final Card attacker, final Combat combat, final GameEntity defender) — Checks if there is a tactical reward for attacking
  method: public final static List<Card> getPossibleBlockers(final List<Card> blockers, final List<Card> attackers, final boolean nextTurn) — Predicts the opponent's available blockers
  method: public final List<Card> notNeededAsBlockers(final List<Card> currentAttackers, final List<Card> potentialAttackers) — Figures out which creatures the AI can send without leaving itself vulnerable to counterattack

AiBlockController (concrete)
  extends/implements: None
  method: private void makeGoodBlocks(final Combat combat) — Decides on favorable or neutral trades
  method: private void makeGangBlocks(final Combat combat) — Decides on multi-blocks that favor the AI
  method: private void makeTradeBlocks(final Combat combat) — Performs worse trades if life total is in danger
  method: private void makeChumpBlocks(final Combat combat) — Assigns sacrificial blockers if life is in danger

PlayerController (abstract)
  extends/implements: None
  method: public abstract SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent)
  method: public abstract void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets)
  method: public abstract List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs)
  method: public abstract void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs)
  method: public abstract boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory)
  method: public abstract boolean playSaFromPlayEffect(SpellAbility tgtSA)
  method: public abstract List<PaperCard> sideboard(final Deck deck, GameType gameType, String message)
  method: public abstract List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses)
  method: public abstract Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder)
  method: public abstract Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount)
  method: public abstract Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different)
  method: public abstract CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message)
  method: public abstract CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message)
  method: public abstract Integer announceRequirements(SpellAbility ability, int min, int max, String announce)
  method: public abstract TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional)
  method: public abstract boolean chooseTargetsFor(SpellAbility currentAbility)
  method: public abstract Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets)
  method: public abstract boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested)
  method: public abstract Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max)
  method: public abstract CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params)
  method: public abstract CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional)
  method: public abstract <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params)
  method: public abstract <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params)
  method: public abstract List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params)
  method: public abstract SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params)
  method: public abstract boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params)
  method: public abstract boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner)
  method: public abstract boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question)
  method: public abstract boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic)
  method: public abstract boolean confirmTrigger(WrappedAbility sa)
  method: public abstract List<Card> exertAttackers(List<Card> attackers)
  method: public abstract List<Card> enlistAttackers(List<Card> attackers)
  method: public abstract void declareAttackers(Player attacker, Combat combat)
  method: public abstract void declareBlockers(Player defender, Combat combat)
  method: public abstract CardCollection orderBlockers(Card attacker, CardCollection blockers)
  method: public abstract CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers)
  method: public abstract CardCollection orderAttackers(Card blocker, CardCollection attackers)
  method: public abstract void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix)
  method: public abstract void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix)
  method: public abstract void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value)
  method: public abstract ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN)
  method: public abstract ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN)
  method: public abstract boolean willPutCardOnTop(Card c)
  method: public abstract CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source)
  method: public abstract CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max)
  method: public abstract CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa)
  method: public abstract CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard)
  method: public abstract CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave)
  method: public abstract Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction)
  method: public abstract List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards)
  method: public abstract CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid)
  method: public abstract List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand)
  method: public abstract Player chooseStartingPlayer(boolean isFirstGame)
  method: public abstract PlayerZone chooseStartingHand(List<PlayerZone> zones)
  method: public abstract Mana chooseManaFromPool(List<Mana> manaChoices)
  method: public abstract String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional)
  method: public abstract String chooseSector(Card assignee, String ai, List<String> sectors)
  method: public abstract List<Card> chooseContraptionsToCrank(List<Card> contraptions)
  method: public abstract int chooseSprocket(Card assignee, List<Integer> sprockets)
  method: public abstract PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls)
  method: public abstract Integer chooseRollToIgnore(List<Integer> rolls)
  method: public abstract List<Integer> chooseDiceToReroll(List<Integer> rolls)
  method: public abstract Integer chooseRollToModify(List<Integer> rolls)
  method: public abstract RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls)
  method: public abstract String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness)
  method: public abstract Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional)
  method: public abstract boolean mulliganKeepHand(Player player, int cardsToReturn)
  method: public abstract CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn)
  method: public abstract List<SpellAbility> chooseSpellAbilityToPlay()
  method: public abstract boolean playChosenSpellAbility(SpellAbility sa)
  method: public abstract List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat)
  method: public abstract int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max)
  method: public abstract int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max)
  method: public abstract int chooseNumber(SpellAbility sa, String title, int min, int max)
  method: public abstract int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer)
  method: public abstract boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice)
  method: public abstract boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call)
  method: public abstract byte chooseColor(String message, SpellAbility sa, ColorSet colors)
  method: public abstract byte chooseColorAllowColorless(String message, Card c, ColorSet colors)
  method: public abstract ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options)
  method: public abstract ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name)
  method: public abstract ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message)
  method: public abstract CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params)
  method: public abstract boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp)
  method: public abstract CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params)
  method: public abstract String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard)
  method: public abstract boolean confirmPayment(CostPart costPart, String string, SpellAbility sa)
  method: public abstract ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers)
  method: public abstract StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers)
  method: public abstract String chooseProtectionType(SpellAbility sa, List<String> choices)
  method: public abstract void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards)
  method: public abstract void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards)
  method: public abstract void revealUnsupported(Map<Player, List<PaperCard>> unsupported)
  method: public abstract void resetAtEndOfTurn()
  method: public abstract List<OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues)
  method: public abstract List<CostPart> orderCosts(List<CostPart> costs)
  method: public abstract boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers)
  method: public abstract boolean payCostDuringRoll(Cost cost, SpellAbility sa)
  method: public abstract boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt)
  method: public abstract boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect)
  method: public abstract boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect)
  method: public abstract CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt)
  method: public abstract CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt)
  method: public abstract String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message)
  method: public abstract String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message)
  method: public abstract Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider)
  method: public abstract List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider)
  method: public abstract void autoPassCancel()
  method: public abstract void awaitNextInput()
  method: public abstract void cancelAwaitNextInput()

PlayerControllerAi (concrete)
  extends/implements: PlayerController
  // How it's instantiated: The AI player is created in `forge-ai/src/main/java/forge/ai/LobbyPlayerAi.java` within the `createIngamePlayer` and `createControllerFor` methods. The constructor signature is `new PlayerControllerAi(Game game, Player p, LobbyPlayer lp)`. We will inject our new `PlayerControllerGemini` at this juncture for testing, likely extending `LobbyPlayerAi` or writing a custom `LobbyPlayerGemini`.

Combat (concrete)
  extends/implements: None
  method: public final void addAttacker(final Card c, GameEntity defender) — Declares an attacker
  method: public final void addAttacker(final Card c, GameEntity defender, AttackingBand band) — Declares an attacker with banding
  method: public final void setBlocked(final Card attacker, boolean value) — Marks an attacker as blocked
  method: public final void addBlocker(final Card attacker, final Card blocker) — Declares a specific blocker for an attacker

Player (concrete)
  extends/implements: GameEntity
  method: public final int getLife() — Get current life total
  method: public final ManaPool getManaPool() — Get current mana pool
  method: public final PlayerZone getZone(final ZoneType zone) — Get the PlayerZone object for a given zone
  method: public final CardCollectionView getCardsIn(final ZoneType zoneType) — Get a read-only list of cards in a specific zone
  method: public final CardCollectionView getCardsIn(final Iterable<ZoneType> zones) — Get a read-only list of cards across multiple zones
  // List of SpellAbility objects the player can currently play is obtained through a utility function in the AI system: `ComputerUtilAbility.getSpellAbilities(player.getCardsIn(ZoneType.Hand), player);` or similar context-specific methods, since it depends on the rules engine, priority, and mana available.

Card (concrete)
  extends/implements: GameEntity, GameObject, IHasIcon
  method: public final String getName() — Get the card's current name
  method: public final boolean isTapped() — Check if the card is currently tapped
  method: public final int getCounters(final CounterEnumType counterType) — Get the count of a specific counter type
  method: public final int getNetPower() — Get the card's current modified power
  method: public final int getNetToughness() — Get the card's current modified toughness
  method: public final CardTypeView getType() — Get the card's type object (Creature, Instant, etc.)
  method: public CardRules getRules() — Get the base rules of the card

Game (concrete)
  extends/implements: IGame
  method: public final PhaseHandler getPhaseHandler() — Gets the PhaseHandler which controls current phase and turn
  method: public final MagicStack getStack() — Gets the stack of spells and abilities
  method: public final PlayerCollection getPlayers() — Gets the list of all players in the game
  // Current Phase can be accessed via `game.getPhaseHandler().getPhase()`
  // Current Priority Player can be accessed via `game.getPhaseHandler().getPriorityPlayer()`

## Tier 2 — Needed soon after

SpellAbility (abstract)
  extends/implements: GameObject
  method: public ApiType getApi() — Identifies the base API effect of the ability (e.g., Damage, Destroy, Counter)
  method: public TargetRestrictions getTargetRestrictions() — Gets the targeting rules for the spell/ability
  method: public TargetChoices getTargets() — Gets the specific targets chosen for the spell/ability
  method: public Cost getPayCosts() — Gets the cost object associated with casting/activating this
  method: public ManaCostBeingPaid getManaCostBeingPaid() — Gets the mana cost requirements for the ability

CardRules (concrete)
  extends/implements: None
  method: public String getOracleText() — Gets the human-readable rules string for the card

ManaPool (concrete)
  extends/implements: ManaConversionMatrix, Iterable<Mana>
  method: public final int getAmountOfColor(final byte color) — Returns the available mana count for a specific color (from ManaAtom)
  method: public final int totalMana() — Returns the total amount of mana floating
  method: public final boolean isEmpty() — Checks if the mana pool is empty

CardTypeView (interface)
  extends/implements: Serializable
  method: public String toString() — The toString() method correctly compiles and returns the human-readable string (e.g. "Legendary Creature - Goblin")

ZoneType (enum)
  values: Hand, Library, Graveyard, Battlefield, Exile, Flashback, Command, Stack, Sideboard, Ante, Merged, SchemeDeck, PlanarDeck, AttractionDeck, Junkyard, ContraptionDeck, Subgame, ExtraHand, None

PhaseType (enum)
  values: UNTAP, UPKEEP, DRAW, MAIN1, COMBAT_BEGIN, COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, COMBAT_FIRST_STRIKE_DAMAGE, COMBAT_DAMAGE, COMBAT_END, MAIN2, END_OF_TURN, CLEANUP

## Tier 3 — Build scaffolding

forge-ai/pom.xml
  Dependencies:
  - forge-core (project dependency)
  - forge-game (project dependency)
  - commons-math3 (version 3.6.1)

parent pom.xml
  Dependencies:
  - slf4j-api
  Note: There are no JSON or HTTP libraries (like Gson, Jackson, OkHttp) included in either the `forge-ai` POM or the parent POM. A new dependency will need to be introduced explicitly into the project to handle web requests or JSON parsing.
