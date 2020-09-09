package io.netty.example.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author kyushu
 */
public class ChannelInboundHandlerC extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        System.out.println("ChannelInboundHandlerC ==> " + msg);
        ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("exceptionCaughtC Inbound throw exception");
        ctx.fireExceptionCaught(cause);
    }
}
