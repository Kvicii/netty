/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

/**
 * {@link ChannelHandler} which adds callbacks for state changes. This allows the user
 * to hook in to state changes easily.
 * 入站处理器 方法体现出的更多是事件的触发 触发大多是被动的
 * <p>
 * 以OP_READ事件为例: 在Channel中发生OP_READ事件后 会被EventLoop查询到 然后分发给ChannelInboundHandler入站处理 调用其read方法从通道中读取数据
 * 入站处理方向: Channel -> ChannelInboundHandler
 */
public interface ChannelInboundHandler extends ChannelHandler {

	/**
	 * The {@link Channel} of the {@link ChannelHandlerContext} was registered with its {@link EventLoop}
	 * 当通道注册完成后 会调用fireChannelRegistered触发通道注册事件 通道会启动该入站操作的pipeline处理 在通道注册过的入站处理器handler的channelRegistered方法会被调用到
	 * 即channel注册到NioEventLoop对应的Selector之后 会回调该方法
	 */
	void channelRegistered(ChannelHandlerContext ctx) throws Exception;

	/**
	 * The {@link Channel} of the {@link ChannelHandlerContext} was unregistered from its {@link EventLoop}
	 */
	void channelUnregistered(ChannelHandlerContext ctx) throws Exception;

	/**
	 * The {@link Channel} of the {@link ChannelHandlerContext} is now active
	 * 当通道激活完成后 调用fireChannelActive触发通道激活事件 通道会将该入站操作的pipeline处理 在通道注册过的入站处理器的handler的channelActive方法会被调用到
	 */
	void channelActive(ChannelHandlerContext ctx) throws Exception;

	/**
	 * The {@link Channel} of the {@link ChannelHandlerContext} was registered is now inactive and reached its
	 * end of lifetime.
	 * 当连接被断开或者不可用 调用fireChannelInactive触发连接不可用事件 通道会启动对应的pipeline处理 在通道注册过的入站处理器handler的channelInactive方法会被调用到
	 */
	void channelInactive(ChannelHandlerContext ctx) throws Exception;

	/**
	 * Invoked when the current {@link Channel} has read a message from the peer.
	 * 当通道缓冲区可读 调用fireChannelRead触发通道可读事件 通道会启动该入站操作的pipeline处理 在通道注册过的入站处理器的handler的channelRead方法会被调用到
	 * <p>
	 * channel读到了数据或接收到了连接
	 * 对于服务端channel msg是一个连接 对于客户端channel msg是一个ByteBuf的数据
	 */
	void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception;

	/**
	 * Invoked when the last message read by the current read operation has been consumed by
	 * {@link #channelRead(ChannelHandlerContext, Object)}.  If {@link ChannelOption#AUTO_READ} is off, no further
	 * attempt to read an inbound data from the current {@link Channel} will be made until
	 * {@link ChannelHandlerContext#read()} is called.
	 * 当通道缓冲区读完 调用fireChannelReadComplete触发通道读完事件 通道会启动该入站操作的pipeline处理 在通道注册过的入站处理器handler的channelReadComplete方法会被调用到
	 */
	void channelReadComplete(ChannelHandlerContext ctx) throws Exception;

	/**
	 * Gets called if an user event was triggered.
	 * <p>
	 * 用户可以自定义一些triggered事件
	 */
	void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception;

	/**
	 * Gets called once the writable state of a {@link Channel} changed. You can check the state with
	 * {@link Channel#isWritable()}.
	 * <p>
	 * 通道的可写状态做了改变
	 */
	void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception;

	/**
	 * Gets called if a {@link Throwable} was thrown.
	 * 当通道处理过程发生异常时 调用fireExceptionCaught触发异常捕获事件 通道会启动异常捕获的pipeline处理 在通道注册过的处理器handler的exceptionCaught方法会被调用到
	 * 该方法是在通道管理器中ChannelHandler定义的方法 入站处理器 出站处理器接口都继承到了该方法
	 */
	@Override
	@SuppressWarnings("deprecation")
	void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;
}
