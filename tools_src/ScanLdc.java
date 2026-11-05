import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Uso: java ScanLdc <jar> <literal> [contexto]
 * Lista LDC de uma string constante com o contexto seguinte.
 */
public class ScanLdc {
    public static void main(String[] a) throws Exception {
        String jar = a[0];
        String lit = a[1];
        int ctx = a.length > 2 ? Integer.parseInt(a[2]) : 3;
        try (ZipFile zip = new ZipFile(new File(jar))) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                byte[] data = read(zip, e);
                ClassNode cn = new ClassNode();
                try {
                    new ClassReader(data).accept(cn, 0);
                } catch (Throwable t) {
                    continue;
                }
                for (MethodNode m : cn.methods) {
                    AbstractInsnNode[] arr = m.instructions.toArray();
                    for (int i = 0; i < arr.length; i++) {
                        if (!(arr[i] instanceof LdcInsnNode)) continue;
                        Object cst = ((LdcInsnNode) arr[i]).cst;
                        if (!lit.equals(cst)) continue;
                        System.out.println(cn.name + "." + m.name + m.desc + " @" + i);
                        for (int j = Math.max(0, i - 2); j <= Math.min(arr.length - 1, i + ctx); j++) {
                            System.out.println("   " + (j == i ? ">>" : "  ") + " [" + j + "] " + str(arr[j]));
                        }
                    }
                }
            }
        }
    }

    static String str(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        String base = op < 0 ? "PSEUDO" : org.objectweb.asm.util.Printer.OPCODES[op];
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode mi = (MethodInsnNode) insn;
            return base + " " + mi.owner + "." + mi.name + mi.desc;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode fi = (FieldInsnNode) insn;
            return base + " " + fi.owner + "." + fi.name + ":" + fi.desc;
        }
        if (insn instanceof VarInsnNode) return base + " " + ((VarInsnNode) insn).var;
        if (insn instanceof LdcInsnNode) return base + " " + ((LdcInsnNode) insn).cst;
        if (insn instanceof IntInsnNode) return base + " " + ((IntInsnNode) insn).operand;
        if (insn instanceof JumpInsnNode) return base + " -> @";
        return base;
    }

    static byte[] read(ZipFile zip, ZipEntry e) throws Exception {
        InputStream is = zip.getInputStream(e);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
