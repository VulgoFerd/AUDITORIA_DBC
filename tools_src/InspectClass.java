import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class InspectClass {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: InspectClass <jar> <classname>");
            System.exit(2);
        }
        String jar = args[0];
        String className = args[1].replace('/', '.').replace('.', '/');
        
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().equals(className + ".class")) {
                    InputStream is = zip.getInputStream(entry);
                    ClassReader cr = new ClassReader(is);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    
                    System.out.println("=== " + cn.name + " ===");
                    System.out.println("Fields:");
                    for (FieldNode f : cn.fields) {
                        System.out.println("  " + f.access + " " + f.name + " " + f.desc + " = " + f.value);
                    }
                    System.out.println("Methods:");
                    for (MethodNode m : cn.methods) {
                        System.out.println("  " + m.access + " " + m.name + " " + m.desc);
                    }
                    is.close();
                    return;
                }
            }
            System.out.println("Class not found: " + className);
        } finally {
            zip.close();
        }
    }
}