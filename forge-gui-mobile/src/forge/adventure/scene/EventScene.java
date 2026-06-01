package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Scaling;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.AdventureEventData;
import forge.adventure.data.DialogData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.IAfterMatch;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.AdventureEventController;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gui.FThreads;
import forge.screens.TransitionScreen;
import forge.util.MyRandom;

import java.util.Arrays;
import java.util.List;

import static forge.adventure.util.AdventureEventController.EventStatus.*;

public class EventScene extends MenuScene implements IAfterMatch {
    TextraLabel money, shards;
    TextraButton advance, back, editDeck, nextPage, previousPage;
    private Table scrollContainer;
    ScrollPane scroller;
    Table root, headerTable, metaDraftTable;
    int pageIndex = 0;
    Scene lastGameScene;

    Table[] eventPages;

    static AdventureEventData currentEvent;
    static PointOfInterestChanges changes;

    private Array<DialogData> entryDialog;
    private AdventureEventData.AdventureEventMatch humanMatch = null;

    private int packsSelected = 0; //Used for meta drafts, booster drafts will use existing logic.

    private EventScene() {
        super(Forge.isLandscapeMode() ? "ui/event.json" : "ui/event_portrait.json");
        // TODO: Add translation
        float townPriceModifier = changes == null ? 1f : changes.getTownPriceModifier();
        DialogData introDialog = new DialogData();
        introDialog.text = "Enter this event?";
        DialogData enterWithCoin = new DialogData();

        DialogData enterWithShards = new DialogData();
        enterWithShards.name = String.format("Spend %d [+shards]", Math.round(currentEvent.eventRules.shardsToEnter * townPriceModifier));
        DialogData enterWithGold = new DialogData();
        enterWithGold.name = String.format("Spend %d [+gold]", Math.round(currentEvent.eventRules.goldToEnter * townPriceModifier));

        DialogData.ConditionData hasGold = new DialogData.ConditionData();
        hasGold.hasGold = Math.round(currentEvent.eventRules.goldToEnter * townPriceModifier);
        enterWithGold.condition = new DialogData.ConditionData[]{hasGold};

        DialogData.ConditionData hasShards = new DialogData.ConditionData();
        hasShards.hasShards = Math.round(currentEvent.eventRules.shardsToEnter * townPriceModifier);
        enterWithShards.condition = new DialogData.ConditionData[]{hasShards};

        if (currentEvent.eventRules.acceptsChallengeCoin) {
            enterWithCoin.name = "Redeem a Challenge Coin [+ChallengeCoin]";

            DialogData.ConditionData hasCoin = new DialogData.ConditionData();
            hasCoin.item = "Challenge Coin";
            enterWithCoin.condition = new DialogData.ConditionData[]{hasCoin};

            DialogData.ActionData giveCoin = new DialogData.ActionData();
            giveCoin.removeItem = hasCoin.item;
            enterWithCoin.action = new DialogData.ActionData[]{giveCoin};
        } else if (currentEvent.eventRules.acceptsSilverChallengeCoin) {
            enterWithCoin.name = "Redeem a Challenge Coin [+SilverChallengeCoin]";
            DialogData.ConditionData hasCoin = new DialogData.ConditionData();
            hasCoin.item = "Silver Challenge Coin";
            enterWithCoin.condition = new DialogData.ConditionData[]{hasCoin};

            DialogData.ActionData giveCoin = new DialogData.ActionData();
            giveCoin.removeItem = hasCoin.item;
            enterWithCoin.action = new DialogData.ActionData[]{giveCoin};
        } else if (currentEvent.eventRules.acceptsBronzeChallengeCoin) {
            enterWithCoin.name = "Redeem a Challenge Coin [+BronzeChallengeCoin]";
            DialogData.ConditionData hasCoin = new DialogData.ConditionData();
            hasCoin.item = "Bronze Challenge Coin";
            enterWithCoin.condition = new DialogData.ConditionData[]{hasCoin};

            DialogData.ActionData giveCoin = new DialogData.ActionData();
            giveCoin.removeItem = hasCoin.item;
            enterWithCoin.action = new DialogData.ActionData[]{giveCoin};

        } else {
            DialogData.ConditionData alwaysFalse = new DialogData.ConditionData();
            alwaysFalse.item = "NonexistentItem";
            enterWithCoin.condition = new DialogData.ConditionData[]{alwaysFalse};
        }

        DialogData.ActionData spendGold = new DialogData.ActionData();
        spendGold.addGold = -Math.round(currentEvent.eventRules.goldToEnter * townPriceModifier);
        enterWithGold.action = new DialogData.ActionData[]{spendGold};

        DialogData.ActionData spendShards = new DialogData.ActionData();
        spendShards.addShards = -Math.round(currentEvent.eventRules.shardsToEnter * townPriceModifier);
        enterWithShards.action = new DialogData.ActionData[]{spendShards};


        DialogData decline = new DialogData();
        //todo: add translation
        decline.name = "Do not enter event";

        enterWithCoin.callback = (result) -> {
            currentEvent.eventStatus = AdventureEventController.EventStatus.Entered;
            broadcastEventInitIfHost();
            refresh();
        };
        enterWithShards.callback = (result) -> {
            currentEvent.eventStatus = AdventureEventController.EventStatus.Entered;
            broadcastEventInitIfHost();
            refresh();
        };
        enterWithGold.callback = (result) -> {
            currentEvent.eventStatus = AdventureEventController.EventStatus.Entered;
            broadcastEventInitIfHost();
            refresh();
        };

        introDialog.options = new DialogData[4];
        introDialog.options[0] = enterWithCoin;
        introDialog.options[1] = enterWithShards;
        introDialog.options[2] = enterWithGold;
        introDialog.options[3] = decline;

        entryDialog = new Array<>();
        entryDialog.add(introDialog);

        TypingLabel blessingScroll = Controls.newTypingLabel("[BLACK]" + currentEvent.getDescription(changes));
        blessingScroll.skipToTheEnd();
        blessingScroll.setAlignment(Align.topLeft);
        blessingScroll.setWrap(true);

        ui.onButtonPress("return", EventScene.this::back);
        ui.onButtonPress("advance", EventScene.this::advance);
        ui.onButtonPress("editDeck", EventScene.this::editDeck);

        back = ui.findActor("return");
        advance = ui.findActor("advance");
        nextPage = ui.findActor("nextPage");
        nextPage.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (nextPage.isDisabled())
                    return;
                nextPage(false);
            }
        });

        previousPage = ui.findActor("previousPage");
        previousPage.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (previousPage.isDisabled())
                    return;
                nextPage(true);
            }
        });

        editDeck = ui.findActor("editDeck");

        Window window = ui.findActor("scrollWindow");
        root = ui.findActor("enemies");


        Window header = ui.findActor("header");
        header.toFront();
        headerTable = new Table(Controls.getSkin());
        headerTable.add("Event Standings").expand();
        header.add(headerTable).expand();

        ScrollPane blessing = ui.findActor("blessingInfo");
        blessing.setActor(blessingScroll);
        blessingScroll.setWidth(blessing.getWidth() - 5);
        blessing.layout();
        window.add(root);

        metaDraftTable = ui.findActor("metaDraftTable");
        root.add(metaDraftTable);
        metaDraftTable.setVisible(false);

        refresh();
    }

    private void refresh() {
        if (metaDraftTable.isVisible()) {
            scrollContainer = metaDraftTable;
            headerTable.clear();
            //todo: add translation
            headerTable.add("Pack Selection");

            if (currentEvent.eventStatus == Entered) {
                loadMetaDraft();
            }

        } else {
            scrollContainer = new Table(Controls.getSkin());
            scrollContainer.row();

            Arrays.sort(currentEvent.participants);

            for (AdventureEventData.AdventureEventParticipant participant : currentEvent.participants) {
                Image avatar = participant.getAvatar();
                avatar.setScaling(Scaling.stretch);
                scrollContainer.add(avatar).pad(5).size(16).fillY();
                scrollContainer.add().width(16);

                boolean notEliminated = !currentEvent.eventStatus.equals(AdventureEventController.EventStatus.Started) || !currentEvent.matches.containsKey(currentEvent.currentRound) || currentEvent.matches.get(currentEvent.currentRound).stream().anyMatch(q -> q.p1.equals(participant) || q.p2.equals(participant));
                TextraLabel participantName = Controls.newTextraLabel((notEliminated ? "" : "[RED]") + participant.getName());
                participantName.setWrap(true);

                scrollContainer.add(participantName).fillX().pad(5).width(120);
                scrollContainer.add().width(16);
                scrollContainer.add(String.format("%d-%d", participant.wins, participant.losses)).pad(5);
                scrollContainer.row();
            }

            eventPages = new Table[currentEvent.rounds + 1];
            eventPages[0] = scrollContainer;
            for (int i = 0; i < currentEvent.rounds; i++) {

                Table round = new Table(Controls.getSkin());
                round.row();

                List<AdventureEventData.AdventureEventMatch> matches = currentEvent.getMatches(i + 1);

                if (matches == null) {
                    //todo: add translation
                    round.add(Controls.newTextraLabel("Pairings not yet generated"));
                } else {
                    Table roundScrollContainer = new Table(Controls.getSkin());
                    for (AdventureEventData.AdventureEventMatch match : matches) {

                        Table p1Table = new Table(Controls.getSkin());

                        Image p1Avatar = match.p1.getAvatar();
                        p1Avatar.setScaling(Scaling.stretch);

                        p1Table.add(p1Avatar).pad(5).size(16).fillY().top();
                        String color = match.winner == null ? "" : match.winner.equals(match.p1) ? "[GREEN]" : match.winner.equals(match.p2) ? "[RED]" : "";
                        TypingLabel p1Name = Controls.newTypingLabel(color + match.p1.getName());
                        p1Name.skipToTheEnd();
                        p1Name.setWrap(true);
                        p1Table.add(p1Name).width(50).expandX().top();

                        roundScrollContainer.add(p1Table).left().uniformY().top().padBottom(10);

                        Table verbTable = new Table(Controls.getSkin());

                        //todo: add translations
                        if (match.p2 == null) {
                            verbTable.add("has a bye").expand().fillX().top();
                        } else if (match.winner != null && match.winner.equals(match.p1)) {
                            verbTable.add("defeated").expand().fillX().top();
                        } else if (match.winner != null && match.winner.equals(match.p2)) {
                            verbTable.add("defeated by").expand().fillX().top();
                        } else {
                            verbTable.add("versus").expand().fillX().top();
                        }

                        roundScrollContainer.add(verbTable).padLeft(10).padRight(10).top();

                        Table p2Table = new Table(Controls.getSkin());
                        if (match.p2 != null) {
                            Image p2Avatar = match.p2.getAvatar();
                            p2Avatar.setScaling(Scaling.stretch);
                            String color2 = match.winner == null ? "" : match.winner.equals(match.p2) ? "[GREEN]" : match.winner.equals(match.p1) ? "[RED]" : "";
                            TypingLabel p2Name = Controls.newTypingLabel(color2 + match.p2.getName());
                            p2Name.skipToTheEnd();
                            p2Name.setWrap(true);
                            p2Table.add(p2Name).width(50).expandX().top();
                            p2Table.add(p2Avatar).pad(5).size(16).fillY().top();
                        }
                        roundScrollContainer.add(p2Table).right().uniformY().top().padBottom(10);
                        roundScrollContainer.row();
                    }
                    round.add(roundScrollContainer).expandX().fillX();
                    round.row();
                }
                eventPages[i + 1] = round;
            }
        }
        performTouch(scrollPaneOfActor(scrollContainer)); //can use mouse wheel if available to scroll

        root.clear();
        scrollContainer.layout();
        scroller = new ScrollPane(scrollContainer);

        root.add(scroller).fill().prefWidth(root.getWidth());

        root.layout();
        scroller.layout();

        scroller.clear();
        scroller.setActor(eventPages[pageIndex]);
        performTouch(scroller);

        //todo: add translations
        switch (currentEvent.eventStatus) {
            case Available:
                nextPage.setDisabled(true);
                previousPage.setDisabled(true);
                editDeck.setDisabled(true);
                editDeck.setVisible(false);
                advance.setText("Join Event");
                advance.setVisible(true);
                break;
            case Entered:
                nextPage.setDisabled(true);
                previousPage.setDisabled(true);
                editDeck.setDisabled(true);
                editDeck.setVisible(false);
                if (currentEvent.getDraft() != null) {
                    advance.setText("Enter Draft");
                } else if (currentEvent.format == AdventureEventController.EventFormat.Sealed) {
                    advance.setText("Create Deck");
                } else {
                    advance.setText("Select Deck");
                }
                advance.setVisible(true);
                break;
            case Ready:
                advance.setText("Start Event");
                advance.setVisible(true);
                editDeck.setDisabled(false);
                editDeck.setVisible(true);
                nextPage.setDisabled(false);
                previousPage.setDisabled(false);
                publishMultiplayerReadyState();
                break;
            case Started:
                if (forge.gamemodes.net.adventure.AdventureNetSession.getInstance().isActiveClient()) {
                    // Client doesn't drive round progression — the host's "Play round N" click
                    // broadcasts BATTLE_INIT which pulls this client into the same co-op match.
                    advance.setText("Waiting for host...");
                    advance.setDisabled(true);
                } else {
                    advance.setText("Play round " + currentEvent.currentRound);
                    advance.setDisabled(false);
                }
                advance.setVisible(true);
                editDeck.setDisabled(false);
                editDeck.setVisible(true);
                nextPage.setDisabled(false);
                previousPage.setDisabled(false);
                publishMultiplayerReadyState();
                break;
            case Completed:
                advance.setText("Collect Rewards");
                advance.setVisible(true);
                editDeck.setDisabled(true);
                editDeck.setVisible(false);
                nextPage.setDisabled(false);
                previousPage.setDisabled(false);
                break;
            case Awarded:
            case Abandoned:
                advance.setVisible(false);
                editDeck.setDisabled(true);
                editDeck.setVisible(false);
                nextPage.setDisabled(false);
                previousPage.setDisabled(false);
                AdventureEventController.instance().finalizeEvent(currentEvent);
                break;
        }
    }

    private static EventScene object;

    public static EventScene instance(Scene lastGameScene, AdventureEventData event, PointOfInterestChanges localChanges) {
        currentEvent = event;
        changes = localChanges;
        // if (object == null)
        object = new EventScene();
        if (lastGameScene != null)
            object.lastGameScene = lastGameScene;
        return object;
    }

    /**
     * Client-side: publish the just-finalized event deck so the host can use it when building
     * this slot's RegisteredPlayer at match start, and signal EVENT_READY so the host knows
     * this client has committed.  No-op on host or when the deck isn't ready yet.
     *
     * Idempotent — refresh() can fire many times; the host treats repeats as set inserts.
     */
    private static void publishMultiplayerReadyState() {
        final forge.gamemodes.net.adventure.AdventureNetSession session =
                forge.gamemodes.net.adventure.AdventureNetSession.getInstance();
        if (currentEvent == null || currentEvent.registeredDeck == null) return;
        // Always update the event-deck override (used by buildClientPlayerStatePayload).
        session.activeEventDeck = currentEvent.registeredDeck;
        if (!session.isActiveClient() || session.client == null || session.mySlotIndex < 0) return;
        // Resend PLAYER_STATE so the host's cached state reflects the drafted deck before
        // the host's BATTLE_INIT triggers DuelScene.enter() and reads getPlayerState(slot).
        try {
            session.client.send(new forge.gamemodes.net.adventure.AdventureNetEvent(
                    forge.gamemodes.net.adventure.AdventureNetEvent.Type.PLAYER_STATE,
                    forge.adventure.stage.GameStage.buildClientPlayerStatePayload(session.mySlotIndex)));
        } catch (final Exception ignored) { }
        try {
            session.client.send(new forge.gamemodes.net.adventure.AdventureNetEvent(
                    forge.gamemodes.net.adventure.AdventureNetEvent.Type.EVENT_READY,
                    Integer.valueOf(session.mySlotIndex)));
        } catch (final Exception ignored) { }
    }

    /**
     * Broadcasts EVENT_INIT so connected adventure clients open the same EventScene with
     * a deserialized copy of {@code currentEvent}.  No-op outside multiplayer host context.
     * Each client recreates the same draft/sealed pool locally from the shared event seed.
     */
    private static void broadcastEventInitIfHost() {
        final forge.gamemodes.net.adventure.AdventureNetSession session =
                forge.gamemodes.net.adventure.AdventureNetSession.getInstance();
        if (!session.isActiveHost() || currentEvent == null) return;
        session.activeEventData = currentEvent;
        session.eventReadyClients.clear();
        forge.gamemodes.net.server.FServerManager.getInstance().broadcast(
                new forge.gamemodes.net.adventure.AdventureNetEvent(
                        forge.gamemodes.net.adventure.AdventureNetEvent.Type.EVENT_INIT,
                        currentEvent));
    }

    private void nextPage(boolean reverse) {
        //todo: add translations
        headerTable.clear();
        if (!reverse && ++pageIndex >= eventPages.length) {
            pageIndex = 0;
        } else if (reverse && --pageIndex < 0) {
            pageIndex = eventPages.length - 1;
        }
        if (pageIndex == 0) {
            headerTable.add("Event Standings").expand();
        } else {
            headerTable.add("Round " + (pageIndex) + " of " + (eventPages.length - 1));
        }

        refresh();
    }

    @Override
    public void enter() {
        super.enter();
        GameHUD.getInstance().updateBGM();
        scrollContainer.clear();

        if (money != null) {
            WorldSave.getCurrentSave().getPlayer().onGoldChange(() -> money.setText("[+Gold] [BLACK]" + AdventurePlayer.current().getGold()));
        }
        if (shards != null) {
            WorldSave.getCurrentSave().getPlayer().onShardsChange(() -> shards.setText("[+Shards] [BLACK]" + AdventurePlayer.current().getShards()));
        }
        performTouch(scrollPaneOfActor(scrollContainer)); //can use mouse wheel if available to scroll

        if (currentEvent.eventStatus == Entered) {
            loadMetaDraft();
        }

        refresh();
    }

    public void editDeck() {
        DeckEditScene.getInstance().loadEvent(currentEvent);
        Forge.switchScene(DeckEditScene.getInstance());
    }

    public void advance() {
        switch (currentEvent.eventStatus) {
            case Available:
                activate(entryDialog); //Entry fee pop-up
                break;
            case Entered: //Start draft or select deck
                //Show progress / wait indicator? Draft can take a while to generate
                switch (currentEvent.format) {
                    case Draft:
                        editDeck();
                        break;
                    case Sealed:
                        if (currentEvent.registeredDeck.get(DeckSection.Sideboard) == null) {
                            currentEvent.generateSealedPool();
                        }
                        currentEvent.eventStatus = Ready;
                        editDeck();
                        break;
                    case Jumpstart:
                        loadMetaDraft();
                }
                break;
            case Ready: //Commit to selected deck
                if(!validateDeck())
                    break;
                currentEvent.startEvent();
            case Started: //Play next round
                if(!validateDeck())
                    break;
                advance.setDisabled(true);
                startRound();
                break;
            case Completed://Show results, allow collection of rewards
            case Awarded: //Show results but don't allow any further interaction
                advance.setDisabled(true);
                currentEvent.giveRewards();
                break;

            case Abandoned: //Show results but don't allow any interaction
                break;
        }
        refresh();
    }

    @Override
    public boolean back() {
        if (currentEvent.eventStatus.equals(Awarded)) {
            AdventureEventController.instance().finalizeEvent(currentEvent);
            currentEvent = null;
        }
        Forge.switchScene(lastGameScene == null ? GameScene.instance() : lastGameScene);
        return true;
    }

    public void startRound() {
        // Multiplayer client: host owns round timing; ignore local triggers entirely
        // (BATTLE_INIT from the host will pull this client into the co-op match).
        if (forge.gamemodes.net.adventure.AdventureNetSession.getInstance().isActiveClient()) {
            advance.setDisabled(true);
            return;
        }
        for (AdventureEventData.AdventureEventMatch match : currentEvent.getMatches(currentEvent.currentRound)) {
            match.round = currentEvent.currentRound;
            if (match.winner != null) continue;

            if (match.p2 == null) {
                //shouldn't happen under current setup, but this would be a bye
                match.winner = match.p1;
                match.p1.wins += 1;
            }

            if (match.p1 instanceof AdventureEventData.AdventureEventHuman) {
                humanMatch = match;
            } else if (match.p2 instanceof AdventureEventData.AdventureEventHuman) {
                AdventureEventData.AdventureEventParticipant placeholder = match.p1;
                match.p1 = match.p2;
                match.p2 = placeholder;
                humanMatch = match;
            } else {
                //Todo: Actually run match simulation here
                if (MyRandom.percentTrue(50)) {
                    match.p1.wins++;
                    match.p2.losses++;
                    match.winner = match.p1;
                } else {
                    match.p1.losses++;
                    match.p2.wins++;
                    match.winner = match.p2;
                }
            }
        }

        if (humanMatch != null && humanMatch.round != currentEvent.currentRound)
            humanMatch = null;
        if (humanMatch != null) {
            DuelScene duelScene = DuelScene.instance();
            EnemySprite enemy = humanMatch.p2.getSprite();
            currentEvent.nextOpponent = humanMatch.p2;
            advance.setDisabled(true);
            // Multiplayer host: gate on every connected client having sent EVENT_READY for the
            // current event, then mirror MapStage.beginDuel() by broadcasting BATTLE_INIT so all
            // clients enter the same co-op match.  Their drafted decks come in via PLAYER_STATE
            // (activeEventDeck override) ahead of the host's DuelScene.enter() reading them.
            final forge.gamemodes.net.adventure.AdventureNetSession netSession =
                    forge.gamemodes.net.adventure.AdventureNetSession.getInstance();
            if (netSession.isActiveHost()) {
                if (!allRemoteClientsEventReady(netSession)) {
                    showWaitingForClientsDialog();
                    advance.setDisabled(false);
                    return;
                }
                netSession.serverLobby.initiateBattle(enemy.getData(),
                        WorldStage.getInstance().collectPlayerPositions());
            }
            FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new TransitionScreen(() -> {
                duelScene.initDuels(WorldStage.getInstance().getPlayerSprite(), enemy, false, currentEvent);
                advance.setDisabled(false);
                Forge.switchScene(duelScene);
            }, Forge.takeScreenshot(), true, false, false, false, "", Current.player().avatar(), enemy.getAtlasPath(), Current.player().getName(), enemy.getName(), humanMatch.p1.getRecord(), humanMatch.p2.getRecord())));
        } else {
            finishRound();
            advance.setDisabled(false);
        }
    }

    /**
     * Host-side: returns true if every connected remote client has sent EVENT_READY for the
     * active event.  Used to gate startRound() so a still-drafting client doesn't get dragged
     * into a match with an empty/stale deck.
     */
    private static boolean allRemoteClientsEventReady(
            final forge.gamemodes.net.adventure.AdventureNetSession session) {
        if (session.serverLobby == null) return true;
        for (int i = 1; i < session.serverLobby.getNumberOfSlots(); i++) {
            final forge.gamemodes.match.LobbySlot slot = session.serverLobby.getSlot(i);
            if (slot == null) continue;
            if (slot.getType() != forge.gamemodes.match.LobbySlotType.REMOTE) continue;
            if (!session.eventReadyClients.contains(i)) return false;
        }
        return true;
    }

    private void showWaitingForClientsDialog() {
        DialogData wait = new DialogData();
        wait.text = "Waiting for other players to finish building their decks.";
        DialogData ok = new DialogData();
        ok.locname = "lblOK";
        wait.options = new DialogData[]{ ok };
        loadDialog(wait);
    }

    public void setWinner(boolean winner, boolean isArena) {
        if (winner) {
            humanMatch.winner = humanMatch.p1;
            humanMatch.p1.wins++;
            humanMatch.p2.losses++;
            currentEvent.matchesWon++;
        } else {
            humanMatch.winner = humanMatch.p2;
            humanMatch.p2.wins++;
            humanMatch.p1.losses++;
            currentEvent.matchesLost++;
        }

//        if (winner) {
//            AdventureQuestController.instance().updateQuestsWin(currentMob,enemies);
//            AdventureQuestController.instance().showQuestDialogs(MapStage.this);
//        } else {
//           AdventureQuestController.instance().updateQuestsLose(currentMob);
//           AdventureQuestController.instance().showQuestDialogs(MapStage.this);
//        }

        finishRound();
    }

    public void finishRound() {
        // TODO: Handle the scenario where a 3-match duel includes a draw. 
        // Currently, the system does not account for draws, which may lead to incorrect behavior.
        // Consider adding logic to track and resolve draw matches, ensuring the overall match outcome is determined correctly.
        if (currentEvent.currentRound == currentEvent.rounds) {
            finishEvent();
        } else currentEvent.currentRound += 1;
        refresh();
    }

    public void finishEvent() {
        currentEvent.eventStatus = AdventureEventController.EventStatus.Completed;
    }

    public void loadMetaDraft() {
        if(currentEvent.jumpstartBoosters.isEmpty()) {
            metaDraftTable.setVisible(false);
            return;
        }

        metaDraftTable.setVisible(true);

        metaDraftTable.clear();
        for (Deck deckOption : currentEvent.jumpstartBoosters) {
            if (metaDraftTable.hasChildren())
                metaDraftTable.row();

            final TypingLabel packName = Controls.newTypingLabel(deckOption.getName().replaceFirst("^[A-Z]([A-Z|\\d]){2}\\s", ""));
            packName.skipToTheEnd();

            metaDraftTable.add(packName);
            //TODO: Replace with translations

            TextraButton previewButton = Controls.newTextButton("Preview");
            previewButton.addListener(new ClickListener() {
                public void clicked(InputEvent event, float x, float y) {
                    Forge.switchScene(DeckPreviewScene.getInstance(deckOption));
                }
            });

            TextraButton selectButton = Controls.newTextButton("Select");
            if (deckOption.getTags().contains("Selected")) {
                packName.setColor(Color.FOREST);
                selectButton.setColor(Color.FOREST);
                selectButton.setDisabled(true);
            } else {
                selectButton.addListener(new ClickListener() {
                    public void clicked(InputEvent event, float x, float y) {
                        selectButton.clearListeners();
                        deckOption.getTags().add("Selected");
                        if (!selectedJumpstartPackIsLast(deckOption)) {
                            loadMetaDraft();
                        } else {
                            metaDraftTable.setVisible(false);
                        }
                    }
                });
            }

            metaDraftTable.add(previewButton).padLeft(10);
            metaDraftTable.add(selectButton).padLeft(10);
        }
        eventPages[0] = metaDraftTable;
    }

    private boolean validateDeck() {
        String deckError = currentEvent.format.getDeckFormat().getDeckConformanceProblem(currentEvent.registeredDeck);

        if(deckError != null) {
            DialogData warning = new DialogData();
            warning.locname = "lblInvalidDeck";
            warning.text = "Deck " + deckError; //Needs localization but so does getDeckConformanceProblem.

            DialogData dismiss = new DialogData();
            dismiss.locname = "lblOK";
            warning.options = new DialogData[]{dismiss};

            loadDialog(warning);
            return false;
        }
        return true;
    }

    private boolean selectedJumpstartPackIsLast(Deck selectedPack) {
        int packsToPick = 3;
        int packsPicked = 0;
        Deck currentPicks = new Deck();
        for (Deck deckOption : currentEvent.jumpstartBoosters) {
            if (deckOption.getTags().contains("Selected")) {
                packsPicked++;
                currentPicks.getMain().addAll(deckOption.getAllCardsInASinglePool());
            }
            if (packsPicked >= packsToPick) {
                currentEvent.registeredDeck.getMain().clear();
                currentEvent.registeredDeck.getMain().addAll(currentPicks.getAllCardsInASinglePool());
                metaDraftTable.setVisible(false);
                currentEvent.eventStatus = Ready;
                refresh();
                return true;
            }
        }
        return false;
    }


}
