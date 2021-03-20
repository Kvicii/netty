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

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.AbstractConstant;
import io.netty.util.ConstantPool;
import io.netty.util.internal.ObjectUtil;

import java.net.InetAddress;
import java.net.NetworkInterface;

/**
 * A {@link ChannelOption} allows to configure a {@link ChannelConfig} in a type-safe
 * way. Which {@link ChannelOption} is supported depends on the actual implementation
 * of {@link ChannelConfig} and may depend on the nature of the transport it belongs
 * to.
 *
 * @param <T> the type of the value which is valid for the {@link ChannelOption}
 */
public class ChannelOption<T> extends AbstractConstant<ChannelOption<T>> {

    private static final ConstantPool<ChannelOption<Object>> pool = new ConstantPool<ChannelOption<Object>>() {
        @Override
        protected ChannelOption<Object> newConstant(int id, String name) {
            return new ChannelOption<Object>(id, name);
        }
    };

    /**
     * Returns the {@link ChannelOption} of the specified name.
     */
    @SuppressWarnings("unchecked")
    public static <T> ChannelOption<T> valueOf(String name) {
        return (ChannelOption<T>) pool.valueOf(name);
    }

    /**
     * Shortcut of {@link #valueOf(String) valueOf(firstNameComponent.getName() + "#" + secondNameComponent)}.
     */
    @SuppressWarnings("unchecked")
    public static <T> ChannelOption<T> valueOf(Class<?> firstNameComponent, String secondNameComponent) {
        return (ChannelOption<T>) pool.valueOf(firstNameComponent, secondNameComponent);
    }

    /**
     * Returns {@code true} if a {@link ChannelOption} exists for the given {@code name}.
     */
    public static boolean exists(String name) {
        return pool.exists(name);
    }

    /**
     * Creates a new {@link ChannelOption} for the given {@code name} or fail with an
     * {@link IllegalArgumentException} if a {@link ChannelOption} for the given {@code name} exists.
     *
     * @deprecated use {@link #valueOf(String)}.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static <T> ChannelOption<T> newInstance(String name) {
        return (ChannelOption<T>) pool.newInstance(name);
    }

    public static final ChannelOption<ByteBufAllocator> ALLOCATOR = valueOf("ALLOCATOR");
    public static final ChannelOption<RecvByteBufAllocator> RCVBUF_ALLOCATOR = valueOf("RCVBUF_ALLOCATOR");
    public static final ChannelOption<MessageSizeEstimator> MESSAGE_SIZE_ESTIMATOR = valueOf("MESSAGE_SIZE_ESTIMATOR");

    public static final ChannelOption<Integer> CONNECT_TIMEOUT_MILLIS = valueOf("CONNECT_TIMEOUT_MILLIS");
    /**
     * @deprecated Use {@link MaxMessagesRecvByteBufAllocator}
     * and {@link MaxMessagesRecvByteBufAllocator#maxMessagesPerRead(int)}.
     */
    @Deprecated
    public static final ChannelOption<Integer> MAX_MESSAGES_PER_READ = valueOf("MAX_MESSAGES_PER_READ");
    public static final ChannelOption<Integer> MAX_MESSAGES_PER_WRITE = valueOf("MAX_MESSAGES_PER_WRITE");

    public static final ChannelOption<Integer> WRITE_SPIN_COUNT = valueOf("WRITE_SPIN_COUNT");
    /**
     * @deprecated Use {@link #WRITE_BUFFER_WATER_MARK}
     */
    @Deprecated
    public static final ChannelOption<Integer> WRITE_BUFFER_HIGH_WATER_MARK = valueOf("WRITE_BUFFER_HIGH_WATER_MARK");
    /**
     * @deprecated Use {@link #WRITE_BUFFER_WATER_MARK}
     */
    @Deprecated
    public static final ChannelOption<Integer> WRITE_BUFFER_LOW_WATER_MARK = valueOf("WRITE_BUFFER_LOW_WATER_MARK");
    /**
     * 水位线是 Netty 自己对发送出去的数据的缓冲区 是应用层面的
     */
    public static final ChannelOption<WriteBufferWaterMark> WRITE_BUFFER_WATER_MARK =
            valueOf("WRITE_BUFFER_WATER_MARK");

    public static final ChannelOption<Boolean> ALLOW_HALF_CLOSURE = valueOf("ALLOW_HALF_CLOSURE");
    public static final ChannelOption<Boolean> AUTO_READ = valueOf("AUTO_READ");

