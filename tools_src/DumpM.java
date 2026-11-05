import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Dumps a single method with resolved labels, opcodes and local variable table. */
public class DumpM {
    public static void main(String[] args) throws Exception {
        String jar = args[0], cls = args[1], meth = args[2];
        String desc = args.length > 3 ? args[3] : null;
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry e = zip.getEntry(cls.replace('.', '/') + ".class");
            if (e == null) { System.out.println("class not found: " + cls); return; }
            ClassNode cn = new ClassNode();
            new ClassReader(zip.getInputStream(e)).accept(cn, 0);
            for (MethodNode m : cn.methods) {
                if (!m.name.equals(meth)) continue;
                if (desc != null && !m.desc.equals(desc)) continue;
                System.out.println("CLASS " + cn.name);
                System.out.println("METHOD " + m.name + m.desc + " access=" + m.access
                        + " maxStack=" + m.maxStack + " maxLocals=" + m.maxLocals);
                Map<LabelNode, Integer> labels = new HashMap<LabelNode, Integer>();
                int id = 0;
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof LabelNode) labels.put((LabelNode) insn, id++);
                }
                int i = 0;
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    System.out.println(String.format("%4d  %s", i++, fmt(insn, labels)));
                }
                System.out.println("TRY:");
                if (m.tryCatchBlocks != null) for (TryCatchBlockNode t : m.tryCatchBlocks) {
                    System.out.println("  " + lbl(t.start, labels) + ".." + lbl(t.end, labels)
                            + " handler " + lbl(t.handler, labels) + " type " + t.type);
                }
                System.out.println("LVT:");
                if (m.localVariables != null) for (LocalVariableNode v : m.localVariables) {
                    System.out.println("  slot " + v.index + " " + v.name + " " + v.desc
                            + " [" + lbl(v.start, labels) + ".." + lbl(v.end, labels) + "]");
                }
                return;
            }
            System.out.println("method not found: " + meth + (desc == null ? "" : desc));
        }
    }

    static String lbl(LabelNode l, Map<LabelNode, Integer> labels) {
        Integer v = labels.get(l);
        return v == null ? "?" : "L" + v;
    }

    static String fmt(AbstractInsnNode insn, Map<LabelNode, Integer> labels) {
        if (insn instanceof LabelNode) return "L" + labels.get(insn) + ":";
        if (insn instanceof LineNumberNode) return "line " + ((LineNumberNode) insn).line;
        if (insn instanceof FrameNode) {
            FrameNode f = (FrameNode) insn;
            return "FRAME " + f.type + " local=" + f.local + " stack=" + f.stack;
        }
        String op = opName(insn.getOpcode());
        if (insn instanceof VarInsnNode) return op + " " + ((VarInsnNode) insn).var;
        if (insn instanceof IincInsnNode) {
            IincInsnNode n = (IincInsnNode) insn;
            return "IINC " + n.var + " " + n.incr;
        }
        if (insn instanceof IntInsnNode) return op + " " + ((IntInsnNode) insn).operand;
        if (insn instanceof TypeInsnNode) return op + " " + ((TypeInsnNode) insn).desc;
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            return op + " " + f.owner + "." + f.name + ":" + f.desc;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            return op + " " + m.owner + "." + m.name + m.desc;
        }
        if (insn instanceof LdcInsnNode) return "LDC " + ((LdcInsnNode) insn).cst;
        if (insn instanceof JumpInsnNode) return op + " -> L" + labels.get(((JumpInsnNode) insn).label);
        if (insn.getOpcode() == Opcodes.TABLESWITCH || insn.getOpcode() == Opcodes.LOOKUPSWITCH) return "SWITCH";
        return op;
    }

    static String opName(int op) {
        try { return org.objectweb.asm.util.Printer.OPCODES[op]; }
        catch (Throwable t) { return "OP" + op; }
    }
}
