import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.Printer;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Diagnostico: roda o Analyzer (SimpleVerifier) em um metodo e imprime a
 * instrucao que falhou com contexto. Somente leitura, nao faz parte do produto.
 *
 * uso: DiagFlow <jar> <classe-interna> <metodo> <desc> <cp-file>
 */
public final class DiagFlow {

    public static void main(String[] args) throws Exception {
        File jar = new File(args[0]);
        String className = args[1];
        String methodName = args[2];
        String methodDesc = args[3];
        File cpFile = new File(args[4]);

        ClassNode cn;
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry en = zip.getEntry(className + ".class");
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
            cn = new ClassNode();
            new ClassReader(raw).accept(cn, 0);
        }

        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(methodName) && mn.desc.equals(methodDesc)) {
                target = mn;
                break;
            }
        }
        if (target == null) {
            System.out.println("metodo nao encontrado");
            System.exit(2);
        }

        HierarchyResolver resolver = new HierarchyResolver();
        for (String path : readClasspath(cpFile)) {
            File f = new File(path);
            if (f.isDirectory()) {
                resolver.addDirectory(f);
            } else if (f.isFile()) {
                resolver.addJar(f);
            }
        }
        resolver.register(readJarEntry(jar, className));

        boolean isInterface = (cn.access & Opcodes.ACC_INTERFACE) != 0;
        SimpleVerifier verifier = new DiagVerifier(resolver, cn, isInterface);
        Analyzer<BasicValue> analyzer = new Analyzer<>(verifier);
        try {
            analyzer.analyze(cn.name, target);
            System.out.println("OK: metodo verifica");
        } catch (AnalyzerException e) {
            System.out.println("FALHA: " + e.getMessage());
            AbstractInsnNode[] insns = target.instructions.toArray();
            System.out.println("total instrucoes (com labels): " + insns.length);
            int idx = parseIndex(e.getMessage());
            if (idx >= 0) {
                int from = Math.max(0, idx - 8);
                int to = Math.min(insns.length - 1, idx + 4);
                for (int i = from; i <= to; i++) {
                    String mark = (i == idx) ? ">>" : "  ";
                    System.out.println(mark + " [" + i + "] " + describe(insns[i]));
                }
            }
        }
    }

    /** Verificador apoiado no HierarchyResolver (mesma politica do VerifyJar). */
    private static final class DiagVerifier extends SimpleVerifier {
        private final HierarchyResolver resolver;

        DiagVerifier(HierarchyResolver resolver, ClassNode cn, boolean isInterface) {
            super(Opcodes.ASM9,
                    Type.getObjectType(cn.name),
                    cn.superName == null ? Type.getType(Object.class) : Type.getObjectType(cn.superName),
                    types(cn.interfaces), isInterface);
            this.resolver = resolver;
        }

        private static List<Type> types(List<String> names) {
            List<Type> out = new ArrayList<Type>(names.size());
            for (String n : names) {
                out.add(Type.getObjectType(n));
            }
            return out;
        }

        @Override
        protected boolean isInterface(Type type) {
            if (type.getSort() != Type.OBJECT) return false;
            String n = type.getInternalName();
            return resolver.exists(n) && resolver.isInterface(n);
        }

        @Override
        protected Type getSuperClass(Type type) {
            if (type.getSort() != Type.OBJECT) return null;
            String n = type.getInternalName();
            if (!resolver.exists(n)) {
                throw new HierarchyResolver.MissingClassException(n);
            }
            if (resolver.isInterface(n)) return Type.getType(Object.class);
            String s = resolver.superClassOf(n);
            return s == null ? null : Type.getObjectType(s);
        }

        @Override
        protected boolean isAssignableFrom(Type t1, Type t2) {
            if (t1.equals(t2)) return true;
            if (t1.getSort() == Type.OBJECT && t2.getSort() == Type.OBJECT) {
                String a = t1.getInternalName();
                String b = t2.getInternalName();
                if (!resolver.exists(a) || !resolver.exists(b)) {
                    throw new HierarchyResolver.MissingClassException(resolver.exists(a) ? b : a);
                }
                return resolver.isAssignableFrom(a, b);
            }
            if (t1.getSort() == Type.ARRAY || t2.getSort() == Type.ARRAY) {
                return resolver.isAssignableFrom(t1.getDescriptor(), t2.getDescriptor());
            }
            return false;
        }

        @Override
        public BasicValue merge(BasicValue value1, BasicValue value2) {
            if (value1.equals(value2)) {
                return value1;
            }
            Type t1 = value1.getType();
            Type t2 = value2.getType();
            if (t1 == null || t2 == null) {
                return BasicValue.UNINITIALIZED_VALUE;
            }
            int s1 = t1.getSort();
            int s2 = t2.getSort();
            boolean p1 = s1 >= Type.BOOLEAN && s1 <= Type.DOUBLE;
            boolean p2 = s2 >= Type.BOOLEAN && s2 <= Type.DOUBLE;
            if (p1 || p2) {
                return BasicValue.UNINITIALIZED_VALUE;
            }
            String n1 = nameOf(t1);
            String n2 = nameOf(t2);
            if ("null".equals(n1)) {
                return value2;
            }
            if ("null".equals(n2)) {
                return value1;
            }
            if (resolver.isAssignableFrom(n1, n2)) {
                return value1;
            }
            if (resolver.isAssignableFrom(n2, n1)) {
                return value2;
            }
            String common = resolver.commonSuperClass(n1, n2);
            if (!HierarchyResolver.OBJECT.equals(common)) {
                return new BasicValue(typeOf(common));
            }
            String itf = resolver.commonInterface(n1, n2);
            if (itf != null) {
                return new BasicValue(Type.getObjectType(itf));
            }
            return BasicValue.REFERENCE_VALUE;
        }

        private static String nameOf(Type t) {
            if (t.getSort() == Type.ARRAY) {
                return t.getDescriptor();
            }
            if (t.getSort() == Type.OBJECT) {
                return t.getInternalName();
            }
            return t.getDescriptor();
        }

        private static Type typeOf(String n) {
            return n.startsWith("[") ? Type.getType(n) : Type.getObjectType(n);
        }
    }

    private static List<String> readClasspath(File file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(file.toPath(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return out;
    }

    private static byte[] readJarEntry(File jar, String className) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry en = zip.getEntry(className + ".class");
            try (InputStream is = zip.getInputStream(en)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }
    }

    private static String describe(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        String name = (op < 0) ? insn.getClass().getSimpleName() : Printer.OPCODES[op];
        StringBuilder sb = new StringBuilder(name);
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            sb.append(' ').append(m.owner).append('.').append(m.name).append(m.desc);
        } else if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            sb.append(' ').append(f.owner).append('.').append(f.name).append(':').append(f.desc);
        } else if (insn instanceof VarInsnNode) {
            sb.append(" var=").append(((VarInsnNode) insn).var);
        } else if (insn instanceof LdcInsnNode) {
            sb.append(' ').append(((LdcInsnNode) insn).cst);
        } else if (insn instanceof IntInsnNode) {
            sb.append(' ').append(((IntInsnNode) insn).operand);
        } else if (insn instanceof TypeInsnNode) {
            sb.append(' ').append(((TypeInsnNode) insn).desc);
        }
        return sb.toString();
    }

    private static int parseIndex(String msg) {        if (msg == null) {
            return -1;
        }
        String key = "instruction ";
        int i = msg.indexOf(key);
        if (i < 0) {
            return -1;
        }
        int j = i + key.length();
        int k = j;
        while (k < msg.length() && Character.isDigit(msg.charAt(k))) {
            k++;
        }
        try {
            return Integer.parseInt(msg.substring(j, k));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
