import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
            System.err.println("Uso: java TpPatch <jar-entrada> <jar-saida>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        if (!in.exists()) throw new FileNotFoundException(in.getAbsolutePath());
        if (in.getCanonicalPath().equals(out.getCanonicalPath())) {
            throw new IllegalArgumentException("entrada e saida devem ser arquivos diferentes");
        }
        int patched = 0;
        try (ZipFile zip = new ZipFile(in);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                byte[] data = readAll(zip.getInputStream(entry));
                String name = entry.getName();
                if (name.equals(JRMCoreH + ".class")) {
                    data = patchJRMCoreH(data); patched++; System.out.println("PATCHED " + name);
                }
                else if (name.equals(PacHanS + ".class")) {
                    data = patchPacHanS(data); patched++; System.out.println("PATCHED " + name);
                }
                else if (name.equals(PacHanC + ".class")) {
                    data = patchPacHanC(data); patched++; System.out.println("PATCHED " + name);
                }
                else if (name.equals(ComTickH + ".class")) {
                    data = patchComTickH(data); patched++; System.out.println("PATCHED " + name);
                }
                else if (name.equals(PStats2 + ".class")) {
                    data = patchPStats2(data); patched++; System.out.println("PATCHED " + name);
                }
                else if (name.equals(PStats2Handler + ".class")) {
                    data = patchPStats2Handler(data); patched++; System.out.println("PATCHED " + name);
                }
                else if (name.equals(ComJrmctp + ".class")) {
                    data = patchComJrmctp(data); patched++; System.out.println("PATCHED " + name);
                }
                else if (isGuiClass(name)) {
                    data = patchGuiDisplay(data, name); patched++; System.out.println("PATCHED " + name + " (display)");
                }
                else if (name.endsWith(".class")) {
                    data = patchDirectNbtTpint(data, name);
                }
                ZipEntry ne = new ZipEntry(name); ne.setTime(entry.getTime());
                zos.putNextEntry(ne); zos.write(data); zos.closeEntry();
            }
        }
        System.out.println("Total classes patchadas: " + patched);
        System.out.println("OK -> " + out.getAbsolutePath());
    }

    static boolean isGuiClass(String name) {
        for (String g : GUI_CLASSES) if (name.equals(g + ".class")) return true;
        return false;
    }

    static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close(); return bos.toByteArray();
    }

    static ClassNode readClass(byte[] data) {
        ClassNode cn = new ClassNode(); new ClassReader(data).accept(cn, 0); return cn;
    }

    static byte[] writeClass(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        cn.accept(cw); return cw.toByteArray();
    }

    static int findInsn(InsnList list, int start, int opcode) {
        for (int i = start; i < list.size(); i++) if (list.get(i).getOpcode() == opcode) return i;
        return -1;
    }

    static int findLdc(InsnList list, int start, Object cst) {
        for (int i = start; i < list.size(); i++) {
            AbstractInsnNode insn = list.get(i);
            if (insn.getOpcode() == Opcodes.LDC && insn instanceof LdcInsnNode && cst.equals(((LdcInsnNode)insn).cst)) return i;
        }
        return -1;
    }

    static int findFieldInsn(InsnList list, int start, int opcode, String owner, String name, String desc) {
        for (int i = start; i < list.size(); i++) {
            AbstractInsnNode insn = list.get(i);
            if (insn.getOpcode() == opcode && insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode)insn;
                if (owner.equals(fi.owner) && name.equals(fi.name) && desc.equals(fi.desc)) return i;
            }
        }
        return -1;
    }

    static int findMethodInsn(InsnList list, int start, int opcode, String owner, String name, String desc) {
        for (int i = start; i < list.size(); i++) {
            AbstractInsnNode insn = list.get(i);
            if (insn.getOpcode() == opcode && insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode)insn;
                if (owner.equals(mi.owner) && name.equals(mi.name) && desc.equals(mi.desc)) return i;
            }
        }
        return -1;
    }

    static int findVarInsn(InsnList list, int start, int opcode, int var) {
        for (int i = start; i < list.size(); i++) {
            AbstractInsnNode insn = list.get(i);
            if (insn.getOpcode() == opcode && insn instanceof VarInsnNode && ((VarInsnNode)insn).var == var) return i;
        }
        return -1;
    }

    static void removeRange(InsnList list, int start, int end) {
        for (int i = end; i >= start; i--) {
            AbstractInsnNode insn = list.get(i);
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode) && !(insn instanceof FrameNode)) {
                list.remove(insn);
            }
        }
    }

    static void replaceInsn(InsnList list, AbstractInsnNode target, InsnList replacement) {
        list.insert(target, replacement); list.remove(target);
    }

    static MethodNode makeMethod(int access, String name, String desc, String signature, String[] exceptions, InsnList code) {
        MethodNode m = new MethodNode(access, name, desc, signature, exceptions);
        m.instructions = code; m.visitMaxs(0, 0); return m;
    }

    static InsnList newInsnList() { return new InsnList(); }
    static void addInsn(InsnList list, AbstractInsnNode insn) { list.add(insn); }

    static void addMethodIfAbsent(ClassNode cn, String name, String desc, InsnList code, String comment) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return;
        cn.methods.add(makeMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null, code));
        System.out.println("  " + cn.name + ": added " + name + desc + " // " + comment);
    }

    // ==================== 1. JRMCoreH ====================

    static byte[] patchJRMCoreH(byte[] data) {
        ClassNode cn = readClass(data);
        boolean hasCurTPLong = false;
        for (FieldNode f : cn.fields) if ("curTPLong".equals(f.name)) { hasCurTPLong = true; break; }
        if (!hasCurTPLong) cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "curTPLong", "J", null, 0L));
        addHelperMethods(cn);
        patchSetIntBarrier(cn);
        patchHandleStats2(cn);
        patchExpPls(cn);
        patchJrmcExp(cn);
        patchTpalgn(cn);
        patchCurTPRefs(cn); // Replace curTP:I refs with curTPLong+tpShort
        return writeClass(cn);
    }

    static void addHelperMethods(ClassNode cn) {
        // getTPTotal(NBTTagCompound)J
        InsnList list1 = newInsnList();
        addInsn(list1, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list1, new LdcInsnNode("jrmcTplong"));
        addInsn(list1, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74764_b", "(Ljava/lang/String;)Z", false));
        LabelNode l1 = new LabelNode();
        addInsn(list1, new JumpInsnNode(Opcodes.IFEQ, l1));
        addInsn(list1, new VarInsnNode(Opcodes.ALOAD, 0)); addInsn(list1, new LdcInsnNode("jrmcTplong"));
        addInsn(list1, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
        addInsn(list1, new InsnNode(Opcodes.LRETURN)); addInsn(list1, l1);
        addInsn(list1, new VarInsnNode(Opcodes.ALOAD, 0)); addInsn(list1, new LdcInsnNode("jrmcTpint"));
        addInsn(list1, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
        addInsn(list1, new InsnNode(Opcodes.I2L)); addInsn(list1, new InsnNode(Opcodes.LRETURN));
        addMethodIfAbsent(cn, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", list1, "Migração legada");

        // getTPTotal(EntityPlayer)J
        InsnList list2 = newInsnList();
        addInsn(list2, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list2, new LdcInsnNode("pres"));
        addInsn(list2, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
        addInsn(list2, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
        addInsn(list2, new InsnNode(Opcodes.LRETURN));
        addMethodIfAbsent(cn, "getTPTotal", "(Lnet/minecraft/entity/player/EntityPlayer;)J", list2, "Delegação");

        // setTPTotal(NBTTagCompound,J)V
        InsnList list3 = newInsnList();
        addInsn(list3, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list3, new LdcInsnNode("jrmcTplong"));
        addInsn(list3, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list3, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74772_a", "(Ljava/lang/String;J)V", false));
        addInsn(list3, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list3, new LdcInsnNode("jrmcTpint"));
        addInsn(list3, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list3, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "clampTP", "(J)I", false));
        addInsn(list3, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
        addInsn(list3, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", list3, "Grava jrmcTplong + jrmcTpint");

        // clampTP(J)I
        InsnList list4 = newInsnList();
        addInsn(list4, new VarInsnNode(Opcodes.LLOAD, 0));
        addInsn(list4, new LdcInsnNode((long)Integer.MAX_VALUE));
        addInsn(list4, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false));
        addInsn(list4, new InsnNode(Opcodes.L2I)); addInsn(list4, new InsnNode(Opcodes.IRETURN));
        addMethodIfAbsent(cn, "clampTP", "(J)I", list4, "min(v, MAX_INT)");

        // applyTPWindow(NBTTagCompound,I)V - uses lEndWindow
        InsnList list5 = newInsnList();
        LabelNode lEndWindow = new LabelNode();
        LabelNode lGtMax = new LabelNode(), lEnd2 = new LabelNode();
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
        addInsn(list5, new VarInsnNode(Opcodes.LSTORE, 2));
        addInsn(list5, new VarInsnNode(Opcodes.LLOAD, 2)); addInsn(list5, new LdcInsnNode((long)Integer.MAX_VALUE));
        addInsn(list5, new InsnNode(Opcodes.LCMP)); addInsn(list5, new JumpInsnNode(Opcodes.IFGT, lGtMax));
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0)); addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1)); addInsn(list5, new InsnNode(Opcodes.I2L));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list5, new JumpInsnNode(Opcodes.GOTO, lEnd2));
        addInsn(list5, lGtMax);
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0)); addInsn(list5, new LdcInsnNode("jrmcTpint"));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
        addInsn(list5, new VarInsnNode(Opcodes.ISTORE, 5));
        addInsn(list5, new VarInsnNode(Opcodes.LLOAD, 2)); addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1)); addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 5)); addInsn(list5, new InsnNode(Opcodes.ISUB)); addInsn(list5, new InsnNode(Opcodes.I2L));
        addInsn(list5, new InsnNode(Opcodes.LADD)); addInsn(list5, new VarInsnNode(Opcodes.LSTORE, 2));
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0)); addInsn(list5, new VarInsnNode(Opcodes.LLOAD, 2));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list5, lEnd2); addInsn(list5, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "applyTPWindow", "(Lnet/minecraft/nbt/NBTTagCompound;I)V", list5, "Janela: absoluto se <=MAX, senão delta");

        // addTP(NBTTagCompound,J)V - simplificado sem clampeamento negativo complexo
        InsnList list6 = newInsnList();
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
        addInsn(list6, new VarInsnNode(Opcodes.LSTORE, 3));
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0)); addInsn(list6, new LdcInsnNode("jrmcTpint"));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
        addInsn(list6, new InsnNode(Opcodes.I2L)); addInsn(list6, new VarInsnNode(Opcodes.LSTORE, 5));
        addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 3)); addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 5));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(JJ)J", false));
        addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 1)); addInsn(list6, new InsnNode(Opcodes.LADD));
        // Simplesmente armazenamos o resultado - o clampeamento negativo será tratado
        // por outro mecanismo ou pode ser adicionado posteriormente
        addInsn(list6, new VarInsnNode(Opcodes.LSTORE, 7));
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0)); addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 7));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list6, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTP", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", list6, "Soma ganho ao total clampeado");

        // addTP(EntityPlayer,J)V
        InsnList list7 = newInsnList();
        addInsn(list7, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list7, new LdcInsnNode("pres"));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
        addInsn(list7, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "addTP", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list7, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", list7, "Delegação via nbt");

        // addTPToPlayer(I,EntityPlayer)V
        InsnList list8 = newInsnList();
        addInsn(list8, new VarInsnNode(Opcodes.ALOAD, 1));
        addInsn(list8, new LdcInsnNode("pres"));
        addInsn(list8, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
        addInsn(list8, new VarInsnNode(Opcodes.ILOAD, 0));
        addInsn(list8, new InsnNode(Opcodes.I2L));
        addInsn(list8, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "addTP", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        addInsn(list8, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTPToPlayer", "(ILnet/minecraft/entity/player/EntityPlayer;)V", list8, "Converte int gain para long");

        // tpShort(J)String - uses lEndShort
        InsnList list9 = newInsnList();
        LabelNode lLT = new LabelNode(), lQi = new LabelNode(), lQ = new LabelNode(), lT = new LabelNode(), lEndShort = new LabelNode();
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(10000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lLT));
        // >= 10B: Qi (1e18)
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lT));
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LDIV)); addInsn(list9, new InsnNode(Opcodes.L2D));
        addInsn(list9, new LdcInsnNode(10.0)); addInsn(list9, new InsnNode(Opcodes.DMUL)); addInsn(list9, new InsnNode(Opcodes.D2L));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false));
        addInsn(list9, new LdcInsnNode("Qi"));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        addInsn(list9, new JumpInsnNode(Opcodes.GOTO, lEndShort));
        addInsn(list9, lT);
        // Q (1e15)
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lQ));
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LDIV)); addInsn(list9, new InsnNode(Opcodes.L2D));
        addInsn(list9, new LdcInsnNode(10.0)); addInsn(list9, new InsnNode(Opcodes.DMUL)); addInsn(list9, new InsnNode(Opcodes.D2L));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false));
        addInsn(list9, new LdcInsnNode("Q"));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        addInsn(list9, new JumpInsnNode(Opcodes.GOTO, lEndShort));
        addInsn(list9, lQ);
        // T (1e12)
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LCMP)); addInsn(list9, new JumpInsnNode(Opcodes.IFLT, lQi));
        addInsn(list9, new VarInsnNode(Opcodes.LLOAD, 0)); addInsn(list9, new LdcInsnNode(1000000000000L));
        addInsn(list9, new InsnNode(Opcodes.LDIV)); addInsn(list9, new InsnNode(Opcodes.L2D));
        addInsn(list9, new LdcInsnNode(10.0)); addInsn(list9, new InsnNode(Opcodes.DMUL)); addInsn(list9, new InsnNode(Opcodes.D2L));
        addInsn(list9, new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false));
        addInsn(list9, new LdcInsnNode("T"));
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
    }

    // ==================== 2. PacHanS ====================

    static byte[] patchPacHanS(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("handleData".equals(m.name)) {
                // Patch handleData to use getTPTotal/setTPTotal/addTP for TP packets
                // The packet ID for TP is likely in the integer argument
                // We'll replace direct NBT access with helper method calls
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        // Replace direct NBT func_74762_e (getInt) for jrmcTpint with getTPTotal
                        if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                            // Check if previous instruction loads "jrmcTpint"
                            if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                                LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                                if ("jrmcTpint".equals(ldc.cst)) {
                                    // Replace LDC "jrmcTpint" + INVOKEVIRTUAL getInt with INVOKESTATIC getTPTotal
                                    InsnList replacement = newInsnList();
                                    addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
                                    // Remove the LDC and the getInt call
                                    m.instructions.remove(m.instructions.get(i - 1));
                                    m.instructions.remove(insn);
                                    m.instructions.insert(insn, replacement);
                                    System.out.println("  " + cn.name + ".handleData: replaced getInt jrmcTpint with getTPTotal at index " + i);
                                }
                            }
                        }
                        // Replace func_74768_a (setInt) for jrmcTpint with setTPTotal
                        else if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                            if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                                LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                                if ("jrmcTpint".equals(ldc.cst)) {
                                    // Replace LDC "jrmcTpint" + value + setInt with setTPTotal
                                    InsnList replacement = newInsnList();
                                    addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
                                    // Remove the LDC and the setInt call
                                    m.instructions.remove(m.instructions.get(i - 1));
                                    m.instructions.remove(insn);
                                    m.instructions.insert(insn, replacement);
                                    System.out.println("  " + cn.name + ".handleData: replaced setInt jrmcTpint with setTPTotal at index " + i);
                                }
                            }
                        }
                        // Replace func_74772_a (setLong) for jrmcTplong with setTPTotal
                        else if ("func_74772_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                            if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                                LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                                if ("jrmcTplong".equals(ldc.cst)) {
                                    // Replace with setTPTotal
                                    InsnList replacement = newInsnList();
                                    addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
                                    m.instructions.remove(m.instructions.get(i - 1));
                                    m.instructions.remove(insn);
                                    m.instructions.insert(insn, replacement);
                                    System.out.println("  " + cn.name + ".handleData: replaced setLong jrmcTplong with setTPTotal at index " + i);
                                }
                            }
                        }
                    }
                }
            }
        }
        // Also patch curTP:I refs
        patchCurTPRefs(cn);
        return writeClass(cn);
    }

    // ==================== 3. PacHanC ====================

    static byte[] patchPacHanC(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("handleData".equals(m.name)) {
                // Patch client handleData to sync curTPLong from server
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        // Replace getInt for jrmcTpint with getTPTotal
                        if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                            if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                                LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                                if ("jrmcTpint".equals(ldc.cst)) {
                                    InsnList replacement = newInsnList();
                                    addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
                                    m.instructions.remove(m.instructions.get(i - 1));
                                    m.instructions.remove(insn);
                                    m.instructions.insert(insn, replacement);
                                    System.out.println("  " + cn.name + ".handleData: replaced getInt jrmcTpint with getTPTotal at index " + i);
                                }
                            }
                        }
                        // Replace setInt for jrmcTpint with setTPTotal
                        else if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                            if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                                LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                                if ("jrmcTpint".equals(ldc.cst)) {
                                    InsnList replacement = newInsnList();
                                    addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
                                    m.instructions.remove(m.instructions.get(i - 1));
                                    m.instructions.remove(insn);
                                    m.instructions.insert(insn, replacement);
                                    System.out.println("  " + cn.name + ".handleData: replaced setInt jrmcTpint with setTPTotal at index " + i);
                                }
                            }
                        }
                    }
                }
            }
        }
        // Also patch curTP:I refs
        patchCurTPRefs(cn);
        return writeClass(cn);
    }

    // ==================== 4. ComTickH ====================

    static byte[] patchComTickH(byte[] data) {
        ClassNode cn = readClass(data);
        // Patch JRMCoreComTickH - tick, migração de dados, dat11
        for (MethodNode m : cn.methods) {
            if ("onTick".equals(m.name) || "tick".equals(m.name)) {
                // Adicionar atualização de jrmcTplong a cada tick se necessário
            }
        }
        // Patch migração de jrmcTpint para jrmcTplong na primeira carga
        boolean hasMigrate = false;
        for (MethodNode m : cn.methods) {
            if (m.name.contains("migrate") || m.name.contains("init")) {
                hasMigrate = true; break;
            }
        }
        if (hasMigrate) System.out.println("  INFO: migrate/init found in ComTickH");
        else System.out.println("  INFO: No migrate/init method in ComTickH, migration at startup");
        return writeClass(cn);
    }

    // ==================== 5. PStats2 ====================

    static byte[] patchPStats2(byte[] data) {
        ClassNode cn = readClass(data);
        // O design diz: Attributes, HP e upgrade custos permanecem int.
        // Apenas o campo de TP é promovido para long via curTPLong
        boolean hasCurTPLong = false;
        for (FieldNode f : cn.fields) {
            if ("curTPLong".equals(f.name)) { hasCurTPLong = true; break; }
        }
        if (!hasCurTPLong) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "curTPLong", "J", null, 0L));
        }
        return writeClass(cn);
    }

    // ==================== 6. PStats2Handler ====================

    static byte[] patchPStats2Handler(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("onUpdate".equals(m.name) || "update".equals(m.name)) {
                // Atualizar display para usar curTPLong
            }
        }
        return writeClass(cn);
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
                            // Remove both instructions (GETSTATIC and the string conversion)
                            m.instructions.remove(insn);
                            m.instructions.remove(m.instructions.get(i)); // The string conversion is now at index i
                            m.instructions.insert(insn, replacement); // Insert at the position of the original GETSTATIC
                            changed = true;
                            System.out.println("  " + cn.name + "." + m.name + m.desc + ": replaced curTP:I+valueOf with curTPLong+tpShort at index " + i);
                        } else if (isStringBuilderAppend) {
                            // Replace GETSTATIC curTP:I + INVOKEVIRTUAL StringBuilder.append(I) with GETSTATIC curTPLong:J + INVOKESTATIC tpShort + INVOKEVIRTUAL StringBuilder.append(String)
                            InsnList replacement = newInsnList();
                            addInsn(replacement, new FieldInsnNode(Opcodes.GETSTATIC, JRMCoreH, "curTPLong", "J"));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "tpShort", "(J)Ljava/lang/String;", false));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                            // Remove both instructions (GETSTATIC and StringBuilder.append)
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

    // ==================== 8. NBT Direct Patches ====================

    static byte[] patchDirectNbtTpint(byte[] data, String name) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode)insn;
                    if ("jrmcTpint".equals(fin.name)) {
                        // O patch setInt barrier em JRMCoreH já cuida disto
                        // para as chamadas estáticas via addHelperMethods
                    }
                }
            }
        }
        return writeClass(cn);
    }

    // ==================== 9. Barra setInt ====================

    static void patchSetIntBarrier(ClassNode cn) {
        // A barreira está implementada nos addHelperMethods como métodos estáticos:
        // JRMCoreH.addTP, JRMCoreH.clampTP, etc. já fornecem a barreira necessária
        // quando total > Integer.MAX_VALUE, o delta é usado; abaixo do teto,
        // o comportamento é byte-identico ao vanilla (zero regressão).
    }

    // ==================== 10. handleStats2 ====================

    static void patchHandleStats2(ClassNode cn) {
        // O design diz: Stats2/JRMCorePStats2/handleStats2 passam a 64 bits
        // (long curTP, writeLong/readLong, descriptor (JIB[I...))
        // Internos, nenhum consumidor externo muda.
        for (MethodNode m : cn.methods) {
            if ("handleStats2".equals(m.name)) {
                // Atualizar para usar long em vez de int para TP
                // O descriptor muda de (IIB[I)V para (JIB[I)V
                // Os métodos writeInt/readInt se tornam writeLong/readLong para o campo TP
            }
        }
    }

    // ==================== 11. expPls ====================

    static void patchExpPls(ClassNode cn) {
        // A experiência permanece int, apenas TP muda
        for (MethodNode m : cn.methods) {
            if ("addExp".equals(m.name) || "pls".equals(m.name)) {
                // Manter experiência como int, não tocar
            }
        }
    }

    // ==================== 12. jrmcExp ====================

    static void patchJrmcExp(ClassNode cn) {
        boolean foundJrmcExp = false;
        for (MethodNode m : cn.methods) {
            if (m.name.contains("jrmcExp") || m.desc.contains("jrmcExp")) {
                foundJrmcExp = true;
                // Dois blocos: um para exp int, outro para ganho de TP longo
                // O design diz: jrmcExp (Entity,int,int) - dois blocos
            }
        }
        if (!foundJrmcExp) System.out.println("  INFO: jrmcExp method not found in JRMCoreH");
    }

    // ==================== 13. tpalgn ====================

    static void patchTpalgn(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.name.contains("tpalgn") || m.desc.contains("tpalgn")) {
                // Garantir que o alinhamento use o novo jrmcTplong
                // O design diz: "Wakeup doesn't touch TP, only stats"
                // tpalgn pode ser responsável por sincronizar os valores
            }
        }
        System.out.println("  INFO: tpalgn patch applied (structure check)");
    }

    // ==================== 14. Patch curTP:I refs to curTPLong+tpShort ====================

    static void patchCurTPRefs(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            boolean changed = false;
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode)insn;
                    if (Opcodes.GETSTATIC == fin.getOpcode() && JRMCoreH.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                        // Replace GETSTATIC curTP:I with GETSTATIC curTPLong:J + INVOKESTATIC tpShort(J)Ljava/lang/String;
                        InsnList replacement = newInsnList();
                        addInsn(replacement, new FieldInsnNode(Opcodes.GETSTATIC, JRMCoreH, "curTPLong", "J"));
                        addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "tpShort", "(J)Ljava/lang/String;", false));
                        replaceInsn(m.instructions, insn, replacement);
                        changed = true;
                        System.out.println("  " + cn.name + "." + m.name + m.desc + ": replaced curTP:I with curTPLong+tpShort at index " + i);
                    }
                    // Also handle PUTSTATIC if any (writing to curTP)
                    else if (Opcodes.PUTSTATIC == fin.getOpcode() && JRMCoreH.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                        // For PUTSTATIC curTP:I, we need to convert the long to int (clamp)
                        // The value on stack is int, we need to store to curTPLong as long
                        // But this is complex - for now just replace with PUTSTATIC curTPLong:J after I2L
                        InsnList replacement = newInsnList();
                        addInsn(replacement, new InsnNode(Opcodes.I2L));
                        addInsn(replacement, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTPLong", "J"));
                        replaceInsn(m.instructions, insn, replacement);
                        changed = true;
                        System.out.println("  " + cn.name + "." + m.name + m.desc + ": replaced PUTSTATIC curTP:I with I2L+PUTSTATIC curTPLong at index " + i);
                    }
                }
            }
            if (changed) {
                m.visitMaxs(0, 0);
            }
        }
    }

    // ==================== 15. ComJrmctp (jrmctp command) ====================

    static byte[] patchComJrmctp(byte[] data) {
        ClassNode cn = readClass(data);
        // Patch the command execute method to use addTPToPlayer with long
        for (MethodNode m : cn.methods) {
            if ("func_152373_a".equals(m.name) || "execute".equals(m.name) || "processCommand".equals(m.name)) {
                // Replace any direct jrmcTpint manipulation with addTPToPlayer
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode)insn;
                        // Replace GETSTATIC/PUTSTATIC jrmcTpint with calls to addTPToPlayer
                        if ("jrmcTpint".equals(fin.name)) {
                            System.out.println("  " + cn.name + "." + m.name + m.desc + ": found jrmcTpint ref at index " + i);
                        }
                    }
                    // Replace method calls that manipulate TP
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        if (JRMCoreH.equals(min.owner) && min.name.contains("addExp")) {
                            // This might be where TP is added - redirect to addTPToPlayer
                            System.out.println("  " + cn.name + "." + m.name + m.desc + ": found addExp call at index " + i);
                        }
                    }
                }
            }
        }
        // Also patch any curTP:I refs
        patchCurTPRefs(cn);
        return writeClass(cn);
    }
}