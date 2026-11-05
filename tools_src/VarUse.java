import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Uso: java VarUse <jar> <classe-interna> <metodo-nome> <metodo-desc> <local> */
public class VarUse {
    public static void main(String[] a) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(read(a[0], a[1])).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if (!m.name.equals(a[2])) continue;
            if (a.length > 3 && !m.desc.equals(a[3])) continue;
            int local = Integer.parseInt(a[4]);
            AbstractInsnNode[] arr = m.instructions.toArray();
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) arr[i];
                    if (v.var == local) {
                        System.out.println("[" + i + "] " + print(arr[i]) + "  ctx: "
                                + ctx(arr, i - 2) + " | " + ctx(arr, i + 2));
                    }
                }
            }
        }
    }
    static String ctx(AbstractInsnNode[] arr, int i) {
        if (i < 0 || i >= arr.length) return "";
        return print(arr[i]);
    }
    static String print(AbstractInsnNode insn) {
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
        return base;
    }
    static byte[] read(String jar, String nm) throws Exception {
        try (ZipFile zip = new ZipFile(new File(jar))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.getName().equals(nm)) continue;
                InputStream is = zip.getInputStream(en);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                return bos.toByteArray();
            }
        }
        return null;
    }
}
