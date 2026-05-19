package forge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.ai.gemini.GameStateSerializer;
import forge.ai.gemini.GeminiChatBus;
import forge.ai.gemini.GeminiClient;
import forge.ai.gemini.GeminiCommentator;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class PlayerControllerGemini extends PlayerControllerAi {

    private final String systemPrompt;

    private final GeminiClient gemini = new GeminiClient();
    private final GeminiCommentator commentator = new GeminiCommentator();
    private int turnsUntilComment = nextCommentInterval();
    private static final ExecutorService COMMENT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gemini-commentator");
        t.setDaemon(true);
        return t;
    });

    private static int nextCommentInterval() {
        return 1 + (int) (Math.random() * 4);
    }

    public PlayerControllerGemini(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
        String name = lp.getName();
        this.systemPrompt =
            "You are " + name + ", an opponent in a Magic: The Gathering duel. " +
            "Embody the character of " + name + " in your decision-making style. " +
            "You will receive the current game state as a JSON object and must make a strategic decision. " +
            "Always respond with valid JSON exactly matching the response_format field. " +
            "Be strategic: consider life totals, board presence, available mana, card synergies, and blocking threats.";
    }

    @Override
    public void resetAtEndOfTurn() {
        super.resetAtEndOfTurn();
        if (--turnsUntilComment <= 0) {
            turnsUntilComment = nextCommentInterval();
            final String playerName = player.getName();
            final Game currentGame = getGame();
            COMMENT_EXECUTOR.submit(() -> {
                try {
                    String stateJson = GameStateSerializer.buildCommentaryPayload(currentGame, player);
                    String comment = commentator.getCommentary(stateJson);
                    if (comment != null && !comment.isEmpty()) {
                        GeminiChatBus.post(playerName, comment);
                    }
                } catch (Exception e) {
                    Logger.warn("Gemini commentary failed: {}", e.getMessage());
                }
            });
        }
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // Run each candidate through canPlaySa(), which validates targeting and sets
        // up target choices on the SpellAbility as a side effect. Only pre-targeted
        // spells are sent to Gemini so the engine can play them immediately.
        AiController ai = getAiController();
        List<SpellAbility> available = new ArrayList<>();
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(player.getCardsIn(ZoneType.Hand), player)) {
            if (!sa.isLandAbility()
                    && ai.canPlaySa(sa) == AiPlayDecision.WillPlay
                    && ComputerUtilMana.canPayManaCost(sa, player, 0, false)) {
                available.add(sa);
            }
        }

        CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(getGame(), player);
        if (lands != null) {
            for (Card land : lands) {
                available.addAll(land.getAllPossibleAbilities(player, true));
            }
        }

        if (available.isEmpty()) return null;

        try {
            String payload = GameStateSerializer.buildSpellChoicePayload(getGame(), player, available);
            JsonObject response = gemini.ask(systemPrompt, payload);

            String action = response.get("action").getAsString();
            if ("pass".equals(action)) return null;

            int idx = response.get("play_index").getAsInt();
            if (idx >= 0 && idx < available.size()) {
                List<SpellAbility> result = new ArrayList<>();
                result.add(available.get(idx));
                return result;
            }
            Logger.warn("Gemini returned out-of-range play_index {}, falling back to AI", idx);
        } catch (Exception e) {
            Logger.warn("Gemini spell choice failed ({}), falling back to AI", e.getMessage());
        }

        return super.chooseSpellAbilityToPlay();
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        List<Card> potentialAttackers = new ArrayList<>();
        for (Card card : attacker.getCardsIn(ZoneType.Battlefield)) {
            if (card.isCreature() && !card.isTapped()) {
                potentialAttackers.add(card);
            }
        }

        if (potentialAttackers.isEmpty()) return;

        Player defender = null;
        for (Player p : getGame().getPlayers()) {
            if (!p.equals(attacker)) { defender = p; break; }
        }
        if (defender == null) return;

        try {
            String payload = GameStateSerializer.buildAttackPayload(getGame(), attacker, potentialAttackers, defender);
            JsonObject response = gemini.ask(systemPrompt, payload);

            JsonArray attackerIndices = response.getAsJsonArray("attackers");
            for (int i = 0; i < attackerIndices.size(); i++) {
                int idx = attackerIndices.get(i).getAsInt();
                if (idx >= 0 && idx < potentialAttackers.size()) {
                    combat.addAttacker(potentialAttackers.get(idx), defender);
                }
            }
            return;
        } catch (Exception e) {
            Logger.warn("Gemini attack declaration failed ({}), falling back to AI", e.getMessage());
        }

        super.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        CardCollection attackers = combat.getAttackers();
        if (attackers.isEmpty()) return;

        List<Card> potentialBlockers = new ArrayList<>();
        for (Card card : defender.getCardsIn(ZoneType.Battlefield)) {
            if (card.isCreature() && !card.isTapped()) {
                potentialBlockers.add(card);
            }
        }

        if (potentialBlockers.isEmpty()) return;

        try {
            String payload = GameStateSerializer.buildBlockPayload(getGame(), defender, attackers, potentialBlockers);
            JsonObject response = gemini.ask(systemPrompt, payload);

            JsonArray blocks = response.getAsJsonArray("blocks");
            for (int i = 0; i < blocks.size(); i++) {
                JsonObject block = blocks.get(i).getAsJsonObject();
                int attackerIdx = block.get("attacker_index").getAsInt();
                int blockerIdx = block.get("blocker_index").getAsInt();
                if (attackerIdx >= 0 && attackerIdx < attackers.size() &&
                    blockerIdx >= 0 && blockerIdx < potentialBlockers.size()) {
                    combat.addBlocker(attackers.get(attackerIdx), potentialBlockers.get(blockerIdx));
                }
            }
            return;
        } catch (Exception e) {
            Logger.warn("Gemini block declaration failed ({}), falling back to AI", e.getMessage());
        }

        super.declareBlockers(defender, combat);
    }
}
