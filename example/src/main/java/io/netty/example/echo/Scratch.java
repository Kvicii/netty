package io.netty.example.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

public class Scratch {
    public static void main(String[] args) {
        // example 1
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        ByteBuf byteBuf = allocator.directBuffer(16);
        // example 2
        // int page = 1024 * 8;
        // allocator.directBuffer(2 * page);
        byteBuf.release();
    }
}
