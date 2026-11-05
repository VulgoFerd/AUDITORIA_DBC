import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Classifica os sitios de jrmcTpint em leitura/escrita e agrupa pelos "shapes"
 * de instrucoes vizinhas, para provar que o patch e mecanico.
 *
 * Uso: java TpShapes <jar> [key]
 */
public class TpShapes {

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String key = args.length > 1 ? args[1] : "jrmcTpint";
        ZipFile zip = new ZipFile(new File(jar));
        Map<String, Integer> shapes = new TreeMap<String, Integer>();
        Map<String, List<String>> where = new LinkedHashMap<String, List<String>>();
        int read = 0, write = 0;
        try {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] data = read(zip, entry);
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);
                for (MethodNode m : cn.methods) {
                    List<AbstractInsnNode> all = real(m);
                    for (int i = 0; i < all.size(); i++) {
                        AbstractInsnNode insn = all.get(i);
                        if (!(insn instanceof LdcInsnNode)) continue;
                        Object c = ((LdcInsnNode) insn).cst;
                        if (!key.equals(c)) continue;
                        // acha o invoke que consome a string
                        int j = i + 1;
                        while (j < all.size() && !(all.get(j) instanceof MethodInsnNode)) j++;
                        if (j >= all.size()) continue;
                        MethodInsnNode call = (MethodInsnNode) all.get(j);
                        String kind = call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name + call.desc;
                        String dir = kind;
                        if (call.name.equals("func_74762_e")) read++;
                        else if (call.name.equals("func_74768_a")) write++;
                        String after = sig(all, j + 1, 3);
                        String before = sig(all, Math.max(0, i - 3), 3);
                        String shape = "CALL " + dir + " | next: " + after + " | prev: " + before;
                        Integer n = shapes.get(shape);
                        shapes.put(shape, n == null ? 1 : n + 1);
                        String loc = cn.name + "." + m.name + " @ " + i;
                        List<String> l = where.get(shape);
                        if (l == null) { l = new ArrayList<String>(); where.put(shape, l); }
                        l.add(loc);
                    }
                }
            }
        } finally {
            zip.close();
        }
        System.out.println("READ=" + read + " WRITE=" + write + " shapes=" + shapes.size());
        for (Map.Entry<String, Integer> e : shapes.entrySet()) {
            System.out.println();
            System.out.println("[" + e.getValue() + "x] " + e.getKey());
            for (String w : where.get(e.getKey())) System.out.println("      " + w);
        }
    }

    static String sig(List<AbstractInsnNode> all, int from, int n) {
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (int i = from; i < all.size() && c < n; i++) {
            AbstractInsnNode x = all.get(i);
            if (x instanceof LabelNode || x instanceof LineNumberNode) continue;
            if (c > 0) sb.append(" ; ");
            sb.append(shortName(x));
            c++;
        }
        return sb.toString();
    }

    static String shortName(AbstractInsnNode x) {
        if (x instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) x;
            String owner = m.owner.substring(m.owner.lastIndexOf('/') + 1);
            return owner + "." + m.name + m.desc;
        }
        if (x instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) x;
            return "F:" + f.owner.substring(f.owner.lastIndexOf('/') + 1) + "." + f.name + ":" + f.desc;
        }
        if (x instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) x).cst;
            return c instanceof String ? "\"" + c + "\"" : String.valueOf(c);
        }
        return x.getClass().getSimpleName() + "(" + x.getOpcode() + ")";
    }

    static List<AbstractInsnNode> real(MethodNode m) {
        List<AbstractInsnNode> out = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode x : m.instructions.toArray()) {
            if (x instanceof LabelNode || x instanceof LineNumberNode) continue;
            out.add(x);
        }
        return out;
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
