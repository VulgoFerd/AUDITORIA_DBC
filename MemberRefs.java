import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.TreeMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Catalogo de membros chamados num jar, agrupado por owner.
 *
 * Uso: java MemberRefs <jar> [ownerSubstring]
 */
public class MemberRefs {

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String filter = args.length > 1 ? args[1] : "";
        Map<String, Integer> calls = new TreeMap<String, Integer>();
        ZipFile zip = new ZipFile(new File(jar));
        try {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] data = read(zip, entry);
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        if (!(insn instanceof MethodInsnNode)) continue;
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (!mi.owner.contains(filter)) continue;
                        String key = mi.owner + "." + mi.name + mi.desc;
                        Integer n = calls.get(key);
                        calls.put(key, n == null ? 1 : n + 1);
                    }
                }
            }
        } finally {
            zip.close();
        }
        for (Map.Entry<String, Integer> e : calls.entrySet()) {
            System.out.println(e.getKey() + "  x" + e.getValue());
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
