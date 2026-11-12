package cpw.mods.fml.common.network.simpleimpl;

public interface IMessage {
    void toBytes(io.netty.buffer.ByteBuf buf);
    void fromBytes(io.netty.buffer.ByteBuf buf);
}