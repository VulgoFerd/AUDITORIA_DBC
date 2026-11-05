import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lista refs a um campo com contexto de instrucoes.
 * Uso: java CurTPContext <jar> <classe-interna> <campo> [raio]
 */
public class CurTPContext {

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String cls = args[1];
        String field = args.length > 2 ? args[2] : "curTP";
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 8;

        byte[] data = readEntry(jar, cls);
        if (data == null) throw new IllegalStateException("entrada nao encontrada: " + cls);

        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);

        for (MethodNode m : cn.methods) {
            AbstractInsnNode[] arr = m.instructions.toArray();
            for (int i = 0; i < arr.length; i++) {
                if (!(arr[i] instanceof FieldInsnNode)) continue;
                FieldInsnNode fi = (FieldInsnNode) arr[i];
                if (!field.equals(fi.name)) continue;
                System.out.println("=== " + cn.name + "." + m.name + m.desc + " @" + i
                        + " op=" + fi.getOpcode() + " " + fi.owner + "." + fi.name + ":" + fi.desc);
                int from = Math.max(0, i - radius);
                int to = Math.min(arr.length - 1, i + radius);
                for (int j = from; j <= to; j++) {
                    System.out.println("   " + (j == i ? ">>" : "  ") + " [" + j + "] " + str(arr[j]));
                }
            }
        }
    }

    static String str(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        String base = name(op);
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode mi = (MethodInsnNode) insn;
            return base + " " + mi.owner + "." + mi.name + mi.desc;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode fi = (FieldInsnNode) insn;
            return base + " " + fi.owner + "." + fi.name + ":" + fi.desc;
        }
        if (insn instanceof JumpInsnNode) {
            return base + " -> label@" + System.identityHashCode(((JumpInsnNode) insn).label);
        }
        if (insn instanceof LdcInsnNode) {
            return base + " " + ((LdcInsnNode) insn).cst;
        }
        if (insn instanceof VarInsnNode) {
            return base + " " + ((VarInsnNode) insn).var;
        }
        if (insn instanceof IntInsnNode) {
            return base + " " + ((IntInsnNode) insn).operand;
        }
        if (insn instanceof LabelNode) {
            return "LABEL@" + System.identityHashCode(insn);
        }
        if (insn instanceof LineNumberNode) {
            return "LINE " + ((LineNumberNode) insn).line;
        }
        return base;
    }

    static String name(int op) {
        if (op < 0) return "PSEUDO";
        try {
            return org.objectweb.asm.util.Printer.OPCODES[op];
        } catch (Throwable t) {
            return "OP" + op;
        }
    }

    static byte[] readEntry(String jar, String nm) throws Exception {
        try (ZipFile zip = new ZipFile(new File(jar))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().equals(nm)) continue;
                InputStream is = zip.getInputStream(entry);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                return bos.toByteArray();
            }
        }
        return null;
    }
}
