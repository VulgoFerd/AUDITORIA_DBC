import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Auditoria: lista TODOS os acessos a JinRyuu/JRMCore/JRMCoreH.curTP:I em um
 * JAR. Usado para provar que a lista de sites cobre cada acesso.
 *
 * uso: AuditCurTp <jar> [jar...]
 */
public final class AuditCurTp {

    public static void main(String[] args) throws Exception {
        int totalRead = 0;
        int totalWrite = 0;
        for (String arg : args) {
            File jar = new File(arg);
            int reads = 0;
            int writes = 0;
            System.out.println("== " + jar.getName() + " ==");
            try (ZipFile zip = new ZipFile(jar)) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (en.isDirectory() || !en.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] raw;
                    try (InputStream is = zip.getInputStream(en)) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        raw = out.toByteArray();
                    }
                    ClassNode cn = new ClassNode();
                    new ClassReader(raw).accept(cn, 0);
                    for (MethodNode mn : cn.methods) {
                        for (AbstractInsnNode insn : mn.instructions.toArray()) {
                            if (!(insn instanceof FieldInsnNode)) {
                                continue;
                            }
                            FieldInsnNode fi = (FieldInsnNode) insn;
                            if (!"JinRyuu/JRMCore/JRMCoreH".equals(fi.owner)
                                    || !"curTP".equals(fi.name) || !"I".equals(fi.desc)) {
                                continue;
                            }
                            int op = fi.getOpcode();
                            String kind;
                            if (op == Opcodes.GETSTATIC || op == Opcodes.GETFIELD) {
                                kind = "READ";
                                reads++;
                            } else {
                                kind = "WRITE";
                                writes++;
                            }
                            System.out.println(kind + "\t" + cn.name + "." + mn.name + mn.desc);
                        }
                    }
                }
            }
            System.out.println("  reads=" + reads + " writes=" + writes);
            totalRead += reads;
            totalWrite += writes;
        }
        System.out.println("TOTAL reads=" + totalRead + " writes=" + totalWrite);
    }
}
