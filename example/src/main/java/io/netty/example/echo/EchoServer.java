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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server.
        // 在服务器端会指定两个EventLoopGroup 一个用于新连接的监听和接收 另外一个用于处理IO事件
        EventLoopGroup bossGroup = new NioEventLoopGroup(); // 创建NioEventLoopGroup时创建了Selector  查询父通道(NioServerSocketChannel)的IO事件
        EventLoopGroup workerGroup = new NioEventLoopGroup();   // 查询所有子通道(NioSocketChannel)的IO事件
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    // 由父SocketChannel负责创建自SocketChannel
                    .channel(NioServerSocketChannel.class)
                    // .localAddress(PORT)
                    .option(ChannelOption.SO_BACKLOG, 100)
                    .handler(new LoggingHandler(LogLevel.INFO)) // 为父通道NioServerSocketChannel装配流水线 一般是不需要的 因为父通道的逻辑是固定的: 接收新连接 -> 创建子通道 -> 初始化子通道
                    // 两种设置keepalive的风格
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(NioChannelOption.SO_KEEPALIVE, true)
//                    .childHandler()
                    // ====================
                    // 选择内存池实现 默认使用池化的实现
                    // .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    // 选择堆外/堆外内存的内存池实现--方式一
                    // 设置io.netty.type参数--方式二
                    .childOption(ChannelOption.ALLOCATOR, new PooledByteBufAllocator(false))
//                    .childAttr()
                    .childHandler(new ChannelInitializer<SocketChannel>() { // 为子通道NioSocketChannel初始化channelPipeline
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc()));
                            }
                            p.addLast(new ChannelInboundHandlerA());
                            p.addLast(new ChannelInboundHandlerB());
                            p.addLast(new ChannelInboundHandlerC());
                            p.addLast(new ChannelOutboundHandlerA());
                            p.addLast(new ChannelOutboundHandlerB());
                            p.addLast(new ChannelOutboundHandlerC());
                            p.addLast(new ExceptionCaughtHandler());
                            // p.addLast(new LoggingHandler(LogLevel.INFO));
//                            p.addLast(serverHandler);   // 将channelHandler添加到责任链 在请求或响应时都会经过链上的channelHandler处理
                        }
                    });

            // Start the server.
            ChannelFuture f = b.bind(PORT).sync();  // 阻塞当前Thread 一直到端口绑定完成

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();   // 应用程序会阻塞等待直到服务器关闭
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
