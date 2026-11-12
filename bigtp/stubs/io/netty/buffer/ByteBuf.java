package io.netty.buffer;

public abstract class ByteBuf {
    public abstract ByteBuf writeLong(long value);
    public abstract long readLong();
}