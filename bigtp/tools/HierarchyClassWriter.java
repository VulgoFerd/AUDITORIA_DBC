import org.objectweb.asm.ClassWriter;

/**
 * Unico ClassWriter do patcher.
 *
 * Usa {@link HierarchyResolver} para calcular quadros. Todas as classes
 * referenciadas precisam estar disponiveis; qualquer ausencia aborta o build
 * com {@link HierarchyResolver.MissingClassException}.
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
        return resolver.commonSuperClass(type1, type2);
    }
}
