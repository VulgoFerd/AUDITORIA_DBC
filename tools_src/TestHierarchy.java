import java.io.*;
import java.util.*;

public class TestHierarchy {
    public static void main(String[] args) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream("hierarchy.bin"))) {
            @SuppressWarnings("unchecked")
            Map<String, Set<String>> h = (Map<String, Set<String>>) ois.readObject();
            System.out.println("Total entries: " + h.size());
            // Find classes with Entity, NBT, Gui, Command in their name
            for (Map.Entry<String, Set<String>> e : h.entrySet()) {
                String key = e.getKey();
                if (key.contains("Entity") || key.contains("NBT") || key.contains("Gui") || key.contains("Command") || key.contains("Player")) {
                    System.out.println(key + " -> " + e.getValue());
                }
            }
        }
    }
}