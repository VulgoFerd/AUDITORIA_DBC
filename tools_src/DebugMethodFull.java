import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.Opcodes;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.io.ByteArrayOutputStream;

public class DebugMethodFull {
    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String clsName = args.length > 1 ? args[1] : "JinRyuu/JRMCore/JRMCoreComTickH.class";
        String methodName = args.length > 2 ? args[2] : "updatePlayersData";
        int startIdx = args.length > 3 ? Integer.parseInt(args[3]) : 1500;
        int endIdx = args.length > 4 ? Integer.parseInt(args[4]) : 1550;
        String wantDesc = System.getenv("WANT_DESC");
        
        byte[] data = readEntry(jar, clsName);
        if (data == null) throw new IllegalStateException("entrada nao encontrada: " + clsName);

        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);
        
        for (MethodNode m : cn.methods) {
            if (m.name.equals(methodName)) {
                if (wantDesc != null && !wantDesc.equals(m.desc)) continue;
                System.out.println("Metodo: " + m.name + m.desc);
                System.out.println("  maxStack: " + m.maxStack);
                System.out.println("  maxLocals: " + m.maxLocals);
                System.out.println("  instructions: " + m.instructions.size());
                
                for (int i = startIdx; i < Math.min(endIdx, m.instructions.size()); i++) {
                    AbstractInsnNode insn = m.instructions.get(i);
                    System.out.println("  [" + i + "] " + getInsnString(insn));
                }
                break;
            }
        }
    }
    
    static String getInsnString(AbstractInsnNode insn) {
        if (insn == null) return "null";
        int opcode = insn.getOpcode();
        String name = getOpcodeName(opcode);
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode min = (MethodInsnNode)insn;
            return name + " " + min.owner + "." + min.name + min.desc;
        } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode) {
            org.objectweb.asm.tree.FieldInsnNode fin = (org.objectweb.asm.tree.FieldInsnNode)insn;
            return name + " " + fin.owner + "." + fin.name + ":" + fin.desc;
        } else if (insn instanceof org.objectweb.asm.tree.JumpInsnNode) {
            return name + " -> " + System.identityHashCode(((org.objectweb.asm.tree.JumpInsnNode)insn).label);
        } else if (insn instanceof LdcInsnNode) {
            LdcInsnNode ldc = (LdcInsnNode)insn;
            return name + " " + ldc.cst;
        } else if (insn instanceof VarInsnNode) {
            VarInsnNode var = (VarInsnNode)insn;
            return name + " " + var.var;
        } else {
            return name;
        }
    }
    
    static String getOpcodeName(int opcode) {
        switch (opcode) {
            case Opcodes.NOP: return "NOP";
            case Opcodes.ACONST_NULL: return "ACONST_NULL";
            case Opcodes.ICONST_M1: return "ICONST_M1";
            case Opcodes.ICONST_0: return "ICONST_0";
            case Opcodes.ICONST_1: return "ICONST_1";
            case Opcodes.ICONST_2: return "ICONST_2";
            case Opcodes.ICONST_3: return "ICONST_3";
            case Opcodes.ICONST_4: return "ICONST_4";
            case Opcodes.ICONST_5: return "ICONST_5";
            case Opcodes.LCONST_0: return "LCONST_0";
            case Opcodes.LCONST_1: return "LCONST_1";
            case Opcodes.FCONST_0: return "FCONST_0";
            case Opcodes.FCONST_1: return "FCONST_1";
            case Opcodes.FCONST_2: return "FCONST_2";
            case Opcodes.DCONST_0: return "DCONST_0";
            case Opcodes.DCONST_1: return "DCONST_1";
            case Opcodes.BIPUSH: return "BIPUSH";
            case Opcodes.SIPUSH: return "SIPUSH";
            case Opcodes.LDC: return "LDC";
            
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.LLOAD: return "LLOAD";
            case Opcodes.FLOAD: return "FLOAD";
            case Opcodes.DLOAD: return "DLOAD";
            case Opcodes.ALOAD: return "ALOAD";
            case Opcodes.IALOAD: return "IALOAD";
            case Opcodes.LALOAD: return "LALOAD";
            case Opcodes.FALOAD: return "FALOAD";
            case Opcodes.DALOAD: return "DALOAD";
            case Opcodes.AALOAD: return "AALOAD";
            case Opcodes.BALOAD: return "BALOAD";
            case Opcodes.CALOAD: return "CALOAD";
            case Opcodes.SALOAD: return "SALOAD";
            case Opcodes.ISTORE: return "ISTORE";
            case Opcodes.LSTORE: return "LSTORE";
            case Opcodes.FSTORE: return "FSTORE";
            case Opcodes.DSTORE: return "DSTORE";
            case Opcodes.ASTORE: return "ASTORE";
            case Opcodes.IASTORE: return "IASTORE";
            case Opcodes.LASTORE: return "LASTORE";
            case Opcodes.FASTORE: return "FASTORE";
            case Opcodes.DASTORE: return "DASTORE";
            case Opcodes.AASTORE: return "AASTORE";
            case Opcodes.BASTORE: return "BASTORE";
            case Opcodes.CASTORE: return "CASTORE";
            case Opcodes.SASTORE: return "SASTORE";
            case Opcodes.POP: return "POP";
            case Opcodes.POP2: return "POP2";
            case Opcodes.DUP: return "DUP";
            case Opcodes.DUP_X1: return "DUP_X1";
            case Opcodes.DUP_X2: return "DUP_X2";
            case Opcodes.DUP2: return "DUP2";
            case Opcodes.DUP2_X1: return "DUP2_X1";
            case Opcodes.DUP2_X2: return "DUP2_X2";
            case Opcodes.SWAP: return "SWAP";
            case Opcodes.IADD: return "IADD";
            case Opcodes.LADD: return "LADD";
            case Opcodes.FADD: return "FADD";
            case Opcodes.DADD: return "DADD";
            case Opcodes.ISUB: return "ISUB";
            case Opcodes.LSUB: return "LSUB";
            case Opcodes.FSUB: return "FSUB";
            case Opcodes.DSUB: return "DSUB";
            case Opcodes.IMUL: return "IMUL";
            case Opcodes.LMUL: return "LMUL";
            case Opcodes.FMUL: return "FMUL";
            case Opcodes.DMUL: return "DMUL";
            case Opcodes.IDIV: return "IDIV";
            case Opcodes.LDIV: return "LDIV";
            case Opcodes.FDIV: return "FDIV";
            case Opcodes.DDIV: return "DDIV";
            case Opcodes.IREM: return "IREM";
            case Opcodes.LREM: return "LREM";
            case Opcodes.FREM: return "FREM";
            case Opcodes.DREM: return "DREM";
            case Opcodes.INEG: return "INEG";
            case Opcodes.LNEG: return "LNEG";
            case Opcodes.FNEG: return "FNEG";
            case Opcodes.DNEG: return "DNEG";
            case Opcodes.ISHL: return "ISHL";
            case Opcodes.LSHL: return "LSHL";
            case Opcodes.ISHR: return "ISHR";
            case Opcodes.LSHR: return "LSHR";
            case Opcodes.IUSHR: return "IUSHR";
            case Opcodes.LUSHR: return "LUSHR";
            case Opcodes.IAND: return "IAND";
            case Opcodes.LAND: return "LAND";
            case Opcodes.IOR: return "IOR";
            case Opcodes.LOR: return "LOR";
            case Opcodes.IXOR: return "IXOR";
            case Opcodes.LXOR: return "LXOR";
            case Opcodes.IINC: return "IINC";
            case Opcodes.I2L: return "I2L";
            case Opcodes.I2F: return "I2F";
            case Opcodes.I2D: return "I2D";
            case Opcodes.L2I: return "L2I";
            case Opcodes.L2F: return "L2F";
            case Opcodes.L2D: return "L2D";
            case Opcodes.F2I: return "F2I";
            case Opcodes.F2L: return "F2L";
            case Opcodes.F2D: return "F2D";
            case Opcodes.D2I: return "D2I";
            case Opcodes.D2L: return "D2L";
            case Opcodes.D2F: return "D2F";
            case Opcodes.I2B: return "I2B";
            case Opcodes.I2C: return "I2C";
            case Opcodes.I2S: return "I2S";
            case Opcodes.LCMP: return "LCMP";
            case Opcodes.FCMPL: return "FCMPL";
            case Opcodes.FCMPG: return "FCMPG";
            case Opcodes.DCMPL: return "DCMPL";
            case Opcodes.DCMPG: return "DCMPG";
            case Opcodes.IFEQ: return "IFEQ";
            case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFLT: return "IFLT";
            case Opcodes.IFGE: return "IFGE";
            case Opcodes.IFGT: return "IFGT";
            case Opcodes.IFLE: return "IFLE";
            case Opcodes.IF_ICMPEQ: return "IF_ICMPEQ";
            case Opcodes.IF_ICMPNE: return "IF_ICMPNE";
            case Opcodes.IF_ICMPLT: return "IF_ICMPLT";
            case Opcodes.IF_ICMPGE: return "IF_ICMPGE";
            case Opcodes.IF_ICMPGT: return "IF_ICMPGT";
            case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
            case Opcodes.IF_ACMPEQ: return "IF_ACMPEQ";
            case Opcodes.IF_ACMPNE: return "IF_ACMPNE";
            case Opcodes.GOTO: return "GOTO";
            case Opcodes.JSR: return "JSR";
            case Opcodes.RET: return "RET";
            case Opcodes.TABLESWITCH: return "TABLESWITCH";
            case Opcodes.LOOKUPSWITCH: return "LOOKUPSWITCH";
            case Opcodes.IRETURN: return "IRETURN";
            case Opcodes.LRETURN: return "LRETURN";
            case Opcodes.FRETURN: return "FRETURN";
            case Opcodes.DRETURN: return "DRETURN";
            case Opcodes.ARETURN: return "ARETURN";
            case Opcodes.RETURN: return "RETURN";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.PUTSTATIC: return "PUTSTATIC";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            case Opcodes.INVOKEDYNAMIC: return "INVOKEDYNAMIC";
            case Opcodes.NEW: return "NEW";
            case Opcodes.NEWARRAY: return "NEWARRAY";
            case Opcodes.ANEWARRAY: return "ANEWARRAY";
            case Opcodes.ARRAYLENGTH: return "ARRAYLENGTH";
            case Opcodes.ATHROW: return "ATHROW";
            case Opcodes.CHECKCAST: return "CHECKCAST";
            case Opcodes.INSTANCEOF: return "INSTANCEOF";
            case Opcodes.MONITORENTER: return "MONITORENTER";
            case Opcodes.MONITOREXIT: return "MONITOREXIT";
            case Opcodes.MULTIANEWARRAY: return "MULTIANEWARRAY";
            case Opcodes.IFNULL: return "IFNULL";
            case Opcodes.IFNONNULL: return "IFNONNULL";
            default: return "OP(" + opcode + ")";
        }
    }
    
    static byte[] readEntry(String jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(new File(jar))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().equals(name)) continue;
                InputStream is = zip.getInputStream(entry);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                return bos.toByteArray();
            }
        }
        return null;
    }
}