import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Uso: java ScanRefs <jar> <regex>   (procura em MethodInsnNode e FieldInsnNode) */
public class ScanRefs {
    public static void main(String[] a) throws Exception {
        String jar = a[0];
        String re = a[1];
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
                    int i = 0;
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        int idx = i++;
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode mi = (MethodInsnNode) insn;
                            String s = mi.owner + "." + mi.name + mi.desc;
                            if (s.matches(".*" + re + ".*")) {
                                System.out.println(cn.name + "." + m.name + m.desc + " @" + idx + " CALL " + s);
                            }
                        } else if (insn instanceof FieldInsnNode) {
                            FieldInsnNode fi = (FieldInsnNode) insn;
                            String s = fi.owner + "." + fi.name + ":" + fi.desc;
                            if (s.matches(".*" + re + ".*")) {
                                System.out.println(cn.name + "." + m.name + m.desc + " @" + idx + " FIELD " + s);
                            }
                        }
                    }
                }
            }
        }
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
