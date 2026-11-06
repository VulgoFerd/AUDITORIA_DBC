import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verificador de JAR da 1.0.7.
 *
 * Verificacoes (todas abortam o processo com exit != 0):
 *  1. integridade do ZIP (CRC, entradas duplicadas, nomes invalidos);
 *  2. classes obrigatorias presentes;
 *  3. versao de bytecode esperada;
 *  4. superclasse e interfaces resolvidas (nunca Object por fallback);
 *  5. toda instrucao de campo resolve para um campo declarado na hierarquia,
 *     com a mesma descricao e com static/instance correto;
 *  6. toda invocacao resolve para um metodo declarado na hierarquia, com
 *     descricao exata (pega o bug Stats2 -> handleUpgrade da 1.0.6);
 *  7. analise de fluxo com SimpleVerifier sobre a hierarquia real;
 *  8. uso de chaves de TP no NBT coerente com o tipo (LONG x INT);
 *  9. checkcast/instanceof/new com tipos resolvidos.
 *
 * Uso:
 *   java VerifyJar --jar <jar> [--cp <a;b;...>] [--required <arquivo>]
 *                  [--srg-methods <methods.csv>] [--srg-fields <fields.csv>]
 *                  [--expect-major 52] [--verbose]
 *
 * Sem --cp, apenas a hierarquia interna do JAR e o JRE sao usados.
 */
public final class VerifyJar {

