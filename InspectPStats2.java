import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class InspectPStats2 {
    public static void main(String[] args) throws Exception {
        String jarPath = "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\artifacts\\JRMCore-v1.3.51-bignum.jar";
        
        try (ZipFile zip = new ZipFile(jarPath)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                
                if (name.equals("JinRyuu/JRMCore/p/JRMCorePStats2.class")) {
                    System.out.println("=== JRMCorePStats2 ===");
                    byte[] data = zip.getInputStream(entry).readAllBytes();
                    ClassReader cr = new ClassReader(data);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    
                    System.out.println("Fields:");
                    for (FieldNode f : cn.fields) {
                        System.out.println("  " + f.name + " " + f.desc);
                    }
                    
                    System.out.println("\nMethods:");
                    for (MethodNode m : cn.methods) {
                        System.out.println("  " + m.name + " " + m.desc);
                        if (m.name.equals("toBytes") || m.name.equals("fromBytes") || m.name.equals("<init>")) {
                            System.out.println("    Bytecode:");
                            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                                int opcode = insn.getOpcode();
                                String opName = getOpcodeName(opcode);
                                String detail = "";
                                if (insn instanceof MethodInsnNode) {
                                    MethodInsnNode min = (MethodInsnNode) insn;
                                    detail = " " + min.owner + "." + min.name + min.desc;
                                } else if (insn instanceof FieldInsnNode) {
                                    FieldInsnNode fin = (FieldInsnNode) insn;
                                    detail = " " + fin.owner + "." + fin.name + " " + fin.desc;
                                } else if (insn instanceof VarInsnNode) {
                                    VarInsnNode vin = (VarInsnNode) insn;
                                    detail = " var=" + vin.var;
                                } else if (insn instanceof LdcInsnNode) {
                                    LdcInsnNode ldc = (LdcInsnNode) insn;
                                    detail = " cst=" + ldc.cst;
                                } else if (insn instanceof IntInsnNode) {
                                    IntInsnNode iin = (IntInsnNode) insn;
                                    detail = " operand=" + iin.operand;
                                } else if (insn instanceof JumpInsnNode) {
                                    JumpInsnNode jin = (JumpInsnNode) insn;
                                    detail = " label=" + jin.label;
                                } else if (insn instanceof IincInsnNode) {
                                    IincInsnNode iin = (IincInsnNode) insn;
                                    detail = " var=" + iin.var + " incr=" + iin.incr;
                                } else if (insn instanceof TypeInsnNode) {
                                    TypeInsnNode tin = (TypeInsnNode) insn;
                                    detail = " " + tin.desc;
                                } else if (insn instanceof MultiANewArrayInsnNode) {
                                    MultiANewArrayInsnNode man = (MultiANewArrayInsnNode) insn;
                                    detail = " " + man.desc + " dims=" + man.dims;
                                }
                                System.out.println("      " + opcode + " (" + opName + ")" + detail);
                            }
                        }
                    }
                }
            }
        }
    }
    
    static String getOpcodeName(int opcode) {
        switch (opcode) {
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
            case 11: return "FCONST_0";
            case 12: return "FCONST_1";
            case 13: return "FCONST_2";
            case 14: return "DCONST_0";
            case 15: return "DCONST_1";
            case 16: return "BIPUSH";
            case 17: return "SIPUSH";
            case 18: return "LDC";
            case 19: return "LDC_W";
            case 20: return "LDC2_W";
            case 21: return "ILOAD";
            case 22: return "LLOAD";
            case 23: return "FLOAD";
            case 24: return "DLOAD";
            case 25: return "ALOAD";
            case 26: return "IALOAD";
            case 27: return "LALOAD";
            case 28: return "FALOAD";
            case 29: return "DALOAD";
            case 30: return "AALOAD";
            case 31: return "BALOAD";
            case 32: return "CALOAD";
            case 33: return "SALOAD";
            case 34: return "ISTORE";
            case 35: return "LSTORE";
            case 36: return "FSTORE";
            case 37: return "DSTORE";
            case 38: return "ASTORE";
            case 39: return "IASTORE";
            case 40: return "LASTORE";
            case 41: return "FASTORE";
            case 42: return "DASTORE";
            case 43: return "AASTORE";
            case 44: return "BASTORE";
            case 45: return "CASTORE";
            case 46: return "SASTORE";
            case 47: return "POP";
            case 48: return "POP2";
            case 49: return "DUP";
            case 50: return "DUP_X1";
            case 51: return "DUP_X2";
            case 52: return "DUP2";
            case 53: return "DUP2_X1";
            case 54: return "DUP2_X2";
            case 55: return "SWAP";
            case 56: return "IADD";
            case 57: return "LADD";
            case 58: return "FADD";
            case 59: return "DADD";
            case 60: return "ISUB";
            case 61: return "LSUB";
            case 62: return "FSUB";
            case 63: return "DSUB";
            case 64: return "IMUL";
            case 65: return "LMUL";
            case 66: return "FMUL";
            case 67: return "DMUL";
            case 68: return "IDIV";
            case 69: return "LDIV";
            case 70: return "FDIV";
            case 71: return "DDIV";
            case 72: return "IREM";
            case 73: return "LREM";
            case 74: return "FREM";
            case 75: return "DREM";
            case 76: return "INEG";
            case 77: return "LNEG";
            case 78: return "FNEG";
            case 79: return "DNEG";
            case 80: return "ISHL";
            case 81: return "LSHL";
            case 82: return "ISHR";
            case 83: return "LSHR";
            case 84: return "IUSHR";
            case 85: return "LUSHR";
            case 86: return "IAND";
            case 87: return "LAND";
            case 88: return "IOR";
            case 89: return "LOR";
            case 90: return "IXOR";
            case 91: return "LXOR";
            case 92: return "IINC";
            case 96: return "I2L";
            case 97: return "I2F";
            case 98: return "I2D";
            case 99: return "L2I";
            case 100: return "L2F";
            case 101: return "L2D";
            case 102: return "F2I";
            case 103: return "F2L";
            case 104: return "F2D";
            case 105: return "D2I";
            case 106: return "D2L";
            case 107: return "D2F";
            case 108: return "I2B";
            case 109: return "I2C";
            case 110: return "I2S";
            case 112: return "LCMP";
            case 113: return "FCMPL";
            case 114: return "FCMPG";
            case 115: return "DCMPL";
            case 116: return "DCMPG";
            case 117: return "IFEQ";
            case 118: return "IFNE";
            case 119: return "IFLT";
            case 120: return "IFGE";
            case 121: return "IFGT";
            case 122: return "IFLE";
            case 123: return "IF_ICMPEQ";
            case 124: return "IF_ICMPNE";
            case 125: return "IF_ICMPLT";
            case 126: return "IF_ICMPGE";
            case 127: return "IF_ICMPGT";
            case 128: return "IF_ICMPLE";
            case 129: return "IF_ACMPEQ";
            case 130: return "IF_ACMPNE";
            case 131: return "GOTO";
            case 132: return "JSR";
            case 133: return "RET";
            case 134: return "TABLESWITCH";
            case 135: return "LOOKUPSWITCH";
            case 136: return "IRETURN";
            case 137: return "LRETURN";
            case 138: return "FRETURN";
            case 139: return "DRETURN";
            case 140: return "ARETURN";
            case 141: return "RETURN";
            case 142: return "GETSTATIC";
            case 143: return "PUTSTATIC";
            case 144: return "GETFIELD";
            case 145: return "PUTFIELD";
            case 146: return "INVOKEVIRTUAL";
            case 147: return "INVOKESPECIAL";
            case 148: return "INVOKESTATIC";
            case 149: return "INVOKEINTERFACE";
            case 150: return "INVOKEDYNAMIC";
            case 151: return "NEW";
            case 152: return "NEWARRAY";
            case 153: return "ANEWARRAY";
            case 154: return "ARRAYLENGTH";
            case 155: return "ATHROW";
            case 156: return "CHECKCAST";
            case 157: return "INSTANCEOF";
            case 158: return "MONITORENTER";
            case 159: return "MONITOREXIT";
            case 160: return "WIDE";
            case 161: return "MULTIANEWARRAY";
            case 162: return "IFNULL";
            case 163: return "IFNONNULL";
            case 164: return "GOTO_W";
            case 165: return "JSR_W";
            case 166: return "BREAKPOINT";
            case 167: return "IMPDEP1";
            case 168: return "IMPDEP2";
            case 169: return "LDC2_W (sic)";
            case 170: return "IFNULL (sic)";
            case 171: return "IFNONNULL (sic)";
            case 172: return "GOTO_W (sic)";
            case 173: return "JSR_W (sic)";
            case 174: return "BREAKPOINT (sic)";
            case 175: return "IMPDEP1 (sic)";
            case 176: return "IMPDEP2 (sic)";
            case 177: return "ATHROW (sic)";
            case 178: return "GETFIELD (sic)";
            case 179: return "PUTFIELD (sic)";
            case 180: return "GETFIELD";
            case 181: return "PUTFIELD";
            case 182: return "INVOKEVIRTUAL";
            case 183: return "INVOKESPECIAL";
            case 184: return "INVOKESTATIC";
            case 185: return "INVOKEINTERFACE";
            case 186: return "INVOKEDYNAMIC";
            case 187: return "NEW";
            case 188: return "NEWARRAY";
            case 189: return "ANEWARRAY";
            case 190: return "ARRAYLENGTH";
            case 191: return "ATHROW (sic)";
            case 192: return "CHECKCAST (sic)";
            case 193: return "INSTANCEOF (sic)";
            case 194: return "MONITORENTER (sic)";
            case 195: return "MONITOREXIT (sic)";
            case 196: return "WIDE (sic)";
            case 197: return "MULTIANEWARRAY (sic)";
            case 198: return "IFNULL (sic)";
            case 199: return "IFNONNULL (sic)";
            default: return "UNKNOWN(" + opcode + ")";
        }
    }
}