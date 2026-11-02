import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.zip.*;

public class InspectChain {
    public static void main(String[] args) throws Exception {
        String jarPath = args[0];
        try (ZipFile zip = new ZipFile(jarPath)) {
            String[] classes = {
                "JinRyuu/JRMCore/p/JRMCorePStats2.class",
                "JinRyuu/JRMCore/p/JRMCorePStats2$Handler.class",
                "JinRyuu/JRMCore/JRMCorePacHanC.class",
                "JinRyuu/JRMCore/JRMCoreH.class"
            };
            for (String c : classes) {
                ZipEntry entry = zip.getEntry(c);
                if (entry == null) { System.out.println("MISSING " + c); continue; }
                byte[] data = zip.getInputStream(entry).readAllBytes();
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);
                System.out.println("### " + c);
                for (FieldNode f : cn.fields) {
                    if (f.name.equals("curTP") || f.name.equals("curExp")) System.out.println("  FIELD " + f.name + " " + f.desc);
                }
                for (MethodNode m : cn.methods) {
                    if (m.name.equals("Stats2") || m.name.equals("handleStats2") || m.name.equals("toBytes")
                        || m.name.equals("fromBytes") || m.name.equals("<init>") || m.name.equals("handleClientMessage")
                        || m.name.equals("getInt") || m.name.equals("setInt") || m.name.equals("getLongFromNBT")) {
                        System.out.println("  METHOD " + m.name + m.desc);
                        if (m.name.equals("handleClientMessage") || m.name.equals("Stats2") || m.name.equals("handleStats2")) {
                            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                                if (i instanceof MethodInsnNode) {
                                    MethodInsnNode mi = (MethodInsnNode) i;
                                    if (mi.owner.contains("JRMCorePStats2") || mi.owner.contains("JRMCorePacHan") || mi.name.equals("Stats2"))
                                        System.out.println("      CALL " + mi.owner + "." + mi.name + mi.desc);
                                } else if (i instanceof FieldInsnNode) {
                                    FieldInsnNode fi = (FieldInsnNode) i;
                                    if (fi.name.equals("curTP")) System.out.println("      FIELDOP " + fi.getOpcode() + " " + fi.owner + "." + fi.name + " " + fi.desc);
                                }
                            }
                        }
                    }
                }
                System.out.println();
            }
        }
    }
}
