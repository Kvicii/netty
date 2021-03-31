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
import io.netty.util.internal.ObjectUtil;

import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;

/**
 * A decoder that splits the received {@link ByteBuf}s by one or more delimiters. It is particularly
 * useful for decoding the frames which ends with a delimiter such as {@link
 * Delimiters#nulDelimiter() NUL} or {@linkplain Delimiters#lineDelimiter() newline characters}.
 *
 * <h3>Predefined delimiters</h3>
 *
 * <p>{@link Delimiters} defines frequently used delimiters for convenience' sake.
 *
 * <h3>Specifying more than one delimiter</h3>
 *
 * <p>{@link DelimiterBasedFrameDecoder} allows you to specify more than one delimiter. If more than
 * one delimiter is found in the buffer, it chooses the delimiter which produces the shortest frame.
 * For example, if you have the following data in the buffer:
 *
 * <pre>
 * +--------------+
 * | ABC\nDEF\r\n |
 * +--------------+
 * </pre>
 *
 * a {@link DelimiterBasedFrameDecoder}({@link Delimiters#lineDelimiter()
 * Delimiters.lineDelimiter()}) will choose {@code '\n'} as the first delimiter and produce two
 * frames:
 *
 * <pre>
 * +-----+-----+
 * | ABC | DEF |
 * +-----+-----+
 * </pre>
 *
 * rather than incorrectly choosing {@code '\r\n'} as the first delimiter:
 *
 * <pre>
 * +----------+
 * | ABC\nDEF |
 * +----------+
 * </pre>
 *
 * 分隔符封装成帧(Framing)解码实现 由于编码实现简单 所以不需要编码类
 */
public class DelimiterBasedFrameDecoder extends ByteToMessageDecoder {

  // 支持多个分隔符
  private final ByteBuf[] delimiters;
  // 最终解码出来数据包的最大长度 超过该长度可能会进入丢弃模式
  private final int maxFrameLength;
  private final boolean stripDelimiter;
  // 超过最大解码长度是否抛出异常 如果为true 立即抛出 如果为false 晚一些抛出
  private final boolean failFast;
  // 是否处于丢弃模式 true表示处于丢弃模式
  private boolean discardingTooLongFrame;
  // 丢弃的数据长度
  private int tooLongFrameLength;
  /**
   * Set only when decoding with "\n" and "\r\n" as the delimiter.
   * 行解码器
   */
  private final LineBasedFrameDecoder lineBasedDecoder;

