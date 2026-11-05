import java.io.*;
import java.util.*;

/**
 * Builds a minimal class hierarchy for key Minecraft classes
 * to enable proper frame computation in HierarchyClassWriter.
 */
public class BuildMinimalHierarchy {
    public static void main(String[] args) throws Exception {
        Map<String, Set<String>> hierarchy = new HashMap<>();

        // java.lang.Object - root
        hierarchy.put("java/lang/Object", new HashSet<>());

        // Key Minecraft classes with their superclass/interface relationships
        // Based on MCP 1.7.10 mappings

        // NBT hierarchy
        hierarchy.put("net/minecraft/nbt/NBTBase", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/nbt/NBTTagCompound", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagList", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagByte", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagShort", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagInt", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagLong", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagFloat", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagDouble", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagString", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagByteArray", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));
        hierarchy.put("net/minecraft/nbt/NBTTagIntArray", new HashSet<>(Arrays.asList("net/minecraft/nbt/NBTBase")));

        // Entity hierarchy
        hierarchy.put("net/minecraft/entity/Entity", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/entity/EntityLiving", new HashSet<>(Arrays.asList("net/minecraft/entity/Entity")));
        hierarchy.put("net/minecraft/entity/EntityCreature", new HashSet<>(Arrays.asList("net/minecraft/entity/EntityLiving")));
        hierarchy.put("net/minecraft/entity/EntityPlayer", new HashSet<>(Arrays.asList("net/minecraft/entity/EntityLiving")));
        hierarchy.put("net/minecraft/entity/player/EntityPlayerMP", new HashSet<>(Arrays.asList("net/minecraft/entity/EntityPlayer")));
        hierarchy.put("net/minecraft/entity/monster/IMob", new HashSet<>());

        // Gui hierarchy
        hierarchy.put("net/minecraft/client/gui/GuiScreen", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/client/gui/GuiYesNoCallback", new HashSet<>());
        hierarchy.put("net/minecraft/client/gui/GuiButton", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/client/gui/Gui", new HashSet<>(Arrays.asList("java/lang/Object")));

        // Command hierarchy
        hierarchy.put("net/minecraft/command/CommandBase", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/command/ICommandSender", new HashSet<>());

        // Network
        hierarchy.put("cpw/mods/fml/common/network/simpleimpl/IMessage", new HashSet<>());
        hierarchy.put("cpw/mods/fml/common/network/simpleimpl/IMessageHandler", new HashSet<>());
        hierarchy.put("io/netty/channel/ChannelHandler", new HashSet<>());

        // Forge events
        hierarchy.put("cpw/mods/fml/common/eventhandler/Event", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraftforge/event/entity/EntityEvent", new HashSet<>(Arrays.asList("cpw/mods/fml/common/eventhandler/Event")));
        hierarchy.put("net/minecraftforge/event/entity/living/LivingEvent", new HashSet<>(Arrays.asList("net/minecraftforge/event/entity/EntityEvent")));
        hierarchy.put("net/minecraftforge/event/entity/player/PlayerEvent", new HashSet<>(Arrays.asList("net/minecraftforge/event/entity/living/LivingEvent")));

        // Extended entity properties
        hierarchy.put("net/minecraftforge/common/IExtendedEntityProperties", new HashSet<>());

        // Rendering
        hierarchy.put("net/minecraft/client/renderer/entity/RendererLivingEntity", new HashSet<>(Arrays.asList("net/minecraft/client/renderer/entity/Renderer")));
        hierarchy.put("net/minecraft/client/renderer/entity/Render", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/client/renderer/entity/RenderPlayer", new HashSet<>(Arrays.asList("net/minecraft/client/renderer/entity/RendererLivingEntity")));
        hierarchy.put("net/minecraft/client/model/ModelBase", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/client/model/ModelBiped", new HashSet<>(Arrays.asList("net/minecraft/client/model/ModelBase")));

        // TileEntity
        hierarchy.put("net/minecraft/tileentity/TileEntity", new HashSet<>(Arrays.asList("java/lang/Object")));

        // World/Explosion
        hierarchy.put("net/minecraft/world/Explosion", new HashSet<>(Arrays.asList("java/lang/Object")));

        // Items/Blocks
        hierarchy.put("net/minecraft/item/Item", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/item/ItemArmor", new HashSet<>(Arrays.asList("net/minecraft/item/Item")));
        hierarchy.put("net/minecraft/item/ItemBlock", new HashSet<>(Arrays.asList("net/minecraft/item/Item")));
        hierarchy.put("net/minecraft/item/ItemFood", new HashSet<>(Arrays.asList("net/minecraft/item/Item")));
        hierarchy.put("net/minecraft/block/Block", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/block/BlockFence", new HashSet<>(Arrays.asList("net/minecraft/block/Block")));

        // Inventory
        hierarchy.put("net/minecraft/inventory/Container", new HashSet<>(Arrays.asList("java/lang/Object")));
        hierarchy.put("net/minecraft/inventory/IInventory", new HashSet<>());
        hierarchy.put("net/minecraft/inventory/Slot", new HashSet<>(Arrays.asList("java/lang/Object")));

        // CreativeTabs
        hierarchy.put("net/minecraft/creativetab/CreativeTabs", new HashSet<>(Arrays.asList("java/lang/Object")));

        // MovementInput
        hierarchy.put("net/minecraft/util/MovementInput", new HashSet<>());

        // AI
        hierarchy.put("net/minecraft/entity/ai/EntityAIBase", new HashSet<>(Arrays.asList("java/lang/Object")));

        // Projectile
        hierarchy.put("net/minecraft/entity/IProjectile", new HashSet<>());
        hierarchy.put("net/minecraft/entity/projectile/EntityArrow", new HashSet<>(Arrays.asList("net/minecraft/entity/Entity")));

        // Throwable
        hierarchy.put("cpw/mods/fml/common/registry/IThrowableEntity", new HashSet<>());
        hierarchy.put("cpw/mods/fml/common/registry/IEntityAdditionalSpawnData", new HashSet<>());

        // EntitySelector
        hierarchy.put("net/minecraft/command/IEntitySelector", new HashSet<>());

        // Texture
        hierarchy.put("net/minecraft/client/renderer/texture/AbstractTexture", new HashSet<>(Arrays.asList("java/lang/Object")));

        // Packet
        hierarchy.put("kamkeel/npcdbc/network/AbstractPacket", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/network/DBCPacketHandler", new HashSet<>());

        // NPCs
        hierarchy.put("noppes/npcs/client/gui/util/IGuiData", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/IScrollData", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/ITextfieldListener", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/SubGuiInterface", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/ICustomScrollListener", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/ISubGuiListener", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/GuiNPCInterface", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/GuiNPCInterface2", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/ISliderListener", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/util/GuiCustomScroll", new HashSet<>());
        hierarchy.put("noppes/npcs/client/gui/player/inventory/GuiCNPCInventory", new HashSet<>(Arrays.asList("net/minecraft/client/gui/inventory/GuiContainer")));
        hierarchy.put("noppes/npcs/client/model/util/ModelScaleRenderer", new HashSet<>(Arrays.asList("net/minecraft/client/model/ModelRenderer")));
        hierarchy.put("noppes/npcs/client/renderer/RenderNPCHumanMale", new HashSet<>(Arrays.asList("net/minecraft/client/renderer/entity/RendererLivingEntity")));

        // NPCs scripted
        hierarchy.put("noppes/npcs/scripted/entity/ScriptLivingBase", new HashSet<>(Arrays.asList("net/minecraft/entity/EntityLiving")));
        hierarchy.put("noppes/npcs/scripted/event/player/PlayerEvent", new HashSet<>(Arrays.asList("cpw/mods/fml/common/eventhandler/Event")));

        // NPCs API
        hierarchy.put("noppes/npcs/api/entity/IDBCPlayer", new HashSet<>());
        hierarchy.put("noppes/npcs/api/entity/IProfileData", new HashSet<>());

        // DBC API
        hierarchy.put("kamkeel/npcdbc/api/aura/IAuraHandler", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/skill/ISkill", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/skill/ISkillHandler", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/outline/IOutlineHandler", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/form/IForm", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/form/IFormAdvanced", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/form/IFormStackable", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/form/IFormMastery", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/form/IFormMasteryLinkData", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/form/IFormDisplay", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/form/IFormHandler", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/event/IDBCEvent", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/event/IDBCEvent$SkillEvent", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/npc/IDBCDisplay", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/npc/IKiWeaponData", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/ability/IDBCAbilityStats", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/effect/IPlayerBonus", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/effect/IBonusHandler", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/util/IDBCSettingsHandler", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlay", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlayContext", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlayScript", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlayChain", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlayScript$Functions", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlay$TextureFunction", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlay$ColorFunction", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlay$RenderFunction", new HashSet<>());
        hierarchy.put("kamkeel/npcdbc/api/client/overlay/IOverlay$Type", new HashSet<>(Arrays.asList("java/lang/Enum")));

        // JRMCore packets
        hierarchy.put("JinRyuu/JRMCore/p/Amh", new HashSet<>());
        hierarchy.put("JinRyuu/JRMCore/p/BAmh", new HashSet<>(Arrays.asList("JinRyuu/JRMCore/p/Amh")));
        hierarchy.put("JinRyuu/JRMCore/p/CAmh", new HashSet<>(Arrays.asList("JinRyuu/JRMCore/p/BAmh")));
        hierarchy.put("JinRyuu/JRMCore/p/SAmh", new HashSet<>(Arrays.asList("JinRyuu/JRMCore/p/Amh")));
        hierarchy.put("JinRyuu/JRMCore/p/PD", new HashSet<>());

        // JRMCore entities
        hierarchy.put("JinRyuu/JRMCore/entity/EntityJRMC", new HashSet<>(Arrays.asList("net/minecraft/entity/EntityCreature")));
        hierarchy.put("JinRyuu/JRMCore/entity/EntityNPC", new HashSet<>(Arrays.asList("net/minecraft/entity/EntityCreature")));
        hierarchy.put("JinRyuu/JRMCore/entity/EntityEnAttacks", new HashSet<>(Arrays.asList("net/minecraft/entity/Entity")));
        hierarchy.put("JinRyuu/JRMCore/entity/EntityKiAttacks", new HashSet<>(Arrays.asList("net/minecraft/entity/Entity")));
        hierarchy.put("JinRyuu/JRMCore/entity/EntityKiAttacksLight", new HashSet<>(Arrays.asList("net/minecraft/entity/Entity")));

        // JRMCore models
        hierarchy.put("JinRyuu/JRMCore/entity/ModelBipedBody", new HashSet<>(Arrays.asList("net/minecraft/client/model/ModelBiped")));

        // JRMCore gui
        hierarchy.put("JinRyuu/JRMCore/JRMCoreGui", new HashSet<>(Arrays.asList("net/minecraft/client/gui/Gui")));
        hierarchy.put("JinRyuu/JRMCore/JRMCoreGuiScreen", new HashSet<>(Arrays.asList("net/minecraft/client/gui/GuiScreen")));
        hierarchy.put("JinRyuu/JRMCore/JRMCoreGuiButtons00", new HashSet<>(Arrays.asList("net/minecraft/client/gui/GuiButton")));
        hierarchy.put("JinRyuu/JRMCore/JRMCoreGuiButtons01", new HashSet<>(Arrays.asList("net/minecraft/client/gui/GuiButton")));

        // Write hierarchy to file
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("hierarchy.bin"))) {
            oos.writeObject(hierarchy);
        }
        System.out.println("Minimal hierarchy written to hierarchy.bin (" + hierarchy.size() + " classes)");
    }
}