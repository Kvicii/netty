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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 * 数据传输类型的socket描述符 负责数据传输 同一条TCP的socket传输链路在服务端和客户端分别有一个SocketChannel
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
	private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);
	private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

	private static SocketChannel newSocket(SelectorProvider provider) {
		try {
			/**
			 *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
			 *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
			 *
			 *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
			 */
			return provider.openSocketChannel();
		} catch (IOException e) {
			throw new ChannelException("Failed to open a socket.", e);
		}
	}

	private final SocketChannelConfig config;

	/**
	 * Create a new instance
	 */
	public NioSocketChannel() {
		this(DEFAULT_SELECTOR_PROVIDER);
	}

	/**
	 * Create a new instance using the given {@link SelectorProvider}.
	 */
	public NioSocketChannel(SelectorProvider provider) {
		this(newSocket(provider));
	}

	/**
	 * Create a new instance using the given {@link SocketChannel}.
	 */
	public NioSocketChannel(SocketChannel socket) {
		this(null, socket);
	}

	/**
	 * Create a new instance
	 *
	 * @param parent 经过netty包装过的服务端channel
	 *               {@link NioServerSocketChannel} the {@link Channel} which created this instance or {@code null} if it was created by the user
	 * @param socket JDK原生的客户端channel
	 *               {@link SocketChannel} the {@link SocketChannel} which will be used
	 */
	public NioSocketChannel(Channel parent, SocketChannel socket) {
		super(parent, socket);
		config = new NioSocketChannelConfig(this, socket.socket()); // 禁止Nagle算法
	}

	@Override
	public ServerSocketChannel parent() {
		return (ServerSocketChannel) super.parent();
	}

	@Override
	public SocketChannelConfig config() {
		return config;
	}

	@Override
	protected SocketChannel javaChannel() {
		return (SocketChannel) super.javaChannel();
	}

	@Override
	public boolean isActive() {
		SocketChannel ch = javaChannel();
		return ch.isOpen() && ch.isConnected();
	}

	@Override
	public boolean isOutputShutdown() {
		return javaChannel().socket().isOutputShutdown() || !isActive();
	}

	@Override
	public boolean isInputShutdown() {
		return javaChannel().socket().isInputShutdown() || !isActive();
	}

	@Override
	public boolean isShutdown() {
		Socket socket = javaChannel().socket();
		return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
	}

	@Override
	public InetSocketAddress localAddress() {
		return (InetSocketAddress) super.localAddress();
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return (InetSocketAddress) super.remoteAddress();
	}

	@SuppressJava6Requirement(reason = "Usage guarded by java version check")
	@UnstableApi
	@Override
	protected final void doShutdownOutput() throws Exception {
		if (PlatformDependent.javaVersion() >= 7) {
			javaChannel().shutdownOutput();
		} else {
			javaChannel().socket().shutdownOutput();
		}
	}

	@Override
	public ChannelFuture shutdownOutput() {
		return shutdownOutput(newPromise());
	}

	@Override
	public ChannelFuture shutdownOutput(final ChannelPromise promise) {
		final EventLoop loop = eventLoop();
		if (loop.inEventLoop()) {
			((AbstractUnsafe) unsafe()).shutdownOutput(promise);
		} else {
			loop.execute(new Runnable() {
				@Override
				public void run() {
					((AbstractUnsafe) unsafe()).shutdownOutput(promise);
				}
			});
		}
		return promise;
	}

	@Override
	public ChannelFuture shutdownInput() {
		return shutdownInput(newPromise());
	}

	@Override
	protected boolean isInputShutdown0() {
		return isInputShutdown();
	}

	@Override
	public ChannelFuture shutdownInput(final ChannelPromise promise) {
		EventLoop loop = eventLoop();
		if (loop.inEventLoop()) {
			shutdownInput0(promise);
		} else {
			loop.execute(new Runnable() {
				@Override
				public void run() {
					shutdownInput0(promise);
				}
			});
		}
		return promise;
	}

	@Override
	public ChannelFuture shutdown() {
		return shutdown(newPromise());
	}

	@Override
	public ChannelFuture shutdown(final ChannelPromise promise) {
		ChannelFuture shutdownOutputFuture = shutdownOutput();
		if (shutdownOutputFuture.isDone()) {
			shutdownOutputDone(shutdownOutputFuture, promise);
		} else {
			shutdownOutputFuture.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
					shutdownOutputDone(shutdownOutputFuture, promise);
				}
			});
		}
		return promise;
	}

	private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
		ChannelFuture shutdownInputFuture = shutdownInput();
		if (shutdownInputFuture.isDone()) {
			shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
		} else {
			shutdownInputFuture.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
					shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
				}
			});
		}
	}

	private static void shutdownDone(ChannelFuture shutdownOutputFuture,
									 ChannelFuture shutdownInputFuture,
									 ChannelPromise promise) {
		Throwable shutdownOutputCause = shutdownOutputFuture.cause();
		Throwable shutdownInputCause = shutdownInputFuture.cause();
		if (shutdownOutputCause != null) {
			if (shutdownInputCause != null) {
				logger.debug("Exception suppressed because a previous exception occurred.",
						shutdownInputCause);
			}
			promise.setFailure(shutdownOutputCause);
		} else if (shutdownInputCause != null) {
			promise.setFailure(shutdownInputCause);
		} else {
			promise.setSuccess();
		}
	}

	private void shutdownInput0(final ChannelPromise promise) {
		try {
			shutdownInput0();
			promise.setSuccess();
		} catch (Throwable t) {
			promise.setFailure(t);
		}
	}

	@SuppressJava6Requirement(reason = "Usage guarded by java version check")
	private void shutdownInput0() throws Exception {
		if (PlatformDependent.javaVersion() >= 7) {
			javaChannel().shutdownInput();
		} else {
			javaChannel().socket().shutdownInput();
		}
	}

	@Override
	protected SocketAddress localAddress0() {
		return javaChannel().socket().getLocalSocketAddress();
	}

	@Override
	protected SocketAddress remoteAddress0() {
		return javaChannel().socket().getRemoteSocketAddress();
	}

	@Override
	protected void doBind(SocketAddress localAddress) throws Exception {
		doBind0(localAddress);
	}

	private void doBind0(SocketAddress localAddress) throws Exception {
		if (PlatformDependent.javaVersion() >= 7) {
			SocketUtils.bind(javaChannel(), localAddress);
		} else {
			SocketUtils.bind(javaChannel().socket(), localAddress);
		}
	}

	@Override
	protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
		if (localAddress != null) {
			doBind0(localAddress);
		}

		boolean success = false;
		try {
			boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
			if (!connected) {
				selectionKey().interestOps(SelectionKey.OP_CONNECT);
			}
			success = true;
			return connected;
		} finally {
			if (!success) {
				doClose();
			}
		}
	}

	@Override
	protected void doFinishConnect() throws Exception {
		if (!javaChannel().finishConnect()) {
			throw new Error();
		}
	}

	@Override
	protected void doDisconnect() throws Exception {
		doClose();
	}

	@Override
	protected void doClose() throws Exception {
		super.doClose();
		javaChannel().close();
	}

	@Override
	protected int doReadBytes(ByteBuf byteBuf) throws Exception {
		final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
		allocHandle.attemptedBytesRead(byteBuf.writableBytes());
		return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
	}

	/**
	 * @param buf the {@link ByteBuf} from which the bytes should be written
	 *            netty中自定义的ByteBuf
	 * @return 向JDK底层写了多少字节
	 * @throws Exception
	 */
	@Override
	protected int doWriteBytes(ByteBuf buf) throws Exception {
		final int expectedWrittenBytes = buf.readableBytes();
		/**
		 * 默认会调用到 {@link io.netty.buffer.PooledDirectByteBuf#readBytes(GatheringByteChannel, int)}
		 */
		return buf.readBytes(javaChannel(), expectedWrittenBytes);
	}

	@Override
	protected long doWriteFileRegion(FileRegion region) throws Exception {
		final long position = region.transferred();
		return region.transferTo(javaChannel(), position);
	}

	private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
		// By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
		// SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
		// make a best effort to adjust as OS behavior changes.
		if (attempted == written) { // 判断尝试写入的和已经写入的大小是不是相同的 -- 一次性写完了 所以扩大一次写入的数据量
			if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
				((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
			}
		} else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {    // 一次性没有全部写入 尝试缩小写入的量
			((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
		}
	}

	@Override
	protected void doWrite(ChannelOutboundBuffer in) throws Exception {
		SocketChannel ch = javaChannel();
		int writeSpinCount = config().getWriteSpinCount();  // 有数据要写且能写入 最多尝试16次
		do {
			if (in.isEmpty()) { // 判断是否写完
				// All written so clear OP_WRITE
				clearOpWrite(); // 都写完了也就不需要写16次了
				// Directly return here so incompleteWrite(...) is not called.
				return;
			}

			// Ensure the pending writes are made of ByteBufs only.
			int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite();   // 根据要写入数据的大小动态调整
			ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite);   // 最多返回1024个数据 总的size尽量不要超过maxBytesPerGatheringWrite
			int nioBufferCnt = in.nioBufferCount();

			// Always use nioBuffers() to workaround data-corruption.
			// See https://github.com/netty/netty/issues/2761
			switch (nioBufferCnt) {
				case 0:
					// We have something else beside ByteBuffers to write so fallback to normal writes.
					writeSpinCount -= doWrite0(in);
					break;
				case 1: {
					// Only one ByteBuf so use non-gathering write
					// Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
					// to check if the total size of all the buffers is non-zero.
					ByteBuffer buffer = nioBuffers[0];
					int attemptedBytes = buffer.remaining();
					final int localWrittenBytes = ch.write(buffer); // 一次写入一个数据
					if (localWrittenBytes <= 0) {   // 进入这个逻辑说明已经无数据可写了 此时会注册一个OP_WRITE事件 等能写入的时候再通知去写
						incompleteWrite(true);
						return;
					}
					adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite);
					in.removeBytes(localWrittenBytes);  // 从channelOutboundBuffer中移除已经写出的数据
					--writeSpinCount;   // 减少写入次数
					break;
				}
				default: {
					// Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
					// to check if the total size of all the buffers is non-zero.
					// We limit the max amount to int above so cast is safe
					long attemptedBytes = in.nioBufferSize();
					final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);   // 一次写入多个数据
					if (localWrittenBytes <= 0) {
						incompleteWrite(true);
						return;
					}
					// Casting to int is safe because we limit the total amount of data in the nioBuffers to int above.
					adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
							maxBytesPerGatheringWrite);
					in.removeBytes(localWrittenBytes);
					--writeSpinCount;
					break;
				}
			}
		} while (writeSpinCount > 0);

		incompleteWrite(writeSpinCount < 0);    // 写了16次数据还没有写完 直接schedule一个新的flush task 而不是注册写事件
	}

	@Override
	protected AbstractNioUnsafe newUnsafe() {
		return new NioSocketChannelUnsafe();
	}

	private final class NioSocketChannelUnsafe extends NioByteUnsafe {
		@Override
		protected Executor prepareToClose() {
			try {
				if (javaChannel().isOpen() && config().getSoLinger() > 0) {
					// We need to cancel this key of the channel so we may not end up in a eventloop spin
					// because we try to read or write until the actual close happens which may be later due
					// SO_LINGER handling.
					// See https://github.com/netty/netty/issues/4449
					doDeregister(); // 需要逗留到数据收发完成或到了设置的soLinger时间 由于是阻塞的所以提交到另外的Executor中执行 提前deregister掉 逗留期不接受新的数据 deregister包含SelectionKey的cancel的原因之一
					return GlobalEventExecutor.INSTANCE;
				}
			} catch (Throwable ignore) {
				// Ignore the error as the underlying channel may be closed in the meantime and so
				// getSoLinger() may produce an exception. In this case we just return null.
				// See https://github.com/netty/netty/issues/4449
			}
			return null;
		}
	}

	private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
		private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;

		private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
			super(channel, javaSocket);
			calculateMaxBytesPerGatheringWrite();
		}

		@Override
		protected void autoReadCleared() {
			clearReadPending();
		}

		@Override
		public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
			super.setSendBufferSize(sendBufferSize);
			calculateMaxBytesPerGatheringWrite();
			return this;
		}

		/**
		 * 可以根据option选项开启keepalive
		 *
		 * @param option
		 * @param value
		 * @param <T>
		 * @return
		 */
		@Override
		public <T> boolean setOption(ChannelOption<T> option, T value) {
			if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
				// childOption(NioChannelOption.SO_KEEPALIVE, true) --> NIO的option设置
				return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
			}
			// childOption(ChannelOption.SO_KEEPALIVE, true) --> 普通的option设置
			return super.setOption(option, value);
		}

		@Override
		public <T> T getOption(ChannelOption<T> option) {
			if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
				return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
			}
			return super.getOption(option);
		}

		@Override
		public Map<ChannelOption<?>, Object> getOptions() {
			if (PlatformDependent.javaVersion() >= 7) {
				return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
			}
			return super.getOptions();
		}

		void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
			this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
		}

		int getMaxBytesPerGatheringWrite() {
			return maxBytesPerGatheringWrite;
		}

		private void calculateMaxBytesPerGatheringWrite() {
			// Multiply by 2 to give some extra space in case the OS can process write data faster than we can provide.
			int newSendBufferSize = getSendBufferSize() << 1;
			if (newSendBufferSize > 0) {
				setMaxBytesPerGatheringWrite(newSendBufferSize);
			}
		}

		private SocketChannel jdkChannel() {
			return ((NioSocketChannel) channel).javaChannel();
		}
	}
}
