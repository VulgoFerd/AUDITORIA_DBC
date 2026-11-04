import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Collections;

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

    // Hierarchy for frame computation
    static Map<String, Set<String>> hierarchy;

    static void loadHierarchy() throws IOException, ClassNotFoundException {
        File hierarchyFile = new File("hierarchy.bin");
        if (!hierarchyFile.exists()) {
            throw new IOException("hierarchy.bin not found. Run JarHierarchy first to generate it.");
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(hierarchyFile))) {
            @SuppressWarnings("unchecked")
            Map<String, Set<String>> h = (Map<String, Set<String>>) ois.readObject();
            hierarchy = h;
            System.out.println("Loaded hierarchy with " + hierarchy.size() + " classes");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java TpPatch <input.jar> <output.jar>");
            System.exit(1);
        }
        loadHierarchy();
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
                    try {
                        patched = patchComJrmctp(data);
                    } catch (Exception e) {
                        System.err.println("  WARNING: ComJrmctp patching failed, using original: " + e.getMessage());
                        e.printStackTrace();
                        patched = data;
                    }
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
        ClassWriter cw = new HierarchyClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // Custom ClassWriter that uses pre-built hierarchy for frame computation
    static class HierarchyClassWriter extends ClassWriter {

        public HierarchyClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // If either type is not a class (array, primitive), use Object
            if (type1.startsWith("[") || type2.startsWith("[") ||
                type1.charAt(0) < 'A' || type2.charAt(0) < 'A') {
                return "java/lang/Object";
            }

            // Use the hierarchy map to find common superclass
            Set<String> ancestors1 = getAllAncestors(type1);
            Set<String> ancestors2 = getAllAncestors(type2);

            // Find the lowest common ancestor
            for (String a1 : ancestors1) {
                if (ancestors2.contains(a1)) {
                    return a1;
                }
            }

            // Fallback - should not happen if hierarchy is complete
            // Throw error to detect incomplete hierarchy rather than silently producing bad frames
            throw new RuntimeException("HierarchyClassWriter: No common superclass found for " + type1 + " and " + type2 + ". Hierarchy incomplete or types not in hierarchy.");
        }

        private Set<String> getAllAncestors(String type) {
            Set<String> result = new LinkedHashSet<>();
            String current = type;
            while (current != null && !current.equals("java/lang/Object")) {
                result.add(current);
                Set<String> parents = hierarchy.get(current);
                if (parents == null || parents.isEmpty()) break;
                // For single inheritance, pick the first non-interface parent
                // (interfaces are listed alongside superclass)
                String superClass = null;
                for (String p : parents) {
                    // Skip interfaces (they don't have fields/methods in the same way)
                    // We could check if it's an interface but for simplicity just use first
                    if (superClass == null) superClass = p;
                }
                current = superClass;
            }
            result.add("java/lang/Object");
            return result;
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
        // Note: TpUtil.setMirror expects (EntityPlayer, int)V - swap arg order
        InsnList h14 = newInsnList();
        h14.add(new VarInsnNode(Opcodes.ALOAD, 1));  // EntityPlayer first
        h14.add(new VarInsnNode(Opcodes.ILOAD, 0));  // int second
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
                // Original: (EntityPlayer, int, int, byte, int[])V
                // New: (EntityPlayer, long, int, byte, int[])V
                // First param (EntityPlayer) at slot 0
                // Second param (curTP) was int at slot 1, now long at slots 1-2
                // Shift all local variable indices >= 2 by +1
                m.desc = "(Lnet/minecraft/entity/player/EntityPlayer;JIB[I)V";
                
                // Shift local variables: curTP went from 1 slot (slot 1) to 2 slots (slots 1-2)
                // So all vars >= 2 need to shift by +1
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof VarInsnNode) {
                        VarInsnNode var = (VarInsnNode)insn;
                        // First param (curTP) was at local variable 1 as int, now it's long at 1-2
                        if (var.getOpcode() == Opcodes.ILOAD && var.var == 1) {
                            var.setOpcode(Opcodes.LLOAD);
                            System.out.println("  JRMCoreH.Stats2: changed curTP load from ILOAD to LLOAD");
                        }
                        // Shift all local variable indices >= 2 by +1
                        else if (var.var >= 2) {
                            int oldVar = var.var;
                            var.var = oldVar + 1;
                            System.out.println("  JRMCoreH.Stats2: shifted local var " + oldVar + " to " + var.var + " (opcode=" + var.getOpcode() + ")");
                        }
                    } else if (insn instanceof IincInsnNode) {
                        IincInsnNode iinc = (IincInsnNode)insn;
                        if (iinc.var >= 2) {
                            int oldVar = iinc.var;
                            iinc.var = oldVar + 1;
                            System.out.println("  JRMCoreH.Stats2: shifted IINC var " + oldVar + " to " + iinc.var);
                        }
                    }
                }
                
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
                m.maxLocals += 1;
                System.out.println("  JRMCoreH.Stats2: descriptor changed to long, locals shifted");
                break;
            }
        }
    }

    static void patchHandleStats2Method(ClassNode cn) {
        // handleStats2 is in PacHanC, not JRMCoreH - already handled by patchPacHanCHandleStats2
        // This method is kept for compatibility but does nothing
        System.out.println("  JRMCoreH.patchHandleStats2Method: handleStats2 is in PacHanC, already handled");
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
                            AbstractInsnNode insertPoint = (nbtLoad != null) ? nbtLoad : min;
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            // Insert at captured point
                            m.instructions.insert(insertPoint, replacement);
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
                            AbstractInsnNode insertPoint = min;
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            m.instructions.insert(insertPoint, replacement);
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
                            
                            AbstractInsnNode insertPoint = (nbtLoad != null) ? nbtLoad : min;
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            m.instructions.insert(insertPoint, replacement);
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
                            AbstractInsnNode insertPoint = min;
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            m.instructions.insert(insertPoint, replacement);
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
        System.out.println("  DBCData.saveFromNBT: patching to use TpUtil.writeTP");
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
                        // Build replacement: ALOAD nbt, ALOAD this, GETFIELD TP:J, INVOKESTATIC TpUtil.writeTP
                        InsnList replacement = newInsnList();
                        
                        // Find nbt load (should be before LDC)
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
                            // Pattern: ALOAD nbt, ALOAD this, GETFIELD TP:J, INVOKESTATIC TpUtil.writeTP
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var)); // ALOAD nbt
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, 0)); // ALOAD this
                            replacement.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "TP", "J")); // GETFIELD TP:J
                            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/TpUtil", "writeTP", "(Lnet/minecraft/nbt/NBTTagCompound;J)V", false)); // TpUtil.writeTP
                            
                            // Remove the old LDC, GETFIELD, setInteger
                            AbstractInsnNode insertPoint = nbtLoad;
                            m.instructions.remove(ldc);
                            m.instructions.remove(getField);
                            m.instructions.remove(setInt);
                            
                            // Insert replacement after nbtLoad
                            m.instructions.insert(insertPoint, replacement);
                            
                            System.out.println("  DBCData.saveFromNBT: replaced jrmcTpint write with TpUtil.writeTP");
                        }
                        break;
                    }
                }
            }
        }
    }
    
    static void patchDBCDataLoadFromNBT(MethodNode m, ClassNode cn) {
        System.out.println("  DBCData.loadFromNBT: patching to use TpUtil.getTP");
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
                        // Build replacement: ALOAD nbt, INVOKESTATIC TpUtil.getTP, PUTFIELD TP:J
                        InsnList replacement = newInsnList();
                        
                        // Find nbt load (should be before LDC)
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
                            // Pattern: ALOAD nbt, INVOKESTATIC TpUtil.getTP, ALOAD this, SWAP, PUTFIELD TP:J
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)nbtLoad).var)); // ALOAD nbt
                            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/TpUtil", "getTP", "(Lnet/minecraft/nbt/NBTTagCompound;)J", false)); // TpUtil.getTP
                            replacement.add(new VarInsnNode(Opcodes.ALOAD, 0)); // ALOAD this
                            // Stack: [tp_long_low, tp_long_high, this] -> need [this, tp_long_low, tp_long_high]
                            // DUP2_X1: [this, tp_long_low, tp_long_high, this]
                            // POP2: [this, tp_long_low, tp_long_high]
                            replacement.add(new InsnNode(Opcodes.DUP2_X1));
                            replacement.add(new InsnNode(Opcodes.POP2));
                            replacement.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, "TP", "J")); // PUTFIELD TP:J
                            
                            // Remove the old LDC, getInt, putField
                            AbstractInsnNode insertPoint = nbtLoad;
                            m.instructions.remove(ldc);
                            m.instructions.remove(getInt);
                            m.instructions.remove(putField);
                            
                            // Insert replacement after nbtLoad
                            m.instructions.insert(insertPoint, replacement);
                            
                            System.out.println("  DBCData.loadFromNBT: replaced jrmcTpint read with TpUtil.getTP");
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
        // Also update the Stats2 constructor call descriptor and handleStats2 call if present
        for (MethodNode m : cn.methods) {
            if ("handleClientMessage".equals(m.name) || "handleServerMessage".equals(m.name)) {
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode)insn;
                        if ("<init>".equals(min.name) && PStats2.equals(min.owner) && "(IIB[I)V".equals(min.desc)) {
                            min.desc = "(JIB[I)V";
                            System.out.println("  PStats2$Handler." + m.name + ": PStats2 constructor descriptor -> long");
                        }
                        if ("handleStats2".equals(min.name) && PacHanC.equals(min.owner) && "(IIB[ILnet/minecraft/entity/player/EntityPlayer;)V".equals(min.desc)) {
                            min.desc = "(JIB[ILnet/minecraft/entity/player/EntityPlayer;)V";
                            System.out.println("  PStats2$Handler." + m.name + ": PacHanC.handleStats2 call descriptor -> long");
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
                            AbstractInsnNode insertPoint = (nbtLoad != null) ? nbtLoad : min;
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            // Insert at captured point
                            m.instructions.insert(insertPoint, replacement);
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
                            AbstractInsnNode insertPoint = min;
                            m.instructions.remove(ldc);
                            m.instructions.remove(min);
                            m.instructions.insert(insertPoint, replacement);
                            System.out.println("  ComTickH.updatePlayersData: replaced setInt jrmcTpint with setTPTotal at index " + i);
                        }
                    }
                }
            }
            // Skip the direct PUTSTATIC curTP:I patching - let helper methods handle it
        }
    }

    static void patchComTickHSendPlayerData(MethodNode m, ClassNode cn) {
        System.out.println("  ComTickH.sendPlayerData: patching to send curTPLong");
        // The sendPlayerData method sends various data arrays to client
        // TP is in dat11 (index 11) which corresponds to jrmcTpint
        // We need to ensure the data being sent uses curTPLong
        // The actual data population happens in updatePlayersData which is already patched
        // This method just sends the pre-populated arrays
        // No changes needed here if updatePlayersData is correctly patched
        System.out.println("  ComTickH.sendPlayerData: data arrays already patched in updatePlayersData");
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
        // Debug: print local variable instructions for ComJrmctp execute method
        for (MethodNode m : cn.methods) {
            if ("func_71515_b".equals(m.name) || "execute".equals(m.name) || "processCommand".equals(m.name)) {
                System.out.println("  DEBUG ComJrmctp." + m.name + " local variable instructions:");
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof VarInsnNode) {
                        VarInsnNode var = (VarInsnNode)insn;
                        System.out.println("    " + var.getOpcode() + " var=" + var.var);
                    } else if (insn instanceof IincInsnNode) {
                        IincInsnNode iinc = (IincInsnNode)insn;
                        System.out.println("    IINC var=" + iinc.var);
                    }
                }
                System.out.println("    maxLocals=" + m.maxLocals);
            }
        }
        return writeClass(cn);
    }

    static void patchComJrmctpExecute(MethodNode m, ClassNode cn) {
        System.out.println("  ComJrmctp.execute: rewriting jrmcTpint NBT operations to long TP");
        // Collect all replacements first to avoid index shifting issues
        List<Replacement> replacements = new ArrayList<>();
        // Track converted variables: oldVar -> newVar mapping
        Map<Integer, Integer> convertedVarMap = new HashMap<>();
        
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
                            // Find the ISTORE after this method call in the snapshot
                            VarInsnNode istoreNode = null;
                            int istoreIdx = -1;
                            for (int j = i + 1; j < arr.length; j++) {
                                if (arr[j] instanceof VarInsnNode) {
                                    VarInsnNode var = (VarInsnNode)arr[j];
                                    if (var.getOpcode() == Opcodes.ISTORE) {
                                        istoreNode = var;
                                        istoreIdx = j;
                                        break;
                                    }
                                }
                            }
                            // Build replacement: INVOKESTATIC getTPTotal
                            // If nbtLoad exists, we insert AFTER it (so nbt is on stack)
                            // If no nbtLoad, we need to add ALOAD nbt (assume local 12)
                            InsnList replacement = newInsnList();
                            if (nbtLoad == null) {
                                addInsn(replacement, new VarInsnNode(Opcodes.ALOAD, 12)); // nbt typically at local 12
                            }
                            addInsn(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCoreH, "getTPTotal",
                                    "(Ljava/lang/Object;)J", false));
                            replacements.add(new Replacement(ldc, min, nbtLoad, replacement, "getTPTotal", istoreNode, istoreIdx));
                            if (istoreNode != null) {
                                System.out.println("  ComJrmctp.execute: found ISTORE at var " + istoreNode.var + " for getTPTotal at snapshot index " + istoreIdx);
                            }
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
        
        // First pass: collect all getTPTotal replacements and their ISTORE nodes, grouped by oldVar
        Map<Integer, List<Replacement>> getTPTotalByOldVar = new HashMap<>();
        for (Replacement r : replacements) {
            if (r.label.equals("getTPTotal") && r.istoreNode != null) {
                int oldVar = r.istoreNode.var;
                getTPTotalByOldVar.computeIfAbsent(oldVar, k -> new ArrayList<>()).add(r);
            }
        }
        
        // Allocate one newVar per oldVar
        Map<Integer, Integer> oldVarToNewVar = new HashMap<>();
        for (int oldVar : getTPTotalByOldVar.keySet()) {
            int newVar = m.maxLocals + 1;
            m.maxLocals = newVar + 1; // long takes 2 slots
            oldVarToNewVar.put(oldVar, newVar);
            System.out.println("  ComJrmctp.execute: allocated new var " + newVar + " for getTPTotal results (was var " + oldVar + ")");
        }
        
        // Apply all replacements and modify ISTOREs
        for (Replacement r : replacements) {
            m.instructions.remove(r.ldc);
            m.instructions.remove(r.min);
            
            // Save the last node of replacement BEFORE insertion (nodes are moved during insert)
            AbstractInsnNode replacementLast = r.replacement.getLast();
            
            if (r.label.equals("getTPTotal") && r.nbtLoad != null) {
                // For getTPTotal with nbtLoad: insert AFTER nbtLoad so nbt is on stack
                // insert() inserts BEFORE the given node, so we insert before nbtLoad.getNext()
                AbstractInsnNode afterNbtLoad = r.nbtLoad.getNext();
                if (afterNbtLoad != null) {
                    m.instructions.insert(afterNbtLoad, r.replacement);
                } else {
                    m.instructions.add(r.replacement);
                }
            } else {
                // For getTPTotal without nbtLoad, or setTPTotal: insert at min position (replacing the call)
                m.instructions.insert(r.min, r.replacement);
            }
            
            // If this was a getTPTotal replacement, use the stored ISTORE node from snapshot
            if (r.label.equals("getTPTotal") && r.istoreNode != null) {
                // Use the ISTORE node we found in the snapshot
                VarInsnNode var = r.istoreNode;
                int oldVar = var.var;
                int newVar = oldVarToNewVar.get(oldVar);
                
                System.out.println("  ComJrmctp.execute: converting ISTORE at var " + oldVar + " to LSTORE at var " + newVar);
                
                // Change ISTORE to LSTORE at NEW variable index
                var.setOpcode(Opcodes.LSTORE);
                var.var = newVar;
                
                // Track this conversion: oldVar -> newVar
                // We'll replace ILOAD oldVar; I2L with LLOAD newVar
                convertedVarMap.put(oldVar, newVar);
            } else if (r.label.equals("getTPTotal")) {
                System.out.println("  ComJrmctp.execute: WARNING - no ISTORE found for getTPTotal at snapshot index " + r.istoreIndexInSnapshot);
            }
            System.out.println("  ComJrmctp.execute: NBT." + r.label + " -> " + r.label);
        }
        
        // Replace ILOAD oldVar; I2L with LLOAD newVar for all converted variables
        // Do this BEFORE remapping
        AbstractInsnNode[] arr2 = m.instructions.toArray();
        System.out.println("  ComJrmctp.execute: scanning for ILOAD/I2L pairs, convertedVarMap=" + convertedVarMap);
        for (int i = 0; i < arr2.length - 1; i++) {
            if (arr2[i] instanceof VarInsnNode && arr2[i + 1] instanceof InsnNode) {
                VarInsnNode var = (VarInsnNode)arr2[i];
                InsnNode insn = (InsnNode)arr2[i + 1];
                if (var.getOpcode() == Opcodes.ILOAD && insn.getOpcode() == Opcodes.I2L) {
                    System.out.println("  ComJrmctp.execute: found ILOAD " + var.var + "; I2L at index " + i);
                    Integer newVar = convertedVarMap.get(var.var);
                    if (newVar != null) {
                        var.setOpcode(Opcodes.LLOAD);
                        var.var = newVar;
                        m.instructions.remove(insn);
                        System.out.println("  ComJrmctp.execute: replaced ILOAD with LLOAD at new var " + newVar);
                    } else {
                        System.out.println("  ComJrmctp.execute: no mapping for var " + var.var);
                    }
                }
            }
        }
        
        // Update maxLocals to accommodate the new highest variable index
        // (We allocated new variables at the end, so just recalculate maxLocals)
        int maxVar = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode var = (VarInsnNode)insn;
                // Long/double take 2 slots
                int opcode = var.getOpcode();
                int slots = (opcode == Opcodes.LLOAD || opcode == Opcodes.LSTORE ||
                             opcode == Opcodes.DLOAD || opcode == Opcodes.DSTORE) ? 2 : 1;
                maxVar = Math.max(maxVar, var.var + slots);
            } else if (insn instanceof IincInsnNode) {
                IincInsnNode iinc = (IincInsnNode)insn;
                maxVar = Math.max(maxVar, iinc.var + 1);
            }
        }
        if (maxVar > m.maxLocals) {
            System.out.println("  ComJrmctp.execute: updating maxLocals from " + m.maxLocals + " to " + maxVar);
            m.maxLocals = maxVar;
        }
        
        // Also apply generic keyed-access rewrite for any JRMCoreH.getInt/setInt calls
        rewriteKeyedTpAccessForMethod(m, cn);
    }
    
    // Helper method to shift local variable indices after a given index by a delta
    static void shiftLocalVarsAfter(MethodNode m, int startVar, int delta) {
        System.out.println("  shiftLocalVarsAfter: startVar=" + startVar + ", delta=" + delta);
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode var = (VarInsnNode)insn;
                if (var.var >= startVar) {
                    System.out.println("    shifting " + var.getOpcode() + " var=" + var.var + " -> " + (var.var + delta));
                    var.var += delta;
                }
            } else if (insn instanceof IincInsnNode) {
                IincInsnNode iinc = (IincInsnNode)insn;
                if (iinc.var >= startVar) {
                    System.out.println("    shifting IINC var=" + iinc.var + " -> " + (iinc.var + delta));
                    iinc.var += delta;
                }
            }
        }
        // Do NOT manually update maxLocals - let COMPUTE_FRAMES compute it
        // m.maxLocals += delta;
    }

    // Proper one-pass variable remapping for multiple converted variables
    // Each converted var (int->long) takes 2 slots instead of 1
    // All vars > convertedVar get shifted by +1 per converted var below them
    static void remapLocalVariables(MethodNode m, Set<Integer> convertedVars) {
        if (convertedVars.isEmpty()) return;
        
        // Sort converted vars for binary search
        List<Integer> sortedConverted = new ArrayList<>(convertedVars);
        Collections.sort(sortedConverted);
        System.out.println("  remapLocalVariables: converted vars = " + sortedConverted);
        
        // Build remapping: newIndex(originalVar) = originalVar + count(converted < originalVar)
        // But we also need to handle the converted vars themselves (they stay at same index but become long)
        
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode var = (VarInsnNode)insn;
                int orig = var.var;
                // Count how many converted vars are strictly less than orig
                int shift = 0;
                for (int cv : sortedConverted) {
                    if (cv < orig) shift++;
                    else break;
                }
                if (shift > 0) {
                    int newVar = orig + shift;
                    System.out.println("    remap opcode=" + var.getOpcode() + " var=" + orig + " -> " + newVar + " (shift=" + shift + ")");
                    var.var = newVar;
                } else {
                    System.out.println("    no-shift opcode=" + var.getOpcode() + " var=" + orig + " (shift=0)");
                }
            } else if (insn instanceof IincInsnNode) {
                IincInsnNode iinc = (IincInsnNode)insn;
                int orig = iinc.var;
                int shift = 0;
                for (int cv : sortedConverted) {
                    if (cv < orig) shift++;
                    else break;
                }
                if (shift > 0) {
                    int newVar = orig + shift;
                    System.out.println("    remap IINC var=" + orig + " -> " + newVar + " (shift=" + shift + ")");
                    iinc.var = newVar;
                }
            }
        }
    }

    static class Replacement {
        final LdcInsnNode ldc;
        final MethodInsnNode min;
        final AbstractInsnNode nbtLoad;
        final InsnList replacement;
        final String label;
        // For getTPTotal: the ISTORE node in the original snapshot (to avoid search issues after modifications)
        final VarInsnNode istoreNode;
        final int istoreIndexInSnapshot;
        
        Replacement(LdcInsnNode ldc, MethodInsnNode min, AbstractInsnNode nbtLoad, InsnList replacement, String label) {
            this(ldc, min, nbtLoad, replacement, label, null, -1);
        }
        
        Replacement(LdcInsnNode ldc, MethodInsnNode min, AbstractInsnNode nbtLoad, InsnList replacement, String label, VarInsnNode istoreNode, int istoreIndexInSnapshot) {
            this.ldc = ldc;
            this.min = min;
            this.nbtLoad = nbtLoad;
            this.replacement = replacement;
            this.label = label;
            this.istoreNode = istoreNode;
            this.istoreIndexInSnapshot = istoreIndexInSnapshot;
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
                // Update to use long for curTP - apply full local variable shifting like PacHanC
                // Original descriptor: (IIB[I)V or similar - first param is curTP as int
                // New descriptor: (JIB[I)V - first param is curTP as long (2 slots)
                m.desc = "(JIB[I)V";
                
                // Shift all local variable indices >= 2 by +1 (since curTP went from 1 slot to 2 slots)
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof VarInsnNode) {
                        VarInsnNode var = (VarInsnNode)insn;
                        // First param (curTP) was at local variable 1 as int, now it's long at 1-2
                        if (var.getOpcode() == Opcodes.ILOAD && var.var == 1) {
                            var.setOpcode(Opcodes.LLOAD);
                            System.out.println("  JRMCoreH.handleStats2: changed curTP load from ILOAD to LLOAD");
                        }
                        // Shift all local variable indices >= 2 by +1
                        else if (var.var >= 2) {
                            int oldVar = var.var;
                            var.var = oldVar + 1;
                            System.out.println("  JRMCoreH.handleStats2: shifted local var " + oldVar + " to " + var.var + " (opcode=" + var.getOpcode() + ")");
                        }
                    } else if (insn instanceof IincInsnNode) {
                        IincInsnNode iinc = (IincInsnNode)insn;
                        if (iinc.var >= 2) {
                            int oldVar = iinc.var;
                            iinc.var = oldVar + 1;
                            System.out.println("  JRMCoreH.handleStats2: shifted IINC var " + oldVar + " to " + iinc.var);
                        }
                    }
                }
                
                // Also update any field accesses to curTP:I -> curTPLong:J
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode)insn;
                        if (JRMCoreH.equals(fin.owner) && "curTP".equals(fin.name) && "I".equals(fin.desc)) {
                            if (fin.getOpcode() == Opcodes.GETSTATIC) {
                                // GETSTATIC curTP:I -> GETSTATIC curTPLong:J (for display)
                                fin.name = "curTPLong";
                                fin.desc = "J";
                                System.out.println("  JRMCoreH.handleStats2: replaced GETSTATIC curTP:I with GETSTATIC curTPLong:J");
                            } else if (fin.getOpcode() == Opcodes.PUTSTATIC) {
                                // PUTSTATIC curTP:I -> dual field update (curTPLong:J + curTP:I via clampTP)
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
                                System.out.println("  JRMCoreH.handleStats2: replaced PUTSTATIC curTP:I with dual field update");
                            }
                        }
                    }
                }
                
                m.maxLocals += 1;
                System.out.println("  JRMCoreH.handleStats2: descriptor changed to long, locals shifted");
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
        System.out.println("  patchGainSiteJrmcExp: patching gain site near index " + approxIndex);
        AbstractInsnNode[] arr = m.instructions.toArray();
        
        // Search around approxIndex for the pattern:
        // getInt(jrmcTpint) -> ISTORE temp
        // ILOAD temp, ILOAD gain_computed, IMUL/IADD -> ISTORE result
        // clamp with getMaxTP
        // setInt(jrmcTpint)
        // Replace with: ALOAD 13 (target player), <gain>, I2L, INVOKESTATIC TpUtil.addTP
        
        int searchStart = Math.max(0, approxIndex - 10);
        int searchEnd = Math.min(arr.length, approxIndex + 30);
        
        for (int i = searchStart; i < searchEnd; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                // Look for getInt with jrmcTpint
                if ("getInt".equals(min.name) && JRMCoreH.equals(min.owner)
                        && "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I".equals(min.desc)) {
                    if (i > 0 && arr[i - 1] instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)arr[i - 1];
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Found getInt(jrmcTpint) - now look for the setInt(jrmcTpint) after
                            for (int j = i + 1; j < Math.min(arr.length, i + 60); j++) {
                                if (arr[j] instanceof MethodInsnNode) {
                                    MethodInsnNode setMin = (MethodInsnNode)arr[j];
                                    if ("setInt".equals(setMin.name) && JRMCoreH.equals(setMin.owner)
                                            && "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V".equals(setMin.desc)) {
                                        if (j > 0 && arr[j - 1] instanceof LdcInsnNode) {
                                            LdcInsnNode setLdc = (LdcInsnNode)arr[j - 1];
                                            if ("jrmcTpint".equals(setLdc.cst)) {
                                                // Found the pattern! 
                                                // The target player is in local 13 (for first site) or 3 (for second site)
                                                // The gain is computed as ILOAD 10 (tpGain) * ILOAD 1 (e) or similar
                                                // We'll insert: ALOAD <player>, <gain computation>, I2L, INVOKESTATIC TpUtil.addTP
                                                
                                                // Find the player ALOAD before setInt
                                                VarInsnNode playerLoad = null;
                                                for (int k = j - 2; k >= 0; k--) {
                                                    if (arr[k] instanceof VarInsnNode) {
                                                        VarInsnNode var = (VarInsnNode)arr[k];
                                                        if (var.getOpcode() == Opcodes.ALOAD && (var.var == 13 || var.var == 3)) {
                                                            playerLoad = var;
                                                            break;
                                                        }
                                                    }
                                                }
                                                
                                                // Find the gain computation - look for IMUL or IADD before setInt
                                                // The pattern is: ILOAD tp, ILOAD gain, IMUL, IADD -> ISTORE
                                                // We'll replace the whole thing
                                                
                                                InsnList replacement = newInsnList();
                                                if (playerLoad != null) {
                                                    replacement.add(new VarInsnNode(Opcodes.ALOAD, playerLoad.var));
                                                } else {
                                                    replacement.add(new VarInsnNode(Opcodes.ALOAD, 3)); // fallback
                                                }
                                                
                                                // The gain is computed locally - we need to replicate the computation
                                                // For simplicity, we'll look for the IMUL pattern
                                                // Pattern: ILOAD 10, ILOAD 1, IMUL
                                                // But since we're replacing the whole block, we can't easily extract it
                                                // Instead, let's compute: the gain is already in a local before setInt
                                                // Look for ISTORE before setInt
                                                VarInsnNode gainStore = null;
                                                for (int k = j - 1; k >= i; k--) {
                                                    if (arr[k] instanceof VarInsnNode) {
                                                        VarInsnNode var = (VarInsnNode)arr[k];
                                                        if (var.getOpcode() == Opcodes.ISTORE) {
                                                            gainStore = var;
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (gainStore != null) {
                                                    // Load the computed gain
                                                    replacement.add(new VarInsnNode(Opcodes.ILOAD, gainStore.var));
                                                } else {
                                                    // Fallback: try to compute it
                                                    // tpGain is at local 10, e is at local 1
                                                    replacement.add(new VarInsnNode(Opcodes.ILOAD, 10));
                                                    replacement.add(new VarInsnNode(Opcodes.ILOAD, 1));
                                                    replacement.add(new InsnNode(Opcodes.IMUL));
                                                }
                                                replacement.add(new InsnNode(Opcodes.I2L));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/TpUtil", "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", false));
                                                
                                                // Find start of pattern - the ALOAD before first LDC jrmcTpint
                                                AbstractInsnNode startNode = null;
                                                for (int k = i - 1; k >= 0; k--) {
                                                    if (arr[k] instanceof VarInsnNode) {
                                                        VarInsnNode var = (VarInsnNode)arr[k];
                                                        if (var.getOpcode() == Opcodes.ALOAD && (var.var == 3 || var.var == 13)) {
                                                            startNode = var;
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (startNode == null) startNode = ldc;
                                                
                                                // Remove all instructions from startNode to setMin (inclusive)
                                                AbstractInsnNode cur = startNode;
                                                while (cur != null && cur != setMin.getNext()) {
                                                    AbstractInsnNode next = cur.getNext();
                                                    m.instructions.remove(cur);
                                                    cur = next;
                                                }
                                                m.instructions.remove(setLdc);
                                                m.instructions.remove(setMin);
                                                
                                                // Insert replacement at startNode position
                                                m.instructions.insert(startNode, replacement);
                                                
                                                System.out.println("  jrmcExp: replaced gain site near index " + approxIndex + " with TpUtil.addTP");
                                                return;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    static void patchTpalgn(MethodNode m, ClassNode cn) {
        System.out.println("  JRMCoreH.tpalgn: replacing jrmcTpint gain sites with addTP");
        patchGainSiteTpalgn(m, 30);
        patchGainSiteTpalgn(m, 234);
    }

    static void patchGainSiteTpalgn(MethodNode m, int approxIndex) {
        System.out.println("  patchGainSiteTpalgn: patching gain site near index " + approxIndex);
        AbstractInsnNode[] arr = m.instructions.toArray();
        
        // Search around approxIndex for the pattern:
        // getInt(jrmcTpint) -> ISTORE temp
        // ILOAD temp, ILOAD 1 (gain), IADD -> ISTORE result
        // clamp with getMaxTP
        // setInt(jrmcTpint)
        // Replace with: ALOAD 0 (player), ILOAD 1 (gain), I2L, INVOKESTATIC TpUtil.addTP
        
        int searchStart = Math.max(0, approxIndex - 10);
        int searchEnd = Math.min(arr.length, approxIndex + 30);
        
        for (int i = searchStart; i < searchEnd; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                // Look for getInt with jrmcTpint
                if ("getInt".equals(min.name) && JRMCoreH.equals(min.owner)
                        && "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I".equals(min.desc)) {
                    if (i > 0 && arr[i - 1] instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)arr[i - 1];
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Found getInt(jrmcTpint) - now look for the pattern
                            // Check if there's an ILOAD 1 (gain) and IADD nearby
                            // and a setInt(jrmcTpint) after
                            for (int j = i + 1; j < Math.min(arr.length, i + 30); j++) {
                                if (arr[j] instanceof MethodInsnNode) {
                                    MethodInsnNode setMin = (MethodInsnNode)arr[j];
                                    if ("setInt".equals(setMin.name) && JRMCoreH.equals(setMin.owner)
                                            && "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V".equals(setMin.desc)) {
                                        if (j > 0 && arr[j - 1] instanceof LdcInsnNode) {
                                            LdcInsnNode setLdc = (LdcInsnNode)arr[j - 1];
                                            if ("jrmcTpint".equals(setLdc.cst)) {
                                                // Found the pattern! Replace surgically
                                                // Pattern: ALOAD 0, LDC, getInt, ...computation..., LDC, setInt
                                                // Keep the first ALOAD 0, replace the rest with: ILOAD 1, I2L, INVOKESTATIC addTP
                                                
                                                // Find the first ALOAD 0 (player load for getInt)
                                                AbstractInsnNode startNode = null;
                                                for (int k = i - 1; k >= 0; k--) {
                                                    if (arr[k] instanceof VarInsnNode) {
                                                        VarInsnNode var = (VarInsnNode)arr[k];
                                                        if (var.getOpcode() == Opcodes.ALOAD && var.var == 0) {
                                                            startNode = var;
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (startNode == null) {
                                                    System.out.println("  tpalgn: WARNING - could not find startNode for gain site near " + approxIndex);
                                                    return;
                                                }
                                                
                                                // Build replacement: ILOAD 1 (gain), I2L, INVOKESTATIC addTP
                                                // (ALOAD 0 is already there as startNode)
                                                InsnList replacement = newInsnList();
                                                replacement.add(new VarInsnNode(Opcodes.ILOAD, 1));
                                                replacement.add(new InsnNode(Opcodes.I2L));
                                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/TpUtil", "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", false));
                                                
                                                // Remove the pattern instructions: LDC, getInt, ...computation..., LDC, setInt
                                                // Keep startNode (ALOAD 0)
                                                // Remove from LDC (i-1) to setMin (inclusive)
                                                List<AbstractInsnNode> toRemove = new ArrayList<>();
                                                // Add LDC before getInt
                                                toRemove.add(ldc);
                                                // Add getInt
                                                toRemove.add(min);
                                                // Add instructions between getInt and setLdc
                                                for (int k = i + 1; k < j - 1; k++) {
                                                    toRemove.add(arr[k]);
                                                }
                                                // Add setLdc and setMin
                                                toRemove.add(setLdc);
                                                toRemove.add(setMin);
                                                
                                                for (AbstractInsnNode node : toRemove) {
                                                    m.instructions.remove(node);
                                                }
                                                
                                                // Insert replacement after startNode (ALOAD 0)
                                                m.instructions.insert(startNode, replacement);
                                                
                                                System.out.println("  tpalgn: replaced gain site near index " + approxIndex + " with TpUtil.addTP");
                                                return;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("  tpalgn: WARNING - gain site near index " + approxIndex + " not found");
    }

    static void patchPacHanSHandleTech(MethodNode m, ClassNode cn) {
        System.out.println("  PacHanS.handleTech: patching gain/spend sites");
        // Gain sites: player-based getInt(jrmcTpint) + IADD + setInt(jrmcTpint)
        // Pattern: ALOAD player, LDC jrmcTpint, INVOKESTATIC getInt -> ISTORE
        // ... ILOAD gain, IADD ...
        // ALOAD player, LDC jrmcTpint, INVOKESTATIC setInt
        // Replace with: ALOAD player, <gain>, I2L, INVOKESTATIC addTP
        // DISABLED: surgical patch causes frame verification issues
        // patchPacHanSHandleTechGain(m);
        // The generic rewrite will handle getInt/setInt -> getTPInt/setTPMirror
    }

    static void patchPacHanSHandleTechGain(MethodNode m) {
        System.out.println("  patchPacHanSHandleTechGain: patching gain sites");
        
        // Patch both gain sites in a single pass to avoid stale array issues
        // Site 1: around index 430-440, player=3, gain=12
        // Site 2: around index 468-478, player=3, gain=11
        // We'll search for all gain sites and patch them
        
        boolean patchedSite1 = false;
        boolean patchedSite2 = false;
        
        // Keep searching until both sites are patched or no more sites found
        while (!patchedSite1 || !patchedSite2) {
            AbstractInsnNode[] arr = m.instructions.toArray();
            boolean anyPatched = false;
            
            for (int i = 0; i < arr.length; i++) {
                AbstractInsnNode insn = arr[i];
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode min = (MethodInsnNode)insn;
                    if ("getInt".equals(min.name) && JRMCoreH.equals(min.owner)
                            && "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I".equals(min.desc)) {
                        if (i > 0 && arr[i - 1] instanceof LdcInsnNode) {
                            LdcInsnNode ldc = (LdcInsnNode)arr[i - 1];
                            if ("jrmcTpint".equals(ldc.cst)) {
                                // Found getInt - look for matching setInt
                                for (int j = i + 1; j < Math.min(arr.length, i + 20); j++) {
                                    if (arr[j] instanceof MethodInsnNode) {
                                        MethodInsnNode setMin = (MethodInsnNode)arr[j];
                                        if ("setInt".equals(setMin.name) && JRMCoreH.equals(setMin.owner)
                                                && "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V".equals(setMin.desc)) {
                                            if (j > 0 && arr[j - 1] instanceof LdcInsnNode) {
                                                LdcInsnNode setLdc = (LdcInsnNode)arr[j - 1];
                                                if ("jrmcTpint".equals(setLdc.cst)) {
                                                    // Check if there's an IADD between getInt and setInt
                                                    boolean hasIadd = false;
                                                    for (int k = i; k < j; k++) {
                                                        if (arr[k] instanceof InsnNode && arr[k].getOpcode() == Opcodes.IADD) {
                                                            hasIadd = true;
                                                            break;
                                                        }
                                                    }
                                                    if (hasIadd) {
                                                        // Determine which site this is based on the ISTORE target
                                                        int gainVar = -1;
                                                        int playerVar = 3;
                                                        // Find the ISTORE after getInt
                                                        for (int k = i + 1; k < j; k++) {
                                                            if (arr[k] instanceof VarInsnNode) {
                                                                VarInsnNode var = (VarInsnNode)arr[k];
                                                                if (var.getOpcode() == Opcodes.ISTORE) {
                                                                    gainVar = var.var;
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                        
                                                        // Site 1 uses ISTORE 13, site 2 uses ISTORE 12
                                                        boolean isSite1 = (gainVar == 13 && !patchedSite1);
                                                        boolean isSite2 = (gainVar == 12 && !patchedSite2);
                                                        
                                                        if (isSite1 || isSite2) {
                                                            // This is a gain site - replace it surgically
                                                            // Pattern: ALOAD player, LDC, getInt, ...computation (ISTORE, ILOAD, IADD)..., LDC, setInt
                                                            // Keep the first ALOAD player, replace the rest with: ILOAD gain, I2L, INVOKESTATIC addTP
                                                            
                                                            // Find the first ALOAD player (for getInt)
                                                            AbstractInsnNode startNode = null;
                                                            for (int k = i - 1; k >= 0; k--) {
                                                                if (arr[k] instanceof VarInsnNode) {
                                                                    VarInsnNode var = (VarInsnNode)arr[k];
                                                                    if (var.getOpcode() == Opcodes.ALOAD && var.var == playerVar) {
                                                                        startNode = var;
                                                                        break;
                                                                    }
                                                                }
                                                            }
                                                            if (startNode == null) {
                                                                System.out.println("  handleTech: WARNING - could not find startNode for gain site (ISTORE " + gainVar + ")");
                                                                continue;
                                                            }
                                                            
                                                            // Build replacement: ILOAD gain, I2L, INVOKESTATIC addTP
                                                            // (ALOAD player is already there as startNode)
                                                            InsnList replacement = newInsnList();
                                                            replacement.add(new VarInsnNode(Opcodes.ILOAD, gainVar)); // ILOAD gain
                                                            replacement.add(new InsnNode(Opcodes.I2L));
                                                            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/TpUtil", "addTP", "(Lnet/minecraft/entity/player/EntityPlayer;J)V", false));
                                                            
                                                            // Remove the pattern instructions: LDC, getInt, ...computation..., LDC, setInt
                                                            // Keep startNode (ALOAD player)
                                                            // Remove from LDC (i-1) to setMin (inclusive)
                                                            List<AbstractInsnNode> toRemove = new ArrayList<>();
                                                            // Add LDC before getInt
                                                            toRemove.add(ldc);
                                                            // Add getInt
                                                            toRemove.add(min);
                                                            // Add instructions between getInt and setLdc
                                                            for (int k = i + 1; k < j - 1; k++) {
                                                                toRemove.add(arr[k]);
                                                            }
                                                            // Add setLdc and setMin
                                                            toRemove.add(setLdc);
                                                            toRemove.add(setMin);
                                                            
                                                            for (AbstractInsnNode node : toRemove) {
                                                                m.instructions.remove(node);
                                                            }
                                                            
                                                            // Insert replacement after startNode (ALOAD player)
                                                            m.instructions.insert(startNode, replacement);
                                                            System.out.println("  handleTech: replaced gain site (ISTORE " + gainVar + ") with TpUtil.addTP");
                                                            
                                                            if (isSite1) patchedSite1 = true;
                                                            if (isSite2) patchedSite2 = true;
                                                            anyPatched = true;
                                                            break; // Restart scan since instructions changed
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (!anyPatched) {
                if (!patchedSite1) System.out.println("  handleTech: WARNING - site 1 (ISTORE 13) not found or already patched");
                if (!patchedSite2) System.out.println("  handleTech: WARNING - site 2 (ISTORE 12) not found or already patched");
                break;
            }
        }
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
        System.out.println("  patchComTickHGain: checking for gain site near index " + getIntIndex);
        // The generic rewrite already handles getInt/setInt for jrmcTpint in updatePlayersData
        // This method would handle actual TP gain sites (getInt + IADD + setInt)
        // If any exist, they would be replaced with TpUtil.addTP
        AbstractInsnNode[] arr = m.instructions.toArray();
        
        int searchStart = Math.max(0, getIntIndex - 10);
        int searchEnd = Math.min(arr.length, getIntIndex + 30);
        
        for (int i = searchStart; i < searchEnd; i++) {
            AbstractInsnNode insn = arr[i];
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode)insn;
                if ("func_74762_e".equals(min.name) && "net/minecraft/nbt/NBTTagCompound".equals(min.owner)
                        && "(Ljava/lang/String;)I".equals(min.desc)) {
                    if (i > 0 && arr[i - 1] instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode)arr[i - 1];
                        if ("jrmcTpint".equals(ldc.cst)) {
                            // Check if there's an IADD following (indicating a gain)
                            boolean hasIadd = false;
                            for (int j = i + 1; j < Math.min(arr.length, i + 20); j++) {
                                if (arr[j] instanceof InsnNode && arr[j].getOpcode() == Opcodes.IADD) {
                                    hasIadd = true;
                                    break;
                                }
                                // Stop at branch or method call
                                if (arr[j] instanceof JumpInsnNode || arr[j] instanceof MethodInsnNode) {
                                    break;
                                }
                            }
                            if (hasIadd) {
                                System.out.println("  ComTickH.updatePlayersData: found gain site near index " + getIntIndex + " - would patch with addTP");
                                // The generic rewrite already handles this via getTPInt/setTPMirrorNbt
                                // Actual addTP would require more complex local variable tracking
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    }