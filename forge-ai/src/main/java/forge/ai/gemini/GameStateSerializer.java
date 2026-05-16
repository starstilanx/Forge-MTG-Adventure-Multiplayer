package forge.ai.gemini;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;

public class GameStateSerializer {

    private static JsonObject serializeCard(Card card) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", card.getName());
        obj.addProperty("type", card.getType().toString());
        obj.addProperty("tapped", card.isTapped());
        if (card.isCreature()) {
            obj.addProperty("power", card.getNetPower());
            obj.addProperty("toughness", card.getNetToughness());
        }
        if (card.getRules() != null) {
            String oracle = card.getRules().getOracleText();
            if (oracle != null && !oracle.isEmpty()) {
                obj.addProperty("oracle", oracle);
            }
        }
        return obj;
    }

    private static JsonArray serializeCards(CardCollectionView cards) {
        JsonArray arr = new JsonArray();
        for (Card card : cards) {
            arr.add(serializeCard(card));
        }
        return arr;
    }

    private static JsonObject serializePlayer(Player player, boolean includeHand) {
        JsonObject obj = new JsonObject();
        obj.addProperty("life", player.getLife());
        obj.addProperty("total_mana", player.getManaPool().totalMana());
        obj.add("battlefield", serializeCards(player.getCardsIn(ZoneType.Battlefield)));
        if (includeHand) {
            obj.add("hand", serializeCards(player.getCardsIn(ZoneType.Hand)));
        }
        obj.addProperty("graveyard_count", player.getCardsIn(ZoneType.Graveyard).size());
        obj.addProperty("library_count", player.getCardsIn(ZoneType.Library).size());
        return obj;
    }

    private static Player getOpponent(Game game, Player ai) {
        for (Player p : game.getPlayers()) {
            if (!p.equals(ai)) return p;
        }
        return null;
    }

    public static String buildSpellChoicePayload(Game game, Player ai, List<SpellAbility> available) {
        JsonObject root = new JsonObject();
        root.addProperty("decision", "choose_spell");
        root.addProperty("phase", game.getPhaseHandler().getPhase().toString());
        root.add("ai_player", serializePlayer(ai, true));

        Player opp = getOpponent(game, ai);
        if (opp != null) {
            root.add("opponent", serializePlayer(opp, false));
        }

        JsonArray plays = new JsonArray();
        for (int i = 0; i < available.size(); i++) {
            SpellAbility sa = available.get(i);
            JsonObject play = new JsonObject();
            play.addProperty("index", i);
            play.addProperty("card_name", sa.getHostCard() != null ? sa.getHostCard().getName() : "unknown");
            play.addProperty("description", sa.toString());
            plays.add(play);
        }
        root.add("available_plays", plays);

        JsonObject fmt = new JsonObject();
        fmt.addProperty("action", "play OR pass");
        fmt.addProperty("play_index", "integer index from available_plays (only when action is play)");
        root.add("response_format", fmt);

        return root.toString();
    }

    public static String buildAttackPayload(Game game, Player ai, List<Card> potentialAttackers, Player defender) {
        JsonObject root = new JsonObject();
        root.addProperty("decision", "declare_attackers");
        root.add("ai_player", serializePlayer(ai, false));
        root.add("opponent", serializePlayer(defender, false));

        JsonArray arr = new JsonArray();
        for (int i = 0; i < potentialAttackers.size(); i++) {
            JsonObject entry = serializeCard(potentialAttackers.get(i));
            entry.addProperty("index", i);
            arr.add(entry);
        }
        root.add("potential_attackers", arr);

        JsonObject fmt = new JsonObject();
        fmt.addProperty("attackers", "array of indices from potential_attackers to send into combat");
        root.add("response_format", fmt);

        return root.toString();
    }

    public static String buildCommentaryPayload(Game game, Player ai) {
        JsonObject root = new JsonObject();
        root.addProperty("phase", game.getPhaseHandler().getPhase().toString());
        root.add("ai_player", serializePlayer(ai, false));
        Player opp = getOpponent(game, ai);
        if (opp != null) {
            root.add("opponent", serializePlayer(opp, false));
        }
        return root.toString();
    }

    public static String buildBlockPayload(Game game, Player defender, CardCollection attackers, List<Card> potentialBlockers) {
        JsonObject root = new JsonObject();
        root.addProperty("decision", "declare_blockers");
        root.add("ai_player", serializePlayer(defender, false));

        JsonArray atkArr = new JsonArray();
        for (int i = 0; i < attackers.size(); i++) {
            JsonObject entry = serializeCard(attackers.get(i));
            entry.addProperty("index", i);
            atkArr.add(entry);
        }
        root.add("attackers", atkArr);

        JsonArray blkArr = new JsonArray();
        for (int i = 0; i < potentialBlockers.size(); i++) {
            JsonObject entry = serializeCard(potentialBlockers.get(i));
            entry.addProperty("index", i);
            blkArr.add(entry);
        }
        root.add("potential_blockers", blkArr);

        JsonObject fmt = new JsonObject();
        fmt.addProperty("blocks", "array of {attacker_index, blocker_index} pairs");
        root.add("response_format", fmt);

        return root.toString();
    }
}
