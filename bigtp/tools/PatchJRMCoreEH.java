import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.jar.*;
import java.util.zip.ZipEntry;

public class PatchJRMCoreEH {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchJRMCoreEH <input.jar> <output.jar>");
            System.exit(1);
        }
        
        File inJar = new File(args[0]);
        File outJar = new File(args[1]);
        
        // Always write to a temp file first, then copy to output
        File tempOut = File.createTempFile("jrmcore-eh-", ".jar");
        
        JarFile jar = new JarFile(inJar);
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempOut));
        
        for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
            JarEntry entry = e.nextElement();
            InputStream is = jar.getInputStream(entry);
            
            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                ClassReader cr = new ClassReader(is);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);
                
                if ("JinRyuu/JRMCore/JRMCoreEH".equals(cn.name)) {
                    System.out.println("Patching JRMCoreEH...");
                    patchJRMCoreEH(cn);
                }
                
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                byte[] bytes = cw.toByteArray();
                
                JarEntry newEntry = new JarEntry(entry.getName());
                jos.putNextEntry(newEntry);
                jos.write(bytes);
                jos.closeEntry();
            } else {
                jos.putNextEntry(entry);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    jos.write(buffer, 0, len);
                }
                jos.closeEntry();
            }
            is.close();
        }
        
        jar.close();
        jos.close();
        
        // Copy temp file to output
        Files.copy(tempOut.toPath(), outJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        tempOut.delete();
        System.out.println("Done.");
    }
    
    private static void patchJRMCoreEH(ClassNode cn) {
        // 1. Patch login handler (UJqX2K) to add JRMCorePTpSync send
        for (MethodNode mn : cn.methods) {
            if ("UJqX2K".equals(mn.name) && mn.desc.contains("PlayerLoggedInEvent")) {
                patchLoginHandler(mn, cn);
            }
            if ("dJ7QxA".equals(mn.name) && mn.desc.contains("PlayerEvent$Clone")) {
                patchRespawnHandler(mn, cn);
            }
        }
        
        // 2. Add dimension change handler method
        addDimensionChangeHandler(cn);
    }
    
    private static void patchLoginHandler(MethodNode mn, ClassNode cn) {
        // Find the PD.sendTo call for SyncPlayerPropsMessage (opcode 184 invokestatic)
        // Then insert JRMCorePTpSync send after it
        
        InsnList toInsert = new InsnList();
        
        // We need to get the player (EntityPlayerMP) and call TpUtil.syncToClient(player)
        // At the point after PD.sendTo, the stack has the player in local variable 1 (event)
        // We need to reload the player from the event
        
        // aload_1 (event)
        toInsert.add(new VarInsnNode(Opcodes.ALOAD, 1));
        // getfield player
        toInsert.add(new FieldInsnNode(Opcodes.GETFIELD, 
            "cpw/mods/fml/common/gameevent/PlayerEvent$PlayerLoggedInEvent", 
            "player", "Lnet/minecraft/entity/player/EntityPlayer;"));
        // checkcast EntityPlayerMP
        toInsert.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/entity/player/EntityPlayerMP"));
        // invokestatic TpUtil.syncToClient
        toInsert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "JinRyuu/JRMCore/TpUtil", "syncToClient",
            "(Lnet/minecraft/entity/player/EntityPlayer;)V", false));
        
        // Find the PD.sendTo call (invokestatic PD.sendTo)
        for (int i = 0; i < mn.instructions.size(); i++) {
            AbstractInsnNode insn = mn.instructions.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if ("sendTo".equals(mi.name) && "JinRyuu/JRMCore/p/PD".equals(mi.owner)) {
                    // Insert after this instruction
                    mn.instructions.insert(insn, toInsert);
                    System.out.println("  Patched login handler at index " + i);
                    break;
                }
            }
        }
    }
    
    private static void patchRespawnHandler(MethodNode mn, ClassNode cn) {
        // Find the ExtendedPlayer.copy call (invokevirtual copy)
        // Then insert JRMCorePTpSync send after it
        
        InsnList toInsert = new InsnList();
        
        // aload_1 (event)
        toInsert.add(new VarInsnNode(Opcodes.ALOAD, 1));
        // getfield entityPlayer
        toInsert.add(new FieldInsnNode(Opcodes.GETFIELD,
            "net/minecraftforge/event/entity/player/PlayerEvent$Clone",
            "entityPlayer", "Lnet/minecraft/entity/player/EntityPlayer;"));
        // checkcast EntityPlayerMP
        toInsert.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/entity/player/EntityPlayerMP"));
        // invokestatic TpUtil.syncToClient
        toInsert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "JinRyuu/JRMCore/TpUtil", "syncToClient",
            "(Lnet/minecraft/entity/player/EntityPlayer;)V", false));
        
        for (int i = 0; i < mn.instructions.size(); i++) {
            AbstractInsnNode insn = mn.instructions.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if ("copy".equals(mi.name) && "JinRyuu/JRMCore/i/ExtendedPlayer".equals(mi.owner)) {
                    mn.instructions.insert(insn, toInsert);
                    System.out.println("  Patched respawn handler at index " + i);
                    break;
                }
            }
        }
    }
    
    private static void addDimensionChangeHandler(ClassNode cn) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, 
            "onPlayerChangedDimension", 
            "(Lnet/minecraftforge/event/entity/player/PlayerEvent$PlayerChangedDimensionEvent;)V", 
            null, null);
        
        mn.visitCode();
        
        // aload_1 (event)
        mn.visitVarInsn(Opcodes.ALOAD, 1);
        // getfield player
        mn.visitFieldInsn(Opcodes.GETFIELD,
            "net/minecraftforge/event/entity/player/PlayerEvent$PlayerChangedDimensionEvent",
            "player", "Lnet/minecraft/entity/player/EntityPlayer;");
        // checkcast EntityPlayerMP
        mn.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/entity/player/EntityPlayerMP");
        // invokestatic TpUtil.syncToClient
        mn.visitMethodInsn(Opcodes.INVOKESTATIC,
            "JinRyuu/JRMCore/TpUtil", "syncToClient",
            "(Lnet/minecraft/entity/player/EntityPlayer;)V", false);
        
        mn.visitInsn(Opcodes.RETURN);
        mn.visitMaxs(2, 2);
        mn.visitEnd();
        
        cn.methods.add(mn);
        System.out.println("  Added dimension change handler");
    }
}