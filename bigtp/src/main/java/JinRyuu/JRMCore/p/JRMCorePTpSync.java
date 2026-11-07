package JinRyuu.JRMCore.p;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class JRMCorePTpSync implements IMessage {
    public long tpLong;

    public JRMCorePTpSync() {
    }

    public JRMCorePTpSync(long tpLong) {
        this.tpLong = tpLong;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(tpLong);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.tpLong = buf.readLong();
    }

    public static class Handler extends CAmh<JRMCorePTpSync> {
        public Handler() {
        }

        @Override
        public IMessage handleClientMessage(net.minecraft.entity.player.EntityPlayer player, JRMCorePTpSync message, cpw.mods.fml.common.network.simpleimpl.MessageContext ctx) {
            JinRyuu.JRMCore.TpNetwork.accept(message.tpLong);
            return null;
        }
    }
}