/*
 * Copyright 2013 The Netty Project
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

package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ThreadFactory} implementation with a simple naming rule.
 */
public class DefaultThreadFactory implements ThreadFactory {

	private static final AtomicInteger poolId = new AtomicInteger();

	private final AtomicInteger nextId = new AtomicInteger();
	private final String prefix;
	private final boolean daemon;
	private final int priority;
	protected final ThreadGroup threadGroup;

	public DefaultThreadFactory(Class<?> poolType) {
		this(poolType, false, Thread.NORM_PRIORITY);
	}

	public DefaultThreadFactory(String poolName) {
		this(poolName, false, Thread.NORM_PRIORITY);
	}

	public DefaultThreadFactory(Class<?> poolType, boolean daemon) {
		this(poolType, daemon, Thread.NORM_PRIORITY);
	}

	public DefaultThreadFactory(String poolName, boolean daemon) {
		this(poolName, daemon, Thread.NORM_PRIORITY);
	}

	public DefaultThreadFactory(Class<?> poolType, int priority) {
		this(poolType, false, priority);
	}

	public DefaultThreadFactory(String poolName, int priority) {
		this(poolName, false, priority);
	}

	public DefaultThreadFactory(Class<?> poolType, boolean daemon, int priority) {
		this(toPoolName(poolType), daemon, priority);
	}

	public static String toPoolName(Class<?> poolType) {
		ObjectUtil.checkNotNull(poolType, "poolType");

		String poolName = StringUtil.simpleClassName(poolType);    // NioEventLoopGroup
		switch (poolName.length()) {
			case 0:
				return "unknown";
			case 1:
				return poolName.toLowerCase(Locale.US);
			default:
				if (Character.isUpperCase(poolName.charAt(0)) && Character.isLowerCase(poolName.charAt(1))) {
					return Character.toLowerCase(poolName.charAt(0)) + poolName.substring(1);    // nioEventLoopGroup
				} else {
					return poolName;
				}
		}
	}

	public DefaultThreadFactory(String poolName, boolean daemon, int priority, ThreadGroup threadGroup) {
		ObjectUtil.checkNotNull(poolName, "poolName");

		if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
			throw new IllegalArgumentException(
					"priority: " + priority + " (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)");
		}

		// 线程创建器(即一个线程池)的名称拼接 形式如nioEventLoopGroup-1-
		prefix = poolName + '-' + poolId.incrementAndGet() + '-';
		this.daemon = daemon;
		this.priority = priority;
		this.threadGroup = threadGroup;
	}

	public DefaultThreadFactory(String poolName, boolean daemon, int priority) {
		this(poolName, daemon, priority, System.getSecurityManager() == null ?
				Thread.currentThread().getThreadGroup() : System.getSecurityManager().getThreadGroup());
	}

	@Override
	public Thread newThread(Runnable r) {
		// 形式如nioEventLoopGroup-1- 的prefix继续拼接 -> nioEventLoopGroup-1-1
		Thread t = newThread(FastThreadLocalRunnable.wrap(r), prefix + nextId.incrementAndGet());
		try {
			if (t.isDaemon() != daemon) {
				t.setDaemon(daemon);
			}

			if (t.getPriority() != priority) {
				t.setPriority(priority);
			}
		} catch (Exception ignored) {
			// Doesn't matter even if failed to set.
		}
		return t;
	}

	/**
	 * netty底层使用的线程不是JDK原生的线程 而是经过封装后的线程
	 *
	 * @param r
	 * @param name
	 * @return
	 */
	protected Thread newThread(Runnable r, String name) {
		return new FastThreadLocalThread(threadGroup, r, name);
	}
}
