import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.zip.*;

public class ExtractMethod {
    public static void main(String[] args) throws Exception {
        String jarPath = args[0];
        String className = args[1];
        String methodName = args[2];
        
        try (ZipFile zip = new ZipFile(jarPath)) {
            ZipEntry entry = zip.getEntry(className.replace('.', '/') + ".class");
            if (entry == null) {
                System.err.println("Class not found: " + className);
                return;
            }
            
            ClassReader cr = new ClassReader(zip.getInputStream(entry));
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            
            for (MethodNode m : cn.methods) {
                if (m.name.equals(methodName)) {
                    System.out.println("Method: " + m.name + m.desc);
                    System.out.println("MaxStack: " + m.maxStack + " MaxLocals: " + m.maxLocals);
                    System.out.println("Code:");
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        System.out.println("  " + insn.getOpcode() + " " + insn.getClass().getSimpleName() + " " + 
                            (insn instanceof MethodInsnNode ? ((MethodInsnNode)insn).owner + "." + ((MethodInsnNode)insn).name + ((MethodInsnNode)insn).desc : 
                             insn instanceof FieldInsnNode ? ((FieldInsnNode)insn).owner + "." + ((FieldInsnNode)insn).name + ((FieldInsnNode)insn).desc :
                             insn instanceof LdcInsnNode ? ((LdcInsnNode)insn).cst :
                             insn instanceof VarInsnNode ? ((VarInsnNode)insn).var :
                             insn instanceof TypeInsnNode ? ((TypeInsnNode)insn).desc :
                             ""));
                    }
                    System.out.println("\nTryCatchBlocks:");
                    if (m.tryCatchBlocks != null) {
                        for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
                            System.out.println("  " + tcb.start.getOpcode() + " to " + tcb.end.getOpcode() + " handler " + tcb.handler.getOpcode() + " type " + tcb.type);
                        }
                    }
                    System.out.println("\nLocalVariables:");
                    if (m.localVariables != null) {
                        for (LocalVariableNode lv : m.localVariables) {
                            System.out.println("  " + lv.name + " " + lv.desc + " slot " + lv.index + " start " + lv.start.getOpcode() + " end " + lv.end.getOpcode());
                        }
                    }
                    return;
                }
            }
            System.err.println("Method not found: " + methodName);
        }
    }
}