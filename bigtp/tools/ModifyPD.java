import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public class ModifyPD {
    public static void main(String[] args) throws Exception {
        File inputJar = new File(args[0]);
        File outputJar = new File(args[1]);

        // Extract PD.class from input JAR
        Path tempDir = Files.createTempDirectory("modifyPD");
        File pdClass = tempDir.resolve("JinRyuu/JRMCore/p/PD.class").toFile();
        pdClass.getParentFile().mkdirs();

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(inputJar)) {
            java.util.jar.JarEntry entry = jar.getJarEntry("JinRyuu/JRMCore/p/PD.class");
            if (entry == null) {
                System.err.println("PD.class not found in JAR");
                System.exit(1);
            }
            try (InputStream is = jar.getInputStream(entry);
                 OutputStream os = new FileOutputStream(pdClass)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }
        }

        // Read and modify the class
        ClassReader cr = new ClassReader(Files.readAllBytes(pdClass.toPath()));
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // Find the <clinit> method (static initializer)
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name)) {
                System.out.println("Found <clinit> method, modifying...");
                InsnList insns = mn.instructions;

                // Change both "bipush 22" (array sizes) to "bipush 23"
                int bipushCount = 0;
                for (AbstractInsnNode ain : insns) {
                    if (ain instanceof IntInsnNode && ain.getOpcode() == Opcodes.BIPUSH) {
                        IntInsnNode iin = (IntInsnNode) ain;
                        if (iin.operand == 22) {
                            bipushCount++;
                            System.out.println("Found bipush 22 at index " + insns.indexOf(ain) + ", changing to 23 (count=" + bipushCount + ")");
                            iin.operand = 23;
                        }
                    }
                }

                // Find the last aastore for rp array (JRMCorePData3 at index 21)
                // The LDC is a Type with internalName "JinRyuu/JRMCore/p/JRMCorePData3"
                int lastRpAastore = -1;
                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode ain = insns.get(i);
                    if (ain instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) ain;
                        Object cst = ldc.cst;
                        if (cst instanceof Type) {
                            Type t = (Type) cst;
                            if (t.getInternalName().equals("JinRyuu/JRMCore/p/JRMCorePData3")) {
                                // Find the AASTORE after this
                                for (int j = i + 1; j < insns.size(); j++) {
                                    AbstractInsnNode n = insns.get(j);
                                    if (n instanceof InsnNode && n.getOpcode() == Opcodes.AASTORE) {
                                        lastRpAastore = j;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }

                // Find the last aastore for rph array (JRMCorePData3$Handler at index 21)
                int lastRphAastore = -1;
                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode ain = insns.get(i);
                    if (ain instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) ain;
                        Object cst = ldc.cst;
                        if (cst instanceof Type) {
                            Type t = (Type) cst;
                            if (t.getInternalName().equals("JinRyuu/JRMCore/p/JRMCorePData3$Handler")) {
                                // Find the AASTORE after this
                                for (int j = i + 1; j < insns.size(); j++) {
                                    AbstractInsnNode n = insns.get(j);
                                    if (n instanceof InsnNode && n.getOpcode() == Opcodes.AASTORE) {
                                        lastRphAastore = j;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }

                System.out.println("Last rp aastore at index: " + lastRpAastore);
                System.out.println("Last rph aastore at index: " + lastRphAastore);

                // Insert new entries after the last ones
                if (lastRpAastore >= 0) {
                    InsnList newRp = new InsnList();
                    newRp.add(new InsnNode(Opcodes.DUP));
                    newRp.add(new IntInsnNode(Opcodes.BIPUSH, 22));
                    // Use Type.getObjectType for the new class
                    newRp.add(new LdcInsnNode(Type.getObjectType("JinRyuu/JRMCore/p/JRMCorePTpSync")));
                    newRp.add(new InsnNode(Opcodes.AASTORE));

                    insns.insert(insns.get(lastRpAastore), newRp);
                    System.out.println("Inserted new rp entry at index 22 after aastore at " + lastRpAastore);
                } else {
                    System.err.println("ERROR: Could not find last rp aastore!");
                }

                // Re-find rph aastore since we inserted into rp array (4 instructions added)
                if (lastRphAastore >= 0) {
                    int adjustedRphAastore = lastRphAastore + 4;

                    InsnList newRph = new InsnList();
                    newRph.add(new InsnNode(Opcodes.DUP));
                    newRph.add(new IntInsnNode(Opcodes.BIPUSH, 22));
                    newRph.add(new LdcInsnNode(Type.getObjectType("JinRyuu/JRMCore/p/JRMCorePTpSync$Handler")));
                    newRph.add(new InsnNode(Opcodes.AASTORE));

                    insns.insert(insns.get(adjustedRphAastore), newRph);
                    System.out.println("Inserted new rph entry at index 22 after aastore at " + adjustedRphAastore);
                } else {
                    System.err.println("ERROR: Could not find last rph aastore!");
                }

                break;
            }
        }

        // Write the modified class back to the JAR
        File outFile = new File(tempDir.toFile(), "JinRyuu/JRMCore/p/PD.class");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        Files.write(outFile.toPath(), cw.toByteArray());

        // Create new JAR with modified PD.class
        try (java.util.jar.JarFile inJar = new java.util.jar.JarFile(inputJar);
             java.util.jar.JarOutputStream outJar = new java.util.jar.JarOutputStream(new FileOutputStream(outputJar))) {

            java.util.Enumeration<java.util.jar.JarEntry> entries = inJar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.getName().equals("JinRyuu/JRMCore/p/PD.class")) {
                    // Write modified class
                    java.util.jar.JarEntry newEntry = new java.util.jar.JarEntry(entry.getName());
                    outJar.putNextEntry(newEntry);
                    outJar.write(Files.readAllBytes(outFile.toPath()));
                    outJar.closeEntry();
                } else {
                    // Copy original entry
                    outJar.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                    try (InputStream is = inJar.getInputStream(entry)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            outJar.write(buffer, 0, len);
                        }
                    }
                    outJar.closeEntry();
                }
            }
        }

        System.out.println("Modified PD.class written to " + outputJar);

        // Cleanup
        deleteDir(tempDir.toFile());
    }

    private static void deleteDir(File dir) {
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }
}