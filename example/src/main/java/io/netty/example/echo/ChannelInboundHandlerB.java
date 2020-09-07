package io.netty.example.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author kyushu
 */
public class ChannelInboundHandlerB extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        System.out.println("ChannelInboundHandlerB ==> " + msg);
        ctx.fireChannelRead(msg);   // 通过context直接调用 从当前节点向后传播
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().pipeline().fireChannelRead("hello word"); // 通过context.channel().pipeline()调用 从header节点向后传播
    }
}
