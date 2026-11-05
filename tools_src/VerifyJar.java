import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Valida um jar inteiro antes de instalar.
 *
 * Uso: java VerifyJar <jar> [--expect-long-curTP] [--no-frames] [--classes=a,b,c] [--audit-descriptors]
 *
 *  - frames: reescreve cada metodo com COMPUTE_FRAMES; qualquer metodo que estoure
 *    reprova o jar (e o mesmo erro que o Mixin acusa em runtime).
 *  - refs: lista declaracoes e referencias ao campo curTP com o descritor real.
 *  - --expect-long-curTP: falha se existir referencia curTP:I.
 *  - --audit-descriptors: audita todos callsites para verificar se descritores batem.
 */
public class VerifyJar {

    static final String OWNER = "JinRyuu/JRMCore/JRMCoreH";
    static final String FIELD = "curTP";

    static int classes = 0;
    static int methods = 0;
    static int badFrames = 0;
    static int badRefs = 0;
    static final List<String> failures = new ArrayList<String>();
    static final Map<String, int[]> refs = new TreeMap<String, int[]>(); // "owner.desc" -> {count}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("uso: VerifyJar <jar> [--expect-long-curTP] [--no-frames] [--classes=a,b,c]");
            System.exit(2);
        }
        String jar = args[0];
        boolean expectLong = false;
        boolean checkFrames = true;
        Set<String> only = null;
        boolean auditDescriptors = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--expect-long-curTP")) expectLong = true;
            else if (args[i].equals("--no-frames")) checkFrames = false;
            else if (args[i].startsWith("--classes=")) {
                only = new HashSet<String>();
                for (String s : args[i].substring("--classes=".length()).split(",")) {
                    if (s.trim().length() > 0) only.add(s.trim().replace('.', '/'));
                }
            } else if (args[i].equals("--audit-descriptors")) {
                auditDescriptors = true;
            } else {
                System.err.println("argumento desconhecido: " + args[i]);
                System.exit(2);
            }
        }

        ZipFile zip = new ZipFile(new File(jar));
        try {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                String internal = entry.getName().substring(0, entry.getName().length() - ".class".length());
                if (only != null && !only.contains(internal)) continue;
                byte[] data = read(zip, entry);
                scan(jar, entry.getName(), data, checkFrames, expectLong, auditDescriptors);
            }
        } finally {
            zip.close();
        }

        System.out.println();
        System.out.println("classes: " + classes + " | metodos: " + methods
                + " | frames invalidos: " + badFrames + " | refs curTP:I com --expect-long-curTP: " + badRefs);
        if (!refs.isEmpty()) {
            System.out.println("refs curTP:");
            for (Map.Entry<String, int[]> r : refs.entrySet()) {
                System.out.println("  " + r.getKey() + " x" + r.getValue()[0]);
            }
        }
        if (badFrames > 0 || badRefs > 0) {
            System.out.println("FALHAS:");
            for (String f : failures) System.out.println("  " + f);
            System.out.println("FAIL " + jar);
            System.exit(1);
        }
        System.out.println("OK " + classes + " classes");
    }

    static void scan(String jar, String entryName, byte[] data, boolean checkFrames, boolean expectLong, boolean auditDescriptors) {
        classes++;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(data).accept(cn, 0);
        } catch (Throwable t) {
            failures.add(entryName + " -> nao parseia: " + t);
            badFrames++;
            return;
        }

        for (FieldNode f : cn.fields) {
            if (!FIELD.equals(f.name)) continue;
            record(cn.name, f.desc);
        }

        for (MethodNode m : cn.methods) {
            methods++;
            int insnIndex = 0;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                int curIndex = insnIndex++;
                if (!(insn instanceof FieldInsnNode)) continue;
                FieldInsnNode fi = (FieldInsnNode) insn;
                if (!FIELD.equals(fi.name)) continue;
                record(fi.owner, fi.desc);
                boolean isRead = fi.getOpcode() == Opcodes.GETSTATIC || fi.getOpcode() == Opcodes.GETFIELD;
                if (expectLong && OWNER.equals(fi.owner) && "I".equals(fi.desc) && isRead) {
                    badRefs++;
                    failures.add(entryName + " " + m.name + m.desc + " @" + curIndex + " op=" + fi.getOpcode() + " -> ref curTP:I (leitura)");
                }
            }
            if (!checkFrames || m.instructions.size() == 0) continue;
            String err = tryCompute(cn, m);
            if (err != null) {
                badFrames++;
                failures.add(entryName + " " + m.name + m.desc + " -> " + err);
            }
        }

        // Descriptor audit
        if (auditDescriptors) {
            auditDescriptors(entryName, cn);
        }
    }

    static void auditDescriptors(String entryName, ClassNode cn) {
        // Known method descriptor changes to verify - all valid overloads per method
        Map<String, Map<String, java.util.Set<String>>> expectedDescriptors = new HashMap<String, Map<String, java.util.Set<String>>>();

        // JRMCoreH methods
        Map<String, java.util.Set<String>> jrmcH = new HashMap<String, java.util.Set<String>>();
        jrmcH.put("getTPInt", new java.util.HashSet<String>(java.util.Arrays.asList("(Ljava/lang/Object;)I", "(Lnet/minecraft/entity/player/EntityPlayer;)I")));
        jrmcH.put("setTPMirror", new java.util.HashSet<String>(java.util.Arrays.asList("(ILnet/minecraft/entity/player/EntityPlayer;)V")));
        jrmcH.put("setTPMirrorNbt", new java.util.HashSet<String>(java.util.Arrays.asList("(Ljava/lang/Object;I)V")));
        jrmcH.put("getTPTotal", new java.util.HashSet<String>(java.util.Arrays.asList("(Ljava/lang/Object;)J", "(Lnet/minecraft/entity/player/EntityPlayer;)J")));
        jrmcH.put("setTPTotal", new java.util.HashSet<String>(java.util.Arrays.asList("(Ljava/lang/Object;J)V")));
        jrmcH.put("clampTP", new java.util.HashSet<String>(java.util.Arrays.asList("(J)I")));
        jrmcH.put("addTP", new java.util.HashSet<String>(java.util.Arrays.asList("(Ljava/lang/Object;J)V", "(Lnet/minecraft/entity/player/EntityPlayer;J)V")));
        jrmcH.put("addTPToPlayer", new java.util.HashSet<String>(java.util.Arrays.asList("(ILnet/minecraft/entity/player/EntityPlayer;)V")));
        jrmcH.put("setTPTotalClient", new java.util.HashSet<String>(java.util.Arrays.asList("(J)V")));
        jrmcH.put("handleStats2", new java.util.HashSet<String>(java.util.Arrays.asList("(JIB[I)V")));
        expectedDescriptors.put("JinRyuu/JRMCore/JRMCoreH", jrmcH);

        // TpUtil methods
        Map<String, java.util.Set<String>> tpUtil = new HashMap<String, java.util.Set<String>>();
        tpUtil.put("getTP", new java.util.HashSet<String>(java.util.Arrays.asList("(Lnet/minecraft/nbt/NBTTagCompound;)J", "(Lnet/minecraft/entity/player/EntityPlayer;)J")));
        tpUtil.put("writeTP", new java.util.HashSet<String>(java.util.Arrays.asList("(Lnet/minecraft/nbt/NBTTagCompound;J)V")));
        tpUtil.put("addTP", new java.util.HashSet<String>(java.util.Arrays.asList("(Lnet/minecraft/nbt/NBTTagCompound;J)V", "(Lnet/minecraft/entity/player/EntityPlayer;J)V")));
        tpUtil.put("setMirror", new java.util.HashSet<String>(java.util.Arrays.asList("(Lnet/minecraft/nbt/NBTTagCompound;I)V", "(Lnet/minecraft/entity/player/EntityPlayer;I)V")));
        tpUtil.put("getTPInt", new java.util.HashSet<String>(java.util.Arrays.asList("(Lnet/minecraft/nbt/NBTTagCompound;)I", "(Lnet/minecraft/entity/player/EntityPlayer;)I")));
        expectedDescriptors.put("JinRyuu/JRMCore/TpUtil", tpUtil);

        // PacHanC
        Map<String, java.util.Set<String>> pacHanC = new HashMap<String, java.util.Set<String>>();
        pacHanC.put("handleStats2", new java.util.HashSet<String>(java.util.Arrays.asList("(JIB[I)V", "(JIB[ILnet/minecraft/entity/player/EntityPlayer;)V")));
        expectedDescriptors.put("JinRyuu/JRMCore/JRMCorePacHanC", pacHanC);

        // PStats2
        Map<String, java.util.Set<String>> pStats2 = new HashMap<String, java.util.Set<String>>();
        pStats2.put("<init>", new java.util.HashSet<String>(java.util.Arrays.asList("(JIB[I)V")));
        pStats2.put("toBytes", new java.util.HashSet<String>(java.util.Arrays.asList("(Ljava/io/DataOutput;)V")));
        pStats2.put("fromBytes", new java.util.HashSet<String>(java.util.Arrays.asList("(Ljava/io/DataInput;)V")));
        expectedDescriptors.put("JinRyuu/JRMCore/JRMCorePStats2", pStats2);

        // Check method calls
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    Map<String, java.util.Set<String>> expected = expectedDescriptors.get(min.owner);
                    if (expected != null) {
                        java.util.Set<String> expectedDescs = expected.get(min.name);
                        if (expectedDescs != null && !expectedDescs.contains(min.desc)) {
                            badRefs++;
                            failures.add(entryName + " " + m.name + m.desc + " -> call " + min.owner + "." + min.name + min.desc + " expected one of " + expectedDescs);
                        }
                    }
                }
            }
        }
    }

    static void record(String owner, String desc) {
        String key = owner + "." + FIELD + ":" + desc;
        int[] c = refs.get(key);
        if (c == null) refs.put(key, new int[]{1});
        else c[0]++;
    }

    static String tryCompute(ClassNode original, MethodNode method) {
        ClassNode solo = new ClassNode();
        solo.version = original.version;
        solo.access = original.access;
        solo.name = original.name;
        solo.superName = original.superName;
        solo.interfaces = original.interfaces;
        solo.methods.add(method);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        try {
            solo.accept(cw);
            cw.toByteArray();
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
    }

    static byte[] read(ZipFile zip, ZipEntry entry) throws Exception {
        InputStream is = zip.getInputStream(entry);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
