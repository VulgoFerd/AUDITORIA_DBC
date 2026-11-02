import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mostra o contexto legivel de cada sitio que toca uma chave/membro.
 *
 * Uso: java Sites <jar> <needle> [needle2 ...] [-n antes] [-m depois]
 */
public class Sites {

    static final String[] OP = {
            "nop", "aconst_null", "iconst_m1", "iconst_0", "iconst_1", "iconst_2", "iconst_3", "iconst_4",
            "iconst_5", "lconst_0", "lconst_1", "fconst_0", "fconst_1", "fconst_2", "dconst_0", "dconst_1",
            "bipush", "sipush", "ldc", "ldc_w", "ldc2_w", "iload", "lload", "fload", "dload", "aload",
            "iload_0", "iload_1", "iload_2", "iload_3", "lload_0", "lload_1", "lload_2", "lload_3",
            "fload_0", "fload_1", "fload_2", "fload_3", "dload_0", "dload_1", "dload_2", "dload_3",
            "aload_0", "aload_1", "aload_2", "aload_3", "iaload", "laload", "faload", "daload", "aaload",
            "baload", "caload", "saload", "istore", "lstore", "fstore", "dstore", "astore",
            "istore_0", "istore_1", "istore_2", "istore_3", "lstore_0", "lstore_1", "lstore_2", "lstore_3",
            "fstore_0", "fstore_1", "fstore_2", "fstore_3", "dstore_0", "dstore_1", "dstore_2", "dstore_3",
            "astore_0", "astore_1", "astore_2", "astore_3", "iastore", "lastore", "fastore", "dastore",
            "aastore", "bastore", "castore", "sastore", "pop", "pop2", "dup", "dup_x1", "dup_x2", "dup2",
            "dup2_x1", "dup2_x2", "swap", "iadd", "ladd", "fadd", "dadd", "isub", "lsub", "fsub", "dsub",
            "imul", "lmul", "fmul", "dmul", "idiv", "ldiv", "fdiv", "ddiv", "irem", "lrem", "frem", "drem",
            "ineg", "lneg", "fneg", "dneg", "ishl", "lshl", "ishr", "lshr", "iushr", "lushr", "iand", "land",
            "ior", "lor", "ixor", "lxor", "iinc", "i2l", "i2f", "i2d", "l2i", "l2f", "l2d", "f2i", "f2l",
            "f2d", "d2i", "d2l", "d2f", "i2b", "i2c", "i2s", "lcmp", "fcmpl", "fcmpg", "dcmpl", "dcmpg",
            "ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle", "if_icmpeq", "if_icmpne", "if_icmplt",
            "if_icmpge", "if_icmpgt", "if_icmple", "if_acmpeq", "if_acmpne", "goto", "jsr", "ret",
            "tableswitch", "lookupswitch", "ireturn", "lreturn", "freturn", "dreturn", "areturn", "return",
            "getstatic", "putstatic", "getfield", "putfield", "invokevirtual", "invokespecial", "invokestatic",
            "invokeinterface", "invokedynamic", "new", "newarray", "anewarray", "arraylength", "athrow",
            "checkcast", "instanceof", "monitorenter", "monitorexit", "wide", "multianewarray", "ifnull",
            "ifnonnull", "goto_w", "jsr_w"};

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        List<String> needles = new ArrayList<String>();
        int before = 10, after = 12;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-n")) before = Integer.parseInt(args[++i]);
            else if (args[i].equals("-m")) after = Integer.parseInt(args[++i]);
            else needles.add(args[i]);
        }
        ZipFile zip = new ZipFile(new File(jar));
        try {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] data = read(zip, entry);
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);
                for (MethodNode m : cn.methods) {
                    List<AbstractInsnNode> all = real(m);
                    for (int i = 0; i < all.size(); i++) {
                        String hit = match(all.get(i), needles);
                        if (hit == null) continue;
                        System.out.println("### " + cn.name + "." + m.name + m.desc + " @ " + i + " [" + hit + "]");
                        for (int j = Math.max(0, i - before); j <= Math.min(all.size() - 1, i + after); j++) {
                            System.out.println((j == i ? "  > " : "    ") + txt(all.get(j)));
                        }
                        System.out.println();
                    }
                }
            }
        } finally {
            zip.close();
        }
    }

    static String txt(AbstractInsnNode x) {
        if (x instanceof LabelNode) return "L" + System.identityHashCode(x);
        if (x instanceof LineNumberNode) return "line " + ((LineNumberNode) x).line;
        String op = x.getOpcode() >= 0 && x.getOpcode() < OP.length ? OP[x.getOpcode()] : "op" + x.getOpcode();
        if (x instanceof VarInsnNode) return op + " " + ((VarInsnNode) x).var;
        if (x instanceof IincInsnNode) return "iinc " + ((IincInsnNode) x).var + " " + ((IincInsnNode) x).incr;
        if (x instanceof IntInsnNode) return op + " " + ((IntInsnNode) x).operand;
        if (x instanceof JumpInsnNode) return op + " -> " + System.identityHashCode(((JumpInsnNode) x).label);
        if (x instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) x;
            return op + " " + m.owner + "." + m.name + m.desc;
        }
        if (x instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) x;
            return op + " " + f.owner + "." + f.name + ":" + f.desc;
        }
        if (x instanceof TypeInsnNode) return op + " " + ((TypeInsnNode) x).desc;
        if (x instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) x).cst;
            return "ldc " + (c instanceof String ? "\"" + c + "\"" : String.valueOf(c));
        }
        return op;
    }

    static String match(AbstractInsnNode x, List<String> needles) {
        if (x instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) x;
            for (String n : needles) if (f.name.equals(n)) return f.owner + "." + f.name + ":" + f.desc;
        }
        if (x instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) x;
            for (String n : needles) if (m.name.equals(n)) return m.owner + "." + m.name + m.desc;
        }
        if (x instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) x).cst;
            if (c instanceof String) for (String n : needles) if (c.equals(n)) return "ldc \"" + c + "\"";
        }
        if (x instanceof TypeInsnNode) {
            for (String n : needles) if (((TypeInsnNode) x).desc.equals(n)) return "type " + n;
        }
        return null;
    }

    static List<AbstractInsnNode> real(MethodNode m) {
        List<AbstractInsnNode> out = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode x : m.instructions.toArray()) {
            if (x instanceof LabelNode || x instanceof LineNumberNode) continue;
            out.add(x);
        }
        return out;
    }

    static byte[] read(ZipFile zip, ZipEntry entry) throws Exception {
        InputStream is = zip.getInputStream(entry);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
