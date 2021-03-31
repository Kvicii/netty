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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;

/**
 * A decoder that splits the received {@link ByteBuf}s by the fixed number of bytes. For example, if
 * you received the following four fragmented packets:
 *
 * <pre>
 * +---+----+------+----+
 * | A | BC | DEFG | HI |
 * +---+----+------+----+
 * </pre>
 *
 * A {@link FixedLengthFrameDecoder}{@code (3)} will decode them into the following three packets
 * with the fixed length:
 *
 * <pre>
 * +-----+-----+-----+
 * | ABC | DEF | GHI |
 * +-----+-----+-----+
 * </pre>
 *
 * 固定长度封装成帧(Framing)解码实现 由于编码实现简单 所以不需要编码类
 */
public class FixedLengthFrameDecoder extends ByteToMessageDecoder {

  /**
   * 以frameLength作为解析的长度
   */
  private final int frameLength;

  /**
   * Creates a new instance.
   *
   * @param frameLength the length of the frame
   */
  public FixedLengthFrameDecoder(int frameLength) {
    checkPositive(frameLength, "frameLength");
    this.frameLength = frameLength;
  }

  /**
   * 解码操作
   *
   * @param ctx the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
   * @param in  the {@link ByteBuf} from which to read data
   *            ByteBuf数据累加器
   * @param out the {@link List} to which decoded messages should be added
   *            存放解析结果的集合
   * @throws Exception
   */
  @Override
  protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
      throws Exception {
    Object decoded = decode(ctx, in);
    if (decoded != null) {
      out.add(decoded);
    }
  }

  /**
   * Create a frame out of the {@link ByteBuf} and return it.
   *
   * @param ctx the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
   * @param in the {@link ByteBuf} from which to read data
   * @return frame the {@link ByteBuf} which represent the frame or {@code null} if no frame could
   *     be created.
   */
  protected Object decode(
      @SuppressWarnings("UnusedParameters") ChannelHandlerContext ctx, ByteBuf in)
      throws Exception {
    if (in.readableBytes() < frameLength) {
      // 累加器中可读字节小于设置的长度就不解码数据了 说明在ByteToMessageDecoder中需要再读一些数据进行解码
      return null;
    } else {
      // 从累加器中截取frameLength长度的ByteBuf
      // 解决粘包 半包问题
      return in.readRetainedSlice(frameLength);
    }
  }
}
