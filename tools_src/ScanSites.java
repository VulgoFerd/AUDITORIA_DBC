import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Reports TP-related sites across a jar: LDC jrmcTpint contexts, curTP field refs,
 *  Stats2/handleStats2 call sites and PStats2 constructor call sites. */
public class ScanSites {
    public static void main(String[] args) throws Exception {
        String jar = args[0];
        try (ZipFile zip = new ZipFile(jar)) {
            List<String> names = new ArrayList<String>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.isDirectory() && en.getName().endsWith(".class")) names.add(en.getName());
            }
            Collections.sort(names);
            for (String n : names) {
                ClassReader cr = new ClassReader(zip.getInputStream(zip.getEntry(n)));
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);
                for (MethodNode m : cn.methods) {
                    AbstractInsnNode[] a = m.instructions.toArray();
                    for (int i = 0; i < a.length; i++) {
                        AbstractInsnNode insn = a[i];
                        boolean hit = false;
                        StringBuilder sb = new StringBuilder();
                        if (insn instanceof LdcInsnNode && "jrmcTpint".equals(((LdcInsnNode) insn).cst)) {
                            hit = true;
                            sb.append("LDC jrmcTpint");
                        } else if (insn instanceof FieldInsnNode) {
                            FieldInsnNode f = (FieldInsnNode) insn;
                            if (("curTP".equals(f.name) || "curTPLong".equals(f.name))
                                    && (f.owner.startsWith("JinRyuu/JRMCore/") || f.owner.startsWith("kamkeel/"))) {
                                hit = true;
                                sb.append(field(f));
                            }
                        } else if (insn instanceof MethodInsnNode) {
                            MethodInsnNode mi = (MethodInsnNode) insn;
                            if (("Stats2".equals(mi.name) || "handleStats2".equals(mi.name)
                                    || "addTP".equals(mi.name) || "getTPTotal".equals(mi.name)
                                    || "setTPTotal".equals(mi.name) || "setTPTotalClient".equals(mi.name)
                                    || "getTPInt".equals(mi.name) || "setTPMirror".equals(mi.name)
                                    || "setTPMirrorNbt".equals(mi.name) || "applyTPWindow".equals(mi.name))
                                    && mi.owner.startsWith("JinRyuu/JRMCore/")) {
                                hit = true;
                                sb.append("CALL ").append(mi.owner).append('.').append(mi.name).append(mi.desc);
                            } else if ("<init>".equals(mi.name) && mi.owner.contains("JRMCorePStats2")) {
                                hit = true;
                                sb.append("CTOR ").append(mi.owner).append(mi.desc);
                            }
                        }
                        if (hit) {
                            System.out.println(cn.name + "." + m.name + m.desc + " @" + i + ": " + sb);
                            if (insn instanceof LdcInsnNode) {
                                for (int k = i + 1; k < Math.min(a.length, i + 7); k++) {
                                    System.out.println("    +" + (k - i) + " " + show(a[k]));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    static String show(AbstractInsnNode insn) {
        if (insn == null) return "null";
        if (insn instanceof FieldInsnNode) return field((FieldInsnNode) insn);
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            return "INVOKE " + m.owner + "." + m.name + m.desc;
        }
        if (insn instanceof VarInsnNode) return "VAR op=" + insn.getOpcode() + " v=" + ((VarInsnNode) insn).var;
        if (insn instanceof LdcInsnNode) return "LDC " + ((LdcInsnNode) insn).cst;
        if (insn instanceof IincInsnNode) return "IINC v=" + ((IincInsnNode) insn).var + " " + ((IincInsnNode) insn).incr;
        if (insn instanceof IntInsnNode) return "INT op=" + insn.getOpcode() + " " + ((IntInsnNode) insn).operand;
        if (insn instanceof TypeInsnNode) return "TYPE op=" + insn.getOpcode() + " " + ((TypeInsnNode) insn).desc;
        if (insn instanceof JumpInsnNode) return "JUMP op=" + insn.getOpcode();
        if (insn instanceof LabelNode) return "LABEL";
        if (insn instanceof LineNumberNode) return "LINE";
        if (insn instanceof FrameNode) return "FRAME";
        return "OP " + insn.getOpcode();
    }

    static String field(FieldInsnNode f) {
        return "FIELD op=" + f.getOpcode() + " " + f.owner + "." + f.name + ":" + f.desc;
    }
}
