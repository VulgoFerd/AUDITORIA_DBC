import org.objectweb.asm.ClassWriter;

/**
 * Unico ClassWriter do patcher.
 *
 * Usa {@link HierarchyResolver} para calcular quadros quando possivel.
 * Se uma classe referenciada nao esta no classpath, cai para o comportamento
 * padrao do ASM (java/lang/Object) em vez de abortar. Isso permite patchar
 * mods com dependencias opcionais ausentes (ex.: NarutoC).
 */
public final class HierarchyClassWriter extends ClassWriter {

    public static final int FLAGS = COMPUTE_FRAMES | COMPUTE_MAXS;

    private final HierarchyResolver resolver;

    public HierarchyClassWriter(HierarchyResolver resolver) {
        this(FLAGS, resolver);
    }

    public HierarchyClassWriter(int flags, HierarchyResolver resolver) {
        super(flags);
        if (resolver == null) {
            throw new IllegalArgumentException("HierarchyResolver nulo");
        }
        this.resolver = resolver;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            return resolver.commonSuperClass(type1, type2);
        } catch (HierarchyResolver.MissingClassException e) {
            // Classe ausente no classpath - usar fallback padrao do ASM
            // (java/lang/Object). Isso evita abortar o build por dependencias opcionais.
            System.err.println("WARNING: Classe ausente na hierarquia para getCommonSuperClass: " 
                + e.getMessage() + ". Usando java/lang/Object como fallback.");
            return "java/lang/Object";
        }
    }
}
