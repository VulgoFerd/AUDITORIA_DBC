import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.Opcodes;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.io.ByteArrayOutputStream;

public class DebugMethod {
    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String clsName = args.length > 1 ? args[1] : "JinRyuu/JRMCore/JRMCoreComTickH.class";
        String methodName = args.length > 2 ? args[2] : "updatePlayersData";
        
        byte[] data = readEntry(jar, clsName);
        if (data == null) throw new IllegalStateException("entrada nao encontrada: " + clsName);

        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);
        System.out.println("Classe: " + cn.name);
        System.out.println("Super: " + cn.superName);
        System.out.println("Interfaces: " + cn.interfaces);
        
        for (MethodNode m : cn.methods) {
            if (m.name.equals(methodName)) {
                System.out.println("\nMetodo: " + m.name + m.desc);
                System.out.println("  access: " + m.access);
                System.out.println("  maxStack: " + m.maxStack);
                System.out.println("  maxLocals: " + m.maxLocals);
                System.out.println("  instructions: " + m.instructions.size());
                System.out.println("  localVariables: " + (m.localVariables != null ? m.localVariables.size() : 0));
                if (m.localVariables != null) {
                    for (Object lvObj : m.localVariables) {
                        org.objectweb.asm.tree.LocalVariableNode lv = (org.objectweb.asm.tree.LocalVariableNode) lvObj;
                        System.out.println("    LV: " + lv.name + " " + lv.desc + " slot=" + lv.index + " start=" + lv.start + " end=" + lv.end);
                    }
                }
                
                // Find all var instructions and their indices
                int maxVarIndex = -1;
                for (int i = 0; i < m.instructions.size(); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    if (insn instanceof VarInsnNode) {
                        VarInsnNode var = (VarInsnNode)insn;
                        maxVarIndex = Math.max(maxVarIndex, var.var);
                        if (var.var >= 10) { // Only print high indices
                            System.out.println("  [" + i + "] " + getOpcodeName(var.getOpcode()) + " " + var.var);
                        }
                    } else if (insn instanceof IincInsnNode) {
                        IincInsnNode iinc = (IincInsnNode)insn;
                        maxVarIndex = Math.max(maxVarIndex, iinc.var);
                    }
                }
                System.out.println("  Max var index used: " + maxVarIndex);
                
                // Check if maxLocals is sufficient
                if (m.maxLocals <= maxVarIndex) {
                    System.out.println("  WARNING: maxLocals (" + m.maxLocals + ") <= max var index (" + maxVarIndex + ")");
                }
                
                break;
            }
        }
    }
    
    static String getOpcodeName(int opcode) {
        switch (opcode) {
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.LLOAD: return "LLOAD";
            case Opcodes.FLOAD: return "FLOAD";
            case Opcodes.DLOAD: return "DLOAD";
            case Opcodes.ALOAD: return "ALOAD";
            case Opcodes.ISTORE: return "ISTORE";
            case Opcodes.LSTORE: return "LSTORE";
            case Opcodes.FSTORE: return "FSTORE";
            case Opcodes.DSTORE: return "DSTORE";
            case Opcodes.ASTORE: return "ASTORE";
            case Opcodes.RET: return "RET";
            default: return "VAR(" + opcode + ")";
        }
    }
    
    static byte[] readEntry(String jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(new File(jar))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().equals(name)) continue;
                InputStream is = zip.getInputStream(entry);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                return bos.toByteArray();
            }
        }
        return null;
    }
}