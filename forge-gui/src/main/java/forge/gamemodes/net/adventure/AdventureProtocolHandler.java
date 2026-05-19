package forge.gamemodes.net.adventure;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.function.Consumer;

/**
 * Netty inbound handler for Adventure map events.
 * Intercepts AdventureNetEvent messages and dispatches them to the provided handler.
 * All other message types are passed down the pipeline unchanged via ctx.fireChannelRead().
 *
 * Register this handler BEFORE GameServerHandler in both FServerManager and FGameClient
 * so that AdventureNetEvents are consumed here and GuiGameEvents continue to GameServerHandler.
 */
public class AdventureProtocolHandler extends ChannelInboundHandlerAdapter {

    private final Consumer<AdventureNetEvent> dispatcher;

    public AdventureProtocolHandler(final Consumer<AdventureNetEvent> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof AdventureNetEvent) {
            try {
                dispatcher.accept((AdventureNetEvent) msg);
            } catch (final Exception e) {
                System.err.println("AdventureProtocolHandler: error dispatching " + msg + ": " + e.getMessage());
                e.printStackTrace();
            }
            // Do not fire further — adventure events are terminal at this handler.
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        System.err.println("AdventureProtocolHandler: channel exception: " + cause.getMessage());
        ctx.fireExceptionCaught(cause);
    }
}
