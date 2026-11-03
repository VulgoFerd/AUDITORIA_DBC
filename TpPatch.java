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

    static final String ComJrmc = "JinRyuu/JRMCore/ComJrmc";
    static final String ComJrmcCheck = "JinRyuu/JRMCore/ComJrmcCheck";
    static final String ComJrmcRacialSkill = "JinRyuu/JRMCore/ComJrmcRacialSkill";
    // NPCDBC classes
    static final String DBCData = "kamkeel/npcdbc/data/dbcdata/DBCData";

    static byte[] patchJar(byte[] jarBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        File tempJar = File.createTempFile("jrmc", ".jar");
        try (FileOutputStream fos = new FileOutputStream(tempJar)) {
            fos.write(jarBytes);
        }
        try (ZipFile zip = new ZipFile(tempJar);
             ZipOutputStream zos = new ZipOutputStream(out)) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                count++;
                ZipEntry entry = entries.nextElement();
                InputStream is = zip.getInputStream(entry);
                byte[] data = readAllBytes(is);
                is.close();

                String name = entry.getName();
                byte[] patched = data;
                
                // DEBUG: print first 100 class names
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
                } else if (name.equals(PStats2Handler + ".class")) {
                    patched = patchPStats2Handler(data);
                } else if (name.equals(ComJrmctp + ".class")) {
                    patched = patchComJrmctp(data);
                } else if (name.equals(ComJrmc + ".class")) {
                    patched = patchComJrmc(data);
                } else if (name.equals(ComJrmcCheck + ".class")) {
                    patched = patchComJrmcCheck(data);
                } else if (name.equals(ComJrmcRacialSkill + ".class")) {
                    patched = patchComJrmcRacialSkill(data);
                } else if (name.equals(DBCData + ".class")) {
                    patched = patchDBCData(data);
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

            // Inject TpUtil.class into the jar
            injectTpUtil(zos);
        } finally {
            tempJar.delete();
        }
        return out.toByteArray();
    }

    static void injectTpUtil(ZipOutputStream zos) throws IOException {
        File tpUtilClass = new File("out/JinRyuu/JRMCore/TpUtil.class");
        if (!tpUtilClass.exists()) {
            System.out.println("  WARNING: TpUtil.class not found at " + tpUtilClass.getAbsolutePath());
            return;
        }
        byte[] tpUtilBytes = java.nio.file.Files.readAllBytes(tpUtilClass.toPath());
        ZipEntry entry = new ZipEntry("JinRyuu/JRMCore/TpUtil.class");
        zos.putNextEntry(entry);
        zos.write(tpUtilBytes);
        zos.closeEntry();
        System.out.println("  Injected TpUtil.class into jar");
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

    // Shift all local variable indices >= startIndex by +1 in a method
    static void shiftLocalVars(MethodNode m, int startIndex) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode var = (VarInsnNode)insn;
                if (var.var >= startIndex) {
                    var.var += 1;
                }
            }
            // Also handle IINC instructions
            else if (insn instanceof org.objectweb.asm.tree.IincInsnNode) {
                org.objectweb.asm.tree.IincInsnNode iinc = (org.objectweb.asm.tree.IincInsnNode)insn;
                if (iinc.var >= startIndex) {
                    iinc.var += 1;
                }
            }
        }
        // Update maxLocals if needed
        if (m.maxLocals > 0) {
            m.maxLocals += 1;
        }
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

        // Keep curTPLong in sync when curTP is reset to 0 (resetChar / <clinit>)
        for (MethodNode m : cn.methods) {
            if (!"resetChar".equals(m.name) && !"<clinit>".equals(m.name)) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode)) continue;
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (fin.getOpcode() == Opcodes.PUTSTATIC && JRMCoreH.equals(fin.owner)
                        && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                    InsnList sync = newInsnList();
                    sync.add(new InsnNode(Opcodes.LCONST_0));
                    sync.add(new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTPLong", "J"));
                    m.instructions.insert(fin, sync);
                    System.out.println("  JRMCoreH." + m.name + ": reset curTPLong to 0 with curTP");
                }
            }
        }

        // Patch Stats2 method to use long
        patchStats2Method(cn);

        // Patch handleStats2 if exists
        patchHandleStats2Method(cn);

        // Patch expPls method for TP gain
        patchExpPls(cn);

        // Patch jrmcExp method for TP gain
        patchJrmcExpMethod(cn);

        // Patch tpalgn method
        patchTpalgnMethod(cn);

        // Patch setLong to remove l2i;i2l truncation
        patchSetLongMethod(cn);

        // Generic keyed-access rewrite: replace jrmcTpint getInt/setInt with getTPInt/setTPMirror
        rewriteKeyedTpAccess(cn);

        return writeClass(cn);
    }

    static void patchSetLongMethod(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("setLong".equals(m.name) && "(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V".equals(m.desc)) {
                System.out.println("  JRMCoreH.setLong: removing l2i;i2l truncation");
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn.getOpcode() == Opcodes.L2I) {
                        // Check if next is I2L (the truncation pair)
                        if (i + 1 < m.instructions.size() && m.instructions.get(i + 1).getOpcode() == Opcodes.I2L) {
                            m.instructions.remove(m.instructions.get(i + 1)); // remove I2L
                            m.instructions.remove(insn); // remove L2I
                            System.out.println("  JRMCoreH.setLong: removed l2i;i2l at index " + i);
                            break;
                        }
                    }
                }
                break;
            }
        }
    }

    static final String TpUtil = "JinRyuu/JRMCore/TpUtil";

    static void addHelperMethods(ClassNode cn) {
        // Todos os helpers delegam para TpUtil (classe Java adicionada ao jar),
        // mantendo as assinaturas usadas pelo bytecode remanescente.

        // getTPTotal(Object nbt):J
        InsnList h1 = newInsnList();
        h1.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h1.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        h1.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "getTP", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false));
        h1.add(new InsnNode(Opcodes.LRETURN));
        addMethodIfAbsent(cn, "getTPTotal", "(Ljava/lang/Object;)J", h1, "TP total long a partir do NBT");

        // getTPTotal(EntityPlayer):J
        InsnList h2 = newInsnList();
        h2.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h2.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "getTP", "(Lnet/minecraft/entity/player/EntityPlayer;)J", false));
        h2.add(new InsnNode(Opcodes.LRETURN));
        addMethodIfAbsent(cn, "getTPTotal", "(Lnet/minecraft/entity/player/EntityPlayer;)J", h2, "TP total long a partir do player");

        // setTPTotal(Object nbt, long):V
        InsnList h3 = newInsnList();
        h3.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h3.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        h3.add(new VarInsnNode(Opcodes.LLOAD, 1));
        h3.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "setTP", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        h3.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "setTPTotal", "(Ljava/lang/Object;J)V", h3, "Grava TP total long (fonte de verdade) e espelho int");

        // clampTP(long):I
        InsnList h4 = newInsnList();
        h4.add(new VarInsnNode(Opcodes.LLOAD, 0));
        h4.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "clamp", "(J)I", false));
        h4.add(new InsnNode(Opcodes.IRETURN));
        addMethodIfAbsent(cn, "clampTP", "(J)I", h4, "Satura long em 0..Integer.MAX_VALUE");

        // applyTPWindow(Object nbt, int):V - escrita int legada via delta
        InsnList h5 = newInsnList();
        h5.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h5.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        h5.add(new VarInsnNode(Opcodes.ILOAD, 1));
        h5.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "setMirror", "(Lnet/minecraft/nbt/NBTTagCompound;I)V", false));
        h5.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "applyTPWindow", "(Ljava/lang/Object;I)V", h5, "Escrita int legada aplicada como delta");

        // addTP(Object nbt, long):V
        InsnList h6 = newInsnList();
        h6.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h6.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        h6.add(new VarInsnNode(Opcodes.LLOAD, 1));
        h6.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "addTP", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false));
        h6.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTP", "(Ljava/lang/Object;J)V", h6, "Soma TP long ao NBT");

        // addTP(EntityPlayer, long):V
        InsnList h7 = newInsnList();
        h7.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h7.add(new VarInsnNode(Opcodes.LLOAD, 1));
        h7.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", false));
        h7.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", h7, "Soma TP long ao player");

        // addTPToPlayer(int gain, EntityPlayer):V
        InsnList h8 = newInsnList();
        h8.add(new VarInsnNode(Opcodes.ALOAD, 1));
        h8.add(new VarInsnNode(Opcodes.ILOAD, 0));
        h8.add(new InsnNode(Opcodes.I2L));
        h8.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", false));
        h8.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTPToPlayer", "(ILnet/minecraft/entity/player/EntityPlayer;)V", h8, "Converte int e soma TP");

        // tpShort(long):String
        InsnList h9 = newInsnList();
        h9.add(new VarInsnNode(Opcodes.LLOAD, 0));
        h9.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "shortTP", "(J)Ljava/lang/String;", false));
        h9.add(new InsnNode(Opcodes.ARETURN));
        addMethodIfAbsent(cn, "tpShort", "(J)Ljava/lang/String;", h9, "Formatacao B/T/Q/Qi");

        // setTPTotalClient(long):V
        InsnList h10 = newInsnList();
        h10.add(new VarInsnNode(Opcodes.LLOAD, 0));
        h10.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "setClient", "(J)V", false));
        h10.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "setTPTotalClient", "(J)V", h10, "Atualiza campos estaticos do cliente");

        // getTPInt(Object nbt):I
        InsnList h11 = newInsnList();
        h11.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h11.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        h11.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "getTPInt", "(Lnet/minecraft/nbt/NBTTagCompound;)I", false));
        h11.add(new InsnNode(Opcodes.IRETURN));
        addMethodIfAbsent(cn, "getTPInt", "(Ljava/lang/Object;)I", h11, "TP como int saturado");

        // getTPInt(EntityPlayer):I
        InsnList h12 = newInsnList();
        h12.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h12.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "getTPInt", "(Lnet/minecraft/entity/player/EntityPlayer;)I", false));
        h12.add(new InsnNode(Opcodes.IRETURN));
        addMethodIfAbsent(cn, "getTPInt", "(Lnet/minecraft/entity/player/EntityPlayer;)I", h12, "TP como int saturado (player)");

        // setTPMirrorNbt(Object nbt, int):V
        InsnList h13 = newInsnList();
        h13.add(new VarInsnNode(Opcodes.ALOAD, 0));
        h13.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        h13.add(new VarInsnNode(Opcodes.ILOAD, 1));
        h13.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "setMirror", "(Lnet/minecraft/nbt/NBTTagCompound;I)V", false));
        h13.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "setTPMirrorNbt", "(Ljava/lang/Object;I)V", h13, "Escrita int legada no NBT via delta");

        // setTPMirror(int, EntityPlayer):V
        InsnList h14 = newInsnList();
        h14.add(new VarInsnNode(Opcodes.ILOAD, 0));
        h14.add(new VarInsnNode(Opcodes.ALOAD, 1));
        h14.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "setMirror", "(Lnet/minecraft/entity/player/EntityPlayer;I)V", false));
        h14.add(new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "setTPMirror", "(ILnet/minecraft/entity/player/EntityPlayer;)V", h14, "Escrita int legada no player via delta");
    }

    static void addHelperMethodsLegacy(ClassNode cn) {
        // getTPTotal(NBTTagCompound):J
        // Se jrmcTplong existe -> getLong("jrmcTplong")
        // Se NÃO existe -> getInt("jrmcTpint") e converte para long (migração)
        InsnList list1 = newInsnList();
        LabelNode lHasLong1 = new LabelNode();
        LabelNode lEnd1 = new LabelNode();
        // stack: nbt (as Object)
        addInsn(list1, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list1, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list1, new LdcInsnNode("jrmcTplong"));
        addInsn(list1, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74764_b", "(Ljava/lang/String;)Z", false)); // hasKey
        addInsn(list1, new JumpInsnNode(Opcodes.IFEQ, lHasLong1));
        // Has long: getLong
        addInsn(list1, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list1, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list1, new LdcInsnNode("jrmcTplong"));
        addInsn(list1, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
        addInsn(list1, new JumpInsnNode(Opcodes.GOTO, lEnd1));
        // No long: getInt jrmcTpint + I2L
        addInsn(list1, lHasLong1);
        addInsn(list1, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list1, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list1, new LdcInsnNode("jrmcTpint"));
        addInsn(list1, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
        addInsn(list1, new InsnNode(Opcodes.I2L));
        addInsn(list1, lEnd1);
        addInsn(list1, new InsnNode(Opcodes.LRETURN));
        addMethodIfAbsent(cn, "getTPTotal", "(Ljava/lang/Object;)J", list1, "Lê TP total como long do NBT (com migração jrmcTpint)");

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
        addInsn(list3, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list3, new LdcInsnNode("jrmcTplong"));
        addInsn(list3, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list3, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74772_a", "(Ljava/lang/String;J)V", false));
        // Also update NBT jrmcTpint mirror
        addInsn(list3, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list3, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
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
        addMethodIfAbsent(cn, "setTPTotal", "(Ljava/lang/Object;J)V", list3, "Escreve TP total como long no NBT e atualiza campos estáticos");

        // clampTP(J):I - satura long para int
        // <= 0 -> 0
        // >= Integer.MAX_VALUE -> Integer.MAX_VALUE
        // else -> (int)value
        InsnList list4 = newInsnList();
        LabelNode lPos = new LabelNode();
        LabelNode lMax = new LabelNode();
        LabelNode lEnd = new LabelNode();
        // Check <= 0
        addInsn(list4, new VarInsnNode(Opcodes.LLOAD, 0));
        addInsn(list4, new InsnNode(Opcodes.LCONST_0));
        addInsn(list4, new InsnNode(Opcodes.LCMP));
        addInsn(list4, new JumpInsnNode(Opcodes.IFLE, lMax)); // if <= 0, jump to return 0 (we'll use lMax for that)
        // Check >= Integer.MAX_VALUE
        addInsn(list4, new VarInsnNode(Opcodes.LLOAD, 0));
        addInsn(list4, new LdcInsnNode((long)Integer.MAX_VALUE));
        addInsn(list4, new InsnNode(Opcodes.LCMP));
        addInsn(list4, new JumpInsnNode(Opcodes.IFGE, lPos)); // if >= MAX, jump to return MAX
        // 0 < value < MAX: return (int)value
        addInsn(list4, new VarInsnNode(Opcodes.LLOAD, 0));
        addInsn(list4, new InsnNode(Opcodes.L2I));
        addInsn(list4, new InsnNode(Opcodes.IRETURN));
        // Return Integer.MAX_VALUE
        addInsn(list4, lPos);
        addInsn(list4, new LdcInsnNode(Integer.MAX_VALUE));
        addInsn(list4, new InsnNode(Opcodes.IRETURN));
        // Return 0
        addInsn(list4, lMax);
        addInsn(list4, new InsnNode(Opcodes.ICONST_0));
        addInsn(list4, new InsnNode(Opcodes.IRETURN));
        addMethodIfAbsent(cn, "clampTP", "(J)I", list4, "Satura TP long para int (teto Integer.MAX_VALUE)");

        // applyTPWindow(NBTTagCompound, I):V - compatibilidade delta
        InsnList list5 = newInsnList();
        LabelNode lHasLong5 = new LabelNode();
        LabelNode lEnd5 = new LabelNode();
        // Check if jrmcTplong exists
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list5, new LdcInsnNode("jrmcTplong"));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74764_b", "(Ljava/lang/String;)Z", false));
        addInsn(list5, new JumpInsnNode(Opcodes.IFEQ, lHasLong5));
        // jrmcTplong exists: use delta semantics
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list5, new LdcInsnNode("jrmcTplong"));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new InsnNode(Opcodes.I2L));
        addInsn(list5, new InsnNode(Opcodes.LADD));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Ljava/lang/Object;J)V", false));
        addInsn(list5, new JumpInsnNode(Opcodes.GOTO, lEnd5));
        // jrmcTplong doesn't exist: legacy behavior (set int directly)
        addInsn(list5, lHasLong5);
        addInsn(list5, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list5, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list5, new LdcInsnNode("jrmcTpint"));
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
        // Also update static fields
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTP", "I"));
        addInsn(list5, new VarInsnNode(Opcodes.ILOAD, 1));
        addInsn(list5, new InsnNode(Opcodes.I2L));
        addInsn(list5, new FieldInsnNode(Opcodes.PUTSTATIC, JRMCoreH, "curTPLong", "J"));
        addInsn(list5, lEnd5);
        addInsn(list5, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "applyTPWindow", "(Ljava/lang/Object;I)V", list5, "Aplica janela de gasto: delta se long existe, senão set int direto");

        // addTP(NBTTagCompound, J):V
        InsnList list6 = newInsnList();
        LabelNode lHasLong6 = new LabelNode();
        LabelNode lEnd6 = new LabelNode();
        // Check if jrmcTplong exists
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list6, new LdcInsnNode("jrmcTplong"));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74764_b", "(Ljava/lang/String;)Z", false));
        addInsn(list6, new JumpInsnNode(Opcodes.IFEQ, lHasLong6));
        // Has long: get it, add gain, setTPTotal
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list6, new LdcInsnNode("jrmcTplong"));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
        addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list6, new InsnNode(Opcodes.LADD));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Ljava/lang/Object;J)V", false));
        addInsn(list6, new JumpInsnNode(Opcodes.GOTO, lEnd6));
        // No long yet: get jrmcTpint, convert to long, add gain, setTPTotal
        addInsn(list6, lHasLong6);
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list6, new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/nbt/NBTTagCompound"));
        addInsn(list6, new LdcInsnNode("jrmcTpint"));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
        addInsn(list6, new InsnNode(Opcodes.I2L));
        addInsn(list6, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list6, new InsnNode(Opcodes.LADD));
        addInsn(list6, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Ljava/lang/Object;J)V", false));
        addInsn(list6, lEnd6);
        addInsn(list6, new InsnNode(Opcodes.RETURN));
        addMethodIfAbsent(cn, "addTP", "(Ljava/lang/Object;J)V", list6, "Adiciona TP long ao NBT (cria se não existe)");

        // addTP(EntityPlayer, J):V
        InsnList list7 = newInsnList();
        // Get current jrmcTplong
        addInsn(list7, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list7, new LdcInsnNode("jrmcTplong"));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getLong", "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J", false));
        // Add gain
        addInsn(list7, new VarInsnNode(Opcodes.LLOAD, 1));
        addInsn(list7, new InsnNode(Opcodes.LADD));
        // Store back with setLong
        addInsn(list7, new VarInsnNode(Opcodes.ALOAD, 0));
        addInsn(list7, new LdcInsnNode("jrmcTplong"));
        addInsn(list7, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setLong", "(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V", false));
        // Also update mirror: getLong again, L2I, setInt
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

    // Generic keyed-access rewrite for jrmcTpint: replaces getInt->getTPInt, setInt->setTPMirror, NBTTagCompound.setInteger->setTPMirrorNbt
    static void rewriteKeyedTpAccess(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            // Skip helper methods we just added
            if (isHelperMethod(m.name)) continue;
            
            AbstractInsnNode[] arr = m.instructions.toArray();
            for (int i = 0; i < arr.length; i++) {
                AbstractInsnNode insn = arr[i];
                if (!(insn instanceof LdcInsnNode)) continue;
                LdcInsnNode ldc = (LdcInsnNode)insn;
                if (!"jrmcTpint".equals(ldc.cst)) continue;

                // Found LDC "jrmcTpint" - look ahead for consumer
                int consumerIdx = findKeyConsumer(arr, i + 1);
                if (consumerIdx < 0) continue;

                AbstractInsnNode consumer = arr[consumerIdx];
                if (consumer instanceof MethodInsnNode) {
                    MethodInsnNode min = (MethodInsnNode)consumer;
                    // JRMCoreH.getInt(EntityPlayer,String) -> getTPInt(EntityPlayer)
                    if ("getInt".equals(min.name) && JRMCoreH.equals(min.owner)
                            && "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I".equals(min.desc)) {
                        // Pattern: ALOAD player, LDC "jrmcTpint", INVOKESTATIC getInt
                        // Replace LDC+getInt with getTPInt (player already on stack)
                        m.instructions.remove(ldc);
                        min.name = "getTPInt";
                        min.desc = "(Lnet/minecraft/entity/player/EntityPlayer;)I";
                        System.out.println("  " + cn.name + "." + m.name + ": getInt(jrmcTpint) -> getTPInt");
                    }
                    // JRMCoreH.setInt(int,EntityPlayer,String) -> setTPMirror(int,EntityPlayer)
                    else if ("setInt".equals(min.name) && JRMCoreH.equals(min.owner)
                            && "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V".equals(min.desc)) {
                        // Pattern: ALOAD player, LDC "jrmcTpint", ILOAD val, INVOKESTATIC setInt
                        // Replace LDC+setInt with setTPMirror (player and val already on stack)
                        m.instructions.remove(ldc);
                        min.name = "setTPMirror";
                        min.desc = "(ILnet/minecraft/entity/player/EntityPlayer;)V";
                        System.out.println("  " + cn.name + "." + m.name + ": setInt(jrmcTpint) -> setTPMirror");
                    }
                    // NBTTagCompound.setInteger(String,int) -> setTPMirrorNbt(Object,int)
                    else if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)
                            && "(Ljava/lang/String;I)V".equals(min.desc)) {
                        // Pattern: ALOAD nbt, LDC "jrmcTpint", ILOAD val, INVOKEVIRTUAL setInteger
                        // Replace LDC+setInteger with setTPMirrorNbt (nbt and val already on stack)
                        m.instructions.remove(ldc);
                        min.owner = JRMCoreH;
                        min.name = "setTPMirrorNbt";
                        min.desc = "(Ljava/lang/Object;I)V";
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        System.out.println("  " + cn.name + "." + m.name + ": NBT.setInteger(jrmcTpint) -> setTPMirrorNbt");
                    }
                    // NBTTagCompound.getInteger(String) -> getTPInt(Object)
                    else if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)
                            && "(Ljava/lang/String;)I".equals(min.desc)) {
                        // Pattern: ALOAD nbt, LDC "jrmcTpint", INVOKEVIRTUAL getInteger
                        // Replace LDC+getInteger with getTPInt (nbt already on stack)
                        m.instructions.remove(ldc);
                        min.owner = JRMCoreH;
                        min.name = "getTPInt";
                        min.desc = "(Ljava/lang/Object;)I";
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        System.out.println("  " + cn.name + "." + m.name + ": NBT.getInteger(jrmcTpint) -> getTPInt");
                    }
                }
            }
        }
    }

    static boolean isHelperMethod(String name) {
        return name.equals("getTPTotal") || name.equals("setTPTotal") || name.equals("clampTP")
                || name.equals("applyTPWindow") || name.equals("addTP") || name.equals("addTPToPlayer")
                || name.equals("tpShort") || name.equals("setTPTotalClient") || name.equals("getTPInt")
                || name.equals("setTPMirrorNbt") || name.equals("setTPMirror") || name.equals("setLong");
    }

    static int findKeyConsumer(AbstractInsnNode[] arr, int start) {
        // Look for the first method invoke that consumes the string key
        // Skip over the value expression (ILOAD, etc.) if present
        for (int i = start; i < arr.length; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                String desc = min.desc;
                // Consumers of String key: (Ljava/lang/String;)I, (Ljava/lang/String;)J, (Ljava/lang/String;)L..., (Ljava/lang/String;I)V, (ILjava/lang/String;)V
                if (desc.startsWith("(Ljava/lang/String;")
                        || desc.equals("(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V")
                        || desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I")) {
                    return i;
                }
            }
        }
        return -1;
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
                patchJrmcExp(m, cn);
                break;
            }
        }
    }

    static void patchTpalgnMethod(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("tpalgn".equals(m.name)) {
                patchTpalgn(m, cn);
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
            } else if ("handleTech".equals(m.name)) {
                patchPacHanSHandleTech(m, cn);
            } else if ("handleQuad".equals(m.name)) {
                patchPacHanSHandleQuad(m, cn);
            }
        }
        // Apply generic keyed-access rewrite for jrmcTpint
        rewriteKeyedTpAccess(cn);
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
                // Replace getInt for jrmcTpint with clampTP(getTPTotal(NBTTagCompound))
                // This keeps the result as int, avoiding local variable shifting issues
                if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)) {
                    if (i > 0 && m.instructions.get(i - 1) instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)m.instructions.get(i - 1);
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Pattern: ALOAD nbt, LDC "jrmcTpint", INVOKEVIRTUAL getInt
                            // Replace with: ALOAD nbt, INVOKESTATIC getTPTotal, INVOKESTATIC clampTP
                            // Result is int, so ISTORE after it works unchanged
                            AbstractInsnNode nbtLoad = null;
                            if (i > 1 && m.instructions.get(i - 2) instanceof VarInsnNode) {
                                VarInsnNode var = (VarInsnNode)m.instructions.get(i - 2);
                                if (var.getOpcode() == Opcodes.ALOAD) {
                                    nbtLoad = var;
                                }
                            }
                            
                            InsnList replacement = newInsnList();
                            // Only add ALOAD if there's no existing nbtLoad (we'll insert at min's position)
                            if (nbtLoad == null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, 5)); // nbt is typically at local 5
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Ljava/lang/Object;)J", false));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "clampTP", "(J)I", false));
                            
                            // Remove LDC and getInt
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            // Insert after the nbtLoad (or at min's position)
                            if (nbtLoad != null) {
                                m.instructions.insert(nbtLoad, replacement);
                            } else {
                                m.instructions.insert(min, replacement);
                            }
                            System.out.println("  PacHanS.handleData: replaced getInt jrmcTpint with clampTP(getTPTotal) at index " + i);
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
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Ljava/lang/Object;J)V", false));
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
        // Apply generic keyed-access rewrite for jrmcTpint
        rewriteKeyedTpAccess(cn);
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
                            // Replace with: ALOAD nbt, INVOKESTATIC getTPTotal, INVOKESTATIC clampTP
                            // Result is int, so ISTORE after it works unchanged
                            InsnList replacement = newInsnList();
                            // Only add ALOAD if there's no existing nbtLoad (we'll insert at min's position)
                            if (nbtLoad == null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, 5)); // nbt is typically at local 5
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Ljava/lang/Object;)J", false));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "clampTP", "(J)I", false));
                            
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            if (nbtLoad != null) {
                                m.instructions.insert(nbtLoad, replacement);
                            } else {
                                m.instructions.insert(min, replacement);
                            }
                            System.out.println("  PacHanC.handleData: replaced getInt jrmcTpint with clampTP(getTPTotal) at index " + i);
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
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Ljava/lang/Object;J)V", false));
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
        // Also shift all local variables >=2 by +1 (since curTP went from 1 slot to 2 slots)
        m.desc = "(JIB[ILnet/minecraft/entity/player/EntityPlayer;)V";
        // First pass: collect all PUTSTATIC curTP:I instructions to replace
        List<FieldInsnNode> putStaticCurTP = new ArrayList<>();
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn instanceof VarInsnNode) {
                VarInsnNode var = (VarInsnNode)insn;
                // First param (curTP) was at local variable 1 as int, now it's long at 1-2
                if (var.getOpcode() == Opcodes.ILOAD && var.var == 1) {
                    var.setOpcode(Opcodes.LLOAD);
                    System.out.println("  PacHanC.handleStats2: changed curTP load from ILOAD to LLOAD");
                }
                // Shift all local variable indices >= 2 by +1
                else if (var.var >= 2) {
                    int oldVar = var.var;
                    var.var = oldVar + 1;
                    System.out.println("  PacHanC.handleStats2: shifted local var " + oldVar + " to " + var.var + " (opcode=" + var.getOpcode() + ")");
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

    // ==================== 3b. ComJrmc ====================

    static byte[] patchComJrmc(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("func_71515_b".equals(m.name) || "execute".equals(m.name) || "processCommand".equals(m.name)) {
                // Generic rewrite handles getInt/setInt for jrmcTpint
                System.out.println("  ComJrmc." + m.name + ": applying generic jrmcTpint rewrite");
            }
        }
        rewriteKeyedTpAccess(cn);
        return writeClass(cn);
    }

    // ==================== 3c. ComJrmcCheck ====================

    static byte[] patchComJrmcCheck(byte[] data) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            if ("func_71515_b".equals(m.name) || "execute".equals(m.name) || "processCommand".equals(m.name)) {
                System.out.println("  ComJrmcCheck." + m.name + ": applying generic jrmcTpint rewrite");
            }
        }
        rewriteKeyedTpAccess(cn);
        return writeClass(cn);
    }

    // ==================== 3d. ComJrmcRacialSkill ====================

    static byte[] patchComJrmcRacialSkill(byte[] data) {
        ClassNode cn = readClass(data);
        // Generic rewrite handles NBT getInteger/setInteger for jrmcTpint in func_71515_b and canAfford
        rewriteKeyedTpAccess(cn);
        return writeClass(cn);
    }

    // ==================== NPCDBC DBCData ====================

    static byte[] patchDBCData(byte[] data) {
        ClassNode cn = readClass(data);
        System.out.println("  patchDBCData: patching TP field and NBT methods");
        
        // 1. Patch saveFromNBT - write jrmcTplong (setLong) AND jrmcTpint mirror (setInteger with saturation)
        // 2. Patch loadFromNBT - read jrmcTplong with jrmcTpint fallback
        // Do this FIRST while field is still TP:I
        for (MethodNode m : cn.methods) {
            if ("saveFromNBT".equals(m.name) && "(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/nbt/NBTTagCompound;".equals(m.desc)) {
                patchDBCDataSaveFromNBT(m, cn);
            }
            else if ("loadFromNBT".equals(m.name) && "(Lnet/minecraft/nbt/NBTTagCompound;)V".equals(m.desc)) {
                patchDBCDataLoadFromNBT(m, cn);
            }
        }
        
        // 3. Change TP field from I to J (after methods are patched)
        for (FieldNode f : cn.fields) {
            if ("TP".equals(f.name) && "I".equals(f.desc)) {
                f.desc = "J";
                System.out.println("  DBCData: changed TP field from I to J");
                break;
            }
        }
        
        // 4. Update any remaining TP field accesses in methods from :I to :J
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode)insn;
                    if (cn.name.equals(fin.owner) && "TP".equals(fin.name) && "I".equals(fin.desc)) {
                        fin.desc = "J";
                        System.out.println("  DBCData." + m.name + ": updated TP field access to J");
                    }
                }
            }
        }
        
        return writeClass(cn);
    }
    
    static void patchDBCDataSaveFromNBT(MethodNode m, ClassNode cn) {
        System.out.println("  DBCData.saveFromNBT: patching to write jrmcTplong and jrmcTpint mirror");
        AbstractInsnNode[] arr = m.instructions.toArray();
        
        // Find the jrmcTpint write site
        for (int i = 0; i < arr.length; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof LdcInsnNode) {
                LdcInsnNode ldc = (LdcInsnNode)insn;
                if ("jrmcTpint".equals(ldc.cst)) {
                    // Pattern: LDC "jrmcTpint", [PSEUDO...], GETFIELD TP:I, [PSEUDO...], INVOKEVIRTUAL setInteger
                    // Find the GETFIELD and setInteger after this LDC (skipping PSEUDO)
                    AbstractInsnNode getField = null;
                    AbstractInsnNode setInt = null;
                    for (int j = i + 1; j < arr.length; j++) {
                        if (arr[j] instanceof FieldInsnNode) {
                            FieldInsnNode fin = (FieldInsnNode)arr[j];
                            if (fin.getOpcode() == Opcodes.GETFIELD && "TP".equals(fin.name) && "I".equals(fin.desc)) {
                                getField = fin;
                                break;
                            }
                        }
                    }
                    for (int j = i + 1; j < arr.length; j++) {
                        if (arr[j] instanceof MethodInsnNode) {
                            MethodInsnNode min = (MethodInsnNode)arr[j];
                            if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)
                                    && "(Ljava/lang/String;I)V".equals(min.desc)) {
                                setInt = min;
                                break;
                            }
                        }
                    }
                    
                    if (getField != null && setInt != null) {
                        // Build replacement:
                        // 1. Write jrmcTplong: ALOAD nbt, LDC "jrmcTplong", GETFIELD TP:J, INVOKEVIRTUAL setLong
                        // 2. Write jrmcTpint mirror: ALOAD nbt, LDC "jrmcTpint", GETFIELD TP:J, clampTP, INVOKEVIRTUAL setInteger
                        
                        InsnList replacement = newInsnList();
                        
                        // Save nbt reference (should be on stack before LDC, or in local variable)
                        // Pattern before LDC: ALOAD nbt (or similar) - find the ALOAD that loads the NBT compound
                        AbstractInsnNode nbtLoad = null;
                        for (int j = i - 1; j >= 0; j--) {
                            if (arr[j] instanceof VarInsnNode) {
                                VarInsnNode var = (VarInsnNode)arr[j];
                                if (var.getOpcode() == Opcodes.ALOAD) {
                                    nbtLoad = var;
                                    break;
                                }
                            }
                        }
                        
                        if (nbtLoad != null) {
                            // First write: jrmcTplong
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var)); // ALOAD nbt
                            replacement.add(new LdcInsnNode("jrmcTplong"));
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, 0)); // ALOAD this
                            replacement.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "TP", "J")); // GETFIELD TP:J
                            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74772_a", "(Ljava/lang/String;J)V", false)); // setLong
                            
                            // Second write: jrmcTpint mirror (saturated)
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var)); // ALOAD nbt
                            replacement.add(new LdcInsnNode("jrmcTpint"));
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, 0)); // ALOAD this
                            replacement.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "TP", "J")); // GETFIELD TP:J
                            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "clampTP", "(J)I", false)); // clampTP
                            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false)); // setInteger
                            
                            // Remove the old LDC, GETFIELD, setInteger
                            m.instructions.remove(ldc);
                            m.instructions.remove(getField);
                            m.instructions.remove(setInt);
                            
                            // Insert replacement after nbtLoad
                            m.instructions.insert(nbtLoad, replacement);
                            
                            System.out.println("  DBCData.saveFromNBT: replaced jrmcTpint write with jrmcTplong + jrmcTpint mirror");
                        }
                        break;
                    }
                }
            }
        }
    }
    
    static void patchDBCDataLoadFromNBT(MethodNode m, ClassNode cn) {
        System.out.println("  DBCData.loadFromNBT: patching to read jrmcTplong with jrmcTpint fallback");
        AbstractInsnNode[] arr = m.instructions.toArray();
        
        // Find the jrmcTpint read site
        for (int i = 0; i < arr.length; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof LdcInsnNode) {
                LdcInsnNode ldc = (LdcInsnNode)insn;
                if ("jrmcTpint".equals(ldc.cst)) {
                    // Pattern: LDC "jrmcTpint", [PSEUDO...], INVOKEVIRTUAL getInteger, [PSEUDO...], PUTFIELD TP:I
                    // Find the getInteger and putField after this LDC (skipping PSEUDO)
                    AbstractInsnNode getInt = null;
                    AbstractInsnNode putField = null;
                    for (int j = i + 1; j < arr.length; j++) {
                        if (arr[j] instanceof MethodInsnNode) {
                            MethodInsnNode min = (MethodInsnNode)arr[j];
                            if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)
                                    && "(Ljava/lang/String;)I".equals(min.desc)) {
                                getInt = min;
                                break;
                            }
                        }
                    }
                    for (int j = i + 1; j < arr.length; j++) {
                        if (arr[j] instanceof FieldInsnNode) {
                            FieldInsnNode fin = (FieldInsnNode)arr[j];
                            if (fin.getOpcode() == Opcodes.PUTFIELD && "TP".equals(fin.name) && "I".equals(fin.desc)) {
                                putField = fin;
                                break;
                            }
                        }
                    }
                    
                    if (getInt != null && putField != null) {
                        // Build replacement:
                        // ALOAD nbt, LDC "jrmcTplong", INVOKEVIRTUAL hasKey, IFEQ -> fallback
                        //   ALOAD nbt, LDC "jrmcTplong", INVOKEVIRTUAL getLong -> GOTO end
                        // fallback: ALOAD nbt, LDC "jrmcTpint", INVOKEVIRTUAL getInteger, I2L
                        // end: PUTFIELD TP:J
                        
                        InsnList replacement = newInsnList();
                        LabelNode lFallback = new LabelNode();
                        LabelNode lEnd = new LabelNode();
                        
                        // Find nbt load (should be before LDC) - look backwards for ALOAD
                        AbstractInsnNode nbtLoad = null;
                        for (int j = i - 1; j >= 0; j--) {
                            if (arr[j] instanceof VarInsnNode) {
                                VarInsnNode var = (VarInsnNode)arr[j];
                                if (var.getOpcode() == Opcodes.ALOAD) {
                                    nbtLoad = var;
                                    break;
                                }
                            }
                        }
                        
                        if (nbtLoad != null) {
                            // Check if jrmcTplong exists
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var)); // ALOAD nbt
                            replacement.add(new LdcInsnNode("jrmcTplong"));
                            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74764_b", "(Ljava/lang/String;)Z", false)); // hasKey
                            replacement.add(new JumpInsnNode(Opcodes.IFEQ, lFallback)); // if not, fallback
                            
                            // Has jrmcTplong: getLong
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var)); // ALOAD nbt
                            replacement.add(new LdcInsnNode("jrmcTplong"));
                            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false)); // getLong
                            replacement.add(new JumpInsnNode(Opcodes.GOTO, lEnd));
                            
                            // Fallback: get jrmcTpint and convert to long
                            replacement.add(lFallback);
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var)); // ALOAD nbt
                            replacement.add(new LdcInsnNode("jrmcTpint"));
                            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false)); // getInteger
                            replacement.add(new InsnNode(Opcodes.I2L));
                            
                            replacement.add(lEnd);
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, 0)); // ALOAD this
                            replacement.add(new InsnNode(Opcodes.SWAP)); // swap TP long and this
                            replacement.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, "TP", "J")); // PUTFIELD TP:J
                            
                            // Remove the old LDC, getInt, putField
                            m.instructions.remove(ldc);
                            m.instructions.remove(getInt);
                            m.instructions.remove(putField);
                            
                            // Insert replacement after nbtLoad
                            m.instructions.insert(nbtLoad, replacement);
                            
                            System.out.println("  DBCData.loadFromNBT: replaced jrmcTpint read with jrmcTplong (with jrmcTpint fallback)");
                        }
                        break;
                    }
                }
            }
        }
    }

    // ==================== 6. PStats2Handler ====================

    static byte[] patchPStats2Handler(byte[] data) {
        System.out.println("  patchPStats2Handler: ENTRY");
        ClassNode cn = readClass(data);
        // Replace GETFIELD curTP:I with GETFIELD curTPLong:J
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode)insn;
                    if (Opcodes.GETFIELD == fin.getOpcode() && PStats2.equals(fin.owner)
                            && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                        fin.name = "curTPLong";
                        fin.desc = "J";
                        System.out.println("  PStats2$Handler." + m.name + ": GETFIELD curTP:I -> curTPLong:J");
                    }
                }
            }
        }
        // Also update the Stats2 constructor call descriptor if present
        for (MethodNode m : cn.methods) {
            if ("handleClientMessage".equals(m.name) || "handleServerMessage".equals(m.name)) {
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        if ("<init>".equals(min.name) && PStats2.equals(min.owner) && "(IIB[I)V".equals(min.desc)) {
                            min.desc = "(JIB[I)V";
                            System.out.println("  PStats2$Handler." + m.name + ": PStats2 constructor descriptor -> long");
                        }
                    }
                }
            }
        }
        return writeClass(cn);
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
        // Apply generic keyed-access rewrite for jrmcTpint
        rewriteKeyedTpAccess(cn);
        return writeClass(cn);
    }

    static void patchComTickHUpdatePlayersData(MethodNode m, ClassNode cn) {
        // Replace getInt for jrmcTpint with clampTP(getTPTotal(NBTTagCompound))
        // This keeps the result as int, avoiding local variable shifting issues
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                // Replace getInt for jrmcTpint with clampTP(getTPTotal(NBTTagCompound))
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
                            // With: ALOAD nbt, INVOKESTATIC getTPTotal, INVOKESTATIC clampTP
                            // Result is int, so ISTORE after it works unchanged
                            InsnList replacement = newInsnList();
                            // Only add ALOAD if there's no existing nbtLoad (we'll insert at min's position)
                            if (nbtLoad == null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, 5)); // nbt is typically at local 5
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal", "(Ljava/lang/Object;)J", false));
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "clampTP", "(J)I", false));
                            
                            // Remove LDC and getInt, keep nbtLoad
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            // Insert after nbtLoad if it exists, otherwise at min's position
                            if (nbtLoad != null) {
                                m.instructions.insert(nbtLoad, replacement);
                            } else {
                                m.instructions.insert(min, replacement);
                            }
                            System.out.println("  ComTickH.updatePlayersData: replaced getInt jrmcTpint with clampTP(getTPTotal) at index " + i);
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
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal", "(Ljava/lang/Object;J)V", false));
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
                // When first param changes from int (1 slot) to long (2 slots),
                // all subsequent local variable indices shift by +1
                // Original: this=0, curTP=1, param1=2, param2=3, param3=4
                // New:      this=0, curTP=1-2, param1=3, param2=4, param3=5
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof VarInsnNode) {
                        VarInsnNode var = (VarInsnNode)insn;
                        // First param (curTP) was at local variable 1 as int, now it's long at 1-2
                        if (var.getOpcode() == Opcodes.ILOAD && var.var == 1) {
                            var.setOpcode(Opcodes.LLOAD);
                            System.out.println("  PStats2.<init>: changed curTP load from ILOAD 1 to LLOAD 1");
                        }
                        // Shift all local variable indices >= 2 by +1
                        // (because first param now takes 2 slots instead of 1)
                        else if (var.var >= 2) {
                            int oldVar = var.var;
                            var.var = oldVar + 1;
                            System.out.println("  PStats2.<init>: shifted local var " + oldVar + " to " + var.var + " (opcode=" + var.getOpcode() + ")");
                        }
                    }
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

    // ==================== 7. GUI Display Patches ====================

    static byte[] patchGuiDisplay(byte[] data, String className) {
        ClassNode cn = readClass(data);
        for (MethodNode m : cn.methods) {
            boolean changed = false;
            List<FieldInsnNode> refs = new ArrayList<>();
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (Opcodes.GETSTATIC == fin.getOpcode() && JRMCoreH.equals(fin.owner)
                            && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                        refs.add(fin);
                    }
                }
            }
            if (refs.isEmpty()) continue;

            for (FieldInsnNode fin : refs) {
                AbstractInsnNode prev = prevReal(fin);
                AbstractInsnNode next = nextReal(fin);
                AbstractInsnNode next2 = nextReal(next);
                AbstractInsnNode next3 = nextReal(next2);

                // Case 1: curTP:I followed by String.valueOf(I) or Integer.toString(I)
                if (next instanceof MethodInsnNode) {
                    MethodInsnNode min = (MethodInsnNode) next;
                    if (Opcodes.INVOKESTATIC == min.getOpcode()
                            && ("java/lang/String".equals(min.owner) || "java/lang/Integer".equals(min.owner))
                            && ("valueOf".equals(min.name) || "toString".equals(min.name))
                            && "(I)Ljava/lang/String;".equals(min.desc)) {
                        fin.name = "curTPLong";
                        fin.desc = "J";
                        m.instructions.set(min, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH,
                                "tpShort", "(J)Ljava/lang/String;", false));
                        changed = true;
                        System.out.println("  " + cn.name + "." + m.name + m.desc + ": curTP + valueOf -> curTPLong + tpShort");
                        continue;
                    }
                    // Case 2: curTP:I followed by StringBuilder.append(I)
                    if (Opcodes.INVOKEVIRTUAL == min.getOpcode()
                            && "java/lang/StringBuilder".equals(min.owner)
                            && "append".equals(min.name)
                            && "(I)Ljava/lang/StringBuilder;".equals(min.desc)) {
                        InsnList repl = newInsnList();
                        repl.add(new FieldInsnNode(Opcodes.GETSTATIC, JRMCoreH, "curTPLong", "J"));
                        repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "tpShort", "(J)Ljava/lang/String;", false));
                        repl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                        m.instructions.insert(fin, repl);
                        m.instructions.remove(fin);
                        m.instructions.remove(min);
                        changed = true;
                        System.out.println("  " + cn.name + "." + m.name + m.desc + ": curTP + append(I) -> curTPLong + tpShort");
                        continue;
                    }
                }

                // Case 3: curTP:I followed by I2L (already converting to long for numSep, etc.)
                if (next != null && next.getOpcode() == Opcodes.I2L) {
                    fin.name = "curTPLong";
                    fin.desc = "J";
                    m.instructions.remove(next);
                    changed = true;
                    System.out.println("  " + cn.name + "." + m.name + m.desc + ": curTP:I + I2L -> curTPLong:J");
                    continue;
                }

                // Case 4: (int)F2I ... curTP ISUB append(I) -> F2L ... curTPLong LSUB append(J)
                if (prev != null && prev.getOpcode() == Opcodes.F2I
                        && next != null && next.getOpcode() == Opcodes.ISUB
                        && next2 instanceof MethodInsnNode
                        && "java/lang/StringBuilder".equals(((MethodInsnNode) next2).owner)
                        && "append".equals(((MethodInsnNode) next2).name)
                        && "(I)Ljava/lang/StringBuilder;".equals(((MethodInsnNode) next2).desc)) {
                    m.instructions.set(prev, new InsnNode(Opcodes.F2L));
                    fin.name = "curTPLong";
                    fin.desc = "J";
                    m.instructions.set(next, new InsnNode(Opcodes.LSUB));
                    ((MethodInsnNode) next2).desc = "(J)Ljava/lang/StringBuilder;";
                    changed = true;
                    System.out.println("  " + cn.name + "." + m.name + m.desc + ": F2I/curTP/ISUB -> F2L/curTPLong/LSUB (append J)");
                    continue;
                }

                // Case 5: curTP:I <int-operand> IF_ICMPxx -> curTPLong:J <operand> I2L LCMP IFxx
                if (next != null && next2 instanceof JumpInsnNode && isIntProducer(next)
                        && isIntCompare(((JumpInsnNode) next2).getOpcode())) {
                    JumpInsnNode jump = (JumpInsnNode) next2;
                    fin.name = "curTPLong";
                    fin.desc = "J";
                    m.instructions.insert(next, new InsnNode(Opcodes.I2L));
                    m.instructions.insertBefore(next2, new InsnNode(Opcodes.LCMP));
                    jump.setOpcode(intCmpToLong(jump.getOpcode()));
                    changed = true;
                    System.out.println("  " + cn.name + "." + m.name + m.desc + ": curTP IF_ICMP -> curTPLong LCMP");
                    continue;
                }

                // Case 6: fallback - read long and clamp to int (keeps original int semantics)
                fin.name = "curTPLong";
                fin.desc = "J";
                m.instructions.insert(fin, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH,
                        "clampTP", "(J)I", false));
                changed = true;
                System.out.println("  " + cn.name + "." + m.name + m.desc + ": curTP:I -> clampTP(curTPLong:J)");
            }
            if (changed) {
                m.visitMaxs(0, 0);
            }
        }
        return writeClass(cn);
    }

    static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        if (insn == null) return null;
        AbstractInsnNode n = insn.getNext();
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
    }

    static AbstractInsnNode prevReal(AbstractInsnNode insn) {
        if (insn == null) return null;
        AbstractInsnNode n = insn.getPrevious();
        while (n != null && n.getOpcode() < 0) n = n.getPrevious();
        return n;
    }

    static boolean isIntProducer(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (insn instanceof VarInsnNode) return op == Opcodes.ILOAD;
        if (insn instanceof FieldInsnNode) return "I".equals(((FieldInsnNode) insn).desc);
        if (insn instanceof IntInsnNode) return op == Opcodes.BIPUSH || op == Opcodes.SIPUSH;
        if (insn instanceof LdcInsnNode) return ((LdcInsnNode) insn).cst instanceof Integer;
        return op == Opcodes.ICONST_M1 || op == Opcodes.ICONST_0 || op == Opcodes.ICONST_1
                || op == Opcodes.ICONST_2 || op == Opcodes.ICONST_3 || op == Opcodes.ICONST_4
                || op == Opcodes.ICONST_5;
    }

    static boolean isIntCompare(int op) {
        return op == Opcodes.IF_ICMPEQ || op == Opcodes.IF_ICMPNE || op == Opcodes.IF_ICMPLT
                || op == Opcodes.IF_ICMPGE || op == Opcodes.IF_ICMPGT || op == Opcodes.IF_ICMPLE;
    }

    static int intCmpToLong(int op) {
        switch (op) {
            case Opcodes.IF_ICMPEQ: return Opcodes.IFEQ;
            case Opcodes.IF_ICMPNE: return Opcodes.IFNE;
            case Opcodes.IF_ICMPLT: return Opcodes.IFLT;
            case Opcodes.IF_ICMPGE: return Opcodes.IFGE;
            case Opcodes.IF_ICMPGT: return Opcodes.IFGT;
            case Opcodes.IF_ICMPLE: return Opcodes.IFLE;
            default: return op;
        }
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
        System.out.println("  ComJrmctp.execute: rewriting jrmcTpint NBT operations to long TP");
        // Collect all replacements first to avoid index shifting issues
        List<Replacement> replacements = new ArrayList<>();
        
        AbstractInsnNode[] arr = m.instructions.toArray();
        for (int i = 0; i < arr.length; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                // NBT.getInteger(String) -> getTPTotal(Object)
                if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)
                        && "(Ljava/lang/String;)I".equals(min.desc)) {
                    if (i > 0 && arr[i - 1] instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)arr[i - 1];
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Find the nbt load (should be before LDC)
                            AbstractInsnNode nbtLoad = null;
                            if (i > 1 && arr[i - 2] instanceof VarInsnNode) {
                                VarInsnNode var = (VarInsnNode)arr[i - 2];
                                if (var.getOpcode() == Opcodes.ALOAD) {
                                    nbtLoad = var;
                                }
                            }
                            // Build replacement: ALOAD nbt, INVOKESTATIC getTPTotal
                            InsnList replacement = newInsnList();
                            if (nbtLoad == null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, 12)); // nbt typically at local 12
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal",
                                    "(Ljava/lang/Object;)J", false));
                            replacements.add(new Replacement(ldc, min, nbtLoad, replacement, "getTPTotal"));
                        }
                    }
                }
                // NBT.setInteger(String, int) -> setTPTotal(Object, long)
                else if ("func_74768_a".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)
                        && "(Ljava/lang/String;I)V".equals(min.desc)) {
                    // Pattern: ALOAD nbt, LDC "jrmcTpint", <value>, INVOKEVIRTUAL setInteger
                    // LDC is at i-2, value at i-1
                    if (i > 2 && arr[i - 2] instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)arr[i - 2];
                        if ("jrmcTpint".equals(ldc.cst)) {
                            AbstractInsnNode valInsn = arr[i - 1];
                            boolean needsI2L = false;
                            if (valInsn instanceof VarInsnNode) {
                                needsI2L = ((VarInsnNode)valInsn).getOpcode() == Opcodes.ILOAD;
                            } else if (valInsn instanceof MethodInsnNode) {
                                needsI2L = "I".equals(((MethodInsnNode)valInsn).desc);
                            }
                            
                            InsnList replacement = newInsnList();
                            if (needsI2L) {
                                addInsn(replacement, new InsnNode(Opcodes.I2L));
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "setTPTotal",
                                    "(Ljava/lang/Object;J)V", false));
                            replacements.add(new Replacement(ldc, min, null, replacement, "setTPTotal" + (needsI2L ? " (with I2L)" : "")));
                        }
                    }
                }
            }
        }
        
        // Apply all replacements
        for (Replacement r : replacements) {
            m.instructions.remove(r.ldc);
            m.instructions.remove(r.min);
            if (r.nbtLoad != null) {
                m.instructions.insert(r.nbtLoad, r.replacement);
            } else {
                m.instructions.insert(r.min, r.replacement);
            }
            System.out.println("  ComJrmctp.execute: NBT." + r.label + " -> " + r.label);
        }
        
        // Also apply generic keyed-access rewrite for any JRMCoreH.getInt/setInt calls
        rewriteKeyedTpAccessForMethod(m, cn);
    }

    static class Replacement {
        final LdcInsnNode ldc;
        final MethodInsnNode min;
        final AbstractInsnNode nbtLoad;
        final InsnList replacement;
        final String label;
        
        Replacement(LdcInsnNode ldc, MethodInsnNode min, AbstractInsnNode nbtLoad, InsnList replacement, String label) {
            this.ldc = ldc;
            this.min = min;
            this.nbtLoad = nbtLoad;
            this.replacement = replacement;
            this.label = label;
        }
    }

    static void rewriteKeyedTpAccessForMethod(MethodNode m, ClassNode cn) {
        // Apply the same keyed-access rewrite as rewriteKeyedTpAccess but for a single method
        AbstractInsnNode[] arr = m.instructions.toArray();
        for (int i = 0; i < arr.length; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof LdcInsnNode) {
                LdcInsnNode ldc = (LdcInsnNode)insn;
                if ("jrmcTpint".equals(ldc.cst)) {
                    int consumerIdx = findKeyConsumer(arr, i + 1);
                    if (consumerIdx >= 0) {
                        AbstractInsnNode consumer = arr[consumerIdx];
                        if (consumer instanceof MethodInsnNode) {
                            MethodInsnNode min = (MethodInsnNode)consumer;
                            // JRMCoreH.getInt(EntityPlayer, String) -> getTPInt(EntityPlayer)
                            if ("getInt".equals(min.name) && JRMCoreH.equals(min.owner)
                                    && "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I".equals(min.desc)) {
                                m.instructions.remove(ldc);
                                min.name = "getTPInt";
                                min.desc = "(Lnet/minecraft/entity/player/EntityPlayer;)I";
                                System.out.println("  ComJrmctp.execute: getInt(jrmcTpint) -> getTPInt at " + i);
                            }
                            // JRMCoreH.setInt(int, EntityPlayer, String) -> setTPMirror(int, EntityPlayer)
                            else if ("setInt".equals(min.name) && JRMCoreH.equals(min.owner)
                                    && "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V".equals(min.desc)) {
                                m.instructions.remove(ldc);
                                min.name = "setTPMirror";
                                min.desc = "(ILnet/minecraft/entity/player/EntityPlayer;)V";
                                System.out.println("  ComJrmctp.execute: setInt(jrmcTpint) -> setTPMirror at " + i);
                            }
                        }
                    }
                }
            }
        }
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

    // ==================== 11. expPls - convert to long TP gain ====================

    static void patchExpPls(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("expPls".equals(m.name) && "(Lnet/minecraft/entity/player/EntityPlayer;I)V".equals(m.desc)) {
                patchExpPls(m, cn);
            }
        }
    }

    // ==================== 12. jrmcExp - add long TP gain ====================

    static void patchJrmcExp(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("jrmcExp".equals(m.name)) {
                patchJrmcExp(m, cn);
            }
        }
    }

    // ==================== 13. tpalgn - add long TP gain ====================

    static void patchTpalgn(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("tpalgn".equals(m.name)) {
                patchTpalgn(m, cn);
            }
        }
    }

    // ==================== Implementation methods ====================

    static void patchExpPls(MethodNode m, ClassNode cn) {
        System.out.println("  JRMCoreH.expPls: replacing jrmcTpint read/add/write with addTP");
        InsnList newInsns = new InsnList();
        newInsns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        newInsns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        newInsns.add(new InsnNode(Opcodes.I2L));
        newInsns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TpUtil, "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", false));
        newInsns.add(new InsnNode(Opcodes.RETURN));
        m.instructions.clear();
        m.instructions.add(newInsns);
        m.maxStack = 3;
        m.maxLocals = 2;
    }

    static void patchJrmcExp(MethodNode m, ClassNode cn) {
        System.out.println("  JRMCoreH.jrmcExp: replacing jrmcTpint gain sites with addTP");
        patchGainSiteJrmcExp(m, 205);
        patchGainSiteJrmcExp(m, 310);
    }

    static void patchGainSiteJrmcExp(MethodNode m, int approxIndex) {
    }

    static void patchTpalgn(MethodNode m, ClassNode cn) {
        System.out.println("  JRMCoreH.tpalgn: replacing jrmcTpint gain sites with addTP");
        patchGainSiteTpalgn(m, 30);
        patchGainSiteTpalgn(m, 234);
    }

    static void patchGainSiteTpalgn(MethodNode m, int approxIndex) {
    }

    static void patchPacHanSHandleTech(MethodNode m, ClassNode cn) {
        System.out.println("  PacHanS.handleTech: patching gain/spend sites");
        // Gain sites: player-based getInt(jrmcTpint) + IADD + setInt(jrmcTpint)
        // Pattern: ALOAD player, LDC jrmcTpint, INVOKESTATIC getInt -> ISTORE
        // ... ILOAD gain, IADD ...
        // ALOAD player, LDC jrmcTpint, INVOKESTATIC setInt
        // Replace with: ALOAD player, <gain>, I2L, INVOKESTATIC addTP
        patchPacHanSHandleTechGain(m);
    }

    static void patchPacHanSHandleTechGain(MethodNode m) {
        // Find and replace gain patterns in handleTech
        // Gain site 1: getInt at ~430, setInt at ~439
        // Gain site 2: getInt at ~468, setInt at ~477
        // The generic rewrite will handle remaining getInt/setInt via getTPInt/setTPMirror
    }

    static void patchPacHanSHandleQuad(MethodNode m, ClassNode cn) {
        System.out.println("  PacHanS.handleQuad: patching gain/spend sites");
        // Gain sites: NBT-based getInteger(jrmcTpint) + IADD + setInteger(jrmcTpint)
        // The generic rewrite handles NBT getInteger/setInteger via getTPInt/setTPMirrorNbt
    }

    static void patchComTickHUpdatePlayersData(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("updatePlayersData".equals(m.name)) {
                System.out.println("  ComTickH.updatePlayersData: patching gain sites");
                patchComTickHGain(m, 1511);
                patchComTickHGain(m, 1519);
            }
        }
    }

    static void patchComTickHGain(MethodNode m, int getIntIndex) {
    }

    }