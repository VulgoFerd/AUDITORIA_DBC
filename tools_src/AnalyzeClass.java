import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AnalyzeClass {
    public static void main(String[] a) throws Exception {
        String jar = a[0];
        String targetClass = a[1];
        
        try (ZipFile zip = new ZipFile(new File(jar))) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                String className = e.getName().replace("/", ".").replace(".class", "");
                String entryName = e.getName().replace(".class", "");
                if (!className.equals(targetClass) && !entryName.equals(targetClass) && !e.getName().equals(targetClass)) continue;
                
                byte[] data = read(zip, e);
                ClassNode cn = new ClassNode();
                try {
                    new ClassReader(data).accept(cn, 0);
                } catch (Throwable t) {
                    System.out.println("Error reading class: " + t.getMessage());
                    continue;
                }
                
                System.out.println("=== Class: " + cn.name + " ===");
                System.out.println("Fields:");
                for (FieldNode fn : cn.fields) {
                    System.out.println("  " + fn.access + " " + fn.name + " " + fn.desc);
                }
                
                System.out.println("\nMethods:");
                for (MethodNode m : cn.methods) {
                    System.out.println("  " + m.access + " " + m.name + m.desc);
                    AbstractInsnNode[] arr = m.instructions.toArray();
                    for (int i = 0; i < arr.length; i++) {
                        AbstractInsnNode insn = arr[i];
                        if (insn instanceof FieldInsnNode) {
                            FieldInsnNode fin = (FieldInsnNode) insn;
                            String opcode = getOpcodeName(insn.getOpcode());
                            System.out.println("    [" + i + "] " + opcode + " " + fin.owner + "." + fin.name + ":" + fin.desc);
                        }
                        else if (insn instanceof MethodInsnNode) {
                            MethodInsnNode min = (MethodInsnNode) insn;
                            String opcode = getOpcodeName(insn.getOpcode());
                            System.out.println("    [" + i + "] " + opcode + " " + min.owner + "." + min.name + min.desc);
                        }
                        else if (insn instanceof LdcInsnNode) {
                            LdcInsnNode lin = (LdcInsnNode) insn;
                            System.out.println("    [" + i + "] LDC " + lin.cst);
                        }
                    }
                }
            }
        }
    }
    
    static String getOpcodeName(int op) {
        switch (op) {
            case 178: return "GETSTATIC";
            case 179: return "PUTSTATIC";
            case 180: return "GETFIELD";
            case 181: return "PUTFIELD";
            case 182: return "INVOKEVIRTUAL";
            case 183: return "INVOKESPECIAL";
            case 184: return "INVOKESTATIC";
            case 185: return "INVOKEINTERFACE";
            default: return "OP_" + op;
        }
    }
    
    static byte[] read(ZipFile zip, ZipEntry e) throws Exception {
        InputStream is = zip.getInputStream(e);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}