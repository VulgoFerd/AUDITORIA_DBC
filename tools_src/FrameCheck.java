import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reproduz localmente o erro de frames do Mixin (Frame.merge -> NegativeArraySizeException)
 * e isola o metodo culpado dentro de uma classe.
 *
 * Uso: java FrameCheck <jar> <classe-interna>   (ex.: JinRyuu/JRMCore/JRMCoreH.class)
 */
public class FrameCheck {

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        String cls = args.length > 1 ? args[1] : "JinRyuu/JRMCore/JRMCoreH.class";
        byte[] data = readEntry(jar, cls);
        if (data == null) throw new IllegalStateException("entrada nao encontrada: " + cls);

        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);
        System.out.println("classe: " + cn.name + " | metodos: " + cn.methods.size());

        int bad = 0;
        for (MethodNode m : cn.methods) {
            if (m.instructions.size() == 0) continue;
            String err = tryCompute(cn, m);
            if (err != null) {
                bad++;
                System.out.println("FALHA " + m.name + m.desc + " -> " + err);
            }
        }
        System.out.println("metodos com frames invalidos: " + bad);
    }

    /** Escreve a classe contendo apenas o metodo dado, forcando COMPUTE_FRAMES. */
    static String tryCompute(ClassNode original, MethodNode method) {
        ClassNode solo = new ClassNode();
        solo.version = original.version;
        solo.access = original.access;
        solo.name = original.name;
        solo.superName = original.superName;
        solo.interfaces = original.interfaces;
        solo.methods.add(method);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        try {
            solo.accept(cw);
            cw.toByteArray();
            return null;
        } catch (Throwable t) {
            if (System.getenv("FRAMECHECK_TRACE") != null) {
                t.printStackTrace();
            }
            return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
    }

    static byte[] readEntry(String jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(new File(jar))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().equals(name)) continue;
                InputStream is = zip.getInputStream(entry);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                return bos.toByteArray();
            }
        }
        return null;
    }
}
