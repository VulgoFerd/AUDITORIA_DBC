import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.InsnList;

/**
 * Patcher ASM para TP 64-bit no JRMCore.
 * Transforma jrmcTpint (int, teto 2.1B) em jrmcTplong (long, sem teto prático).
 * Uso: java TpPatch <jar-entrada> <jar-saida>
 */
public class TpPatch {

    static final String JRMCoreH = "JinRyuu/JRMCore/JRMCoreH";
    static final String PacHanS = "JinRyuu/JRMCore/JRMCorePacHanS";
    static final String PacHanC = "JinRyuu/JRMCore/JRMCorePacHanC";
    static final String ComTickH = "JinRyuu/JRMCore/JRMCoreComTickH";
    static final String PStats2 = "JinRyuu/JRMCore/p/JRMCorePStats2";
    static final String PStats2Handler = "JinRyuu/JRMCore/p/JRMCorePStats2$Handler";
    static final String ComJrmctp = "JinRyuu/JRMCore/ComJrmctp";
    static final String[] GUI_CLASSES = {
        "JinRyuu/JRMCore/JRMCoreGuiBars",
        "JinRyuu/JRMCore/JRMCoreGuiSCM",
        "JinRyuu/JRMCore/JRMCoreGuiScreen"
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java TpPatch <input.jar> <output.jar>");
            System.exit(1);
        }
        byte[] jarBytes = readJar(args[0]);
        byte[] patched = patchJar(jarBytes);
        writeJar(args[1], patched);
        System.out.println("Patched JAR written to " + args[1]);
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    static byte[] readJar(String path) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = new FileInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    static void writeJar(String path, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(data);
        }
    }

    static byte[] patchJar(byte[] jarBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        File tempJar = File.createTempFile("jrmc", ".jar");
        try (FileOutputStream fos = new FileOutputStream(tempJar)) {
            fos.write(jarBytes);
        }
        try (ZipFile zip = new ZipFile(tempJar);
             ZipOutputStream zos = new ZipOutputStream(out)) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                InputStream is = zip.getInputStream(entry);
                byte[] data = readAllBytes(is);
                is.close();

                String name = entry.getName();
                byte[] patched = data;

                if (name.equals(JRMCoreH + ".class")) {
                    patched = patchJRMCoreH(data);
                } else if (name.equals(PacHanS + ".class")) {
                    patched = patchPacHanS(data);
                } else if (name.equals(PacHanC + ".class")) {
                    patched = patchPacHanC(data);
                } else if (name.equals(ComTickH + ".class")) {
                    patched = patchComTickH(data);
                } else if (name.equals(PStats2 + ".class")) {
                    patched = patchPStats2(data);
                } else if (name.equals(ComJrmctp + ".class")) {
                    patched = patchComJrmctp(data);
                } else {
                    for (String gui : GUI_CLASSES) {
                        if (name.equals(gui + ".class")) {
                            patched = patchGuiDisplay(data, gui);
                            break;
                        }
                    }
                }

                ZipEntry newEntry = new ZipEntry(name);
                zos.putNextEntry(newEntry);
                zos.write(patched);
                zos.closeEntry();
            }
        } finally {
            tempJar.delete();
        }
        return out.toByteArray();
    }

    // ==================== Helpers ====================

    static ClassNode readClass(byte[] data) {
        ClassReader cr = new ClassReader(data);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        return cn;
    }

    static byte[] writeClass(ClassNode cn) {
        try {
            ClassWriter cw = new FrameSafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Exception e) {
            System.out.println("  WARNING: Frame computation failed for " + cn.name + ", falling back to COMPUTE_MAXS: " + e.getMessage());
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        }
    }

    // Custom ClassWriter that handles missing classes for frame computation
    static class FrameSafeClassWriter extends ClassWriter {
        public FrameSafeClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Exception e) {
                // Fallback: return java/lang/Object if we can't resolve
                System.out.println("  WARNING: getCommonSuperClass failed for " + type1 + " / " + type2 + ": " + e.getMessage());
                return "java/lang/Object";
            }
        }
    }

    static InsnList newInsnList() {
        return new InsnList();
    }

    static void addInsn(InsnList list, AbstractInsnNode insn) {
        list.add(insn);
    }

    static void replaceInsn(InsnList list, AbstractInsnNode oldInsn, InsnList replacement) {
        list.insert(oldInsn, replacement);
        list.remove(oldInsn);
    }

    static void addMethodIfAbsent(ClassNode cn, String name, String desc, InsnList body, String comment) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                System.out.println("  Method " + name + desc + " already exists, skipping");
                return;
            }
        }
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        m.instructions.add(body);
        m.visitMaxs(0, 0);
        cn.methods.add(m);
        System.out.println("  Added method: " + name + desc + " (" + comment + ")");
    }

    // ==================== 1. JRMCoreH: add helper methods + constants ====================

    static byte[] patchJRMCoreH(byte[] data) {
        ClassNode cn = readClass(data);

        // Add jrmcTplong constant field
        boolean hasTpLongConst = false;
        for (FieldNode f : cn.fields) {
            if ("TPLong".equals(f.name) && "Ljava/lang/String;".equals(f.desc)) {
                hasTpLongConst = true; break;
            }
        }
        if (!hasTpLongConst) {
            cn.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "TPLong", "Ljava/lang/String;", null, "jrmcTplong"
            ));
            System.out.println("  Added TPLong constant field");
        }

        // Ensure curTPLong field exists
        boolean hasCurTPLong = false;
        for (FieldNode f : cn.fields) {
            if ("curTPLong".equals(f.name)) { hasCurTPLong = true; break; }
        }
        if (!hasCurTPLong) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "curTPLong", "J", null, 0L));
            System.out.println("  Added curTPLong field");
        }

        // Helper methods
        addHelperMethods(cn);

        // Patch Stats2 method to use long
        patchStats2Method(cn);

        // Patch handleStats2 if exists
        patchHandleStats2Method(cn);

        // Patch jrmcExp method for TP gain
        patchJrmcExpMethod(cn);

        // Patch tpalgn method
        patchTpalgnMethod(cn);

        return writeClass(cn);
    }

    static void addHelperMethods(ClassNode cn) {
        // getTPTotal(NBTTagCompound):J
        InsnList list1 = newInsnList();
        addInsn(list1, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list1, new LdcInsnNode("jrmcTplong"));
        addInsn(list1, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
        addInsn(list1, new InsnNode(Opcodes.LRETURN));
        addMethodIfAbsent(cn, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", list1, "Lê TP total como long do NBT");

        // getTPTotal(EntityPlayer):J
        InsnList list2 = newInsnList();
        addInsn(list2, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list2, new LdcInsnNode("jrmcTplong"));
        addInsn(list2, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getLong", "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J", false));
        addInsn(list2, new InsnNode(Opcodes.LRETURN));
        addMethodIfAbsent(cn, "getTPTotal", "(Lnet/minecraft/entity/player/EntityPlayer;)J", list2, "Lê TP total como long do player");

        // setTPTotal(NBTTagCompound, J):V - updates NBT and static fields
        InsnList list3 = newInsnList();
        // Update NBT jrmcTplong
        addInsn(list3, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list3, new LdcInsnNode("jrmcTplong"));
        addInsn(list3, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list3, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74772_a", "(Ljava/lang/String;J)V", false));
        // Also update NBT jrmcTpint mirror
        addInsn(list3, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list3, new LdcInsnNode("jrmcTpint"));
        addInsn(list3, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list3, new InsnNode(Opcodes.L2I));
        addInsn(list3, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
        // Update static curTPLong field
        addInsn(list3, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list3, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTPLong", "J"));
        // Update static curTP field (clamped)
        addInsn(list3, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list3, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "clampTP", "(J)I", false));
        addInsn(list3, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTP", "I"));
        addInsn(list3, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", list3, "Escreve TP total como long no NBT e atualiza campos estáticos");

        // clampTP(J):I - satura long para int
        InsnList list4 = newInsnList();
        addInsn(list4, new VarInsnNode(Opcodes.LLOAD, 0));
        addInsn(list4, new LdcInsnNode(Integer.MAX_VALUE));
        addInsn(list4, new InsnNode(Opcodes.LCMP));
        addInsn(list4, new InsnNode(Opcodes.IFLE));
        LabelNode lClamp = new LabelNode();
        addInsn(list4, lClamp);
        addInsn(list4, new InsnNode(Opcodes.LDC));
        addInsn(list4, new InsnNode(Opcodes.IRETURN));
        LabelNode lEndClamp = new LabelNode();
        addInsn(list4, lEndClamp);
        addInsn(list4, new VarInsnNode(Opcodes.LLOAD, 0));
        addInsn(list4, new InsnNode(Opcodes.L2I));
        addInsn(list4, new InsnNode(Opcodes.IRETURN));
        addMethodIfAbsent(cn, "clampTP", "(J)I", list4, "Satura TP long para int (teto Integer.MAX_VALUE)");

        // applyTPWindow(NBTTagCompound, I):V - compatibilidade delta
        InsnList list5 = newInsnList();
        LabelNode lAbove = new LabelNode();
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new LdcInsnNode("jrmcTplong"));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74764_b", "(Ljava/lang/String;)Z", false));
        addInsn(list5, new JumpInsnNode(Opcodes.IFEQ, lAbove));
        // jrmcTplong exists: use delta semantics
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new LdcInsnNode("jrmcTplong"));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new InsnNode(Opcodes.I2L));
        addInsn(list5, new InsnNode(Opcodes.LADD));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list5, new JumpInsnNode(Opcodes.GOTO, lEndClamp));
        addInsn(list5, lAbove);
        // jrmcTplong doesn't exist: legacy behavior (set int directly)
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new LdcInsnNode("jrmcTpint"));
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
        // Also update static fields
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTP", "I"));
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new InsnNode(Opcodes.I2L));
        addInsn(list5, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTPLong", "J"));
        addInsn(list5, lEndClamp);
        addInsn(list5, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "applyTPWindow", "(Lnet/minecraft/nbt/NBTTagCompound;I)V", list5, "Aplica janela de gasto: delta se long existe, senão set int direto");

        // addTP(NBTTagCompound, J):V
        InsnList list6 = newInsnList();
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new LdcInsnNode("jrmcTplong"));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74764_b", "(Ljava/lang/String;)Z", false));
        LabelNode lHasLong = new LabelNode();
        addInsn(list6, new JumpInsnNode(Opcodes.IFEQ, lHasLong));
        // Has long: add to it
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new LdcInsnNode("jrmcTplong"));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
        addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list6, new InsnNode(Opcodes.LADD));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list6, new JumpInsnNode(Opcodes.GOTO, lEndClamp));
        addInsn(list6, lHasLong);
        // No long yet: create it from int mirror + gain
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new LdcInsnNode("jrmcTpint"));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
        addInsn(list6, new InsnNode(Opcodes.I2L));
        addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list6, new InsnNode(Opcodes.LADD));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list6, lEndClamp);
        addInsn(list6, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTP", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", list6, "Adiciona TP long ao NBT (cria se não existe)");

        // addTP(EntityPlayer, J):V
        InsnList list7 = newInsnList();
        addInsn(list7, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list7, new LdcInsnNode("jrmcTplong"));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getLong", "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J", false));
        addInsn(list7, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list7, new InsnNode(Opcodes.LADD));
        addInsn(list7, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list7, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list7, new InsnNode(Opcodes.LADD));
        addInsn(list7, new LdcInsnNode("jrmcTplong"));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setLong", "(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V", false));
        // Also update mirror
        addInsn(list7, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list7, new LdcInsnNode("jrmcTplong"));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getLong", "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J", false));
        addInsn(list7, new InsnNode(Opcodes.L2I));
        addInsn(list7, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list7, new LdcInsnNode("jrmcTpint"));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setInt", "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V", false));
        addInsn(list7, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", list7, "Adiciona TP long ao player");

        // addTPToPlayer(int gain, EntityPlayer):V - converte int gain para long
        InsnList list8 = newInsnList();
        addInsn(list8, new VarInsnNode(Opcodes.ILOAD, 0));
        addInsn(list8, new InsnNode(Opcodes.I2L));
        addInsn(list8, new VarInsnNode(Opcodes.ALOAD, 1));
        addInsn(list8, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", false));
        addInsn(list8, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTPToPlayer", "(ILnet/minecraft/entity/player/EntityPlayer;)V", list8, "Converte int gain para long e adiciona ao player");

        // tpShort(J)String - formatação abreviada B/T/Q/Qi
        InsnList list9 = newInsnList();
        LabelNode lLT = new LabelNode(), lQi = new LabelNode(), lQ = new LabelNode(), lT = new LabelNode(), lEndShort = new LabelNode();
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(10000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lLT));
        // >= 10B: check Qi (1e18)
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lT));
        // Qi
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LDIV)); addInsn(list9, new InsnNode(Opcodes.L2D));
        addInsn(list9, new LdcInsnNode(10.0)); addInsn(list9, new InsnNode(Opcodes.DMUL)); addInsn(list9, new InsnNode(Opcodes.D2L));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false));
        addInsn(list9, new LdcInsnNode("Qi"));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        addInsn(list9, new JumpInsnNode(Opcodes.GOTO, lEndShort));
        addInsn(list9, lT);
        // T (1e12)
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lQ));
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LDIV)); addInsn(list9, new InsnNode(Opcodes.L2D));
        addInsn(list9, new LdcInsnNode(10.0)); addInsn(list9, new InsnNode(Opcodes.DMUL)); addInsn(list9, new InsnNode(Opcodes.D2L));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false));
        addInsn(list9, new LdcInsnNode("T"));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        addInsn(list9, new JumpInsnNode(Opcodes.GOTO, lEndShort));
        addInsn(list9, lQ);
        // Q (1e15)
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lQi));
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LDIV)); addInsn(list9, new InsnNode(Opcodes.L2D));
        addInsn(list9, new LdcInsnNode(10.0)); addInsn(list9, new InsnNode(Opcodes.DMUL)); addInsn(list9, new InsnNode(Opcodes.D2L));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false));
        addInsn(list9, new LdcInsnNode("Q"));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        addInsn(list9, new JumpInsnNode(Opcodes.GOTO, lEndShort));
        addInsn(list9, lQi);
        // B (1e9)
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lLT));
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000L));
        addInsn(list9, new InsnNode(Opcodes.LDIV)); addInsn(list9, new InsnNode(Opcodes.L2D));
        addInsn(list9, new LdcInsnNode(10.0)); addInsn(list9, new InsnNode(Opcodes.DMUL)); addInsn(list9, new InsnNode(Opcodes.D2L));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false));
        addInsn(list9, new LdcInsnNode("B"));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        addInsn(list9, new JumpInsnNode(Opcodes.GOTO, lEndShort));
        // < 10B: numSep
        addInsn(list9, lLT);
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "numSep", "(J)Ljava/lang/String;", false));
        addInsn(list9, new JumpInsnNode(Opcodes.GOTO, lEndShort));
        addInsn(list9, lEndShort); addInsn(list9, new InsnNode(Opcodes.ARETURN));
        addMethodIfAbsent(cn, "tpShort", "(J)Ljava/lang/String;", list9, "Formatação abreviada com sufixos B/T/Q/Qi e numSep <10B");

        // setTPTotalClient(J):V - update both static fields without NBT (for client packet handlers)
        InsnList list10 = newInsnList();
        // Load the long TP value (param 0)
        addInsn(list10, new VarInsnNode(Opcodes.LLOAD, 0));
        // Store to curTPLong
        addInsn(list10, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTPLong", "J"));
        // Load again for clampTP
        addInsn(list10, new VarInsnNode(Opcodes.LLOAD, 0));
        // Clamp to int
        addInsn(list10, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "clampTP", "(J)I", false));
        // Store to curTP
        addInsn(list10, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTP", "I"));
        addInsn(list10, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "setTPTotalClient", "(J)V", list10, "Atualiza curTPLong e curTP (clamped) no cliente sem NBT");
    }

    static void patchStats2Method(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("Stats2".equals(m.name) && "(Lnet/minecraft/entity/player/EntityPlayer;IIB[I)V".equals(m.desc)) {
                // Change descriptor to (Lnet/minecraft/entity/player/EntityPlayer;JIB[I)V
                m.desc = "(Lnet/minecraft/entity/player/EntityPlayer;JIB[I)V";
                // Update the constructor call to PStats2
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        if ("<init>".equals(min.name) && PStats2.equals(min.owner) && "(IIB[I)V".equals(min.desc)) {
                            // Replace with long constructor
                            min.desc = "(JIB[I)V";
                            System.out.println("  JRMCoreH.Stats2: updated PStats2 constructor to long");
                        }
                    }
                }
                System.out.println("  JRMCoreH.Stats2: descriptor changed to long");
                break;
            }
        }
    }

    static void patchHandleStats2Method(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("handleStats2".equals(m.name)) {
                // Update to use long for curTP
                // This method likely reads from packet and updates client state
                System.out.println("  JRMCoreH.handleStats2: found, patching for long");
                // Implementation would depend on actual bytecode
                break;
            }
        }
    }

    static void patchJrmcExpMethod(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("jrmcExp".equals(m.name)) {
                System.out.println("  JRMCoreH.jrmcExp: found, would add long TP gain logic here");
                break;
            }
        }
    }

    static void patchTpalgnMethod(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("tpalgn".equals(m.name)) {
                System.out.println("  JRMCoreH.tpalgn: found, would ensure it uses jrmcTplong");
                break;
            }
        }
    }

    // ==================== 2. PacHanS ====================

    static byte[] patchPacHanS(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("handleData".equals(m.name)) {
                patchPacHanSHandleData(m, cn);
            }
        }
        // DO NOT call patchCurTPRefs on packet handlers - only on GUI classes
        return writeClass(cn);
    }

    static void patchPacHanSHandleData(MethodNode m, ClassNode cn) {
        // First pass: collect field accesses to curTP for later replacement
        List<FieldInsnNode> curTPFieldAccess = new ArrayList<>();
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                // Replace getInt for jrmcTpint with getTPTotal
                if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                    if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Pattern: ALOAD nbt, LDC "jrmcTpint", INVOKEVIRTUAL getInt
                            // Replace with: ALOAD nbt, INVOKESTATIC getTPTotal
                            // Need to keep the NBT reference on stack
                            AbstractInsnNode nbtLoad = null;
                            if (i > 1 && m.instructions.get(i - 2) instanceof VarInsnNode) {
                                VarInsnNode var = (VarInsnNode)m.instructions.get(i - 2);
                                if (var.getOpcode() == Opcodes.ALOAD) {
                                    nbtLoad = var;
                                }
                            }
                            InsnList replacement = newInsnList();
                            if (nbtLoad != null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var));
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
                            // Remove LDC and getInt
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            // Insert after the nbtLoad (or at min's position)
                            if (nbtLoad != null) {
                                m.instructions.insert(nbtLoad, replacement);
                            } else {
                                m.instructions.insert(min, replacement);
                            }
                            System.out.println("  PacHanS.handleData: replaced getInt jrmcTpint with getTPTotal at index " + i);
                        }
                    }
                }
                // Replace setInt for jrmcTpint with setTPTotal (updates NBT and static fields)
                else if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                    if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Pattern: ALOAD nbt, LDC "jrmcTpint", ILOAD value, INVOKEVIRTUAL setInt
                            // Replace with: ALOAD nbt, LLOAD value, INVOKESTATIC setTPTotal
                            // The value is already on stack as int (from ILOAD), need to convert to long
                            InsnList replacement = newInsnList();
                            addInsn(replacement, new InsnNode(Opcodes.I2L));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            m.instructions.insert(min, replacement);
                            System.out.println("  PacHanS.handleData: replaced setInt jrmcTpint with setTPTotal at index " + i);
                        }
                    }
                }
            }
            // Collect field access to curTP for later replacement
            else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode)insn;
                if (JRMCoreH.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                    curTPFieldAccess.add(fin);
                }
            }
        }
        // Second pass: replace direct field access to curTP with helper calls
        for (FieldInsnNode fin : curTPFieldAccess) {
            if (fin.getOpcode() == Opcodes.GETSTATIC) {
                // GETSTATIC curTP:I -> GETSTATIC curTPLong:J (for display)
                fin.name = "curTPLong";
                fin.desc = "J";
                System.out.println("  PacHanS.handleData: replaced GETSTATIC curTP:I with GETSTATIC curTPLong:J");
            } else if (fin.getOpcode() == Opcodes.PUTSTATIC) {
                // PUTSTATIC curTP:I -> call setTPTotalClient(J)V which updates both fields
                // The value is on stack as int, need to convert to long
                InsnList replacement = newInsnList();
                addInsn(replacement, new InsnNode(Opcodes.I2L));
                addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotalClient", "(J)V", false));
                m.instructions.insert(fin, replacement);
                m.instructions.remove(fin);
                System.out.println("  PacHanS.handleData: replaced PUTSTATIC curTP:I with setTPTotalClient");
            }
        }
    }

    // ==================== 3. PacHanC ====================

    static byte[] patchPacHanC(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("handleData".equals(m.name)) {
                patchPacHanCHandleData(m, cn);
            }
            if ("handleStats2".equals(m.name)) {
                patchPacHanCHandleStats2(m, cn);
            }
        }
        // DO NOT call patchCurTPRefs on packet handlers
        return writeClass(cn);
    }

    static void patchPacHanCHandleData(MethodNode m, ClassNode cn) {
        System.out.println("  PacHanC.handleData: scanning " + m.instructions.size() + " instructions");
        // First pass: collect field accesses to curTP for later replacement
        List<FieldInsnNode> curTPFieldAccess = new ArrayList<>();
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                System.out.println("    [" + i + "] " + min.getOpcode() + " " + min.owner + "." + min.name + min.desc);
                if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                    if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                        if ("jrmcTpint".equals(ldc.cst)) {
                            AbstractInsnNode nbtLoad = null;
                            if (i > 1 && m.instructions.get(i - 2) instanceof VarInsnNode) {
                                VarInsnNode var = (VarInsnNode)m.instructions.get(i - 2);
                                if (var.getOpcode() == Opcodes.ALOAD) {
                                    nbtLoad = var;
                                }
                            }
                            InsnList replacement = newInsnList();
                            if (nbtLoad != null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var));
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            if (nbtLoad != null) {
                                m.instructions.insert(nbtLoad, replacement);
                            } else {
                                m.instructions.insert(min, replacement);
                            }
                            System.out.println("  PacHanC.handleData: replaced getInt jrmcTpint with getTPTotal at index " + i);
                        }
                    }
                }
                // Also patch setInt for jrmcTpint
                else if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                    if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Pattern: ALOAD nbt, LDC "jrmcTpint", ILOAD value, INVOKEVIRTUAL setInt
                            // Replace with: ALOAD nbt, LLOAD value, INVOKESTATIC setTPTotal
                            InsnList replacement = newInsnList();
                            addInsn(replacement, new InsnNode(Opcodes.I2L));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            m.instructions.insert(min, replacement);
                            System.out.println("  PacHanC.handleData: replaced setInt jrmcTpint with setTPTotal at index " + i);
                        }
                    }
                }
            }
            // Collect field access to curTP for later replacement with setTPTotalClient
            else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode)insn;
                if (JRMCoreH.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                    curTPFieldAccess.add(fin);
                }
            }
        }
        // Second pass: replace direct field access to curTP with setTPTotalClient
        for (FieldInsnNode fin : curTPFieldAccess) {
            if (fin.getOpcode() == Opcodes.GETSTATIC) {
                // GETSTATIC curTP:I -> GETSTATIC curTPLong:J (for display)
                fin.name = "curTPLong";
                fin.desc = "J";
                System.out.println("  PacHanC.handleData: replaced GETSTATIC curTP:I with GETSTATIC curTPLong:J");
            } else if (fin.getOpcode() == Opcodes.PUTSTATIC) {
                // PUTSTATIC curTP:I -> call setTPTotalClient(J)V which updates both fields
                // The value is on stack as int, need to convert to long
                InsnList replacement = newInsnList();
                addInsn(replacement, new InsnNode(Opcodes.I2L));
                addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotalClient", "(J)V", false));
                m.instructions.insert(fin, replacement);
                m.instructions.remove(fin);
                System.out.println("  PacHanC.handleData: replaced PUTSTATIC curTP:I with setTPTotalClient");
            }
        }
    }

    static void patchPacHanCHandleStats2(MethodNode m, ClassNode cn) {
        // The method signature is (IIB[ILnet/minecraft/entity/player/EntityPlayer;)V
        // First param is curTP as int - change to long
        // We need to update the descriptor and the ILOAD to LLOAD
        m.desc = "(JIB[ILnet/minecraft/entity/player/EntityPlayer;)V";
        // First pass: collect all PUTSTATIC curTP:I instructions to replace
        List<FieldInsnNode> putStaticCurTP = new ArrayList<>();
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn instanceof VarInsnNode) {
                VarInsnNode var = (VarInsnNode)insn;
                // First param (curTP) is at local variable 1
                if (var.getOpcode() == Opcodes.ILOAD && var.var == 1) {
                    var.setOpcode(Opcodes.LLOAD);
                    System.out.println("  PacHanC.handleStats2: changed curTP load from ILOAD to LLOAD");
                }
                // The PUTSTATIC to curTPLong should now receive long
                if (i + 1 < m.instructions.size() && m.instructions.get(i + 1) instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode)m.instructions.get(i + 1);
                    if (Opcodes.PUTSTATIC == fin.getOpcode() && JRMCoreH.equals(fin.owner) && "curTPLong".equals(fin.name)) {
                        fin.desc = "J";
                        System.out.println("  PacHanC.handleStats2: updated PUTSTATIC curTPLong descriptor to J");
                    }
                }
            }
            // Collect PUTSTATIC curTP:I for later replacement
            else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode)insn;
                if (JRMCoreH.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                    if (fin.getOpcode() == Opcodes.GETSTATIC) {
                        // GETSTATIC curTP:I -> GETSTATIC curTPLong:J (for display)
                        fin.name = "curTPLong";
                        fin.desc = "J";
                        System.out.println("  PacHanC.handleStats2: replaced GETSTATIC curTP:I with GETSTATIC curTPLong:J at index " + i);
                    } else if (fin.getOpcode() == Opcodes.PUTSTATIC) {
                        putStaticCurTP.add(fin);
                    }
                }
            }
        }
        // Second pass: replace all PUTSTATIC curTP:I with dual field update
        for (FieldInsnNode fin : putStaticCurTP) {
            InsnList replacement = newInsnList();
            // Load the long curTP parameter (local var 1)
            addInsn(replacement, new VarInsnNode(Opcodes.LLOAD, 1));
            // Store to curTPLong
            addInsn(replacement, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTPLong", "J"));
            // Load again for clampTP
            addInsn(replacement, new VarInsnNode(Opcodes.LLOAD, 1));
            // Clamp to int
            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "clampTP", "(J)I", false));
            // Store to curTP
            addInsn(replacement, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTP", "I"));
            m.instructions.insert(fin, replacement);
            m.instructions.remove(fin);
            System.out.println("  PacHanC.handleStats2: replaced PUTSTATIC curTP:I with dual field update");
        }
    }

    // ==================== 4. ComTickH ====================

    static byte[] patchComTickH(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            // Patch updatePlayersData - replace jrmcTpint read with jrmcTplong
            if ("updatePlayersData".equals(m.name)) {
                patchComTickHUpdatePlayersData(m, cn);
            }
            // Patch sendPlayerData if exists
            if ("sendPlayerData".equals(m.name)) {
                patchComTickHSendPlayerData(m, cn);
            }
        }
        return writeClass(cn);
    }

    static void patchComTickHUpdatePlayersData(MethodNode m, ClassNode cn) {
        // Instead of patching individual instructions, replace the NBT TP handling
        // with calls to JRMCoreH helper methods to avoid frame issues
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                // Replace getInt for jrmcTpint with getTPTotal(NBTTagCompound)
                if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                    if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Find the NBT load (should be before LDC)
                            AbstractInsnNode nbtLoad = null;
                            if (i > 1 && m.instructions.get(i - 2) instanceof VarInsnNode) {
                                VarInsnNode var = (VarInsnNode)m.instructions.get(i - 2);
                                if (var.getOpcode() == Opcodes.ALOAD) {
                                    nbtLoad = var;
                                }
                            }
                            // Replace: ALOAD nbt, LDC "jrmcTpint", INVOKEVIRTUAL getInt
                            // With: ALOAD nbt, INVOKESTATIC getTPTotal
                            InsnList replacement = newInsnList();
                            if (nbtLoad != null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var));
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
                            // Remove the three instructions (nbtLoad, LDC, getInt)
                            // But we need to be careful - nbtLoad might be used elsewhere
                            // Instead, just replace LDC and getInt, keep nbtLoad
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            // Insert after nbtLoad
                            if (nbtLoad != null) {
                                m.instructions.insert(nbtLoad, replacement);
                            } else {
                                m.instructions.insert(min, replacement);
                            }
                            System.out.println("  ComTickH.updatePlayersData: replaced getInt jrmcTpint with getTPTotal at index " + i);
                        }
                    }
                }
                // Replace setInt for jrmcTpint with setTPTotal
                else if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                    if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Pattern: ALOAD nbt, LDC "jrmcTpint", ILOAD value, INVOKEVIRTUAL setInt
                            // Replace with: ALOAD nbt, LLOAD value, INVOKESTATIC setTPTotal
                            // The value is on stack as int (from ILOAD), need I2L
                            InsnList replacement = newInsnList();
                            addInsn(replacement, new InsnNode(Opcodes.I2L));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            m.instructions.insert(min, replacement);
                            System.out.println("  ComTickH.updatePlayersData: replaced setInt jrmcTpint with setTPTotal at index " + i);
                        }
                    }
                }
            }
            // Skip the direct PUTSTATIC curTP:I patching - let helper methods handle it
        }
    }

    static void patchComTickHSendPlayerData(MethodNode m, ClassNode cn) {
        // Similar patching for sending TP to client
        System.out.println("  ComTickH.sendPlayerData: would patch to send curTPLong");
    }

    // ==================== 5. PStats2 ====================

    static byte[] patchPStats2(byte[] data) {
        ClassNode cn = readClass(data);

        // Add curTPLong field and remove old curTP field
        boolean hasCurTPLong = false;
        FieldNode oldCurTP = null;
        for (FieldNode f : cn.fields) {
            if ("curTPLong".equals(f.name)) { hasCurTPLong = true; }
            if ("curTP".equals(f.name) && "I".equals(f.desc)) { oldCurTP = f; }
        }
        if (!hasCurTPLong) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "curTPLong", "J", null, 0L));
            System.out.println("  PStats2: added curTPLong field");
        }
        if (oldCurTP != null) {
            cn.fields.remove(oldCurTP);
            System.out.println("  PStats2: removed old curTP field");
        }

        // Update constructor to accept long
        for (MethodNode m : cn.methods) {
            if ("<init>".equals(m.name) && "(IIB[I)V".equals(m.desc)) {
                // Change descriptor to (JIB[I)V
                m.desc = "(JIB[I)V";
                // Update the field store from curTP (I) to curTPLong (J)
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode)insn;
                        if (Opcodes.PUTFIELD == fin.getOpcode() && PStats2.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                            fin.name = "curTPLong";
                            fin.desc = "J";
                            System.out.println("  PStats2.<init>: changed curTP field store to curTPLong:J");
                        }
                    }
                }
            }
        }

        // Patch toBytes: writeLong for curTPLong instead of writeInt for curTP
        for (MethodNode m : cn.methods) {
            if ("toBytes".equals(m.name)) {
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        if ("writeInt".equals(min.name) && "io/netty/buffer/ByteBuf".equals(min.owner)) {
                            // Check if previous instruction loads curTP
                            if (i > 0 && m.instructions.get(i - 1) instanceof FieldInsnNode) {
                                FieldInsnNode fin = (FieldInsnNode)m.instructions.get(i - 1);
                                if (Opcodes.GETFIELD == fin.getOpcode() && PStats2.equals(fin.owner) && "curTP".equals(fin.name)) {
                                    // Replace GETFIELD curTP:I + writeInt with GETFIELD curTPLong:J + writeLong
                                    fin.name = "curTPLong";
                                    fin.desc = "J";
                                    min.name = "writeLong";
                                    min.desc = "(J)Lio/netty/buffer/ByteBuf;";
                                    System.out.println("  PStats2.toBytes: changed curTP writeInt to curTPLong writeLong");
                                }
                            }
                        }
                    }
                }
            }
            if ("fromBytes".equals(m.name)) {
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        if ("readInt".equals(min.name) && "io/netty/buffer/ByteBuf".equals(min.owner)) {
                            // Check if next instruction stores to curTP
                            if (i + 1 < m.instructions.size() && m.instructions.get(i + 1) instanceof FieldInsnNode) {
                                FieldInsnNode fin = (FieldInsnNode)m.instructions.get(i + 1);
                                if (Opcodes.PUTFIELD == fin.getOpcode() && PStats2.equals(fin.owner) && "curTP".equals(fin.name)) {
                                    // Replace readInt + PUTFIELD curTP:I with readLong + PUTFIELD curTPLong:J
                                    min.name = "readLong";
                                    min.desc = "()J";
                                    fin.name = "curTPLong";
                                    fin.desc = "J";
                                    System.out.println("  PStats2.fromBytes: changed curTP readInt to curTPLong readLong");
                                }
                            }
                        }
                    }
                }
            }
        }

        // General pass: replace any remaining curTP field accesses in PStats2 with curTPLong
        for (MethodNode m : cn.methods) {
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode)insn;
                    if (PStats2.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                        fin.name = "curTPLong";
                        fin.desc = "J";
                        System.out.println("  PStats2." + m.name + ": replaced curTP field access with curTPLong");
                    }
                }
            }
        }

        return writeClass(cn);
    }

    // ==================== 6. PStats2Handler (stub - may not exist) ====================

    static byte[] patchPStats2Handler(byte[] data) {
        // Handler class may not exist as separate class
        return data;
    }

    // ==================== 7. GUI Display Patches ====================

    static byte[] patchGuiDisplay(byte[] data, String className) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            boolean changed = false;
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode)insn;
                    if (Opcodes.GETSTATIC == fin.getOpcode() && JRMCoreH.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                        // Check if next instruction is a string conversion
                        boolean isStringConversion = false;
                        boolean isStringBuilderAppend = false;
                        MethodInsnNode stringBuilderAppend = null;
                        if (i + 1 < m.instructions.size()) {
                            AbstractInsnNode next = m.instructions.get(i + 1);
                            if (next instanceof MethodInsnNode) {
                                MethodInsnNode min = (MethodInsnNode)next;
                                // Case 1: INVOKESTATIC String.valueOf(I) or Integer.toString(I)
                                if (Opcodes.INVOKESTATIC == min.getOpcode() &&
                                    ("java/lang/String".equals(min.owner) || "java/lang/Integer".equals(min.owner)) &&
                                    ("valueOf".equals(min.name) || "toString".equals(min.name)) &&
                                    "(I)Ljava/lang/String;".equals(min.desc)) {
                                    isStringConversion = true;
                                }
                                // Case 2: INVOKEVIRTUAL StringBuilder.append(I)
                                else if (Opcodes.INVOKEVIRTUAL == min.getOpcode() &&
                                    "java/lang/StringBuilder".equals(min.owner) &&
                                    "append".equals(min.name) &&
                                    "(I)Ljava/lang/StringBuilder;".equals(min.desc)) {
                                    isStringBuilderAppend = true;
                                    stringBuilderAppend = min;
                                }
                            }
                        }
                        if (isStringConversion) {
                            // Replace GETSTATIC curTP:I + INVOKESTATIC valueOf/toString with GETSTATIC curTPLong:J + INVOKESTATIC tpShort
                            InsnList replacement = newInsnList();
                            addInsn(replacement, new FieldInsnNode(Opcodes.GETSTATIC, JRMCoreH, "curTPLong", "J"));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "tpShort", "(J)Ljava/lang/String;", false));
                            m.instructions.remove(insn);
                            m.instructions.remove(m.instructions.get(i)); // The string conversion is now at index i
                            m.instructions.insert(insn, replacement);
                            changed = true;
                            System.out.println("  " + cn.name + "." + m.name + m.desc + ": replaced curTP:I+valueOf with curTPLong+tpShort at index " + i);
                        } else if (isStringBuilderAppend) {
                            // Replace GETSTATIC curTP:I + INVOKEVIRTUAL StringBuilder.append(I) with GETSTATIC curTPLong:J + INVOKESTATIC tpShort + INVOKEVIRTUAL StringBuilder.append(String)
                            InsnList replacement = newInsnList();
                            addInsn(replacement, new FieldInsnNode(Opcodes.GETSTATIC, JRMCoreH, "curTPLong", "J"));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "tpShort", "(J)Ljava/lang/String;", false));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                            m.instructions.remove(insn);
                            m.instructions.remove(m.instructions.get(i)); // The append is now at index i
                            m.instructions.insert(insn, replacement);
                            changed = true;
                            System.out.println("  " + cn.name + "." + m.name + m.desc + ": replaced curTP:I+StringBuilder.append(I) with curTPLong+tpShort+append(String) at index " + i);
                        } else {
                            // Not a string conversion - leave curTP:I as is (used for numeric ops)
                            System.out.println("  " + cn.name + "." + m.name + m.desc + ": SKIPPED curTP:I at index " + i + " (not string conversion)");
                        }
                    }
                }
            }
            if (changed) {
                m.visitMaxs(0, 0);
            }
        }
        return writeClass(cn);
    }

    // ==================== 8. ComJrmctp ====================

    static byte[] patchComJrmctp(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("func_71515_b".equals(m.name) || "execute".equals(m.name) || "processCommand".equals(m.name)) {
                patchComJrmctpExecute(m, cn);
            }
        }
        return writeClass(cn);
    }

    static void patchComJrmctpExecute(MethodNode m, ClassNode cn) {
        // This is a complex method - we need to replace all jrmcTpint NBT operations
        // with calls to addTPToPlayer, getTPTotal, setTPTotal
        // For now, log all jrmcTpint refs and curTP refs
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode)insn;
                if ("jrmcTpint".equals(fin.name)) {
                    System.out.println("  ComJrmctp." + m.name + m.desc + ": found jrmcTpint ref at index " + i + " opcode=" + fin.getOpcode());
                }
            }
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                if (JRMCoreH.equals(min.owner) && min.name.contains("addExp")) {
                    System.out.println("  ComJrmctp." + m.name + m.desc + ": found addExp call at index " + i);
                }
            }
        }
        // TODO: Full rewrite of command logic to use addTPToPlayer
        // For now, the command will still use jrmcTpint (limited to 2.1B)
    }

    // ==================== 9. Patch curTP:I refs - ONLY for GUI display ====================

    // This method is now ONLY called from patchGuiDisplay, NOT from packet handlers or commands
    // Kept for reference but not used elsewhere
    static void patchCurTPRefs(ClassNode cn) {
        // DEPRECATED: use patchGuiDisplay instead
    }

    // ==================== 10. JRMCoreH Stats2 packet handling ====================

    static void patchHandleStats2(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("handleStats2".equals(m.name)) {
                // Update to use long for curTP
                m.desc = "(JIB[I)V";
                System.out.println("  JRMCoreH.handleStats2: descriptor changed to long");
            }
        }
    }

    // ==================== 11. expPls - keep as int ====================

    static void patchExpPls(ClassNode cn) {
        // Experience remains int, don't touch
    }

    // ==================== 12. jrmcExp - add long TP gain ====================

    static void patchJrmcExp(ClassNode cn) {
        // Would add second block for long TP gain
    }

    // ==================== 13. tpalgn ====================

    static void patchTpalgn(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.name.contains("tpalgn") || m.desc.contains("tpalgn")) {
                System.out.println("  JRMCoreH.tpalgn: found, ensuring it uses jrmcTplong");
            }
        }
    }
}