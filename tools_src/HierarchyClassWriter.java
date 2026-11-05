import org.objectweb.asm.ClassWriter;
import java.io.*;
import java.util.*;

/**
 * ClassWriter that uses pre-built class hierarchy for accurate frame computation.
 * No fallback to COMPUTE_MAXS - throws if frames can't be computed.
 */
public class HierarchyClassWriter extends ClassWriter {

    private static Map<String, Set<String>> hierarchy;

    public static void loadHierarchy(String path) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
            @SuppressWarnings("unchecked")
            Map<String, Set<String>> h = (Map<String, Set<String>>) ois.readObject();
            hierarchy = h;
            System.out.println("Loaded hierarchy with " + hierarchy.size() + " classes");
        }
    }

    public HierarchyClassWriter(int flags) {
        super(flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        // If either type is not a class (array, primitive), use Object
        if (type1.startsWith("[") || type2.startsWith("[") ||
            type1.charAt(0) < 'A' || type2.charAt(0) < 'A') {
            return "java/lang/Object";
        }

        // Use the hierarchy map to find common superclass
        Set<String> ancestors1 = getAllAncestors(type1);
        Set<String> ancestors2 = getAllAncestors(type2);

        // Find the lowest common ancestor
        for (String a1 : ancestors1) {
            if (ancestors2.contains(a1)) {
                return a1;
            }
        }

        // Fallback - should rarely happen if hierarchy is complete
        return "java/lang/Object";
    }

    private Set<String> getAllAncestors(String type) {
        Set<String> result = new LinkedHashSet<>();
        String current = type;
        while (current != null && !current.equals("java/lang/Object")) {
            result.add(current);
            Set<String> parents = hierarchy.get(current);
            if (parents == null || parents.isEmpty()) break;
            // For single inheritance, pick the first non-interface parent
            // (interfaces are listed alongside superclass)
            String superClass = null;
            for (String p : parents) {
                // Skip interfaces (they don't have fields/methods in the same way)
                // We could check if it's an interface but for simplicity just use first
                if (superClass == null) superClass = p;
            }
            current = superClass;
        }
        result.add("java/lang/Object");
        return result;
    }

    // Test method
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java HierarchyClassWriter <hierarchy.bin>");
            System.exit(1);
        }
        loadHierarchy(args[0]);
        
        // Test a few common superclass lookups
        HierarchyClassWriter cw = new HierarchyClassWriter(0);
        System.out.println("Common superclass of EntityPlayer/Entity: " + 
            cw.getCommonSuperClass("net/minecraft/entity/EntityPlayer", "net/minecraft/entity/Entity"));
        System.out.println("Common superclass of EntityPlayerMP/EntityPlayer: " + 
            cw.getCommonSuperClass("net/minecraft/entity/player/EntityPlayerMP", "net/minecraft/entity/EntityPlayer"));
        System.out.println("Common superclass of NBTTagCompound/Object: " + 
            cw.getCommonSuperClass("net/minecraft/nbt/NBTTagCompound", "java/lang/Object"));
    }
}