import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

public class ScanJrmcTpint {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ScanJrmcTpint <jar-file>");
            System.exit(1);
        }
        
        File jarFile = new File(args[0]);
        System.out.println("Scanning: " + jarFile.getName());
        
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            int totalClasses = 0;
            int classesWithRef = 0;
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    totalClasses++;
                    try (InputStream is = jar.getInputStream(entry)) {
                        ClassReader cr = new ClassReader(is);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, 0);
                        
                        // Check string constants for jrmcTpint
                        for (Object cst : cn.visibleAnnotations != null ? cn.visibleAnnotations : Collections.emptyList()) {
                            // annotations
                        }
                        
                        // Check LDC instructions
                        boolean found = false;
                        for (MethodNode mn : cn.methods) {
                            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                                if (insn instanceof LdcInsnNode) {
                                    Object cst = ((LdcInsnNode) insn).cst;
                                    if (cst instanceof String && ((String) cst).contains("jrmcTpint")) {
                                        if (!found) {
                                            System.out.println("  Found in class: " + entry.getName().replace('/', '.').replace(".class", ""));
                                            found = true;
                                            classesWithRef++;
                                        }
                                        System.out.println("    Method: " + mn.name + mn.desc + " -> LDC: " + cst);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("  Error reading " + entry.getName() + ": " + e.getMessage());
                    }
                }
            }
            System.out.println("Total classes: " + totalClasses + ", Classes with jrmcTpint ref: " + classesWithRef);
        }
    }
}