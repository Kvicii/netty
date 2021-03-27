package io.netty.example.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author kyushu
 */
public class ChannelInboundHandlerA extends ChannelInboundHandlerAdapter {

	// @Override
	// public void channelRead(ChannelHandlerContext ctx, Object msg) {
	// 	System.out.println("ChannelInboundHandlerA ==> " + msg);
	// 	ctx.fireChannelRead(msg);
	// }

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		System.out.println("exceptionCaughtA Inbound throw exception");
		ctx.fireExceptionCaught(cause);
	}
}
