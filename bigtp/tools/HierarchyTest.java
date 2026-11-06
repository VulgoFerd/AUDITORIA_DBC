import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/**
 * Testes do HierarchyResolver e do HierarchyClassWriter.
 * Sem dependencias externas; executa com java -cp build/tools HierarchyTest.
 */
public final class HierarchyTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        HierarchyResolver r = new HierarchyResolver();

        // foo/A <- foo/B <- foo/C ; foo/D extends foo/A ; foo/E sem relacao
        r.register(clazz("foo/A", "java/lang/Object", null, false));
        r.register(clazz("foo/B", "foo/A", null, false));
        r.register(clazz("foo/C", "foo/B", null, false));
        r.register(clazz("foo/D", "foo/A", null, false));
        r.register(clazz("foo/E", "java/lang/Object", null, false));
        r.register(clazz("foo/I", "java/lang/Object", null, true));
        r.register(clazz("foo/J", "java/lang/Object", new String[]{"foo/I"}, true));
        r.register(clazz("foo/K", "foo/A", new String[]{"foo/I"}, false));
        r.register(clazz("foo/Abstract", "foo/A", null, false));

        eq("C extends B", "foo/B", r.superClassOf("foo/C"));
        eq("B extends A", "foo/A", r.superClassOf("foo/B"));
        eq("A extends Object", "java/lang/Object", r.superClassOf("foo/A"));
        eq("interface super e Object", "java/lang/Object", r.superClassOf("foo/I"));
        eq("abstract continua classe", false, r.isInterface("foo/Abstract"));
        eq("I e interface", true, r.isInterface("foo/I"));

        eq("common(C,B)", "foo/B", r.commonSuperClass("foo/C", "foo/B"));
        eq("common(B,C)", "foo/B", r.commonSuperClass("foo/B", "foo/C"));
        eq("common(C,D)", "foo/A", r.commonSuperClass("foo/C", "foo/D"));
        eq("common(E,A)", "java/lang/Object", r.commonSuperClass("foo/E", "foo/A"));
        eq("common(I,K)", "foo/I", r.commonSuperClass("foo/I", "foo/K"));
        eq("common(K,I)", "foo/I", r.commonSuperClass("foo/K", "foo/I"));
        eq("common(K,E)", "java/lang/Object", r.commonSuperClass("foo/K", "foo/E"));
        eq("common(I,E)", "java/lang/Object", r.commonSuperClass("foo/I", "foo/E"));

        eq("assignable A<-C", true, r.isAssignableFrom("foo/A", "foo/C"));
        eq("assignable C<-A", false, r.isAssignableFrom("foo/C", "foo/A"));
        eq("assignable I<-K", true, r.isAssignableFrom("foo/I", "foo/K"));
        eq("assignable I<-J", true, r.isAssignableFrom("foo/I", "foo/J"));
        eq("assignable J<-K", false, r.isAssignableFrom("foo/J", "foo/K"));
        eq("assignable Object<-anything", true, r.isAssignableFrom("java/lang/Object", "foo/C"));

        // Arrays
        eq("array super e Object", "java/lang/Object", r.superClassOf("[Lfoo/C;"));
        eq("array interfaces", true, r.interfacesOf("[I").contains("java/lang/Cloneable"));
        eq("assignable Object<-[I", true, r.isAssignableFrom("java/lang/Object", "[I"));
        eq("assignable [A<-[C", true, r.isAssignableFrom("[Lfoo/A;", "[Lfoo/C;"));
        eq("assignable [C<-[A", false, r.isAssignableFrom("[Lfoo/C;", "[Lfoo/A;"));
        eq("common([C,[D)", "[Lfoo/A;", r.commonSuperClass("[Lfoo/C;", "[Lfoo/D;"));
        eq("common([I,[J)", "java/lang/Object", r.commonSuperClass("[I", "[J"));
        eq("common([C,Object)", "java/lang/Object", r.commonSuperClass("[Lfoo/C;", "java/lang/Object"));

        // Classe ausente deve abortar
        expectMissing("classe ausente", r, "foo/DoesNotExist");
        expectMissing("classe ausente em common", r, "foo/DoesNotExist", "foo/A");

        // Writer: hierarquia real no calculo de quadro
        r.register(mergeClass("foo/Merge", "foo/C", "foo/D"));
        byte[] merged = mergeClassBytes(r, "foo/Merge", "foo/C", "foo/D");
        eq("frame usa foo/A (nao Object)", true, framesContain(merged, "foo/A"));

        // Writer: classe ausente aborta em vez de virar Object
        boolean aborted = false;
        try {
            mergeClassBytes(r, "foo/MissingMerge", "foo/B", "foo/DoesNotExist");
        } catch (HierarchyResolver.MissingClassException expected) {
            aborted = true;
        }
        truth("writer aborta sem classe", aborted);

        System.out.println();
        System.out.println("passou: " + passed + " | falhou: " + failed);
        if (failed > 0) {
            System.out.println("RESULTADO: FALHOU");
            System.exit(1);
        }
        System.out.println("RESULTADO: OK");
    }

    // ------------------------------------------------------------ fixtures

    private static byte[] clazz(String name, String superName, String[] itfs, boolean isInterface) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0),
                name, null, "java/lang/Object".equals(superName) && isInterface ? "java/lang/Object" : superName,
                itfs);
        if (!isInterface) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Metodo estatico que retorna uma instancia de t1 ou t2 conforme a flag. */
    private static byte[] mergeClass(String name, String t1, String t2) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pick", "(Z)Lfoo/A;", null, null);
        mv.visitCode();
        Label elseLabel = new Label();
        Label end = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        mv.visitTypeInsn(Opcodes.NEW, t1);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, t1, "<init>", "()V", false);
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(elseLabel);
        mv.visitTypeInsn(Opcodes.NEW, t2);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, t2, "<init>", "()V", false);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] mergeClassBytes(HierarchyResolver r, String className, String t1, String t2) {
        // Le a classe montada com COMPUTE_MAXS e reescreve com COMPUTE_FRAMES.
        byte[] bare = mergeClass(className, t1, t2);
        if (!className.equals("foo/Merge") && !r.exists(className)) {
            r.register(bare);
        }
        ClassNode cn = new ClassNode();
        new ClassReader(bare).accept(cn, 0);
        HierarchyClassWriter cw = new HierarchyClassWriter(r);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean framesContain(byte[] bytes, String type) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (!(insn instanceof FrameNode)) continue;
                FrameNode fn = (FrameNode) insn;
                if (contains(fn.local, type) || contains(fn.stack, type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(List<Object> list, String type) {
        if (list == null) return false;
        for (Object o : list) {
            if (type.equals(o)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ asserts

    private static void eq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            System.out.println("  OK   " + name + " = " + actual);
        } else {
            fail(name, "esperado " + expected + ", obtido " + actual);
        }
    }

    private static void truth(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  OK   " + name);
        } else {
            fail(name, "esperado true");
        }
    }

    private static void expectMissing(String name, HierarchyResolver r, String a) {
        expectMissing(name, r, a, "foo/A");
    }

    private static void expectMissing(String name, HierarchyResolver r, String a, String b) {
        boolean thrown = false;
        try {
            r.commonSuperClass(a, b);
        } catch (HierarchyResolver.MissingClassException e) {
            thrown = true;
        }
        truth(name, thrown);
    }

    private static void fail(String name, String detail) {
        failed++;
        System.out.println("  FAIL " + name + " (" + detail + ")");
    }
}
