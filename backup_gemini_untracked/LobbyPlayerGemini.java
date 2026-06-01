package forge.ai;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

public class LobbyPlayerGemini extends LobbyPlayer implements IGameEntitiesFactory {

    public LobbyPlayerGemini(String name) {
        super(name);
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new PlayerControllerGemini(slave.getGame(), slave, this);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new PlayerControllerGemini(game, p, this));
        return p;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { }
}
