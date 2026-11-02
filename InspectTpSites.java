import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class InspectTpSites {
    public static void main(String[] args) throws Exception {
        String jarPath = args.length > 0 ? args[0]
            : "C:\\Users\\Fernando\\nntdarkmp\\mods\\JRMCore-v1.3.51.jar";

        try (ZipFile zip = new ZipFile(jarPath)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().equals("JinRyuu/JRMCore/p/JRMCorePStats2.class")) continue;

                System.out.println("=== " + entry.getName() + " ===");
                byte[] data = zip.getInputStream(entry).readAllBytes();
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                for (FieldNode f : cn.fields) {
                    System.out.println("FIELD " + f.name + " " + f.desc);
                }

                for (MethodNode m : cn.methods) {
                    boolean uses = false;
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof FieldInsnNode) {
                            FieldInsnNode fin = (FieldInsnNode) insn;
                            if (fin.name.equals("curTP")) { uses = true; break; }
                        }
                    }
                    if (!uses) continue;

                    System.out.println("\nMETHOD " + m.name + m.desc);
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        int op = insn.getOpcode();
                        String d = "";
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode mi = (MethodInsnNode) insn;
                            d = " " + mi.owner + "." + mi.name + mi.desc;
                        } else if (insn instanceof FieldInsnNode) {
                            FieldInsnNode fi = (FieldInsnNode) insn;
                            d = " " + fi.owner + "." + fi.name + " " + fi.desc;
                        } else if (insn instanceof VarInsnNode) {
                            d = " var=" + ((VarInsnNode) insn).var;
                        } else if (insn instanceof LdcInsnNode) {
                            d = " cst=" + ((LdcInsnNode) insn).cst;
                        } else if (insn instanceof IntInsnNode) {
                            d = " op=" + ((IntInsnNode) insn).operand;
                        } else if (insn instanceof IincInsnNode) {
                            IincInsnNode ii = (IincInsnNode) insn;
                            d = " var=" + ii.var + " incr=" + ii.incr;
                        } else if (insn instanceof JumpInsnNode) {
                            d = " ->label";
                        } else if (insn instanceof TypeInsnNode) {
                            d = " " + ((TypeInsnNode) insn).desc;
                        }
                        System.out.println("  " + op + " (" + opName(op) + ")" + d);
                    }
                }
            }
        }
    }

    static String opName(int op) {
        switch (op) {
            case -1: return "PSEUDO";
            case 0: return "NOP";
            case 1: return "ACONST_NULL";
            case 2: return "ICONST_M1";
            case 3: return "ICONST_0";
            case 4: return "ICONST_1";
            case 5: return "ICONST_2";
            case 6: return "ICONST_3";
            case 7: return "ICONST_4";
            case 8: return "ICONST_5";
            case 9: return "LCONST_0";
            case 10: return "LCONST_1";
            case 15: return "DCONST_1";
            case 16: return "BIPUSH";
            case 17: return "SIPUSH";
            case 18: return "LDC";
            case 21: return "ILOAD";
            case 22: return "LLOAD";
            case 24: return "DLOAD";
            case 25: return "ALOAD";
            case 34: return "ISTORE";
            case 35: return "LSTORE";
            case 37: return "DSTORE";
            case 38: return "ASTORE";
            case 46: return "SASTORE";
            case 47: return "POP";
            case 48: return "POP2";
            case 49: return "DUP";
            case 58: return "ISTORE?";
            case 59: return "DADD?";
            case 79: return "DNEG?";
            case 87: return "POP?";
            case 90: return "IXOR?";
            case 92: return "IINC";
            case 96: return "I2L";
            case 99: return "L2I";
            case 132: return "IINC2";
            case 153: return "IFEQ";
            case 160: return "WIDE";
            case 167: return "GOTO";
            case 177: return "RETURN";
            case 178: return "GETSTATIC";
            case 179: return "PUTSTATIC";
            case 180: return "GETFIELD";
            case 181: return "PUTFIELD";
            case 182: return "INVOKEVIRTUAL";
            case 183: return "INVOKESPECIAL";
            case 184: return "INVOKESTATIC";
            case 185: return "INVOKEINTERFACE";
            case 187: return "NEW";
            case 188: return "NEWARRAY";
            case 190: return "ARRAYLENGTH";
            case 191: return "ATHROW";
            default: return "OP" + op;
        }
    }
}