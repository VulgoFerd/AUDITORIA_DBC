import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RemapTpNbt {
    public static void main(String[] args) throws Exception {
        File inputDir = new File(args[0]);
        File outputDir = new File(args[1]);
        
        File tpNbtClass = new File(inputDir, "JinRyuu/JRMCore/TpNbt.class");
        if (!tpNbtClass.exists()) {
            System.err.println("TpNbt.class not found at " + tpNbtClass);
            System.exit(1);
        }
        
        // Read the class
        ClassReader cr = new ClassReader(Files.readAllBytes(tpNbtClass.toPath()));
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        
        // Remap method calls
        Map<String, String> methodRemap = new HashMap<>();
        methodRemap.put("net/minecraft/nbt/NBTTagCompound.getTag(Ljava/lang/String;)Lnet/minecraft/nbt/NBTBase;", "func_74781_a");
        methodRemap.put("net/minecraft/nbt/NBTTagCompound.getInteger(Ljava/lang/String;)I", "func_74762_e");
        methodRemap.put("net/minecraft/nbt/NBTTagCompound.getLong(Ljava/lang/String;)J", "func_74763_f");
        methodRemap.put("net/minecraft/nbt/NBTTagCompound.setInteger(Ljava/lang/String;I)V", "func_74768_a");
        methodRemap.put("net/minecraft/nbt/NBTTagCompound.setLong(Ljava/lang/String;J)V", "func_74772_a");
        methodRemap.put("net/minecraft/nbt/NBTBase.getId()B", "func_74732_a");
        
        for (MethodNode mn : cn.methods) {
            InsnList insns = mn.instructions;
            for (AbstractInsnNode ain : insns) {
                if (ain instanceof MethodInsnNode) {
                    MethodInsnNode min = (MethodInsnNode) ain;
                    String key = min.owner + "." + min.name + min.desc;
                    String newName = methodRemap.get(key);
                    if (newName != null) {
                        System.out.println("Remapping: " + key + " -> " + newName);
                        min.name = newName;
                    }
                }
            }
        }
        
        // Write the remapped class
        File outFile = new File(outputDir, "JinRyuu/JRMCore/TpNbt.class");
        outFile.getParentFile().mkdirs();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        Files.write(outFile.toPath(), cw.toByteArray());
        
        System.out.println("Remapped TpNbt.class written to " + outFile);
    }
}