package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

public class LobbyPlayerGemini extends LobbyPlayerAi {

    public LobbyPlayerGemini(String name) {
        super(name, null);
    }

    private PlayerControllerGemini createGeminiController(Player ai) {
        return new PlayerControllerGemini(ai.getGame(), ai, this);
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createGeminiController(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createGeminiController(ai));
        return ai;
    }
}
