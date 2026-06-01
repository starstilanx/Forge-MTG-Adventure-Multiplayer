package forge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.ai.gemini.GeminiClient;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.tinylog.Logger;

import java.util.List;

public class PlayerControllerGemini extends PlayerControllerAi {

    private final String systemPrompt;
    private GeminiClient client;

    public PlayerControllerGemini(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
        String name = lp.getName();
        this.systemPrompt =
            "You are " + name + ", an opponent in a Magic: The Gathering duel. " +
            "Embody the character of " + name + " in your decision-making style. " +
            "You will receive the current game state and a list of available spells/abilities as JSON. " +
            "Respond ONLY with valid JSON: {\"playIndex\": N} where N is the index from availableSpells " +
            "(-1 to pass priority). Be strategic: consider life totals, board presence, and mana efficiency.";
    }

    private GeminiClient getClient() {
        if (client == null) client = new GeminiClient();
        return client;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> aiChoice = super.chooseSpellAbilityToPlay();
        if (aiChoice == null || aiChoice.isEmpty()) return aiChoice;

        try {
            JsonObject state = buildGameState(aiChoice);
            JsonObject response = getClient().ask(systemPrompt, state.toString());

            int idx = -1;
            if (response.has("playIndex")) {
                idx = response.get("playIndex").getAsInt();
            }

            if (idx >= 0 && idx < aiChoice.size()) {
                return List.of(aiChoice.get(idx));
            }
            return List.of(); // pass
        } catch (Exception e) {
            Logger.warn("Gemini chooseSpellAbilityToPlay failed, falling back to AI: {}", e.getMessage());
            return aiChoice;
        }
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        super.declareAttackers(attacker, combat);
        // Attack decisions delegated to parent AI for now; Gemini influences spell sequencing
    }

    private JsonObject buildGameState(List<SpellAbility> available) {
        JsonObject state = new JsonObject();

        Player opp = getOpponent();
        state.addProperty("myLife", player.getLife());
        state.addProperty("opponentLife", opp != null ? opp.getLife() : 0);
        state.addProperty("myHandSize", player.getCardsIn(ZoneType.Hand).size());
        state.addProperty("myBoardSize", player.getCardsIn(ZoneType.Battlefield).size());
        state.addProperty("oppBoardSize", opp != null ? opp.getCardsIn(ZoneType.Battlefield).size() : 0);
        state.addProperty("turn", player.getGame().getPhaseHandler().getTurn());
        state.addProperty("phase", player.getGame().getPhaseHandler().getPhase().nameForUi);

        JsonArray hand = new JsonArray();
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            JsonObject card = new JsonObject();
            card.addProperty("name", c.getName());
            card.addProperty("manaCost", c.getManaCost().toString());
            hand.add(card);
        }
        state.add("hand", hand);

        JsonArray battlefield = new JsonArray();
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            JsonObject card = new JsonObject();
            card.addProperty("name", c.getName());
            card.addProperty("tapped", c.isTapped());
            battlefield.add(card);
        }
        state.add("myBattlefield", battlefield);

        JsonArray spells = new JsonArray();
        for (int i = 0; i < available.size(); i++) {
            SpellAbility sa = available.get(i);
            JsonObject spell = new JsonObject();
            spell.addProperty("index", i);
            spell.addProperty("card", sa.getHostCard().getName());
            spell.addProperty("description", sa.toUnsuppressedString());
            spells.add(spell);
        }
        state.add("availableSpells", spells);

        return state;
    }

    private Player getOpponent() {
        for (Player p : player.getGame().getPlayers()) {
            if (!p.equals(player)) return p;
        }
        return null;
    }
}
