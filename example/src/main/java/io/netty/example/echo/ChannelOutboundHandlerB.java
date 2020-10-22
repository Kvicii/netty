package io.netty.example.echo;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.TimeUnit;

/**
 * @author kyushu
 */
public class ChannelOutboundHandlerB extends ChannelOutboundHandlerAdapter {

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		// 观察者模式 即netty中的future-promise模式
//		ChannelFuture channelFuture = ctx.channel().writeAndFlush(msg);
//		channelFuture.addListener((ChannelFutureListener) future -> {
//			if (future.isSuccess()) {
//
//			} else {
//
//			}
//		});

		System.out.println("ChannelOutboundHandlerB ==> " + msg);
		ctx.write(msg, promise);    // 通过context直接调用 从当前节点开始向前传播
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		ctx.executor().schedule(() -> {
			ctx.channel().pipeline().write("hello word");  // 通过context.channel()调用 从TailContext节点开始向前传播
		}, 3, TimeUnit.SECONDS);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		System.out.println("exceptionCaughtB Outbound throw exception");
		ctx.fireExceptionCaught(cause);
	}
}