    public static void main(String[] args) {
        Map<String, String> opts = new LinkedHashMap<String, String>();
        Set<String> flags = new HashSet<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--verbose")) {
                flags.add(a);
            } else if (a.startsWith("--")) {
                if (i + 1 >= args.length) {
                    fail("opcao sem valor: " + a);
                }
                opts.put(a, args[++i]);
            } else {
                fail("argumento inesperado: " + a);
            }
        }
        String jarPath = opts.get("--jar");
        if (jarPath == null) {
            fail("--jar e obrigatorio");
        }

        int expectMajor = -1;
        if (opts.containsKey("--expect-major")) {
            expectMajor = Integer.parseInt(opts.get("--expect-major"));
        }

        VerifyJar v = new VerifyJar(jarPath, flags.contains("--verbose"));
        v.expectMajor = expectMajor;
        if (opts.containsKey("--cp")) {
            for (String p : opts.get("--cp").split(";")) {
                if (p.trim().isEmpty()) continue;
                v.addClasspath(p.trim());
            }
        }
        if (opts.containsKey("--cp-file")) {
            v.addClasspathFile(new File(opts.get("--cp-file")));
        }
        if (opts.containsKey("--required")) {
            v.loadRequired(new File(opts.get("--required")));
        }
        if (opts.containsKey("--optional")) {
            v.loadOptionalPrefixes(new File(opts.get("--optional")));
        }
        if (opts.containsKey("--srg-methods")) {
            v.loadSrg(new File(opts.get("--srg-methods")), v.methodAlias);
        }
        if (opts.containsKey("--srg-fields")) {
            v.loadSrg(new File(opts.get("--srg-fields")), v.fieldAlias);
        }

        boolean ok = v.run();
        System.exit(ok ? 0 : 1);
    }

    private static void fail(String message) {
        System.err.println("VerifyJar: " + message);
        System.exit(2);
    }

    // ----------------------------------------------------------------- estado

    private final String jarPath;
    private final boolean verbose;
    private final List<Violation> violations = new ArrayList<Violation>();
    private final List<Violation> ignored = new ArrayList<Violation>();
    private final List<String> optionalPrefixes = new ArrayList<String>();
    private final Set<String> widenedFields = new LinkedHashSet<String>();
    private final Set<String> widenedMethods = new LinkedHashSet<String>();
    private final Set<String> widenedOwners = new LinkedHashSet<String>();
    private int atRules;
    private final Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
    private final Set<String> required = new LinkedHashSet<String>();
    private final HierarchyResolver resolver = new HierarchyResolver();
    private final Map<String, String> methodAlias = new LinkedHashMap<String, String>();
    private final Map<String, String> fieldAlias = new LinkedHashMap<String, String>();
    private final Map<String, ClassInfo> members = new LinkedHashMap<String, ClassInfo>();
    private int expectMajor = -1;

    private VerifyJar(String jarPath, boolean verbose) {
        this.jarPath = jarPath;
        this.verbose = verbose;
    }

    private static final class Violation {
        final String kind;
        final String where;
        final String detail;

        Violation(String kind, String where, String detail) {
            this.kind = kind;
            this.where = where;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return kind + "  " + where + "  " + detail;
        }
    }

    /** Metadados de membros de uma classe. */
    private static final class ClassInfo {
        final String name;
        final String superName;
        final List<String> interfaces;
        final boolean isInterface;
        final Map<String, Member> fields = new LinkedHashMap<String, Member>();
        final Map<String, Member> methods = new LinkedHashMap<String, Member>();

        ClassInfo(String name, String superName, List<String> interfaces, boolean isInterface) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces;
            this.isInterface = isInterface;
        }
    }

    private static final class Member {
        final String owner;
        final String name;
        final String desc;
        final int access;

        Member(String owner, String name, String desc, int access) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.access = access;
        }

        boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

        boolean isPrivate() {
            return (access & Opcodes.ACC_PRIVATE) != 0;
        }

        boolean isFinal() {
            return (access & Opcodes.ACC_FINAL) != 0;
        }

        boolean isAbstract() {
            return (access & Opcodes.ACC_ABSTRACT) != 0;
        }
    }

    // ------------------------------------------------------------- carregar

    private void addClasspath(String path) {
        try {
            File f = new File(path);
            if (f.isDirectory()) {
                resolver.addDirectory(f);
            } else if (f.isFile()) {
                resolver.addJar(f);
            } else {
                fail("classpath inexistente: " + path);
            }
        } catch (IOException e) {
            fail("falha ao abrir classpath " + path + ": " + e.getMessage());
        }
    }

    private void addClasspathFile(File file) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    addClasspath(line);
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            fail("falha ao ler " + file + ": " + e.getMessage());
        }
    }

    private void loadRequired(File file) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    required.add(line.replace('.', '/'));
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            fail("falha ao ler " + file + ": " + e.getMessage());
        }
    }

    private void loadSrg(File file, Map<String, String> target) {        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                        target.put(parts[0], parts[1]);
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            fail("falha ao ler " + file + ": " + e.getMessage());
        }
        System.out.println("alias carregado de " + file.getName() + ": " + target.size() + " nomes");
    }

    private void loadOptionalPrefixes(File file) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    optionalPrefixes.add(line.replace('.', '/'));
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            fail("falha ao ler " + file + ": " + e.getMessage());
        }
        System.out.println("dependencias opcionais declaradas: " + optionalPrefixes.size());
    }

    private boolean isOptionalClass(String internalName) {
        if (internalName == null) {
            return false;
        }
        String n = internalName.replace('.', '/');
        for (String prefix : optionalPrefixes) {
            if (n.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------- executar

    private boolean run() {
        System.out.println("== VerifyJar 1.0.7 ==");
        System.out.println("jar: " + jarPath);

        if (!loadJar()) {
            return report();
        }

        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            resolver.register(e.getValue());
            members.put(e.getKey(), readMembers(e.getValue()));
        }

        System.out.println("classes no jar: " + classes.size());
        System.out.println("classes obrigatorias: " + required.size());
        System.out.println("regras de access transformer honradas: " + atRules);
        System.out.println("classpath externo: " + (resolver == null ? 0 : "resolvido sob demanda"));

        checkRequired();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            verifyClass(e.getKey(), e.getValue());
        }

        return report();
    }

    private boolean loadJar() {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jarPath);
            Map<String, String> seen = new HashMap<String, String>();
            byte[] buffer = new byte[8192];
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (seen.containsKey(lower)) {
                    violation("zip-duplicado", name, "tambem presente como " + seen.get(lower));
                } else {
                    seen.put(lower, name);
                }
                if (name.indexOf('\\') >= 0) {
                    violation("zip-nome-invalido", name, "barra invertida no nome da entrada");
                }
                if (e.isDirectory()) continue;
                byte[] data = readAll(zip.getInputStream(e), buffer);
                if (e.getSize() >= 0 && e.getSize() != data.length) {
                    violation("zip-tamanho", name, "declarado " + e.getSize() + ", lido " + data.length);
                }
                CRC32 crc = new CRC32();
                crc.update(data);
                if (e.getCrc() >= 0 && e.getCrc() != crc.getValue()) {
                    violation("zip-crc", name, "declarado " + e.getCrc() + ", calculado " + crc.getValue());
                }
                if (name.endsWith(".class")) {
                    String internal = name.substring(0, name.length() - ".class".length());
                    if (classes.containsKey(internal)) {
                        violation("classe-duplicada", internal, "duas entradas com o mesmo nome normalizado");
                    } else {
                        classes.put(internal, data);
                    }
                }
            }
            loadAccessTransformers(zip);
            return true;
        } catch (IOException e) {
            violation("jar-ilegivel", jarPath, e.toString());
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // leitura encerrada
                }
            }
        }
    }

    /**
     * Le Access Transformers embarcados no JAR.
     *
     * Um mod que declara {@code META-INF/*_at.cfg} tem permissao de runtime
     * para acessar os membros listados. O verificador honra exatamente as
     * regras declaradas; nada fora do AT vira excecao.
     */
    private void loadAccessTransformers(ZipFile zip) {
        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory()) continue;
            boolean isAt = name.startsWith("META-INF/")
                    && (name.endsWith("_at.cfg") || name.endsWith("accesstransformer.cfg"));
            if (!isAt) continue;
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(e), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("\uFEFF")) {
                            line = line.substring(1);
                        }
                        int hash = line.indexOf('#');
                        if (hash >= 0) {
                            line = line.substring(0, hash);
                        }
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split("\\s+");
                        if (parts.length < 3) continue;
                        String access = parts[0];
                        if (!access.contains("public") && !access.contains("protected")) {
                            continue;
                        }
                        String owner = parts[1].replace('.', '/');
                        String member = parts[2];
                        atRules++;
                        if (member.equals("*")) {
                            widenedOwners.add(owner);
                            continue;
                        }
                        if (member.indexOf('(') >= 0) {
                            widenedMethods.add(owner + "." + member.substring(0, member.indexOf('(')));
                            addAlias(widenedMethods, owner, member.substring(0, member.indexOf('(')), methodAlias);
                        } else {
                            widenedFields.add(owner + "." + member);
                            addAlias(widenedFields, owner, member, fieldAlias);
                        }
                    }
                } finally {
                    r.close();
                }
                System.out.println("access transformer lido: " + name);
            } catch (IOException ex) {
                fail("falha ao ler " + name + ": " + ex.getMessage());
            }
        }
    }

    private static void addAlias(Set<String> target, String owner, String srgMember, Map<String, String> alias) {
        String mcp = alias.get(srgMember);
        if (mcp != null && !mcp.equals(srgMember)) {
            target.add(owner + "." + mcp);
        }
    }

    private boolean isWidened(String owner, String member) {
        if (widenedOwners.contains(owner)) {
            return true;
        }
        return widenedFields.contains(owner + "." + member)
                || widenedMethods.contains(owner + "." + member);
    }

    private void checkRequired() {
        for (String r : required) {
            if (!classes.containsKey(r)) {
                violation("classe-obrigatoria-ausente", r, "nao encontrada no jar");
            }
        }
    }

    // --------------------------------------------------------- por classe

    private void verifyClass(String name, byte[] bytes) {
        ClassNode cn;
        try {
            cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        } catch (RuntimeException e) {
            violation("classe-ilegivel", name, e.toString());
            return;
        }

        if (cn.version != 0 && expectMajor > 0) {
            int major = cn.version & 0xFFFF;
            if (major != expectMajor) {
                violation("versao-bytecode", name,
                        "major " + major + ", esperado " + expectMajor);
            }
        }

        checkStructural(name, bytes);
        checkSupertypes(cn);
        checkMembers(cn);
        checkDataFlow(cn);
        checkTpKeys(cn);

        if (verbose) {
            System.out.println("  ok  " + name + " (" + cn.methods.size() + " metodos)");
        }
    }

    private void checkStructural(String name, byte[] bytes) {
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter sink = new ClassWriter(0);
            cr.accept(new CheckClassAdapter(sink, false), 0);
        } catch (RuntimeException e) {
            violation("estrutura", name, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void checkSupertypes(ClassNode cn) {
        if (cn.superName != null) {
            try {
                resolver.resolve(cn.superName);
            } catch (HierarchyResolver.MissingClassException e) {
                violation("superclasse-ausente", cn.name, cn.superName);
            }
        }
        for (String itf : cn.interfaces) {
            try {
                resolver.resolve(itf);
            } catch (HierarchyResolver.MissingClassException e) {
                violation("interface-ausente", cn.name, itf);
            }
        }
    }

    private void checkMembers(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            String where = cn.name + "." + mn.name + mn.desc;
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    checkFieldInsn(cn, where, mn, (FieldInsnNode) insn);
                } else if (insn instanceof MethodInsnNode) {
                    checkMethodInsn(cn, where, (MethodInsnNode) insn);
                } else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode) {
                    checkTypeInsn(cn, where, (org.objectweb.asm.tree.TypeInsnNode) insn);
                }
            }
        }
    }

    private void checkFieldInsn(ClassNode cn, String where, MethodNode mn, FieldInsnNode fi) {
        int op = fi.getOpcode();
        boolean isStaticAccess = op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC;
        if (!resolver.exists(fi.owner) && !HierarchyResolver.isArray(fi.owner)) {
            if (isOptionalClass(fi.owner)) {
                ignored("dependencia-opcional", where,
                        fi.owner + " ausente (declarado em analysis/optional-deps.txt)");
            } else {
                violation("dependencia-ausente", where, fi.owner + " nao encontrado no classpath");
            }
            return;
        }
        Member m = findField(fi.owner, fi.name, fi.desc);
        if (m == null) {
            violation("campo-nao-resolvido", where,
                    fi.owner + "." + fi.name + ":" + fi.desc);
            return;
        }
        if (m.isStatic() != isStaticAccess) {
            violation("campo-static-divergente", where,
                    fi.owner + "." + fi.name + " acesso " + (isStaticAccess ? "static" : "instance")
                            + " mas declarado " + (m.isStatic() ? "static" : "instance"));
        }
        if (m.isPrivate() && !m.owner.equals(cn.name) && !isWidened(m.owner, m.name)) {
            violation("campo-privado", where, m.owner + "." + m.name);
        }
        if (op == Opcodes.PUTFIELD && m.isFinal() && !m.owner.equals(cn.name)) {
            violation("campo-final-escrita-externa", where, m.owner + "." + m.name);
        }
        if (op == Opcodes.PUTSTATIC && m.isFinal() && !m.owner.equals(cn.name)
                && !"<clinit>".equals(mn.name)) {
            violation("campo-final-escrita-externa", where, m.owner + "." + m.name);
        }
    }

    private void checkMethodInsn(ClassNode cn, String where, MethodInsnNode mi) {
        if (!resolver.exists(mi.owner) && !HierarchyResolver.isArray(mi.owner)) {
            if (isOptionalClass(mi.owner)) {
                ignored("dependencia-opcional", where,
                        mi.owner + " ausente (declarado em analysis/optional-deps.txt)");
            } else {
                violation("dependencia-ausente", where, mi.owner + " nao encontrado no classpath");
            }
            return;
        }
        Member m = findMethod(mi.owner, mi.name, mi.desc);
        if (m == null) {
            violation("metodo-nao-resolvido", where,
                    mi.owner + "." + mi.name + mi.desc + " (opcode " + mi.getOpcode() + ")");
            return;
        }
        boolean ownerIsInterface = isInterfaceOrMissing(mi.owner);
        if (mi.getOpcode() == Opcodes.INVOKEINTERFACE && !ownerIsInterface && !m.isPrivate()) {
            violation("invokeinterface-em-classe", where, mi.owner + "." + mi.name);
        }
        if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && ownerIsInterface) {
            violation("invokevirtual-em-interface", where, mi.owner + "." + mi.name);
        }
        if (m.isPrivate() && !m.owner.equals(cn.name) && !isWidened(m.owner, m.name)) {
            violation("metodo-privado", where, m.owner + "." + m.name);
        }
        if (m.isAbstract()
                && (mi.getOpcode() == Opcodes.INVOKESPECIAL || mi.getOpcode() == Opcodes.INVOKESTATIC)) {
            violation("metodo-abstrato-invocado", where, m.owner + "." + m.name);
        }
    }

    private void checkTypeInsn(ClassNode cn, String where, org.objectweb.asm.tree.TypeInsnNode ti) {
        String t = ti.desc;
        if (HierarchyResolver.isArray(t)) {
            return;
        }
        if (!resolver.exists(t)) {
            if (isOptionalClass(t)) {
                ignored("dependencia-opcional", where,
                        t + " ausente (declarado em analysis/optional-deps.txt)");
            } else {
                violation("tipo-nao-resolvido", where, t + " (opcode " + ti.getOpcode() + ")");
            }
        }
    }

    private void checkDataFlow(ClassNode cn) {
        boolean isInterface = (cn.access & Opcodes.ACC_INTERFACE) != 0;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions.size() == 0) continue;
            String where = cn.name + "." + mn.name + mn.desc;
            try {
                ResolverVerifier verifier = new ResolverVerifier(resolver, cn, isInterface);
                Analyzer<BasicValue> analyzer = new Analyzer<BasicValue>(verifier);
                analyzer.analyze(cn.name, mn);
            } catch (AnalyzerException e) {
                String missing = missingClassOf(e);
                if (missing != null && isOptionalClass(missing)) {
                    ignored("dependencia-opcional", where,
                            missing + " ausente (declarado em analysis/optional-deps.txt)");
                } else {
                    violation("fluxo-invalido", where, e.getMessage());
                }
            } catch (RuntimeException e) {
                violation("fluxo-erro", where, e.toString());
            }
        }
    }

    private static String missingClassOf(AnalyzerException e) {
        String message = e.getMessage();
        if (message == null) {
            return null;
        }
        String marker = "classe ausente na hierarquia: ";
        int at = message.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int start = at + marker.length();
        int end = message.indexOf(' ', start);
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }

    /** Checagem dos tipos das chaves de TP. */
    private void checkTpKeys(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            AbstractInsnNode[] insns = mn.instructions.toArray();
            String where = cn.name + "." + mn.name + mn.desc;
            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) insns[i];
                if (!mi.owner.equals("net/minecraft/nbt/NBTTagCompound")) continue;
                String key = keyBefore(insns, i);
                if (key == null) continue;
                boolean writesLong = "func_74772_a".equals(mi.name) || "setLong".equals(mi.name);
                boolean readsLong = "func_74763_f".equals(mi.name) || "getLong".equals(mi.name);
                boolean writesInt = "func_74768_a".equals(mi.name) || "setInteger".equals(mi.name);
                boolean readsInt = "func_74762_e".equals(mi.name) || "getInteger".equals(mi.name);
                if (key.equals("jrmcTplong")) {
                    if (writesInt || readsInt) {
                        violation("tp-tipo-divergente", where,
                                key + " acessado como INT em " + mi.name + " (autoridade deve ser LONG)");
                    }
                } else if (key.equals("jrmcTpint")) {
                    if (writesLong || readsLong) {
                        violation("tp-tipo-divergente", where,
                                key + " acessado como LONG em " + mi.name + " (espelho deve ser INT)");
                    }
                }
            }
        }
    }

    private static String keyBefore(AbstractInsnNode[] a, int i) {
        for (int j = i - 1; j >= 0 && j >= i - 6; j--) {
            AbstractInsnNode p = a[j];
            if (p instanceof org.objectweb.asm.tree.LdcInsnNode) {
                Object c = ((org.objectweb.asm.tree.LdcInsnNode) p).cst;
                if (c instanceof String && ((String) c).startsWith("jrmcTp")) {
                    return (String) c;
                }
            }
            if (p instanceof FieldInsnNode || p instanceof MethodInsnNode) {
                return null;
            }
        }
        return null;
    }

    // -------------------------------------------------------- resolucao

    private boolean isInterfaceOrMissing(String name) {
        try {
            return resolver.isInterface(name);
        } catch (HierarchyResolver.MissingClassException e) {
            return false;
        }
    }

    private Member findField(String owner, String name, String desc) {
        Member m = findFieldExact(owner, name, desc);
        if (m != null) return m;
        String alias = fieldAlias.get(name);
        if (alias != null) {
            return findFieldExact(owner, alias, desc);
        }
        return null;
    }

    private Member findFieldExact(String owner, String name, String desc) {
        if (owner == null || HierarchyResolver.isArray(owner)) return null;
        ClassInfo info = classInfo(owner);
        if (info == null) return null;
        Member m = info.fields.get(name + ":" + desc);
        if (m != null) return m;
        if (info.superName != null) {
            m = findFieldExact(info.superName, name, desc);
            if (m != null) return m;
        }
        for (String itf : info.interfaces) {
            m = findFieldExact(itf, name, desc);
            if (m != null) return m;
        }
        return null;
    }

    private Member findMethod(String owner, String name, String desc) {
        Member m = findMethodExact(owner, name, desc);
        if (m != null) return m;
        String alias = methodAlias.get(name);
        if (alias != null) {
            return findMethodExact(owner, alias, desc);
        }
        return null;
    }

    private Member findMethodExact(String owner, String name, String desc) {
        if (owner == null || HierarchyResolver.isArray(owner)) {
            // arrays: somente metodos de Object sao invocaveis
            return findMethodExact("java/lang/Object", name, desc);
        }
        ClassInfo info = classInfo(owner);
        if (info == null) return null;
        Member m = info.methods.get(name + desc);
        if (m != null) return m;
        if (!info.isInterface && info.superName != null) {
            m = findMethodExact(info.superName, name, desc);
            if (m != null) return m;
        }
        for (String itf : info.interfaces) {
            m = findMethodExact(itf, name, desc);
            if (m != null) return m;
        }
        return null;
    }

    private ClassInfo classInfo(String name) {
        ClassInfo info = members.get(name);
        if (info != null) return info;
        try {
            resolver.resolve(name);
        } catch (HierarchyResolver.MissingClassException e) {
            return null;
        }
        byte[] bytes = readClassBytes(name);
        if (bytes == null) return null;
        info = readMembers(bytes);
        members.put(name, info);
        return info;
    }

    private byte[] readClassBytes(String name) {
        // O resolver ja sabe onde a classe esta; para membros precisamos dos bytes.
        // Reaproveita o cache do resolver registrando um leitor proprio.
        if (classes.containsKey(name)) return classes.get(name);
        return resolver.readBytes(name);
    }

    private static ClassInfo readMembers(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        ClassInfo info = new ClassInfo(cn.name, cn.superName, cn.interfaces,
                (cn.access & Opcodes.ACC_INTERFACE) != 0);
        for (FieldNode f : cn.fields) {
            info.fields.put(f.name + ":" + f.desc, new Member(cn.name, f.name, f.desc, f.access));
        }
        for (MethodNode m : cn.methods) {
            info.methods.put(m.name + m.desc, new Member(cn.name, m.name, m.desc, m.access));
        }
        return info;
    }

    // -------------------------------------------------------------- relatorio

    private boolean report() {
        System.out.println();
        if (!ignored.isEmpty()) {
            System.out.println("ignorados (documentados): " + ignored.size());
            Map<String, Integer> byClass = new TreeMap<String, Integer>();
            for (Violation v : ignored) {
                Integer n = byClass.get(v.detail);
                byClass.put(v.detail, n == null ? 1 : n + 1);
            }
            for (Map.Entry<String, Integer> e : byClass.entrySet()) {
                System.out.println("  " + e.getValue() + "x " + e.getKey());
            }
        }
        if (violations.isEmpty()) {
            System.out.println("violacoes: 0");
            System.out.println("RESULTADO: OK");
            return true;
        }
        System.out.println("violacoes: " + violations.size());
        Map<String, Integer> byKind = new TreeMap<String, Integer>();
        for (Violation v : violations) {
            Integer n = byKind.get(v.kind);
            byKind.put(v.kind, n == null ? 1 : n + 1);
        }
        for (Map.Entry<String, Integer> e : byKind.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        System.out.println();
        for (Violation v : violations) {
            System.out.println("  " + v);
        }
        System.out.println();
        System.out.println("RESULTADO: FALHOU");
        return false;
    }

    private void violation(String kind, String where, String detail) {
        violations.add(new Violation(kind, where, detail));
    }

    private void ignored(String kind, String where, String detail) {
        ignored.add(new Violation(kind, where, detail));
    }

    private static byte[] readAll(InputStream is, byte[] buffer) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            int n;
            while ((n = is.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            is.close();
        }
    }

    /**
     * SimpleVerifier apoiado no HierarchyResolver.
     *
     * As tres operacoes de hierarquia sao resolvidas pelo resolver real.
     * getClass() nunca deve ser chamada: se for, falha alto em vez de devolver
     * Object por conveniencia.
     */
    private static final class ResolverVerifier extends SimpleVerifier {
        private final HierarchyResolver resolver;

        ResolverVerifier(HierarchyResolver resolver, ClassNode cn, boolean isInterface) {
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
                    throw new HierarchyResolver.MissingClassException(
                            resolver.exists(a) ? b : a);
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
            // Sem superclasse comum: um tipo de quadro pode ser uma interface
            // implementada por ambos (a JVM aceita qualquer supertipo comum).
            String itf = resolver.commonInterface(n1, n2);
            if (itf != null) {
                return new BasicValue(Type.getObjectType(itf));
            }
            return BasicValue.REFERENCE_VALUE;
        }

        private static String nameOf(Type type) {
            return type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
        }

        private static Type typeOf(String name) {
            return HierarchyResolver.isArray(name)
                    ? Type.getType(name)
                    : Type.getObjectType(name);
        }

        @Override
        protected boolean isSubTypeOf(BasicValue value, BasicValue expected) {
            Type actual = value.getType();
            Type exp = expected.getType();
            if (actual == null || exp == null) {
                return actual == exp;
            }
            if (actual.equals(exp)) {
                return true;
            }
            int as = actual.getSort();
            int es = exp.getSort();
            boolean actualPrim = as >= Type.BOOLEAN && as <= Type.DOUBLE;
            boolean expPrim = es >= Type.BOOLEAN && es <= Type.DOUBLE;
            if (actualPrim || expPrim) {
                return false;
            }
            // valor nulo e atribuivel a qualquer referencia
            if (as == Type.OBJECT && "null".equals(actual.getInternalName())) {
                return true;
            }
            if (as == Type.OBJECT && es == Type.OBJECT) {
                return resolver.isAssignableFrom(exp.getInternalName(), actual.getInternalName());
            }
            if (as == Type.ARRAY && es == Type.ARRAY) {
                return resolver.isAssignableFrom(exp.getDescriptor(), actual.getDescriptor());
            }
            if (as == Type.ARRAY && es == Type.OBJECT) {
                return resolver.isAssignableFrom(exp.getInternalName(), actual.getDescriptor());
            }
            return false;
        }

        /**
         * Nunca usada para decisoes de hierarquia: isAssignableFrom,
         * getSuperClass, isInterface e isSubTypeOf sao todos sobrescritos.
         * Retorna Object/Object[] para satisfazer chamadas internas do ASM
         * sem cair em Class.forName (que exigiria o classpath em runtime).
         */
        @Override
        protected Class<?> getClass(Type type) {
            return type.getSort() == Type.ARRAY ? Object[].class : Object.class;
        }
    }
}
