import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Inventario deterministico dos sites de TP de um JAR.
 *
 * Uso: java TpSiteScan <jar> <saida.csv>
 *
 * Saida (CSV, uma linha por site, ordenada):
 *   jar,classe,metodo,descritor,offset,chave,operacao,descritor_operacao,contexto
 *
 * "contexto" resume as 3 instrucoes anteriores que importam para a
 * classificacao (constantes, carga de campo, aritmetica).
 *
 * Regras:
 *  - a chave e a ultima LDC de String iniciada por "jrmcTp" antes da chamada,
 *    procurando no maximo 8 instrucoes para tras;
 *  - chamadas a getInteger/setInteger/getLong/setLong/getTag/hasKey em
 *    NBTTagCompound sao registradas quando a chave for encontrada;
 *  - acessos a JRMCoreH.curTP e JRMCoreH.curTPLong tambem sao registrados.
 */
public final class TpSiteScan {

    private static final String NBT = "net/minecraft/nbt/NBTTagCompound";
    private static final String NBT_BASE = "net/minecraft/nbt/NBTBase";
    private static final String JRMCOREH = "JinRyuu/JRMCore/JRMCoreH";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: TpSiteScan <jar> <saida.csv>");
            System.exit(2);
        }
        List<Row> rows = new ArrayList<Row>();
        ZipFile zip = new ZipFile(args[0]);
        try {
            List<String> names = new ArrayList<String>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.isDirectory() && en.getName().endsWith(".class")) {
                    names.add(en.getName());
                }
            }
            Collections.sort(names);
            for (String name : names) {
                ClassNode cn = new ClassNode();
                new ClassReader(zip.getInputStream(zip.getEntry(name))).accept(cn, 0);
                for (MethodNode mn : cn.methods) {
                    scanMethod(rows, cn.name, mn);
                }
            }
        } finally {
            zip.close();
        }

        // Ordenacao estavel: classe, metodo, descritor, offset.
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int c = a.clazz.compareTo(b.clazz);
                if (c != 0) return c;
                c = a.method.compareTo(b.method);
                if (c != 0) return c;
                c = a.desc.compareTo(b.desc);
                if (c != 0) return c;
                return Integer.compare(a.offset, b.offset);
            }
        });

        Writer w = new OutputStreamWriter(new FileOutputStream(args[1]), "UTF-8");
        try {
            w.write("classe,metodo,descritor,offset,chave,operacao,descritor_operacao,contexto\n");
            for (Row r : rows) {
                w.write(csv(r.clazz) + "," + csv(r.method) + "," + csv(r.desc) + ","
                        + r.offset + "," + csv(r.key) + "," + csv(r.op) + ","
                        + csv(r.opDesc) + "," + csv(r.context) + "\n");
            }
        } finally {
            w.close();
        }
        System.out.println(args[0] + ": " + rows.size() + " sites -> " + args[1]);
    }

    private static void scanMethod(List<Row> rows, String clazz, MethodNode mn) {
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                if (fi.owner.equals(JRMCOREH)
                        && ("curTP".equals(fi.name) || "curTPLong".equals(fi.name))) {
                    String op = fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC
                            ? "field-read" : "field-write";
                    rows.add(new Row(clazz, mn.name, mn.desc, i, fi.name,
                            op + "(" + fi.name + ":" + fi.desc + ")", fi.desc,
                            context(insns, i)));
                }
                continue;
            }
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode mi = (MethodInsnNode) insn;
            String op = operation(mi);
            if (op == null) {
                continue;
            }
            String key = keyBefore(insns, i);
            if (key == null) {
                continue;
            }
            rows.add(new Row(clazz, mn.name, mn.desc, i, key, op + "(" + mi.name + ")", mi.desc,
                    context(insns, i)));
        }
    }

    private static String operation(MethodInsnNode mi) {
        if (mi.owner.equals(NBT)) {
            if ("func_74762_e".equals(mi.name) || "getInteger".equals(mi.name)) return "nbt-read-int";
            if ("func_74763_f".equals(mi.name) || "getLong".equals(mi.name)) return "nbt-read-long";
            if ("func_74768_a".equals(mi.name) || "setInteger".equals(mi.name)) return "nbt-write-int";
            if ("func_74772_a".equals(mi.name) || "setLong".equals(mi.name)) return "nbt-write-long";
            if ("func_74781_a".equals(mi.name) || "getTag".equals(mi.name)) return "nbt-get-tag";
            if ("func_74764_b".equals(mi.name) || "hasKey".equals(mi.name)) return "nbt-has-key";
            if ("func_74765_d".equals(mi.name) || "getShort".equals(mi.name)) return "nbt-read-short";
            if ("func_74777_a".equals(mi.name) || "setShort".equals(mi.name)) return "nbt-write-short";
            if ("func_74771_c".equals(mi.name) || "getByte".equals(mi.name)) return "nbt-read-byte";
            if ("func_74774_a".equals(mi.name) || "setByte".equals(mi.name)) return "nbt-write-byte";
            return null;
        }
        if (mi.owner.equals(JRMCOREH)) {
            if ("getInt".equals(mi.name) && mi.desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I")) {
                return "jrmc-get-int";
            }
            if ("setInt".equals(mi.name) && mi.desc.equals("(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V")) {
                return "jrmc-set-int";
            }
            if ("getLong".equals(mi.name) && mi.desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J")) {
                return "jrmc-get-long";
            }
            if ("setLong".equals(mi.name) && mi.desc.equals("(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V")) {
                return "jrmc-set-long";
            }
        }
        return null;
    }

    /**
     * Ultima constante de texto iniciada por "jrmcTp" antes da chamada.
     * Para no primeiro uso de variavel/metodo que nao seja o getter do NBT,
     * porque a chave e sempre empilhada imediatamente antes.
     */
    private static String keyBefore(AbstractInsnNode[] a, int i) {
        for (int j = i - 1; j >= 0 && j >= i - 8; j--) {
            AbstractInsnNode p = a[j];
            if (p instanceof LdcInsnNode) {
                Object c = ((LdcInsnNode) p).cst;
                if (c instanceof String && ((String) c).startsWith("jrmcTp")) {
                    return (String) c;
                }
            }
            if (p instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) p;
                // aload do jogador + getter de NBT sao permitidos entre a chave e o uso
                if (!(mi.owner.equals(JRMCOREH) && mi.name.equals("nbt"))) {
                    return null;
                }
            }
            if (p instanceof FieldInsnNode) {
                return null;
            }
        }
        return null;
    }

    private static String context(AbstractInsnNode[] a, int i) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, i - 6);
        for (int j = from; j < i; j++) {
            String s = shortInsn(a[j]);
            if (s == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String shortInsn(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) insn).cst;
            if (c instanceof String) {
                String s = (String) c;
                if (s.length() > 24) s = s.substring(0, 24) + "...";
                return "ldc\"" + s + "\"";
            }
            return "ldc" + c;
        }
        if (insn instanceof IntInsnNode) {
            return "push" + ((IntInsnNode) insn).operand;
        }
        if (insn instanceof VarInsnNode) {
            return "var" + ((VarInsnNode) insn).var;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            return "getfield:" + f.owner.substring(f.owner.lastIndexOf('/') + 1) + "." + f.name;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            return "call:" + m.owner.substring(m.owner.lastIndexOf('/') + 1) + "." + m.name;
        }
        if (insn instanceof IincInsnNode) {
            return "iinc" + ((IincInsnNode) insn).var + ":" + ((IincInsnNode) insn).incr;
        }
        int op = insn.getOpcode();
        switch (op) {
            case Opcodes.IADD: return "iadd";
            case Opcodes.ISUB: return "isub";
            case Opcodes.IMUL: return "imul";
            case Opcodes.IDIV: return "idiv";
            case Opcodes.I2L: return "i2l";
            case Opcodes.L2I: return "l2i";
            case Opcodes.LADD: return "ladd";
            case Opcodes.LSUB: return "lsub";
            case Opcodes.LCMP: return "lcmp";
            case Opcodes.IFGE: return "ifge";
            case Opcodes.IFGT: return "ifgt";
            case Opcodes.IFLE: return "ifle";
            case Opcodes.IFLT: return "iflt";
            case Opcodes.IFEQ: return "ifeq";
            case Opcodes.IFNE: return "ifne";
            case Opcodes.IF_ICMPGE: return "if_icmpge";
            case Opcodes.IF_ICMPGT: return "if_icmpgt";
            case Opcodes.IF_ICMPLE: return "if_icmple";
            case Opcodes.IF_ICMPLT: return "if_icmplt";
            case Opcodes.IF_ICMPEQ: return "if_icmpeq";
            case Opcodes.IF_ICMPNE: return "if_icmpne";
            case Opcodes.DUP: return "dup";
            case Opcodes.POP: return "pop";
            case Opcodes.ICONST_0: return "0";
            case Opcodes.ICONST_1: return "1";
            case Opcodes.ICONST_M1: return "-1";
            case Opcodes.I2B: return "i2b";
            case Opcodes.INEG: return "ineg";
            case Opcodes.LNEG: return "lneg";
            case Opcodes.LCONST_0: return "0L";
            default: return null;
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static final class Row {
        final String clazz;
        final String method;
        final String desc;
        final int offset;
        final String key;
        final String op;
        final String opDesc;
        final String context;

        Row(String clazz, String method, String desc, int offset, String key,
            String op, String opDesc, String context) {
            this.clazz = clazz;
            this.method = method;
            this.desc = desc;
            this.offset = offset;
            this.key = key;
            this.op = op;
            this.opDesc = opDesc;
            this.context = context;
        }
    }
}