    /**
     * If {@code true} then the {@link Channel} is closed automatically and immediately on write failure.
     * The default value is {@code true}.
     */
    public static final ChannelOption<Boolean> AUTO_CLOSE = valueOf("AUTO_CLOSE");
    // TCP参数 设置广播模式
    public static final ChannelOption<Boolean> SO_BROADCAST = valueOf("SO_BROADCAST");
    // TCP协议的心跳机制 true为连接保持心跳 默认值为false 启用该功能时 TCP会主动探测空闲连接的有效性 默认心跳间隔是7200s(2h) netty默认关闭此功能
    public static final ChannelOption<Boolean> SO_KEEPALIVE = valueOf("SO_KEEPALIVE");
    /**
     * 缓冲区设置操作系统底层每个连接的 TCP 缓冲区 是操作系统层面的
     */
    // socket发送缓冲区大小
    public static final ChannelOption<Integer> SO_SNDBUF = valueOf("SO_SNDBUF");
    // socket接收缓冲区大小
    public static final ChannelOption<Integer> SO_RCVBUF = valueOf("SO_RCVBUF");
    // TCP参数 设置为true表示地址复用 有4种情况需要用到该参数
    // 1.当有一个相同本地地址和端口的socket1处于TIME_WAIT状态时 而希望的启动的socket2要占用该地址和端口 如在重启服务且保持先前端口时
    // 2.由多块网卡或用IP Alias技术的机器在统一端口启动多个进程 但每个进程绑定的本地IP地址不能相同
    // 3.单个进程绑定相同的端口到多个socket上 但每个socket绑定的IP地址不同
    // 4.完全相同的地址和端口的重复绑定 但这只用于UDP的多播 不用于TCP
    public static final ChannelOption<Boolean> SO_REUSEADDR = valueOf("SO_REUSEADDR");
    // TCP参数 表示关闭socket的延迟时间 默认值-1表示禁用 -1表示socket.close方法立即返回 单操作系统底层会将发送缓冲区的数据全部发送到对端
    // 0表示socket.close方法立即返回 操作系统放弃发送缓冲区的数据 直接向对端发送RST包 对端收到复位错误
    // 非0整数值表示调用socket.close方法的线程被阻塞 知道延迟时间到来 && 发送缓冲区中的数据发送完毕 若超时则对段会收到复位错误
    public static final ChannelOption<Integer> SO_LINGER = valueOf("SO_LINGER");
    // TCP参数 表示服务端接受连接的队列长度 如果队列已满客户端连接将被拒绝 默认值在Windows为200 其他操作系统为128
    public static final ChannelOption<Integer> SO_BACKLOG = valueOf("SO_BACKLOG");
    public static final ChannelOption<Integer> SO_TIMEOUT = valueOf("SO_TIMEOUT");

    public static final ChannelOption<Integer> IP_TOS = valueOf("IP_TOS");
    public static final ChannelOption<InetAddress> IP_MULTICAST_ADDR = valueOf("IP_MULTICAST_ADDR");
    public static final ChannelOption<NetworkInterface> IP_MULTICAST_IF = valueOf("IP_MULTICAST_IF");
    public static final ChannelOption<Integer> IP_MULTICAST_TTL = valueOf("IP_MULTICAST_TTL");
    public static final ChannelOption<Boolean> IP_MULTICAST_LOOP_DISABLED = valueOf("IP_MULTICAST_LOOP_DISABLED");
    // 表示立即发送数据 默认值为true 操作系统默认为false
    // 该参数值与是否开启Nagle算法是相反的 true表示关闭 false表示开启 如果要求高实时性 有数据发送时立刻发送 此选项应设置为true 如果需要减少发送次数和减少网络交互次数 应设置为false
    public static final ChannelOption<Boolean> TCP_NODELAY = valueOf("TCP_NODELAY");
    public static final ChannelOption<Boolean> TCP_FASTOPEN_CONNECT = valueOf("TCP_FASTOPEN_CONNECT");

    @Deprecated
    public static final ChannelOption<Boolean> DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION =
            valueOf("DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION");

    public static final ChannelOption<Boolean> SINGLE_EVENTEXECUTOR_PER_GROUP =
            valueOf("SINGLE_EVENTEXECUTOR_PER_GROUP");

    /**
     * Creates a new {@link ChannelOption} with the specified unique {@code name}.
     */
    private ChannelOption(int id, String name) {
        super(id, name);
    }

    @Deprecated
    protected ChannelOption(String name) {
        this(pool.nextId(), name);
    }

    /**
     * Validate the value which is set for the {@link ChannelOption}. Sub-classes
     * may override this for special checks.
     */
    public void validate(T value) {
        ObjectUtil.checkNotNull(value, "value");
    }
}
