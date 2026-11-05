import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
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
 * Lista classes e metodos cujo bytecode difere entre dois jars.
 * Uso: java ClassDiff <jar-original> <jar-patchado> [prefixo]
 */
public class ClassDiff {

    public static void main(String[] args) throws Exception {
        String prefix = args.length > 2 ? args[2] : "";
        Map<String, byte[]> a = load(args[0], prefix);
        Map<String, byte[]> b = load(args[1], prefix);

        List<String> onlyB = new ArrayList<String>();
        List<String> changed = new ArrayList<String>();

        for (Map.Entry<String, byte[]> e : b.entrySet()) {
            byte[] orig = a.get(e.getKey());
            if (orig == null) {
                onlyB.add(e.getKey());
                continue;
            }
            if (!java.util.Arrays.equals(orig, e.getValue())) changed.add(e.getKey());
        }

        System.out.println("classes so no patchado: " + onlyB.size());
        for (String s : onlyB) System.out.println("  + " + s);

        System.out.println("classes alteradas: " + changed.size());
        for (String name : changed) {
            System.out.println("  ~ " + name);
            reportMethods(a.get(name), b.get(name));
        }
    }

    static void reportMethods(byte[] orig, byte[] patched) {
        Map<String, String> o = methods(orig);
        Map<String, String> p = methods(patched);
        for (Map.Entry<String, String> e : p.entrySet()) {
            String before = o.get(e.getKey());
            if (before == null) {
                System.out.println("      + metodo novo: " + e.getKey());
            } else if (!before.equals(e.getValue())) {
                System.out.println("      ~ " + e.getKey());
            }
        }
        for (String k : o.keySet()) if (!p.containsKey(k)) System.out.println("      - removido: " + k);
    }

    static Map<String, String> methods(byte[] data) {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);
        Map<String, String> out = new TreeMap<String, String>();
        for (MethodNode m : cn.methods) {
            StringBuilder sb = new StringBuilder();
            sb.append("acc=").append(m.access).append(" max=(").append(m.maxStack).append(',').append(m.maxLocals).append(')');
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                sb.append('|');
                sb.append(n.getOpcode() < 0 ? "LABEL" : String.valueOf(n.getOpcode()));
                if (n instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode mi = (org.objectweb.asm.tree.MethodInsnNode) n;
                    sb.append(':').append(mi.owner).append('.').append(mi.name).append(mi.desc);
                } else if (n instanceof org.objectweb.asm.tree.FieldInsnNode) {
                    org.objectweb.asm.tree.FieldInsnNode fi = (org.objectweb.asm.tree.FieldInsnNode) n;
                    sb.append(':').append(fi.owner).append('.').append(fi.name).append(':').append(fi.desc);
                } else if (n instanceof org.objectweb.asm.tree.VarInsnNode) {
                    sb.append(':').append(((org.objectweb.asm.tree.VarInsnNode) n).var);
                } else if (n instanceof org.objectweb.asm.tree.LdcInsnNode) {
                    sb.append(':').append(((org.objectweb.asm.tree.LdcInsnNode) n).cst);
                }
            }
            out.put(m.name + m.desc, sb.toString());
        }
        return out;
    }

    static Map<String, byte[]> load(String jar, String prefix) throws Exception {
        Map<String, byte[]> map = new LinkedHashMap<String, byte[]>();
        try (ZipFile zip = new ZipFile(new File(jar))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (!entry.getName().startsWith(prefix)) continue;
                InputStream is = zip.getInputStream(entry);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                map.put(entry.getName(), bos.toByteArray());
            }
        }
        return map;
    }
}
