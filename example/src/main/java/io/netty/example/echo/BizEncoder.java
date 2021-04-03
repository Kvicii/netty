package io.netty.example.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * -----------------------
 * | 	4   |  4  |   ?  |
 * -----------------------
 * | length | age | name |
 */
public class BizEncoder extends MessageToByteEncoder<BizUser> {

	@Override
	protected void encode(ChannelHandlerContext ctx, BizUser user, ByteBuf out) throws Exception {
		byte[] bytes = user.getName().getBytes();
		out.writeInt(4 + bytes.length);
		out.writeInt(4);
		out.writeBytes(bytes);
	}
}
