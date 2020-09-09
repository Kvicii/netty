package io.netty.example.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author kyushu
 */
public class ExceptionCaughtHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {

        if (cause instanceof RuntimeException) {
            System.out.println("RuntimeException");
        }
    }
}
