import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reescreve JRMCoreH.getMaxTP()I como "ldc Integer.MAX_VALUE; ireturn".
 * Corrige o bytecode invalido gerado por PatchBig.java (que emitia RETURN void).
 *
 * Uso: java FixMaxTP <jar-entrada> <jar-saida>
 */
public class FixMaxTP {

    static final String TARGET = "JinRyuu/JRMCore/JRMCoreH.class";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Uso: java FixMaxTP <jar-entrada> <jar-saida>");
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

                if (entry.getName().equals(TARGET)) {
                    data = patch(data);
                    patched++;
                    System.out.println("PATCHED " + entry.getName());
                }

                ZipEntry ne = new ZipEntry(entry.getName());
                ne.setTime(entry.getTime());
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }
        }
        if (patched != 1) throw new IllegalStateException("esperava 1 classe patchada, obtive " + patched);
        System.out.println("OK -> " + out.getAbsolutePath());
    }

    static byte[] patch(byte[] data) {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);

        boolean found = false;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("getMaxTP") || !m.desc.equals("()I")) continue;

            m.instructions.clear();
            m.tryCatchBlocks.clear();
            if (m.localVariables != null) m.localVariables.clear();
            m.instructions.add(new LdcInsnNode(Integer.MAX_VALUE));
            m.instructions.add(new InsnNode(Opcodes.IRETURN));
            found = true;
            System.out.println("  getMaxTP()I -> ldc " + Integer.MAX_VALUE + "; ireturn");
        }
        if (!found) throw new IllegalStateException("getMaxTP()I nao encontrado");

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
