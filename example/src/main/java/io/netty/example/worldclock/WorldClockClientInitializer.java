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
package io.netty.example.worldclock;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;

public class WorldClockClientInitializer extends ChannelInitializer<SocketChannel> {

    private final SslContext sslCtx;

    public WorldClockClientInitializer(SslContext sslCtx) {
        this.sslCtx = sslCtx;
    }

    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        if (sslCtx != null) {
            p.addLast(sslCtx.newHandler(ch.alloc(), WorldClockClient.HOST, WorldClockClient.PORT));
        }

        // pipeline决定 先解码(自上而下)-->执行业务逻辑-->再编码(自下而上)
        // 一次解码器
        p.addLast(new ProtobufVarint32FrameDecoder());
        // 二次解码器
        p.addLast(new ProtobufDecoder(WorldClockProtocol.LocalTimes.getDefaultInstance()));

        // 二次编码器 处理粘包 半包
        p.addLast(new ProtobufVarint32LengthFieldPrepender());
        // 一次编码器
        p.addLast(new ProtobufEncoder());
        // 业务逻辑ing
        p.addLast(new WorldClockClientHandler());
    }
}
