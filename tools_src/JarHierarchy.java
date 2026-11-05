import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Builds class hierarchy from multiple JARs for proper frame computation.
 * Uses MCP-named classes from forge userdev jar, JRMCore, and NPCDBC.
 * Usage: java JarHierarchy <forge-jar> <jrmc-jar> [npcdbc-jar]
 */
public class JarHierarchy {

    static Map<String, ClassNode> classes = new HashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java JarHierarchy <jrmc-jar> [npcdbc-jar]");
            System.exit(1);
        }
        // Try to load forge jar if available, but don't require it
        // We'll load JRMCore and NPCDBC
        if (args.length >= 1) loadJar(args[0]); // JRMCore
        if (args.length >= 2) loadJar(args[1]); // NPCDBC

        // Build hierarchy: for each class, find its superclass and interfaces
        Map<String, Set<String>> hierarchy = new HashMap<>();
        for (Map.Entry<String, ClassNode> e : classes.entrySet()) {
            String name = e.getKey();
            ClassNode cn = e.getValue();
            Set<String> parents = new HashSet<>();
            if (cn.superName != null && !cn.superName.equals("java/lang/Object")) {
                parents.add(cn.superName);
            }
            if (cn.interfaces != null) parents.addAll(cn.interfaces);
            hierarchy.put(name, parents);
        }

        // Print hierarchy for debugging
        for (Map.Entry<String, Set<String>> e : hierarchy.entrySet()) {
            System.out.println(e.getKey() + " -> " + e.getValue());
        }

        // Serialize for use by HierarchyClassWriter
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream("hierarchy.bin"))) {
            oos.writeObject(hierarchy);
        }
        System.out.println("Hierarchy written to hierarchy.bin (" + hierarchy.size() + " classes)");
    }

    static void loadJar(String path) throws IOException {
        try (ZipFile zip = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(zip.getInputStream(e)).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classes.put(cn.name, cn);
            }
        }
        System.out.println("Loaded " + classes.size() + " classes from " + path);
    }
}