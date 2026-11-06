import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Dumpa uma janela de instrucoes em torno de cada site de TP.
 *
 * Uso: java SiteWindow <jar> <classe> <metodo>
 *      java SiteWindow <jar> --all
 *
 * Saida: indice da instrucao, offset real, opcode e texto ASM.
 */
public final class SiteWindow {

    private static final String NBT = "net/minecraft/nbt/NBTTagCompound";
    private static final String JRMCOREH = "JinRyuu/JRMCore/JRMCoreH";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: SiteWindow <jar> <classe> <metodo> | --all");
            System.exit(2);
        }
        ZipFile zip = new ZipFile(args[0]);
        try {
            if ("--all".equals(args[1])) {
                List<String> names = new ArrayList<String>();
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (!en.isDirectory() && en.getName().endsWith(".class")) {
                        names.add(en.getName());
                    }
                }
                Collections.sort(names);
                for (String n : names) {
                    ClassNode cn = read(zip, n);
                    for (MethodNode mn : cn.methods) {
                        if (hasSite(mn)) {
                            dumpClassMethod(cn.name, mn);
                        }
                    }
                }
            } else {
                String target = args[1];
                String method = args.length > 2 ? args[2] : null;
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    String n = en.getName();
                    if (en.isDirectory() || !n.endsWith(".class")) {
                        continue;
                    }
                    ClassNode cn = read(zip, n);
                    if (!cn.name.equals(target) && !cn.name.endsWith("/" + target)) {
                        continue;
                    }
                    for (MethodNode mn : cn.methods) {
                        if (method == null || method.equals(mn.name)) {
                            dumpClassMethod(cn.name, mn);
                        }
                    }
                }
            }
        } finally {
            zip.close();
        }
    }

    private static ClassNode read(ZipFile zip, String name) throws IOException {
        ClassNode cn = new ClassNode();
        new ClassReader(zip.getInputStream(zip.getEntry(name))).accept(cn, 0);
        return cn;
    }

    private static boolean hasSite(MethodNode mn) {
        AbstractInsnNode[] a = mn.instructions.toArray();
        for (int i = 0; i < a.length; i++) {
            if (a[i] instanceof LdcInsnNode) {
                Object c = ((LdcInsnNode) a[i]).cst;
                if (c instanceof String && ("jrmcTpint".equals(c) || "jrmcTplong".equals(c))) {
                    return true;
                }
            }
            if (a[i] instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode) a[i];
                if (f.owner.equals(JRMCOREH) && ("curTP".equals(f.name) || "curTPLong".equals(f.name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void dumpClassMethod(String clazz, MethodNode mn) {
        AbstractInsnNode[] a = mn.instructions.toArray();
        System.out.println("==== " + clazz + " :: " + mn.name + mn.desc);
        for (int i = 0; i < a.length; i++) {
            if (!isSite(a[i])) {
                continue;
            }
            System.out.println("---- site @insn " + i);
            int from = Math.max(0, i - 14);
            int to = Math.min(a.length - 1, i + 8);
            for (int j = from; j <= to; j++) {
                String mark = j == i ? ">>" : "  ";
                System.out.println(mark + " " + String.format("%5d", j) + "  " + text(a[j]));
            }
        }
        System.out.println();
    }

    private static boolean isSite(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) insn).cst;
            return c instanceof String && ("jrmcTpint".equals(c) || "jrmcTplong".equals(c));
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            return f.owner.equals(JRMCOREH) && ("curTP".equals(f.name) || "curTPLong".equals(f.name));
        }
        return false;
    }

    private static String text(AbstractInsnNode insn) {
        Textifier t = new Textifier();
        TraceMethodVisitor v = new TraceMethodVisitor(t);
        insn.accept(v);
        StringBuilder sb = new StringBuilder();
        for (Object o : t.getText()) {
            sb.append(o.toString().trim());
        }
        return sb.toString();
    }
}
