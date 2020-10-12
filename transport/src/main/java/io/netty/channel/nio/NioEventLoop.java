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

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 */
public final class NioEventLoop extends SingleThreadEventLoop {

	private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

	private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

	private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
			SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

	private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
	private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

	private final IntSupplier selectNowSupplier = () -> selectNow();

	// Workaround for JDK NIO bug.
	//
	// See:
	// - http://bugs.sun.com/view_bug.do?bug_id=6427854
	// - https://github.com/netty/netty/issues/203
	static {
		final String key = "sun.nio.ch.bugLevel";
		final String bugLevel = SystemPropertyUtil.get(key);
		if (bugLevel == null) {
			try {
				AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
					System.setProperty(key, "");
					return null;
				});
			} catch (final SecurityException e) {
				logger.debug("Unable to get/set System Property: " + key, e);
			}
		}

		int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
		if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
			selectorAutoRebuildThreshold = 0;
		}

		SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

		if (logger.isDebugEnabled()) {
			logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
			logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
		}
	}

	/**
	 * The NIO {@link Selector}.
	 */
	private Selector selector;
	private Selector unwrappedSelector;
	private SelectedSelectionKeySet selectedKeys;

	private final SelectorProvider provider;

	private static final long AWAKE = -1L;
	private static final long NONE = Long.MAX_VALUE;

	// nextWakeupNanos is:
	//    AWAKE            when EL is awake
	//    NONE             when EL is waiting with no wakeup scheduled
	//    other value T    when EL is waiting with wakeup scheduled at time T
	private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

	private final SelectStrategy selectStrategy;

	private volatile int ioRatio = 50;
	private int cancelledKeys;
	private boolean needsToSelectAgain;

	NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
				 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
				 EventLoopTaskQueueFactory queueFactory) {
		// 创建Mpsc的TaskQueue
		super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
				rejectedExecutionHandler);  // 创建并且初始化了NioEventLoopGroup
		this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
		this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
		final SelectorTuple selectorTuple = openSelector(); // 创建Selector 使得 一个NioEventLoop与一个Selector做唯一的绑定
		this.selector = selectorTuple.selector;
		this.unwrappedSelector = selectorTuple.unwrappedSelector;
	}

	private static Queue<Runnable> newTaskQueue(
			EventLoopTaskQueueFactory queueFactory) {
		if (queueFactory == null) {
			return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
		}
		return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
	}

	private static final class SelectorTuple {
		final Selector unwrappedSelector;
		final Selector selector;

		SelectorTuple(Selector unwrappedSelector) {
			this.unwrappedSelector = unwrappedSelector;
			this.selector = unwrappedSelector;
		}

		SelectorTuple(Selector unwrappedSelector, Selector selector) {
			this.unwrappedSelector = unwrappedSelector;
			this.selector = selector;
		}
	}

	private SelectorTuple openSelector() {
		final Selector unwrappedSelector;
		try {
			unwrappedSelector = provider.openSelector();    // 先创建一个Selector
		} catch (IOException e) {
			throw new ChannelException("failed to open a new selector", e);
		}

		if (DISABLE_KEY_SET_OPTIMIZATION) { // 判断是否需要优化(默认false 代表需要优化) 不需要优化直接返回
			return new SelectorTuple(unwrappedSelector);
		}

		Object maybeSelectorImplClass = AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
			try {
				return Class.forName(
						"sun.nio.ch.SelectorImpl",
						false,
						PlatformDependent.getSystemClassLoader());
			} catch (Throwable cause) {
				return cause;
			}
		});

		// 判断是否真的获取到了SelectorImpl类 && SelectorImpl是否是Selector的实现
		if (!(maybeSelectorImplClass instanceof Class) ||
				// ensure the current selector implementation is what we can instrument.
				!((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
			if (maybeSelectorImplClass instanceof Throwable) {
				Throwable t = (Throwable) maybeSelectorImplClass;
				logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
			}
			return new SelectorTuple(unwrappedSelector);    // 如果不满足实现关系 直接返回Selector
		}

		final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
		/**
		 * 使用SelectedSelectionKeySet(数组)替换 Selector中的Set<SelectionKey>结构
		 * Set最差时间复杂度会达到O(n) 而数组操作时间复杂度为O(1)
		 */
		final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

		Object maybeException = AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
			try {
				// 获取SelectorImpl中最重要的两个属性: selectedKeys 和 publicSelectedKeys(publicSelectedKeys用于jdk把已经准备好的事件塞到这个set)
				Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
				Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

				if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
					// Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
					// This allows us to also do this in Java9+ without any extra flags.
					long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
					long publicSelectedKeysFieldOffset =
							PlatformDependent.objectFieldOffset(publicSelectedKeysField);
					// 通过反射将优化后的数据结构设置到NioEventLoop的Selector属性中
					if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) { // 优化的逻辑
						PlatformDependent.putObject(
								unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
						PlatformDependent.putObject(
								unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
						return null;
					}
					// We could not retrieve the offset, lets try reflection as last-resort.
				}

				Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
				if (cause != null) {
					return cause;
				}
				cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
				if (cause != null) {
					return cause;
				}

				selectedKeysField.set(unwrappedSelector, selectedKeySet);
				publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
				return null;
			} catch (NoSuchFieldException e) {
				return e;
			} catch (IllegalAccessException e) {
				return e;
			}
		});

		if (maybeException instanceof Exception) {
			selectedKeys = null;
			Exception e = (Exception) maybeException;
			logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
			return new SelectorTuple(unwrappedSelector);
		}
		selectedKeys = selectedKeySet;  // 将SelectedKeySet保存到成员变量中 之后每次Selector操作结束之后如果有IO事件都会把对应的SelectionKey塞到该集合 直接对该集合处理
		logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
		return new SelectorTuple(unwrappedSelector,
				new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
	}

	/**
	 * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
	 */
	public SelectorProvider selectorProvider() {
		return provider;
	}

	@Override
	protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
		return newTaskQueue0(maxPendingTasks);
	}

	private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
		// This event loop never calls takeTask()
		// Multi Producers -> Single Consumer 多个外部线程将任务扔进来由一个线程去消费
		return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.newMpscQueue()
				: PlatformDependent.newMpscQueue(maxPendingTasks);
	}

	/**
	 * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
	 * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
	 * be executed by this event loop when the {@link SelectableChannel} is ready.
	 */
	public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
		ObjectUtil.checkNotNull(ch, "ch");
		if (interestOps == 0) {
			throw new IllegalArgumentException("interestOps must be non-zero.");
		}
		if ((interestOps & ~ch.validOps()) != 0) {
			throw new IllegalArgumentException(
					"invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
		}
		ObjectUtil.checkNotNull(task, "task");

		if (isShutdown()) {
			throw new IllegalStateException("event loop shut down");
		}

		if (inEventLoop()) {
			register0(ch, interestOps, task);
		} else {
			try {
				// Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
				// may block for a long time while trying to obtain an internal lock that may be hold while selecting.
				submit(() -> register0(ch, interestOps, task)).sync();
			} catch (InterruptedException ignore) {
				// Even if interrupted we did schedule it so just mark the Thread as interrupted.
				Thread.currentThread().interrupt();
			}
		}
	}

	private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
		try {
			ch.register(unwrappedSelector, interestOps, task);
		} catch (Exception e) {
			throw new EventLoopException("failed to register a channel", e);
		}
	}

	/**
	 * Returns the percentage of the desired amount of time spent for I/O in the event loop.
	 */
	public int getIoRatio() {
		return ioRatio;
	}

	/**
	 * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
	 * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
	 * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
	 * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
	 */
	public void setIoRatio(int ioRatio) {
		if (ioRatio <= 0 || ioRatio > 100) {
			throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
		}
		this.ioRatio = ioRatio;
	}

	/**
	 * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
	 * around the infamous epoll 100% CPU bug.
	 * <p>
	 * 把老的Selector上面的所有SelectionKey 注册到新的Selector上
	 * 这样在新的Selector上面的阻塞式Select操作就有可能不会发生空轮询bug
	 * 相当于把产生bug的Selector丢弃掉 替换新的Selector
	 */
	public void rebuildSelector() {
		if (!inEventLoop()) {
			execute(() -> rebuildSelector0());
			return;
		}
		rebuildSelector0();
	}

	@Override
	public int registeredChannels() {
		return selector.keys().size() - cancelledKeys;
	}

	private void rebuildSelector0() {
		final Selector oldSelector = selector;
		final SelectorTuple newSelectorTuple;

		if (oldSelector == null) {
			return;
		}

		try {
			newSelectorTuple = openSelector();  // 重新创建一个Selector
		} catch (Exception e) {
			logger.warn("Failed to create a new Selector.", e);
			return;
		}

		// Register all channels to the new Selector.
		int nChannels = 0;
		for (SelectionKey key : oldSelector.keys()) {   // 获取旧Selector上所有的SelectionKey
			/**
			 * 经过netty包装的channel
			 * 见:
			 * {@link AbstractNioChannel#doRegister()} 将attachment注册到Selector
			 */
			Object a = key.attachment();
			try {
				if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
					continue;
				}

				int interestOps = key.interestOps();    // SelectionKey所关注的事件
				key.cancel();   // 取消之前的SelectionKey的事件
				SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);   // 将事件重新注册到新的Selector上去
				if (a instanceof AbstractNioChannel) {
					// Update SelectionKey
					((AbstractNioChannel) a).selectionKey = newKey; // 重新给AbstractNioChannel子类(如NioServerSocketChannel)的SelectionKey赋值
				}
				nChannels++;
			} catch (Exception e) {
				logger.warn("Failed to re-register a Channel to the new Selector.", e);
				if (a instanceof AbstractNioChannel) {
					AbstractNioChannel ch = (AbstractNioChannel) a;
					ch.unsafe().close(ch.unsafe().voidPromise());
				} else {
					@SuppressWarnings("unchecked")
					NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
					invokeChannelUnregistered(task, key, e);
				}
			}
		}

		selector = newSelectorTuple.selector;
		unwrappedSelector = newSelectorTuple.unwrappedSelector;

		try {
			// time to close the old selector as everything else is registered to the new one
			oldSelector.close();
		} catch (Throwable t) {
			if (logger.isWarnEnabled()) {
				logger.warn("Failed to close the old Selector.", t);
			}
		}

		if (logger.isInfoEnabled()) {
			logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
		}
	}

	/**
	 * NioEventLoop由两个线程构成:
	 * 1.接受客户端的连接
	 * 2.处理读写请求
	 * <p>
	 * 一个NioEventLoop对应着一个select
	 * <p>
	 * 监听/处理事件
	 */
	@Override
	protected void run() {
		int selectCnt = 0;  // 空轮询次数 用于统计
		for (; ; ) {
			try {
				int strategy;
				try {
					strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
					switch (strategy) {
						case SelectStrategy.CONTINUE:
							continue;

						case SelectStrategy.BUSY_WAIT:
							// fall-through to SELECT since the busy-wait is not supported with NIO

						case SelectStrategy.SELECT:
							long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
							if (curDeadlineNanos == -1L) {
								curDeadlineNanos = NONE; // nothing on the calendar
							}
							nextWakeupNanos.set(curDeadlineNanos);
							try {
								if (!hasTasks()) {  // 当前异步队列里是否有任务
									strategy = select(curDeadlineNanos);    // 1.轮询IO事件
								}
							} finally {
								// This update is just to help block unnecessary selector wakeups
								// so use of lazySet is ok (no race condition)
								nextWakeupNanos.lazySet(AWAKE);
							}
							// fall through
						default:
					}
				} catch (IOException e) {
					// If we receive an IOException here its because the Selector is messed up. Let's rebuild
					// the selector and retry. https://github.com/netty/netty/issues/8566
					rebuildSelector0();
					selectCnt = 0;
					handleLoopException(e);
					continue;
				}

				selectCnt++;
				cancelledKeys = 0;
				needsToSelectAgain = false;
				final int ioRatio = this.ioRatio;   // 如未设置默认为50 即处理IO事件和处理外部事件的比例是1:1
				boolean ranTasks;
				if (ioRatio == 100) {
					try {
						if (strategy > 0) {
							processSelectedKeys();  // 2.处理IO相关的逻辑(包括新连接的接入)
						}
					} finally {
						/**
						 * Ensure we always run tasks.
						 * 3.处理外部线程扔到TaskQueue(MpscQueue)中的任务
						 * MpScQueue 为每个NioEventLoop绑定Selector时创建的:
						 * {@link NioEventLoopGroup#newChild(java.util.concurrent.Executor, java.lang.Object...)}
						 * {@link SingleThreadEventLoop#SingleThreadEventExecutor(EventExecutorGroup, Executor, boolean, Queue, RejectedExecutionHandler)}
						 */
						ranTasks = runAllTasks();
					}
				} else if (strategy > 0) {  // ioRatio == 50
					final long ioStartTime = System.nanoTime(); // 记录开始时间
					try {
						processSelectedKeys();
					} finally {
						// Ensure we always run tasks.
						final long ioTime = System.nanoTime() - ioStartTime;    // 计算执行时间
						ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio); // 由于ioRatio == 50 -> 等价于 ranTasks = runAllTasks(ioTime)
					}
				} else {
					ranTasks = runAllTasks(0); // This will run the minimum number of tasks
				}

				if (ranTasks || strategy > 0) {
					if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
						logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
								selectCnt - 1, selector);
					}
					selectCnt = 0;
				} else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case).  // 处理异常情况 如JDK空轮询
					selectCnt = 0;
				}
			} catch (CancelledKeyException e) {
				// Harmless exception - log anyway
				if (logger.isDebugEnabled()) {
					logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
							selector, e);
				}
			} catch (Throwable t) {
				handleLoopException(t);
			}
			// Always handle shutdown even if the loop processing threw an exception.
			try {
				if (isShuttingDown()) {
					closeAll();
					if (confirmShutdown()) {    // 优美关闭的关键
						return;
					}
				}
			} catch (Throwable t) {
				handleLoopException(t);
			}
		}
	}

	// returns true if selectCnt should be reset
	private boolean unexpectedSelectorWakeup(int selectCnt) {
		if (Thread.interrupted()) {
			// Thread was interrupted so reset selected keys and break so we not run into a busy loop.
			// As this is most likely a bug in the handler of the user or it's client library we will
			// also log it.
			//
			// See https://github.com/netty/netty/issues/2426
			if (logger.isDebugEnabled()) {
				logger.debug("Selector.select() returned prematurely because " +
						"Thread.currentThread().interrupt() was called. Use " +
						"NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
			}
			return true;
		}
		if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
				selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) { // select空轮询次数 >= 512
			// The selector returned prematurely many times in a row.
			// Rebuild the selector to work around the problem.
			logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
					selectCnt, selector);
			rebuildSelector();  // 避免下一次空轮询继续发生
			return true;
		}
		return false;
	}

	private static void handleLoopException(Throwable t) {
		logger.warn("Unexpected exception in the selector loop.", t);

		// Prevent possible consecutive immediate failures that lead to
		// excessive CPU consumption.
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// Ignore.
		}
	}

	private void processSelectedKeys() {
		if (selectedKeys != null) { // SelectedKeys成员变量就是经过优化之后的结构
			// 不适用JDK的selector.selectedKeys 性能优于1%~2% 垃圾回收更少
			processSelectedKeysOptimized();
		} else {
			processSelectedKeysPlain(selector.selectedKeys());
		}
	}

	@Override
	protected void cleanup() {
		try {
			selector.close();
		} catch (IOException e) {
			logger.warn("Failed to close a selector.", e);
		}
	}

	void cancel(SelectionKey key) {
		key.cancel();   // 无特殊情况(没有配置solinger) 这个cancel实际不会执行 因为在关闭channel的时候已经cancel过了
		cancelledKeys++;
		if (cancelledKeys >= CLEANUP_INTERVAL) {    // 当处理一批事件时 如果发现很多连接都断了(256个) 此时后面的事件可能都失效了 所有select again一下
			cancelledKeys = 0;
			needsToSelectAgain = true;
		}
	}

	private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
		// check if the set is empty and if so just return to not create garbage by
		// creating a new Iterator every time even if there is nothing to process.
		// See https://github.com/netty/netty/issues/597
		if (selectedKeys.isEmpty()) {
			return;
		}

		Iterator<SelectionKey> i = selectedKeys.iterator();
		for (; ; ) {
			final SelectionKey k = i.next();
			final Object a = k.attachment();
			i.remove();

			if (a instanceof AbstractNioChannel) {
				processSelectedKey(k, (AbstractNioChannel) a);
			} else {
				@SuppressWarnings("unchecked")
				NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
				processSelectedKey(k, task);
			}

			if (!i.hasNext()) {
				break;
			}

			if (needsToSelectAgain) {
				selectAgain();
				selectedKeys = selector.selectedKeys();

				// Create the iterator again to avoid ConcurrentModificationException
				if (selectedKeys.isEmpty()) {
					break;
				} else {
					i = selectedKeys.iterator();
				}
			}
		}
	}

	/**
	 * 轮询事件
	 */
	private void processSelectedKeysOptimized() {
		for (int i = 0; i < selectedKeys.size; ++i) {   // 遍历数组
			final SelectionKey k = selectedKeys.keys[i];    // 获取SelectionKey
			// null out entry in the array to allow to have it GC'ed once the Channel close
			// See https://github.com/netty/netty/issues/2363
			selectedKeys.keys[i] = null;    // 数组中的引用设置为null

			/**
			 * {@link AbstractNioChannel#doRegister()} 将AbstractNioChannel 作为attachment注册到Selector
			 */
			final Object a = k.attachment();

			if (a instanceof AbstractNioChannel) {
				processSelectedKey(k, (AbstractNioChannel) a);
			} else {
				@SuppressWarnings("unchecked")
				NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
				processSelectedKey(k, task);
			}

			if (needsToSelectAgain) {
				// null out entries in the array to allow to have it GC'ed once the Channel close
				// See https://github.com/netty/netty/issues/2363
				selectedKeys.reset(i + 1);

				selectAgain();
				i = -1;
			}
		}
	}

	private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
		final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();    // 获取Channel对应的Unsafe
		if (!k.isValid()) { // SelectionKey不是合法的
			final EventLoop eventLoop;
			try {
				eventLoop = ch.eventLoop();
			} catch (Throwable ignored) {
				// If the channel implementation throws an exception because there is no event loop, we ignore this
				// because we are only trying to determine if ch is registered to this event loop and thus has authority
				// to close ch.
				return;
			}
			// Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
			// and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
			// still healthy and should not be closed.
			// See https://github.com/netty/netty/issues/5125
			if (eventLoop == this) {
				// close the channel if the key is not valid anymore
				unsafe.close(unsafe.voidPromise()); // 调用close方法 -> 最终会调用到pipeline 进行关闭
			}
			return;
		}

		try {   // 如果是合法的SelectionKey
			int readyOps = k.readyOps();    // 获取SelectionKey的IO事件
			// We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
			// the NIO JDK channel implementation may throw a NotYetConnectedException.
			if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
				// remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
				// See https://github.com/netty/netty/issues/924
				int ops = k.interestOps();
				ops &= ~SelectionKey.OP_CONNECT;
				k.interestOps(ops);

				unsafe.finishConnect();
			}

			// Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
			if ((readyOps & SelectionKey.OP_WRITE) != 0) {
				// Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
				ch.unsafe().forceFlush();   // 注册一个OP_WRITE的执行实际上就是进行flush操作
			}

			/**
			 * Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
			 * to a spin loop
			 *
			 * 如果是 workerGroup 轮询出来的事件可能是OP_READ
			 * 如果是 bossGroup 轮询出来的事件可能是OP_ACCEPT
			 */
			if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {   // 处理读事件/连接断开/连接接入事件--对于OP_ACCEPT和OP_READ都是一套逻辑只不过实现不同
				unsafe.read();  // SocketChannel会执行AbstractNioMessageChannel(OP_READ) ServerSocketChannel会执行AbstractNioByteChannel(OP_ACCEPT)
			}
		} catch (CancelledKeyException ignored) {
			unsafe.close(unsafe.voidPromise());
		}
	}

	private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
		int state = 0;
		try {
			task.channelReady(k.channel(), k);
			state = 1;
		} catch (Exception e) {
			k.cancel();
			invokeChannelUnregistered(task, k, e);
			state = 2;
		} finally {
			switch (state) {
				case 0:
					k.cancel();
					invokeChannelUnregistered(task, k, null);
					break;
				case 1:
					if (!k.isValid()) { // Cancelled by channelReady()
						invokeChannelUnregistered(task, k, null);
					}
					break;
			}
		}
	}

	private void closeAll() {
		selectAgain();  // 去除canceled的SelectionKey
		Set<SelectionKey> keys = selector.keys();
		Collection<AbstractNioChannel> channels = new ArrayList<>(keys.size());
		for (SelectionKey k : keys) {
			Object a = k.attachment();
			if (a instanceof AbstractNioChannel) {
				channels.add((AbstractNioChannel) a);
			} else {
				k.cancel();
				@SuppressWarnings("unchecked")
				NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
				invokeChannelUnregistered(task, k, null);
			}
		}

		for (AbstractNioChannel ch : channels) {
			ch.unsafe().close(ch.unsafe().voidPromise());   // 循环关闭Channel
		}
	}

	private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
		try {
			task.channelUnregistered(k.channel(), cause);
		} catch (Exception e) {
			logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
		}
	}

	@Override
	protected void wakeup(boolean inEventLoop) {
		if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
			selector.wakeup();
		}
	}

	@Override
	protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
		// Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
		return deadlineNanos < nextWakeupNanos.get();
	}

	@Override
	protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
		// Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
		return deadlineNanos < nextWakeupNanos.get();
	}

	Selector unwrappedSelector() {
		return unwrappedSelector;
	}

	int selectNow() throws IOException {
		return selector.selectNow();
	}

	private int select(long deadlineNanos) throws IOException {
		if (deadlineNanos == NONE) {
			return selector.select();
		}
		// Timeout will only be 0 if deadline is within 5 microsecs
		// NioEventLoop底层使用了一个定时任务队列 是按照任务截止的时间升序排序的一个队列
		long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
		// 如果超时 执行非阻塞的selectNow方法 否则执行阻塞式的select 阻塞到timeoutMillis
		return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
	}

	private void selectAgain() {
		needsToSelectAgain = false;
		try {
			selector.selectNow();
		} catch (Throwable t) {
			logger.warn("Failed to update SelectionKeys.", t);
		}
	}
}
