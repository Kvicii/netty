/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
	boolean inputShutdown;

	/**
	 * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
	 */
	protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
		super(parent, ch, readInterestOp);
	}

	/**
	 * 创建服务端unsafe
	 *
	 * @return
	 */
	@Override
	protected AbstractNioUnsafe newUnsafe() {
		return new NioMessageUnsafe();
	}

	@Override
	protected void doBeginRead() throws Exception {
		if (inputShutdown) {
			return;
		}
		super.doBeginRead();
	}

	/**
	 * Unsafe负责读写抽象
	 * 服务端channel 即 {@link NioMessageUnsafe} 指的是读一条新的连接
	 */
	private final class NioMessageUnsafe extends AbstractNioUnsafe {

		private final List<Object> readBuf = new ArrayList<>(); // 服务端channel对应的MessageUnsafe对应的一个字段

		@Override
		public void read() {
			assert eventLoop().inEventLoop();   // 必须是有NioEventLoop线程调用的 如果是外部线程调用的就不继续执行了
			final ChannelConfig config = config();  // 服务端Config 即ServerSocketChannelConfig
			final ChannelPipeline pipeline = pipeline();    // 服务端pipeline
			final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();  // 处理服务端接入的速率
			allocHandle.reset(config);

			boolean closed = false;
			Throwable exception = null;
			try {
				try {
					do {    //  默认情况下一次性读取16个连接
						int localRead = doReadMessages(readBuf);    // 新连接接入 服务端的读是读取新的连接
						if (localRead == 0) {
							break;
						}
						if (localRead < 0) {
							closed = true;
							break;
						}

						allocHandle.incMessagesRead(localRead); // 分配器将读到的连接进行计数
					} while (allocHandle.continueReading());    // 是否继续读 如果是创建连接的请求会跳出
				} catch (Throwable t) {
					exception = t;
				}

				int size = readBuf.size();
				for (int i = 0; i < size; i++) {
					readPending = false;
					/**
					 * NioServerSocketChannel初始化时会添加一个默认的ServerBootstrapAcceptor
					 * 即 {@link ServerBootstrap#init(io.netty.channel.Channel)}
					 *
					 * 服务端channel的pipeline构成:
					 * Head 的Handler --> ServerBootstrapAcceptor 的Handler --> Tail 的Handler
					 * pipeline传播read事件会从Head开始 经由 ServerBootstrapAcceptor 最终传播到Tail
					 *
					 * 最终会把客户端的每一个连接传播到ServerBootstrapAcceptor:
					 * {@link ServerBootstrap.ServerBootstrapAcceptor#channelRead(io.netty.channel.ChannelHandlerContext, java.lang.Object)}
					 */
					pipeline.fireChannelRead(readBuf.get(i));   // 将结果传播出去
				}
				readBuf.clear();
				allocHandle.readComplete();
				pipeline.fireChannelReadComplete();

				if (exception != null) {
					closed = closeOnReadError(exception);

					pipeline.fireExceptionCaught(exception);
				}

				if (closed) {
					inputShutdown = true;
					if (isOpen()) {
						close(voidPromise());
					}
				}
			} finally {
				// Check if there is a readPending which was not processed yet.
				// This could be for two reasons:
				// * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
				// * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
				//
				// See https://github.com/netty/netty/issues/2254
				if (!readPending && !config.isAutoRead()) {
					removeReadOp();
				}
			}
		}
	}

	@Override
	protected void doWrite(ChannelOutboundBuffer in) throws Exception {
		final SelectionKey key = selectionKey();
		final int interestOps = key.interestOps();

		for (; ; ) {
			Object msg = in.current();
			if (msg == null) {
				// Wrote all messages.
				if ((interestOps & SelectionKey.OP_WRITE) != 0) {
					key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
				}
				break;
			}
			try {
				boolean done = false;
				for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
					if (doWriteMessage(msg, in)) {
						done = true;
						break;
					}
				}

				if (done) {
					in.remove();
				} else {
					// Did not write all messages.
					if ((interestOps & SelectionKey.OP_WRITE) == 0) {
						key.interestOps(interestOps | SelectionKey.OP_WRITE);
					}
					break;
				}
			} catch (Exception e) {
				if (continueOnWriteError()) {
					in.remove(e);
				} else {
					throw e;
				}
			}
		}
	}

	/**
	 * Returns {@code true} if we should continue the write loop on a write error.
	 */
	protected boolean continueOnWriteError() {
		return false;
	}

	protected boolean closeOnReadError(Throwable cause) {
		if (!isActive()) {
			// If the channel is not active anymore for whatever reason we should not try to continue reading.
			return true;
		}
		if (cause instanceof PortUnreachableException) {
			return false;
		}
		if (cause instanceof IOException) {
			// ServerChannel should not be closed even on IOException because it can often continue
			// accepting incoming connections. (e.g. too many open files)
			return !(this instanceof ServerChannel);
		}
		return true;
	}

	/**
	 * Read messages into the given array and return the amount which was read.
	 */
	protected abstract int doReadMessages(List<Object> buf) throws Exception;

	/**
	 * Write a message to the underlying {@link java.nio.channels.Channel}.
	 *
	 * @return {@code true} if and only if the message has been written
	 */
	protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
