import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class VerifyClasses {
    public static void main(String[] args) throws Exception {
        String jarPath = "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\artifacts\\JRMCore-v1.3.51-bignum.jar";
        
        try (ZipFile zip = new ZipFile(jarPath)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                
                if (name.endsWith(".class") && (
                    name.contains("JRMCoreH") ||
                    name.contains("JRMCorePStats2") ||
                    name.contains("JRMCorePCost") ||
                    name.contains("JRMCorePacHanC") ||
                    name.contains("JRMCoreComTickH") ||
                    name.contains("ComJrmctp") ||
                    name.contains("JRMCorePacHanS")
                )) {
                    System.out.println("\n=== Verifying: " + name + " ===");
                    byte[] data = zip.getInputStream(entry).readAllBytes();
                    verifyClass(name, data);
                }
            }
        }
    }
    
    static void verifyClass(String name, byte[] data) {
        // Basic structural check - just read and parse
        System.out.println("1. Basic structural check...");
        try {
            ClassReader cr = new ClassReader(data);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            System.out.println("   [OK] Class structure valid");
        } catch (Exception ex) {
            System.out.println("   [FAIL] Class structure invalid: " + ex.getMessage());
            ex.printStackTrace(System.out);
            return;
        }
        
        // Print method signatures for manual inspection
        System.out.println("2. Method signatures in " + name + ":");
        try {
            ClassReader cr = new ClassReader(data);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            
            for (MethodNode mn : cn.methods) {
                if (mn.name.contains("TP") || mn.name.contains("Tp") || mn.name.contains("tp") ||
                    mn.name.contains("getLong") || mn.name.contains("setLong") || mn.name.contains("addLong") ||
                    mn.name.contains("getInt") || mn.name.contains("setInt") ||
                    mn.name.contains("applyPacket") ||
                    mn.name.contains("getMaxTP") ||
                    mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                    System.out.println("   " + mn.name + " " + mn.desc);
                }
            }
        } catch (Exception ex) {
            System.out.println("   [FAIL] Method listing FAILED: " + ex.getMessage());
            ex.printStackTrace(System.out);
        }
        
        // Check for Integer.MAX_VALUE constants used in long contexts
        System.out.println("3. Checking for Integer.MAX_VALUE constants...");
        try {
            ClassReader cr = new ClassReader(data);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            
            for (MethodNode mn : cn.methods) {
                AbstractInsnNode insn = mn.instructions.getFirst();
                while (insn != null) {
                    if (insn.getOpcode() == Opcodes.LDC) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        Object cst = ldc.cst;
                        if (cst instanceof Integer && (Integer)cst == Integer.MAX_VALUE) {
                            // Check if this is used in a Math.min(JJ)J context
                            AbstractInsnNode next = insn.getNext();
                            while (next != null && next.getOpcode() != Opcodes.INVOKESTATIC) {
                                next = next.getNext();
                            }
                            if (next != null && next.getOpcode() == Opcodes.INVOKESTATIC) {
                                MethodInsnNode min = (MethodInsnNode) next;
                                if (min.owner.equals("java/lang/Math") && min.name.equals("min") && min.desc.equals("(JJ)J")) {
                                    System.out.println("   [WARN] Method " + mn.name + ": Integer.MAX_VALUE (int) used for Math.min(JJ)J - should be long!");
                                }
                            }
                        }
                    }
                    insn = insn.getNext();
                }
            }
        } catch (Exception ex) {
            System.out.println("   [FAIL] Constant check FAILED: " + ex.getMessage());
            ex.printStackTrace(System.out);
        }
    }
}