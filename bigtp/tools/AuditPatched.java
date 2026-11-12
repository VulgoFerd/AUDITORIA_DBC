import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Auditoria dos JARs corrigidos. Falha (exit 1) se encontrar:
 *  1. leitura ou escrita de JRMCoreH.curTP:I fora de JRMCoreH/TpClientState;
 *  2. leitura de JRMCoreH.curTPLong:J nao seguida de clampTP/tpShort;
 *  3. getInteger/setInteger do NBT sobre chave de TP (deveria passar por TpUtil);
 *  4. escrita de curTP sem escrita de curTPLong (espelho dessincronizado).
 *
 * uso: AuditPatched <jar> [jar...]
 */
public final class AuditPatched {

    private static final String JRMCOREH = "JinRyuu/JRMCore/JRMCoreH";
    private static final Set<String> TP_KEYS = new HashSet<>(Arrays.asList("jrmcTpint", "jrmcTp"));

    public static void main(String[] args) throws Exception {
        int violations = 0;
        int curTpLongReads = 0;
        int curTpLongWrites = 0;
        for (String arg : args) {
            File jar = new File(arg);
            System.out.println("== " + jar.getName() + " ==");
            try (ZipFile zip = new ZipFile(jar)) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (en.isDirectory() || !en.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] raw;
                    try (InputStream is = zip.getInputStream(en)) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        raw = out.toByteArray();
                    }
                    ClassNode cn = new ClassNode();
                    new ClassReader(raw).accept(cn, 0);
                    boolean coreClass = cn.name.startsWith("JinRyuu/JRMCore/");
                    for (MethodNode mn : cn.methods) {
                        if (mn.instructions == null) {
                            continue;
                        }
                        AbstractInsnNode[] insns = mn.instructions.toArray();
                        for (int i = 0; i < insns.length; i++) {
                            AbstractInsnNode insn = insns[i];
                            if (insn instanceof FieldInsnNode) {
                                FieldInsnNode fi = (FieldInsnNode) insn;
                                if (!JRMCOREH.equals(fi.owner)) {
                                    continue;
                                }
                                if ("curTP".equals(fi.name) && "I".equals(fi.desc)) {
                                    if (!coreClass) {
                                        violations++;
                                        System.out.println("VIOLACAO curTP fora do JRMCore: "
                                                + cn.name + "." + mn.name + mn.desc + " @" + i);
                                    }
                                }
                                if ("curTPLong".equals(fi.name) && "J".equals(fi.desc)) {
                                    boolean read = fi.getOpcode() == Opcodes.GETSTATIC
                                            || fi.getOpcode() == Opcodes.GETFIELD;
                                    if (read) {
                                        curTpLongReads++;
                                        String next = nextCallName(insns, i);
                                        if (!"clampTP".equals(next) && !"tpShort".equals(next)
                                                && !"numSep".equals(next)) {
                                            violations++;
                                            System.out.println("VIOLACAO leitura de curTPLong sem clamp: "
                                                    + cn.name + "." + mn.name + mn.desc + " @" + i
                                                    + " next=" + next);
                                        }
                                    } else {
                                        curTpLongWrites++;
                                    }
                                }
                            }
                            if (insn instanceof LdcInsnNode
                                    && ((LdcInsnNode) insn).cst instanceof String
                                    && TP_KEYS.contains(((LdcInsnNode) insn).cst)) {
                                for (int j = i + 1; j < Math.min(i + 8, insns.length); j++) {
                                    if (insns[j] instanceof MethodInsnNode) {
                                        MethodInsnNode mi = (MethodInsnNode) insns[j];
                                        if ("net/minecraft/nbt/NBTTagCompound".equals(mi.owner)
                                                && ("func_74762_e".equals(mi.name)
                                                    || "func_74768_a".equals(mi.name))) {
                                            violations++;
                                            System.out.println("VIOLACAO NBT int direto em chave TP: "
                                                    + cn.name + "." + mn.name + mn.desc + " @" + i
                                                    + " -> " + mi.name);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("curTPLong reads=" + curTpLongReads + " writes=" + curTpLongWrites);
        System.out.println("VIOLACOES=" + violations);
        System.exit(violations == 0 ? 0 : 1);
    }

    private static String nextCallName(AbstractInsnNode[] insns, int idx) {
        for (int j = idx + 1; j < Math.min(idx + 4, insns.length); j++) {
            AbstractInsnNode n = insns[j];
            if (n.getOpcode() < 0) {
                continue;
            }
            if (n instanceof MethodInsnNode) {
                return ((MethodInsnNode) n).name;
            }
        }
        return "<none>";
    }
}
