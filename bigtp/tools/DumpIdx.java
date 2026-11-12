import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.zip.*;

/** Mostra instrucoes por INDICE de um metodo (nao offset de bytecode). */
public final class DumpIdx {
    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String cls = args[1];
        String name = args[2];
        String desc = args[3];
        int from = Integer.parseInt(args[4]);
        int to = Integer.parseInt(args[5]);
        try (ZipFile zip = new ZipFile(new File(jar))) {
            ZipEntry en = zip.getEntry(cls + ".class");
            ClassNode cn = new ClassNode();
            new ClassReader(zip.getInputStream(en)).accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                if (!mn.name.equals(name) || !mn.desc.equals(desc)) {
                    continue;
                }
                AbstractInsnNode[] a = mn.instructions.toArray();
                for (int i = from; i <= to && i < a.length; i++) {
                    AbstractInsnNode n = a[i];
                    String extra = "";
                    if (n instanceof MethodInsnNode) {
                        MethodInsnNode m = (MethodInsnNode) n;
                        extra = " " + m.owner + "." + m.name + m.desc;
                    } else if (n instanceof FieldInsnNode) {
                        FieldInsnNode f = (FieldInsnNode) n;
                        extra = " " + f.owner + "." + f.name + ":" + f.desc;
                    } else if (n instanceof LdcInsnNode) {
                        extra = " " + ((LdcInsnNode) n).cst;
                    }
                    System.out.println(i + ": " + n.getClass().getSimpleName()
                            + " op=" + n.getOpcode() + extra);
                }
            }
        }
    }
}
