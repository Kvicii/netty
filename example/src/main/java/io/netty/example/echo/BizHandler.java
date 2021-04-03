package io.netty.example.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class BizHandler extends ChannelInboundHandlerAdapter {

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		BizUser user = new BizUser(19, "kyushu");
		ctx.channel().writeAndFlush(user);
	}
}
