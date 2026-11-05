import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Uso: java FindField <jar> <ownerSubstring> <fieldName> */
public class FindField {
    public static void main(String[] args) throws Exception {
        String jar = args[0], ownerFilter = args[1], field = args[2];
        try (ZipFile zip = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (en.isDirectory() || !en.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(zip.getInputStream(en)).accept(cn, 0);
                for (MethodNode m : cn.methods) {
                    AbstractInsnNode[] a = m.instructions.toArray();
                    for (int i = 0; i < a.length; i++) {
                        if (!(a[i] instanceof FieldInsnNode)) continue;
                        FieldInsnNode f = (FieldInsnNode) a[i];
                        if (!f.owner.contains(ownerFilter) || !f.name.equals(field)) continue;
                        System.out.println(cn.name + "." + m.name + m.desc + " @" + i
                                + " " + f.getOpcode() + " " + f.owner + "." + f.name + ":" + f.desc);
                    }
                }
            }
        }
    }
}
