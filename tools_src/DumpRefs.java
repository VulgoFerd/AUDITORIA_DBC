import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dump do contexto de instrucoes que tocam TP/dano num jar.
 *
 * Uso: java DumpRefs <jar> <needle> [needle2 ...]
 * needle: "curTP", "jrmcTpint", "parseInt", "numSep", "getMaxTP", "jrmcDam"
 */
public class DumpRefs {

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        Set<String> needles = new HashSet<String>(Arrays.asList(args).subList(1, args.length));
        ZipFile zip = new ZipFile(new File(jar));
        try {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] data = read(zip, entry);
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);
                for (MethodNode m : cn.methods) {
                    List<AbstractInsnNode> all = Arrays.asList(m.instructions.toArray());
                    for (int i = 0; i < all.size(); i++) {
                        String hit = match(all.get(i), needles);
                        if (hit == null) continue;
                        System.out.println("### " + cn.name + "." + m.name + m.desc + " @ " + i + "  [" + hit + "]");
                        int from = Math.max(0, i - 5);
                        int to = Math.min(all.size() - 1, i + 5);
                        for (int j = from; j <= to; j++) {
                            System.out.println((j == i ? "  > " : "    ") + j + ": " + all.get(j));
                        }
                    }
                }
            }
        } finally {
            zip.close();
        }
    }

    static String match(AbstractInsnNode insn, Set<String> needles) {
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            for (String n : needles) if (f.name.equals(n)) return f.owner + "." + f.name + ":" + f.desc;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            for (String n : needles) if (m.name.equals(n)) return m.owner + "." + m.name + m.desc;
        }
        if (insn instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) insn).cst;
            if (c instanceof String) {
                for (String n : needles) if (c.equals(n)) return "LDC \"" + c + "\"";
            }
        }
        return null;
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
