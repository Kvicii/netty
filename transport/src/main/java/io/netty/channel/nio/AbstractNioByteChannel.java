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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
	private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
	private static final String EXPECTED_TYPES =
			" (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
					StringUtil.simpleClassName(FileRegion.class) + ')';

	private final Runnable flushTask = () -> {
		// Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
		// meantime.
		((AbstractNioUnsafe) unsafe()).flush0();
	};
	private boolean inputClosedSeenErrorOnRead;

	/**
	 * Create a new instance
	 *
	 * @param parent the parent {@link Channel} by which this instance was created. May be {@code null}
	 * @param ch     the underlying {@link SelectableChannel} on which it operates
	 */
	protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
		super(parent, ch, SelectionKey.OP_READ);    // 为客户端channel传入OP_READ事件 后续在将NioSocketChannel绑定到Selector上时 如果Selector轮询到了事件读写 就会进行通知
	}

	/**
	 * Shutdown the input side of the channel.
	 */
	protected abstract ChannelFuture shutdownInput();

	protected boolean isInputShutdown0() {
		return false;
	}

	/**
	 * 创建客户端unsafe
	 *
	 * @return
	 */
	@Override
	protected AbstractNioUnsafe newUnsafe() {
		return new NioByteUnsafe();
	}

	@Override
	public ChannelMetadata metadata() {
		return METADATA;
	}

	final boolean shouldBreakReadReady(ChannelConfig config) {
		return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
	}

	private static boolean isAllowHalfClosure(ChannelConfig config) {
		return config instanceof SocketChannelConfig &&
				((SocketChannelConfig) config).isAllowHalfClosure();
	}

	/**
	 * Unsafe负责读写抽象
	 * 客户端channel的读 {@link NioByteUnsafe} 指的是读取IO数据
	 */
	protected class NioByteUnsafe extends AbstractNioUnsafe {

		private void closeOnRead(ChannelPipeline pipeline) {
			if (!isInputShutdown0()) {  // input是否关闭 没有关闭执行该逻辑
				if (isAllowHalfClosure(config())) { // 是否是半关闭状态 是则关闭读 触发事件
					shutdownInput();
					pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
				} else {
					close(voidPromise());
				}
			} else {
				inputClosedSeenErrorOnRead = true;
				pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
			}
		}

		private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
										 RecvByteBufAllocator.Handle allocHandle) {
			if (byteBuf != null) {
				if (byteBuf.isReadable()) {
					readPending = false;
					pipeline.fireChannelRead(byteBuf);
				} else {
					byteBuf.release();
				}
			}
			allocHandle.readComplete();
			pipeline.fireChannelReadComplete();
			pipeline.fireExceptionCaught(cause);

			// If oom will close the read event, release connection.
			// See https://github.com/netty/netty/issues/10434
			if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
				closeOnRead(pipeline);
			}
		}

		@Override
		public final void read() {
			final ChannelConfig config = config();
			if (shouldBreakReadReady(config)) {
				clearReadPending();
				return;
			}
			final ChannelPipeline pipeline = pipeline();
			final ByteBufAllocator allocator = config.getAllocator();   // ByteBuf分配器
			// RecvByteBufAllocator: 分配ByteBuf缓冲区的组件
			// 动态的根据上一次请求获取到的数据大小 预估这次请求的数据大小大致有多少(处理服务端接入的速率) 根据预估的结果创建符合预期的缓冲区
			// Kafka在请求头中包含了本次请求的数据大小 所以不需要进行预估 直接根据请求头中的值分配ByteBuf
			// 而netty并不能在请求头中指定 由于每次请求并不知道数据的大小 只能根据每次请求的大小动态预估下一次请求的大小 根据动态预估的大小创建ByteBuf
			final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
			allocHandle.reset(config);

			ByteBuf byteBuf = null;
			boolean close = false;
			try {
				do {
					byteBuf = allocHandle.allocate(allocator);  // 猜测分配ByteBuf的大小
					// doReadBytes返回读取到的字节数
					allocHandle.lastBytesRead(doReadBytes(byteBuf));    // 客户端的读是读取IO数据/正常关闭
					if (allocHandle.lastBytesRead() <= 0) { // 读取到的字节数 <= 0
						// nothing was read. release the buffer.
						byteBuf.release();  // 数据清理
						byteBuf = null;
						close = allocHandle.lastBytesRead() < 0;
						if (close) {
							// There is nothing left to read as we received an EOF.
							readPending = false;
						}
						break;
					}

					allocHandle.incMessagesRead(1); // 记录读的次数
					readPending = false;
					pipeline.fireChannelRead(byteBuf);  // 将读到的数据传播出去(业务逻辑处理入口) 同一个OP_READ事件如果数据块很大最多可以读16次--但依然是同一个OP_READ事件
					byteBuf = null;
				} while (allocHandle.continueReading());

				allocHandle.readComplete(); // 记录本次OP_READ事件一共读取了多少数据 计算下一次要分配的大小
				pipeline.fireChannelReadComplete(); // 相当于完成本次OP_READ事件的处理--此时说明这个OP_READ事件已经完成(服务端将响应结果发回)

				if (close) {
					closeOnRead(pipeline);  // 执行关闭
				}
			} catch (Throwable t) {
				handleReadException(pipeline, byteBuf, t, close, allocHandle);  // 异常关闭时的处理
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

	/**
	 * Write objects to the OS.
	 *
	 * @param in the collection which contains objects to write.
	 * @return The value that should be decremented from the write quantum which starts at
	 * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
	 * <ul>
	 *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
	 *     is encountered</li>
	 *     <li>1 - if a single call to write data was made to the OS</li>
	 *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
	 *     data was accepted</li>
	 * </ul>
	 * @throws Exception if an I/O exception occurs during write.
	 */
	protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
		Object msg = in.current();
		if (msg == null) {
			// Directly return here so incompleteWrite(...) is not called.
			return 0;
		}
		return doWriteInternal(in, in.current());
	}

	private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
		if (msg instanceof ByteBuf) {
			ByteBuf buf = (ByteBuf) msg;
			if (!buf.isReadable()) {	// 如果ByteBuf没有可写数据 将该指针指向的ByteBuf节点移除
				in.remove();
				return 0;
			}
			// 将ByteBuf中的数据写入Socket 返回向JDK底层写入的字节数
			final int localFlushedAmount = doWriteBytes(buf);
			if (localFlushedAmount > 0) {	// 如果发生了写入
				in.progress(localFlushedAmount);
				/**
				 * 有可能不会把数据一次性全部写入到Socket中
				 * 该if判断有可能为false 会通过外层的 {@link AbstractNioByteChannel#doWrite(ChannelOutboundBuffer)}  } 调用自旋不断的尝试
				 * 提高吞吐量
				 */
				if (!buf.isReadable()) {	// 如果ByteBuf中的数据已经全部写入到JDK底层了
					// 移除该ByteBuf节点
					in.remove();
				}
				return 1;
			}
		} else if (msg instanceof FileRegion) {	// 文件系统写入
			FileRegion region = (FileRegion) msg;
			if (region.transferred() >= region.count()) {
				in.remove();
				return 0;
			}

			long localFlushedAmount = doWriteFileRegion(region);
			if (localFlushedAmount > 0) {
				in.progress(localFlushedAmount);
				if (region.transferred() >= region.count()) {
					in.remove();
				}
				return 1;
			}
		} else {
			// Should not reach here.
			throw new Error();
		}
		return WRITE_STATUS_SNDBUF_FULL;
	}

	@Override
	protected void doWrite(ChannelOutboundBuffer in) throws Exception {
		int writeSpinCount = config().getWriteSpinCount();
		do {
			// 获取flushedEntry指针指向的ByteBuf数据
			Object msg = in.current();
			if (msg == null) {
				// Wrote all messages.
				clearOpWrite();
				// Directly return here so incompleteWrite(...) is not called.
				return;
			}
			// // 调用JDK底层API进行自旋写
			writeSpinCount -= doWriteInternal(in, msg);
		} while (writeSpinCount > 0);

		incompleteWrite(writeSpinCount < 0);
	}

	@Override
	protected final Object filterOutboundMessage(Object msg) {
		if (msg instanceof ByteBuf) {	// 如果msg是一个ByteBuf
			ByteBuf buf = (ByteBuf) msg;
			if (buf.isDirect()) {	// 并且该msg是堆外内存 直接返回
				return msg;
			}
			// 否则将堆内存转为堆外内存
			return newDirectBuffer(buf);
		}

		if (msg instanceof FileRegion) {
			return msg;
		}

		throw new UnsupportedOperationException(
				"unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
	}

	protected final void incompleteWrite(boolean setOpWrite) {
		// Did not write completely.
		if (setOpWrite) {
			setOpWrite();
		} else {
			// It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
			// use our write quantum. In this case we no longer want to set the write OP because the socket is still
			// writable (as far as we know). We will find out next time we attempt to write if the socket is writable
			// and set the write OP if necessary.
			clearOpWrite();

			// Schedule flush again later so other tasks can be picked up in the meantime
			eventLoop().execute(flushTask);
		}
	}

	/**
	 * Write a {@link FileRegion}
	 *
	 * @param region the {@link FileRegion} from which the bytes should be written
	 * @return amount       the amount of written bytes
	 */
	protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

	/**
	 * Read bytes into the given {@link ByteBuf} and return the amount.
	 */
	protected abstract int doReadBytes(ByteBuf buf) throws Exception;

	/**
	 * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
	 *
	 * @param buf the {@link ByteBuf} from which the bytes should be written
	 * @return amount       the amount of written bytes
	 */
	protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

	protected final void setOpWrite() {
		final SelectionKey key = selectionKey();
		// Check first if the key is still valid as it may be canceled as part of the deregistration
		// from the EventLoop
		// See https://github.com/netty/netty/issues/2104
		if (!key.isValid()) {
			return;
		}
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_WRITE) == 0) {
			key.interestOps(interestOps | SelectionKey.OP_WRITE);
		}
	}

	protected final void clearOpWrite() {
		final SelectionKey key = selectionKey();
		// Check first if the key is still valid as it may be canceled as part of the deregistration
		// from the EventLoop
		// See https://github.com/netty/netty/issues/2104
		if (!key.isValid()) {
			return;
		}
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_WRITE) != 0) {
			key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
		}
	}
}
