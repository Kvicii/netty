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
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

	private final EventExecutor[] children;
	private final Set<EventExecutor> readonlyChildren;
	private final AtomicInteger terminatedChildren = new AtomicInteger();
	private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
	private final EventExecutorChooserFactory.EventExecutorChooser chooser;

	/**
	 * Create a new instance.
	 *
	 * @param nThreads      the number of threads that will be used by this instance.
	 * @param threadFactory the ThreadFactory to use, or {@code null} if the default should be used.
	 * @param args          arguments which will passed to each {@link #newChild(Executor, Object...)} call
	 */
	protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
		this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
	}

	/**
	 * Create a new instance.
	 *
	 * @param nThreads the number of threads that will be used by this instance.
	 * @param executor the Executor to use, or {@code null} if the default should be used.
	 * @param args     arguments which will passed to each {@link #newChild(Executor, Object...)} call
	 */
	protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
		/**
		 * {@link DefaultEventExecutorChooserFactory} 负责创建线程选择器
		 */
		this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
	}

	/**
	 * Create a new instance.
	 *
	 * @param nThreads       the number of threads that will be used by this instance.
	 * @param executor       the Executor to use, or {@code null} if the default should be used.
	 * @param chooserFactory the {@link EventExecutorChooserFactory} to use.
	 * @param args           arguments which will passed to each {@link #newChild(Executor, Object...)} call
	 */
	protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
											EventExecutorChooserFactory chooserFactory, Object... args) {
		if (nThreads <= 0) {
			throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
		}

		if (executor == null) { // 1.创建线程执行器(即线程池)
			/**
			 * 线程执行器(ThreadPerTaskExecutor)每次执行任务时都会创建一个线程实体
			 * 传入的是ThreadFactory
			 * 线程工厂产生的线程最终的线程名称形如: nioEventLoopGroup-1-1    表示这是NioEventLoopGroup第1个线程池下的第1个NioEventLoop线程
			 */
			executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
		}

		children = new EventExecutor[nThreads];

		for (int i = 0; i < nThreads; i++) {    // 2.循环创建NioEventLoop
			boolean success = false;
			try {
				children[i] = newChild(executor, args); // NioEventLoop构造方法内部令Selector与NioEventLoop做唯一的绑定
				success = true;
			} catch (Exception e) {
				// TODO: Think about if this is a good exception type
				throw new IllegalStateException("failed to create a child event loop", e);
			} finally {
				if (!success) {
					for (int j = 0; j < i; j++) {
						children[j].shutdownGracefully();
					}

					for (int j = 0; j < i; j++) {
						EventExecutor e = children[j];
						try {
							while (!e.isTerminated()) {
								e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
							}
						} catch (InterruptedException interrupted) {
							// Let the caller handle the interruption.
							Thread.currentThread().interrupt();
							break;
						}
					}
				}
			}
		}

		chooser = chooserFactory.newChooser(children);  // 3.创建线程选择器 目的是给新连接绑定NioEventLoop
		final FutureListener<Object> terminationListener = future -> {
			if (terminatedChildren.incrementAndGet() == children.length) {
				terminationFuture.setSuccess(null);
			}
		};

		for (EventExecutor e : children) {
			e.terminationFuture().addListener(terminationListener);
		}

		Set<EventExecutor> childrenSet = new LinkedHashSet<>(children.length);
		Collections.addAll(childrenSet, children);
		readonlyChildren = Collections.unmodifiableSet(childrenSet);
	}

	protected ThreadFactory newDefaultThreadFactory() {
		// getClass可以获取到类名首字母小写 如NioEventLoopGroup -> nioEventLoopGroup
		return new DefaultThreadFactory(getClass());
	}

	@Override
	public EventExecutor next() {
		return chooser.next();
	}

	@Override
	public Iterator<EventExecutor> iterator() {
		return readonlyChildren.iterator();
	}

	/**
	 * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
	 * 1:1 to the threads it use.
	 */
	public final int executorCount() {
		return children.length;
	}

	/**
	 * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
	 * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
	 */
	protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

	/**
	 * @param quietPeriod 静默期 the quiet period as described in the documentation
	 * @param timeout     最大优美关闭时间 the maximum amount of time to wait until the executor is {@linkplain #shutdown()}
	 *                    regardless if a task was submitted during the quiet period
	 * @param unit        the unit of {@code quietPeriod} and {@code timeout}
	 * @return
	 */
	@Override
	public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
		for (EventExecutor l : children) {  // 循坏关闭多个NioEventLoop
			l.shutdownGracefully(quietPeriod, timeout, unit);
		}
		return terminationFuture();
	}

	@Override
	public Future<?> terminationFuture() {
		return terminationFuture;
	}

	@Override
	@Deprecated
	public void shutdown() {
		for (EventExecutor l : children) {
			l.shutdown();
		}
	}

	@Override
	public boolean isShuttingDown() {
		for (EventExecutor l : children) {
			if (!l.isShuttingDown()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean isShutdown() {
		for (EventExecutor l : children) {
			if (!l.isShutdown()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean isTerminated() {
		for (EventExecutor l : children) {
			if (!l.isTerminated()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit)
			throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		loop:
		for (EventExecutor l : children) {
			for (; ; ) {
				long timeLeft = deadline - System.nanoTime();
				if (timeLeft <= 0) {
					break loop;
				}
				if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
					break;
				}
			}
		}
		return isTerminated();
	}
}
