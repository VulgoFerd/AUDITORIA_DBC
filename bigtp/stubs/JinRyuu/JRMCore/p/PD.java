package JinRyuu.JRMCore.p;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import net.minecraft.entity.player.EntityPlayerMP;

public class PD {
    public static void sendTo(IMessage message, EntityPlayerMP player) {
        // Stub for testing - does nothing
    }
    
    public static void registerPackets() {
        // Stub
    }
    
    public static void registerMessage(Class<?> handler, Class<?> message) {
        // Stub
    }
}