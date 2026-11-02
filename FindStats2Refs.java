import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class FindStats2Refs {
    public static void main(String[] args) throws Exception {
        String jarPath = args.length > 0 ? args[0]
            : "C:\\Users\\Fernando\\nntdarkmp\\mods\\JRMCore-v1.3.51.jar";

        try (ZipFile zip = new ZipFile(jarPath)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().endsWith(".class")) continue;

                byte[] data = zip.getInputStream(entry).readAllBytes();
                ClassNode cn = new ClassNode();
                try {
                    new ClassReader(data).accept(cn, 0);
                } catch (Exception ex) { continue; }

                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof FieldInsnNode) {
                            FieldInsnNode f = (FieldInsnNode) insn;
                            if (f.owner.equals("JinRyuu/JRMCore/p/JRMCorePStats2")) {
                                System.out.println(entry.getName() + " | " + m.name + m.desc
                                    + " | " + f.getOpcode() + " " + f.name + " " + f.desc);
                            }
                        } else if (insn instanceof MethodInsnNode) {
                            MethodInsnNode mi = (MethodInsnNode) insn;
                            if (mi.owner.equals("JinRyuu/JRMCore/p/JRMCorePStats2")) {
                                System.out.println(entry.getName() + " | " + m.name + m.desc
                                    + " | CALL " + mi.name + mi.desc);
                            }
                        }
                    }
                }
            }
        }
    }
}
