package io.netty.example.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author kyushu
 */
public class ChannelInboundHandlerB extends ChannelInboundHandlerAdapter {

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		// 模拟正常Inbound
		// System.out.println("ChannelInboundHandlerB ==> " + msg);
		// ctx.fireChannelRead(msg);   // 通过context直接调用 从当前节点向后传播
		// 模拟异常
        throw new RuntimeException("模拟异常传递");
	}

	//  @Override
	//  public void channelActive(ChannelHandlerContext ctx) {
	//  	ctx.channel().pipeline().fireChannelRead("hello word"); // 通过context.channel().pipeline()调用 从header节点向后传播
	//  	// ctx.fireChannelRead("hello word");
	//  }

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		System.out.println("exceptionCaughtB Inbound throw exception");
		ctx.fireExceptionCaught(cause);
	}
}
