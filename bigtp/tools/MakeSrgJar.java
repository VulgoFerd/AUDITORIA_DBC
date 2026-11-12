import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Gera um JAR de referencia com nomes de runtime (MCP para classes, SRG para
 * membros) aplicando o packaged.srg do Forge 1.7.10 ao JAR vanilla.
 *
 * Usado APENAS como classpath de verificacao: o runtime 1.7.10 expoe classes
 * com nomes MCP e membros SRG. Nao faz parte do produto.
 *
 * uso: MakeSrgJar <vanilla-1.7.10.jar> <packaged.srg> <out.jar>
 */
public final class MakeSrgJar {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("uso: MakeSrgJar <vanilla.jar> <packaged.srg> <out.jar>");
            System.exit(2);
        }
        File inJar = new File(args[0]);
        File srgFile = new File(args[1]);
        File outJar = new File(args[2]);

        final Map<String, String> classMap = new HashMap<>();
        final Map<String, String> fieldMap = new HashMap<>();
        final Map<String, String> methodMap = new HashMap<>();

        for (String line : Files.readAllLines(srgFile.toPath(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("CL: ")) {
                String[] p = line.substring(4).split(" ");
                if (p.length >= 2) {
                    classMap.put(p[0], p[1]);
                }
            } else if (line.startsWith("FD: ")) {
                String[] p = line.substring(4).split(" ");
                if (p.length >= 2) {
                    fieldMap.put(p[0], leaf(p[1]));
                }
            } else if (line.startsWith("MD: ")) {
                String[] p = line.substring(4).split(" ");
                if (p.length >= 3) {
                    methodMap.put(p[0] + p[1], leaf(p[2]));
                }
            }
        }
        System.out.println("CL=" + classMap.size() + " FD=" + fieldMap.size() + " MD=" + methodMap.size());

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                String mapped = classMap.get(internalName);
                return mapped != null ? mapped : internalName;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String mapped = fieldMap.get(owner + "/" + name);
                return mapped != null ? mapped : name;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String mapped = methodMap.get(owner + "/" + name + descriptor);
                return mapped != null ? mapped : name;
            }
        };

        int classes = 0;
        int kept = 0;
        int unresolved = 0;
        try (ZipFile zip = new ZipFile(inJar);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outJar))) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.isDirectory()) {
                    entries.add(en);
                }
            }
            entries.sort(Comparator.comparing(ZipEntry::getName));

            for (ZipEntry en : entries) {
                byte[] data;
                try (InputStream is = zip.getInputStream(en)) {
                    data = readAll(is);
                }
                String name = en.getName();
                if (name.endsWith(".class")) {
                    ClassReader cr = new ClassReader(data);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, remapper), 0);
                    data = cw.toByteArray();
                    // o JAR vanilla guarda classes notch na raiz (baj.class); o nome
                    // da entrada precisa seguir o nome remapeado da classe
                    name = remapper.map(cr.getClassName()) + ".class";
                    classes++;
                } else {
                    kept++;
                }
                ZipEntry out = new ZipEntry(name);
                out.setTime(0L);
                zos.putNextEntry(out);
                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.println("classes remapeadas: " + classes + ", recursos copiados: " + kept
                + ", classes sem CL: " + unresolved);
        System.out.println("escrito: " + outJar.getAbsolutePath());
    }

    private static String leaf(String qualified) {
        int i = qualified.lastIndexOf('/');
        return i < 0 ? qualified : qualified.substring(i + 1);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