  /**
   * Creates a new instance.
   *
   * @param maxFrameLength the maximum length of the decoded frame. A {@link TooLongFrameException}
   *     is thrown if the length of the frame exceeds this value.
   * @param delimiter the delimiter
   */
  public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf delimiter) {
    this(maxFrameLength, true, delimiter);
  }

  /**
   * Creates a new instance.
   *
   * @param maxFrameLength the maximum length of the decoded frame. A {@link TooLongFrameException}
   *     is thrown if the length of the frame exceeds this value.
   * @param stripDelimiter whether the decoded frame should strip out the delimiter or not
   * @param delimiter the delimiter
   */
  public DelimiterBasedFrameDecoder(int maxFrameLength, boolean stripDelimiter, ByteBuf delimiter) {
    this(maxFrameLength, stripDelimiter, true, delimiter);
  }

  /**
   * Creates a new instance.
   *
   * @param maxFrameLength the maximum length of the decoded frame. A {@link TooLongFrameException}
   *     is thrown if the length of the frame exceeds this value.
   * @param stripDelimiter whether the decoded frame should strip out the delimiter or not
   * @param failFast If <tt>true</tt>, a {@link TooLongFrameException} is thrown as soon as the
   *     decoder notices the length of the frame will exceed <tt>maxFrameLength</tt> regardless of
   *     whether the entire frame has been read. If <tt>false</tt>, a {@link TooLongFrameException}
   *     is thrown after the entire frame that exceeds <tt>maxFrameLength</tt> has been read.
   * @param delimiter the delimiter
   */
  public DelimiterBasedFrameDecoder(
      int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf delimiter) {
    this(
        maxFrameLength,
        stripDelimiter,
        failFast,
        new ByteBuf[] {delimiter.slice(delimiter.readerIndex(), delimiter.readableBytes())});
  }

  /**
   * Creates a new instance.
   *
   * @param maxFrameLength the maximum length of the decoded frame. A {@link TooLongFrameException}
   *     is thrown if the length of the frame exceeds this value.
   * @param delimiters the delimiters
   */
  public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf... delimiters) {
    this(maxFrameLength, true, delimiters);
  }

  /**
   * Creates a new instance.
   *
   * @param maxFrameLength the maximum length of the decoded frame. A {@link TooLongFrameException}
   *     is thrown if the length of the frame exceeds this value.
   * @param stripDelimiter whether the decoded frame should strip out the delimiter or not
   * @param delimiters the delimiters
   */
  public DelimiterBasedFrameDecoder(
      int maxFrameLength, boolean stripDelimiter, ByteBuf... delimiters) {
    this(maxFrameLength, stripDelimiter, true, delimiters);
  }

  /**
   * Creates a new instance.
   *
   * @param maxFrameLength the maximum length of the decoded frame. A {@link TooLongFrameException}
   *     is thrown if the length of the frame exceeds this value.
   * @param stripDelimiter whether the decoded frame should strip out the delimiter or not
   * @param failFast If <tt>true</tt>, a {@link TooLongFrameException} is thrown as soon as the
   *     decoder notices the length of the frame will exceed <tt>maxFrameLength</tt> regardless of
   *     whether the entire frame has been read. If <tt>false</tt>, a {@link TooLongFrameException}
   *     is thrown after the entire frame that exceeds <tt>maxFrameLength</tt> has been read.
   * @param delimiters the delimiters
   */
  public DelimiterBasedFrameDecoder(
      int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf... delimiters) {
    validateMaxFrameLength(maxFrameLength);
    ObjectUtil.checkNonEmpty(delimiters, "delimiters");

    if (isLineBased(delimiters) && !isSubclass()) { // 如果是基于行分隔符进行解码 直接初始化一个行解码器
      lineBasedDecoder = new LineBasedFrameDecoder(maxFrameLength, stripDelimiter, failFast);
      this.delimiters = null;
    } else {
      this.delimiters = new ByteBuf[delimiters.length];
      for (int i = 0; i < delimiters.length; i++) {
        ByteBuf d = delimiters[i];
        validateDelimiter(d);
        this.delimiters[i] = d.slice(d.readerIndex(), d.readableBytes());
      }
      lineBasedDecoder = null;
    }
    this.maxFrameLength = maxFrameLength;
    this.stripDelimiter = stripDelimiter;
    this.failFast = failFast;
  }

  /** Returns true if the delimiters are "\n" and "\r\n". */
  private static boolean isLineBased(final ByteBuf[] delimiters) {
    if (delimiters.length != 2) {
      return false;
    }
    // a == '\r\n'
    // B == '\n'
    ByteBuf a = delimiters[0];
    ByteBuf b = delimiters[1];
    if (a.capacity() < b.capacity()) {
      a = delimiters[1];
      b = delimiters[0];
    }
    return a.capacity() == 2
        && b.capacity() == 1
        && a.getByte(0) == '\r'
        && a.getByte(1) == '\n'
        && b.getByte(0) == '\n';
  }

  /** Return {@code true} if the current instance is a subclass of DelimiterBasedFrameDecoder */
  private boolean isSubclass() {
    return getClass() != DelimiterBasedFrameDecoder.class;
  }

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
   * @param buffer the {@link ByteBuf} from which to read data
   * @return frame the {@link ByteBuf} which represent the frame or {@code null} if no frame could
   *     be created.
   */
  protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
    if (lineBasedDecoder != null) { // 如果是基于行解码器进行解码的 直接使用行解码器进行解码
      return lineBasedDecoder.decode(ctx, buffer);
    }
    // Try all delimiters and choose the delimiter which yields the shortest frame.
    // 找打最小分隔符
    int minFrameLength = Integer.MAX_VALUE;
    ByteBuf minDelim = null;
    for (ByteBuf delim : delimiters) {  // 遍历所有分隔符
      // 找到每个分隔符分割的数据包的长度
      int frameLength = indexOf(buffer, delim);
      if (frameLength >= 0 && frameLength < minFrameLength) { // 计算最小数据包的长度
        minFrameLength = frameLength;
        minDelim = delim;
      }
    }

    if (minDelim != null) { // 已经找到了分隔符
      int minDelimLength = minDelim.capacity();
      ByteBuf frame;

      if (discardingTooLongFrame) { // 丢弃模式
        // We've just finished discarding a very large frame.
        // Go back to the initial state.
        // 标记为非丢弃模式
        discardingTooLongFrame = false;
        // 丢弃该段数据包 直接移动到分隔符下一个位置
        buffer.skipBytes(minFrameLength + minDelimLength);

        int tooLongFrameLength = this.tooLongFrameLength;
        this.tooLongFrameLength = 0;
        if (!failFast) {  // 传播异常
          fail(tooLongFrameLength);
        }
        return null;
      }
      // 非丢弃模式
      if (minFrameLength > maxFrameLength) {  // 数据包长度 > 可解码的最大数据包长度
        // Discard read frame.
        // 丢弃该段数据包 直接移动到分隔符下一个位置
        buffer.skipBytes(minFrameLength + minDelimLength);
        // 传播异常
        fail(minFrameLength);
        return null;
      }
      // 合理的数据包长度
      if (stripDelimiter) { // 跳过分隔符
        // 截取不包含分隔符长度的数据包
        frame = buffer.readRetainedSlice(minFrameLength);
        buffer.skipBytes(minDelimLength);
      } else {  // 截取包含分隔符在内的数据包
        frame = buffer.readRetainedSlice(minFrameLength + minDelimLength);
      }

      return frame;
    } else {	// 未找到分隔符
      if (!discardingTooLongFrame) {  // 非丢弃模式
        if (buffer.readableBytes() > maxFrameLength) {  // 可读字节 > 可解码的最大数据包长度
          // Discard the content of the buffer until a delimiter is found.
          // 将可读字节保存到丢弃的长度
          tooLongFrameLength = buffer.readableBytes();
          // 跳过可读字节
          buffer.skipBytes(buffer.readableBytes());
          // 标记为丢弃状态
          discardingTooLongFrame = true;
          if (failFast) { // 传播异常
            fail(tooLongFrameLength);
          }
        }
      } else {  // 丢弃模式
        // Still discarding the buffer since a delimiter is not found.
        // 丢弃的长度 = 之前丢弃的长度 + 本次可读字节
        tooLongFrameLength += buffer.readableBytes();
        // 跳过可读字节
        buffer.skipBytes(buffer.readableBytes());
      }
      return null;
    }
  }

  private void fail(long frameLength) {
    if (frameLength > 0) {
      throw new TooLongFrameException(
          "frame length exceeds " + maxFrameLength + ": " + frameLength + " - discarded");
    } else {
      throw new TooLongFrameException("frame length exceeds " + maxFrameLength + " - discarding");
    }
  }

  /**
   * Returns the number of bytes between the readerIndex of the haystack and the first needle found
   * in the haystack. -1 is returned if no needle is found in the haystack.
   */
  private static int indexOf(ByteBuf haystack, ByteBuf needle) {
    for (int i = haystack.readerIndex(); i < haystack.writerIndex(); i++) {
      int haystackIndex = i;
      int needleIndex;
      for (needleIndex = 0; needleIndex < needle.capacity(); needleIndex++) {
        if (haystack.getByte(haystackIndex) != needle.getByte(needleIndex)) {
          break;
        } else {
          haystackIndex++;
          if (haystackIndex == haystack.writerIndex() && needleIndex != needle.capacity() - 1) {
            return -1;
          }
        }
      }

      if (needleIndex == needle.capacity()) {
        // Found the needle from the haystack!
        return i - haystack.readerIndex();
      }
    }
    return -1;
  }

  private static void validateDelimiter(ByteBuf delimiter) {
    ObjectUtil.checkNotNull(delimiter, "delimiter");
    if (!delimiter.isReadable()) {
      throw new IllegalArgumentException("empty delimiter");
    }
  }

  private static void validateMaxFrameLength(int maxFrameLength) {
    checkPositive(maxFrameLength, "maxFrameLength");
  }
}
