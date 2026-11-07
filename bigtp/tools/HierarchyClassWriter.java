import org.objectweb.asm.ClassWriter;

import java.io.*;
import java.util.*;

/**
 * Unico ClassWriter do patcher.
 *
 * Usa {@link HierarchyResolver} para calcular quadros quando possivel.
 * Se uma classe referenciada nao esta no classpath:
 *  - se for dependencia opcional (declarada em optional-deps.txt): loga warning e usa fallback Object
 *  - se for dependencia obrigatoria: aborta o build com diagnostico util
 */
public final class HierarchyClassWriter extends ClassWriter {

    public static final int FLAGS = COMPUTE_FRAMES | COMPUTE_MAXS;

    private final HierarchyResolver resolver;
    private final Set<String> optionalPrefixes;

    public HierarchyClassWriter(HierarchyResolver resolver) {
        this(FLAGS, resolver, null);
    }

    public HierarchyClassWriter(int flags, HierarchyResolver resolver) {
        this(flags, resolver, null);
    }

    public HierarchyClassWriter(HierarchyResolver resolver, File optionalDepsFile) {
        this(FLAGS, resolver, optionalDepsFile);
    }

    public HierarchyClassWriter(int flags, HierarchyResolver resolver, File optionalDepsFile) {
        super(flags);
        if (resolver == null) {
            throw new IllegalArgumentException("HierarchyResolver nulo");
        }
        this.resolver = resolver;
        this.optionalPrefixes = loadOptionalPrefixes(optionalDepsFile);
    }

    private static Set<String> loadOptionalPrefixes(File optionalDepsFile) {
        Set<String> prefixes = new HashSet<>();
        if (optionalDepsFile == null || !optionalDepsFile.exists()) {
            return prefixes;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(optionalDepsFile), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                prefixes.add(line.replace('.', '/'));
            }
        } catch (IOException e) {
            System.err.println("WARNING: falha ao ler optional-deps.txt: " + e.getMessage());
        }
        System.out.println("HierarchyClassWriter: dependencias opcionais carregadas: " + prefixes.size());
        return prefixes;
    }

    private boolean isOptionalClass(String internalName) {
        if (internalName == null) return false;
        String n = internalName.replace('.', '/');
        for (String prefix : optionalPrefixes) {
            if (n.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            return resolver.commonSuperClass(type1, type2);
        } catch (HierarchyResolver.MissingClassException e) {
            String missingClass = e.getMessage().replace("classe ausente na hierarquia: ", "").replace(" (adicione o JAR/diretorio correto ao classpath)", "");
            // Check if the missing class is optional
            if (isOptionalClass(missingClass)) {
                System.err.println("WARNING: Classe opcional ausente na hierarquia para getCommonSuperClass: "
                    + missingClass + ". Usando java/lang/Object como fallback.");
                return "java/lang/Object";
            }
            // Required class missing - fail with clear diagnostic
            throw new IllegalStateException("HIERARQUIA INCOMPLETA: classe obrigatoria ausente: " + missingClass
                + " (solicitada em getCommonSuperClass entre " + type1 + " e " + type2 + ")"
                + "\n  Adicione o JAR/diretorio contendo " + missingClass + " ao classpath de build."
                + "\n  Se esta e uma dependencia opcional, declare seu prefixo em analysis/optional-deps.txt");
        }
    }
}
