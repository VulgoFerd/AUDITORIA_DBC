import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifica bytecode estrito usando CheckClassAdapter + ClassLoader com hierarquia.
 *
 * Uso: java VerifyBytecode <jar> [--expect-long-curTP] [--classes=a,b,c] [--audit-descriptors]
 *
 * - frames: reescreve cada classe com COMPUTE_FRAMES e CheckClassAdapter (valida frames, stack, locals)
 * - --expect-long-curTP: falha se existir referência curTP:I
 * - --audit-descriptors: audita todos callsites para verificar se descritores batem
 */
public class VerifyBytecode {

    static final String OWNER = "JinRyuu/JRMCore/JRMCoreH";
    static final String FIELD = "curTP";

    static int classes = 0;
    static int badFrames = 0;
    static int badRefs = 0;
    static final List<String> failures = new ArrayList<String>();

    // Mapa de className -> byte[] para ClassLoader da hierarquia
    static Map<String, byte[]> classBytes = new HashMap<String, byte[]>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("uso: VerifyBytecode <jar> [--expect-long-curTP] [--classes=a,b,c] [--audit-descriptors]");
            System.exit(2);
        }
        String jar = args[0];
        boolean expectLong = false;
        Set<String> only = null;
        boolean auditDescriptors = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--expect-long-curTP")) expectLong = true;
            else if (args[i].startsWith("--classes=")) {
                only = new java.util.HashSet<String>();
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
            // First pass: load all class bytes for hierarchy
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                String internal = entry.getName().substring(0, entry.getName().length() - ".class".length());
                byte[] data = read(zip, entry);
                classBytes.put(internal, data);
            }

            // Second pass: verify each class
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                String internal = entry.getName().substring(0, entry.getName().length() - ".class".length());
                if (only != null && !only.contains(internal)) continue;
                byte[] data = classBytes.get(internal);
                verifyClass(internal, data, expectLong, auditDescriptors);
            }
        } finally {
            zip.close();
        }

        System.out.println();
        System.out.println("classes: " + classes + " | frames invalidos: " + badFrames + " | refs curTP:I: " + badRefs);
        if (badFrames > 0 || badRefs > 0) {
            System.out.println("FALHAS:");
            for (String f : failures) System.out.println("  " + f);
            System.out.println("FAIL " + jar);
            System.exit(1);
        }
        System.out.println("OK " + classes + " classes");
    }

    static void verifyClass(String name, byte[] data, boolean expectLong, boolean auditDescriptors) {
        classes++;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(data).accept(cn, 0);
        } catch (Throwable t) {
            failures.add(name + " -> nao parseia: " + t);
            badFrames++;
            return;
        }

        // Check field references
        for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
            if (!FIELD.equals(f.name)) continue;
            if (expectLong && OWNER.equals(cn.name) && "I".equals(f.desc)) {
                badRefs++;
                failures.add(name + " field " + f.name + ":" + f.desc + " -> field curTP:I (declaracao)");
            }
        }

        // Verify with CheckClassAdapter + COMPUTE_FRAMES using hierarchy ClassLoader
        try {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String a, String b) {
                    // Try to load from hierarchy bytes
                    byte[] ba = classBytes.get(a);
                    byte[] bb = classBytes.get(b);
                    if (ba == null || bb == null) {
                        return "java/lang/Object";
                    }
                    ClassReader ra = new ClassReader(ba);
                    ClassReader rb = new ClassReader(bb);
                    String sa = ra.getSuperName();
                    String sb = rb.getSuperName();
                    // Simple LCA: walk up
                    Set<String> ancestorsA = new java.util.HashSet<String>();
                    String cur = a;
                    while (cur != null) {
                        ancestorsA.add(cur);
                        byte[] bc = classBytes.get(cur);
                        if (bc == null) break;
                        cur = new ClassReader(bc).getSuperName();
                    }
                    cur = b;
                    while (cur != null) {
                        if (ancestorsA.contains(cur)) return cur;
                        byte[] bc = classBytes.get(cur);
                        if (bc == null) break;
                        cur = new ClassReader(bc).getSuperName();
                    }
                    return "java/lang/Object";
                }
            };
            // Use CheckClassAdapter for strict verification
            CheckClassAdapter cca = new CheckClassAdapter(cw, true);
            new ClassReader(data).accept(cca, 0);
            cw.toByteArray(); // Force frame computation
        } catch (Throwable t) {
            badFrames++;
            failures.add(name + " -> CheckClassAdapter: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Descriptor audit
        if (auditDescriptors) {
            auditDescriptors(cn);
        }
    }

    static void auditDescriptors(ClassNode cn) {
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
        for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode min = (org.objectweb.asm.tree.MethodInsnNode) insn;
                    Map<String, java.util.Set<String>> expected = expectedDescriptors.get(min.owner);
                    if (expected != null) {
                        java.util.Set<String> expectedDescs = expected.get(min.name);
                        if (expectedDescs != null && !expectedDescs.contains(min.desc)) {
                            badRefs++;
                            failures.add(cn.name + "." + m.name + m.desc + " -> call " + min.owner + "." + min.name + min.desc + " expected one of " + expectedDescs);
                        }
                    }
                }
            }
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