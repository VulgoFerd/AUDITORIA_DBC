import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolve hierarquia de classes a partir de fontes reais.
 *
 * Diferente da 1.0.6, NAO existe fallback silencioso para java/lang/Object:
 *  - classe ausente em todas as fontes -> {@link MissingClassException};
 *  - classe abstrata/interface tratada corretamente (superclasse != interfaces);
 *  - arrays resolvidos pela regra da JVM (array de referencia e subtipo de
 *    Object, Cloneable e Serializable; array de primitivo idem);
 *  - {@link #commonSuperClass} devolve Object apenas quando o resultado correto
 *    e realmente Object (interfaces sem superclasse comum).
 *
 * Fontes consultadas, em ordem:
 *  1. classes registradas em memoria ({@link #register});
 *  2. JARs e diretorios adicionados explicitamente;
 *  3. resource do ClassLoader do sistema (JRE).
 */
public final class HierarchyResolver {

    public static final String OBJECT = "java/lang/Object";

    /** Erro de hierarquia incompleta. Nunca deve ser mascarado. */
    public static final class MissingClassException extends RuntimeException {
        public MissingClassException(String name) {
            super("classe ausente na hierarquia: " + name
                    + " (adicione o JAR/diretorio correto ao classpath)");
        }
    }

    /** Metadados minimos de uma classe. */
    public static final class ClassInfo {
        public final String name;
        public final String superName;
        public final List<String> interfaces;
        public final boolean isInterface;

        ClassInfo(String name, String superName, List<String> interfaces, boolean isInterface) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces;
            this.isInterface = isInterface;
        }

        @Override
        public String toString() {
            return "ClassInfo{" + name + " extends " + superName
                    + " implements " + interfaces + (isInterface ? " (interface)" : "") + "}";
        }
    }

    private final Map<String, ClassInfo> memory = new LinkedHashMap<String, ClassInfo>();
    private final List<ZipFile> jars = new ArrayList<ZipFile>();
    private final List<File> dirs = new ArrayList<File>();
    private final Map<String, ClassInfo> cache = new LinkedHashMap<String, ClassInfo>();

    // ------------------------------------------------------------- registro

    public void register(byte[] classBytes) {
        ClassInfo info = readInfo(classBytes);
        memory.put(info.name, info);
        cache.put(info.name, info);
    }

    public void addJar(File jar) throws IOException {
        if (!jar.isFile()) {
            throw new FileNotFoundException("JAR nao encontrado: " + jar);
        }
        jars.add(new ZipFile(jar));
    }

    public void addDirectory(File dir) {
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("diretorio nao encontrado: " + dir);
        }
        dirs.add(dir);
    }

    public void close() {
        for (ZipFile z : jars) {
            try {
                z.close();
            } catch (IOException ignored) {
                // fechamento de leitura; nao ha acao corretiva possivel
            }
        }
        jars.clear();
    }

    // -------------------------------------------------------------- consulta

    /** Resolve a classe ou lanca {@link MissingClassException}. */
    public ClassInfo resolve(String internalName) {
        if (internalName == null) {
            throw new IllegalArgumentException("nome de classe nulo");
        }
        ClassInfo info = cache.get(internalName);
        if (info != null) {
            return info;
        }
        info = memory.get(internalName);
        if (info != null) {
            cache.put(internalName, info);
            return info;
        }
        byte[] bytes = readFromSources(internalName);
        if (bytes == null) {
            throw new MissingClassException(internalName);
        }
        info = readInfo(bytes);
        cache.put(internalName, info);
        return info;
    }

    /** Bytes da classe, ou null se ausente. Somente leitura. */
    public byte[] readBytes(String internalName) {
        byte[] bytes = readFromSources(internalName);
        if (bytes == null) {
            throw new MissingClassException(internalName);
        }
        return bytes;
    }

    public boolean exists(String internalName) {
        try {
            resolve(internalName);
            return true;
        } catch (MissingClassException e) {
            return false;
        }
    }

    /** Superclasse direta. Arrays retornam Object. Interfaces retornam Object. */
    public String superClassOf(String internalName) {
        if (isArray(internalName)) {
            return OBJECT;
        }
        ClassInfo info = resolve(internalName);
        if (info.isInterface) {
            return OBJECT;
        }
        if (info.superName == null) {
            return OBJECT;
        }
        return info.superName;
    }

    /** Interfaces diretas declaradas. Arrays implementam Cloneable/Serializable. */
    public List<String> interfacesOf(String internalName) {
        if (isArray(internalName)) {
            return Arrays.asList("java/lang/Cloneable", "java/io/Serializable");
        }
        return resolve(internalName).interfaces;
    }

    public boolean isInterface(String internalName) {
        return !isArray(internalName) && resolve(internalName).isInterface;
    }

    public static boolean isArray(String internalName) {
        return internalName != null && internalName.startsWith("[");
    }

    /** {@code a} e supertipo de {@code b} (ou igual). */
    public boolean isAssignableFrom(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (OBJECT.equals(a)) {
            return true;
        }
        if (b == null) {
            return false;
        }
        if (isArray(b)) {
            if (isArray(a)) {
                String ea = elementType(a);
                String eb = elementType(b);
                if (ea == null || eb == null) {
                    return false;
                }
                if (isPrimitiveDescriptor(ea) || isPrimitiveDescriptor(eb)) {
                    return ea.equals(eb);
                }
                return isAssignableFrom(ea, eb);
            }
            // array pode ser atribuido a Object, Cloneable, Serializable
            return OBJECT.equals(a) || "java/lang/Cloneable".equals(a)
                    || "java/io/Serializable".equals(a);
        }
        if (isArray(a)) {
            return false;
        }
        if (isInterface(a)) {
            return implementsInterface(b, a);
        }
        String cur = b;
        while (cur != null) {
            if (cur.equals(a)) {
                return true;
            }
            ClassInfo info = resolve(cur);
            cur = info.isInterface ? null : info.superName;
        }
        return false;
    }

    /**
     * Superclasse comum no sentido exigido por ASM.
     * Nunca devolve Object por conveniencia: se as classes forem incomuns e
     * nenhuma cadeia chegar a um ancestral comum, lanca excecao.
     */
    public String commonSuperClass(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        if (isAssignableFrom(a, b)) {
            return a;
        }
        if (isAssignableFrom(b, a)) {
            return b;
        }
        if (isArray(a) || isArray(b)) {
            if (isArray(a) && isArray(b)) {
                String ea = elementType(a);
                String eb = elementType(b);
                if (ea != null && eb != null && !isPrimitiveDescriptor(ea)
                        && !isPrimitiveDescriptor(eb)) {
                    String common = commonSuperClass(ea, eb);
                    return "[" + descriptorOf(common);
                }
            }
            return OBJECT;
        }
        if (isInterface(a) && isInterface(b)) {
            return OBJECT;
        }
        String cur = isInterface(a) ? OBJECT : a;
        while (cur != null) {
            if (isAssignableFrom(cur, b)) {
                return cur;
            }
            if (OBJECT.equals(cur)) {
                return OBJECT;
            }
            cur = superClassOf(cur);
        }
        throw new IllegalStateException("sem superclasse comum entre " + a + " e " + b);
    }

    // -------------------------------------------------------------- interno

    /**
     * Interface mais especifica implementada pelas duas classes.
     * Retorna null quando nao ha interface comum.
     *
     * Usada na fusao de tipos do fluxo de dados: a JVM aceita um tipo de
     * quadro que e supertipo de todos os valores de entrada, mesmo que nao
     * exista superclasse comum (ex.: duas mensagens que implementam IMessage).
     */
    public String commonInterface(String a, String b) {
        if (a == null || b == null || isArray(a) || isArray(b)
                || isPrimitiveDescriptor(a) || isPrimitiveDescriptor(b)) {
            return null;
        }
        for (String itf : allInterfaces(a)) {
            if (implementsInterface(b, itf)) {
                return itf;
            }
        }
        return null;
    }

    /** Interfaces de {@code className} em ordem deterministica (diretas, depois herdadas). */
    private List<String> allInterfaces(String className) {
        Set<String> ordered = new LinkedHashSet<String>();
        collectInterfaces(className, ordered, new HashSet<String>());
        return new ArrayList<String>(ordered);
    }

    private void collectInterfaces(String className, Set<String> out, Set<String> visited) {
        if (className == null || isArray(className) || isPrimitiveDescriptor(className)
                || !visited.add(className)) {
            return;
        }
        ClassInfo info = resolve(className);
        for (String itf : info.interfaces) {
            out.add(itf);
            collectInterfaces(itf, out, visited);
        }
        if (!info.isInterface && info.superName != null) {
            collectInterfaces(info.superName, out, visited);
        }
    }

    private boolean implementsInterface(String className, String iface) {
        if (className == null || isArray(className) || isPrimitiveDescriptor(className)) {
            return false;
        }
        ClassInfo info = resolve(className);
        for (String itf : info.interfaces) {
            if (itf.equals(iface) || implementsInterface(itf, iface)) {
                return true;
            }
        }
        if (!info.isInterface && info.superName != null) {
            return implementsInterface(info.superName, iface);
        }
        return false;
    }

    /** Descritor de um nome interno: "java/lang/String" -> "Ljava/lang/String;". */
    public static String descriptorOf(String internalName) {
        if (isArray(internalName)) {
            return internalName;
        }
        return "L" + internalName + ";";
    }

    /**
     * Tipo do elemento de um array.
     * "[Ljava/lang/String;" -> "java/lang/String"
     * "[[I"               -> "[I"
     * "[I"                -> "I" (descritor primitivo)
     */
    private static String elementType(String arrayDescriptor) {
        String rest = arrayDescriptor.substring(1);
        if (rest.startsWith("L") && rest.endsWith(";")) {
            return rest.substring(1, rest.length() - 1);
        }
        return rest;
    }

    private static boolean isPrimitiveDescriptor(String s) {
        return s.length() == 1 && "VZBCSIJFD".indexOf(s.charAt(0)) >= 0;
    }

    private ClassInfo readInfo(byte[] bytes) {
        final String[] name = new String[1];
        final String[] superName = new String[1];
        final List<String> interfaces = new ArrayList<String>();
        final boolean[] isInterface = new boolean[1];
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String n, String signature,
                              String sn, String[] itfs) {
                name[0] = n;
                superName[0] = sn;
                isInterface[0] = (access & Opcodes.ACC_INTERFACE) != 0;
                if (itfs != null) {
                    interfaces.addAll(Arrays.asList(itfs));
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassInfo(name[0], superName[0], interfaces, isInterface[0]);
    }

    private byte[] readFromSources(String internalName) {
        String entry = internalName + ".class";
        for (ZipFile z : jars) {
            ZipEntry e = z.getEntry(entry);
            if (e != null) {
                try {
                    return readAll(z.getInputStream(e));
                } catch (IOException ex) {
                    throw new UncheckedIOException("falha ao ler " + entry + " de " + z.getName(), ex);
                }
            }
        }
        for (File d : dirs) {
            File f = new File(d, entry.replace('/', File.separatorChar));
            if (f.isFile()) {
                try {
                    return readAll(new FileInputStream(f));
                } catch (IOException ex) {
                    throw new UncheckedIOException("falha ao ler " + f, ex);
                }
            }
        }
        InputStream is = ClassLoader.getSystemResourceAsStream(entry);
        if (is != null) {
            try {
                return readAll(is);
            } catch (IOException ex) {
                throw new UncheckedIOException("falha ao ler " + entry + " do ClassLoader", ex);
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            is.close();
        }
    }
}
