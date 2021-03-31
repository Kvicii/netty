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
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 * <p>
 * 行解码器
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /**
     * Maximum length of a frame we're willing to decode.
     * 行解码器最终解码出来数据包的最大长度 超过该长度可能会进入丢弃模式
     */
    private final int maxLength;
    /**
     * Whether or not to throw an exception as soon as we exceed maxLength.
     * 超过最大解码长度是否抛出异常 如果为true 立即抛出 如果为false 晚一些抛出
     */
    private final boolean failFast;
    // 最终解码出来的数据包是否带有换行符 如果为true 不带换行符 如果为false 则带换行符
    private final boolean stripDelimiter;

    /**
     * True if we're discarding input because we're already over maxLength.
     * true代表处于丢弃模式
     */
    private boolean discarding;
    // 解码到现在已经丢弃的字节数
    private int discardedBytes;

    /** Last scan position. */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    /**
     *
     * @param ctx the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in  the {@link ByteBuf} from which to read data
     *            ByteBuf数据累加器
     * @param out the {@link List} to which decoded messages should be added
     *            存放解析结果的集合
     * @throws Exception
     */
    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        // 找到一行的分隔符的位置 如果是'\n'作为结果 就找到'\n'的位置 如果是'\r\n'作为结尾 指向'\r'的位置
        final int eol = findEndOfLine(buffer);
        if (!discarding) {  // 非丢弃模式
            if (eol >= 0) { // 已经找到了结尾的分隔符
                final ByteBuf frame;
                // 计算出一段可读数据的长度
                final int length = eol - buffer.readerIndex();
                // 获取分隔符的长度 如果是'\r\n'结尾 就是2 如果是'\n'结尾 就是1
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                if (length > maxLength) {   // 要读取的数据长度 > 设置的最大长度
                    // 直接丢弃这段可读数据
                    buffer.readerIndex(eol + delimLength);
                    // 传播异常
                    fail(ctx, length);
                    return null;
                }
                // 截取到了一段有效数据
                if (stripDelimiter) {   // 不需要携带分隔符
                    // 直接截取length长度的ByteBuf
                    frame = buffer.readRetainedSlice(length);
                    // 跳过分隔符的字节
                    buffer.skipBytes(delimLength);
                } else {    // 截取包含分隔符在内的ByteBuf
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {    // 未找到换行符 说明在readIndex和writeIndex之间没有分隔符
                final int length = buffer.readableBytes();
                if (length > maxLength) {   // 超过最大解析长度
                    // 标记丢弃的长度
                    discardedBytes = length;
                    // 读指针移到写指针的位置
                    buffer.readerIndex(buffer.writerIndex());
                    // 标记进入丢弃模式
                    discarding = true;
                    offset = 0;
                    if (failFast) { // 传播异常
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        } else {    // 丢弃模式
            if (eol >= 0) { // 已经找到了结尾的分隔符
                // 计算需要丢弃的数据长度 = 前面已经丢弃的 + 本次需要丢弃的
                final int length = discardedBytes + eol - buffer.readerIndex();
                // 获取分隔符的长度 如果是'\r\n'结尾 就是2 如果是'\n'结尾 就是1
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                // 将读指针移动到分隔符后面的那一个位置
                buffer.readerIndex(eol + delimLength);
                // 更新已丢弃的字节数为0
                discardedBytes = 0;
                // 标记为非丢弃模式
                discarding = false;
                if (!failFast) {
                    fail(ctx, length);
                }
            } else {    // 未找到分隔符
                // 丢弃的数据长度 = 前面已经丢弃的 + 当前可读的数据长度
                discardedBytes += buffer.readableBytes();
                // 读指针移到写指针的位置
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            // 丢弃模式下 最终的结果都是丢弃一段字节流 所以直接返回null 表明没有解析出有效的数据包
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     * <p>
     * 返回每一行结尾的分隔符的位置
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        // 找到'\n'分隔符的位置
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        // 如果找到了'\n'的位置 判断前一个字节是否是'\r' 如果是 就把位置指向'\r'的位置
        if (i >= 0) {
            offset = 0;
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        } else {
            offset = totalLength;
        }
        return i;
    }
}
