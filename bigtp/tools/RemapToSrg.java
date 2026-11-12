import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Remapeia um JAR de Minecraft 1.7.10 (nomes MCP, com patches do Forge) para os
 * nomes de runtime (membros SRG), usando um JAR SRG de referencia como oraculo
 * para desambiguar nomes MCP repetidos.
 *
 * Usado APENAS como classpath de verificacao. Nao faz parte do produto.
 *
 * uso: RemapToSrg <mc-mcp-forge.jar> <mc-srg-oracle.jar> <conf-dir> <out.jar>
 */
public final class RemapToSrg {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("uso: RemapToSrg <mc-mcp.jar> <mc-srg-oracle.jar> <conf-dir> <out.jar>");
            System.exit(2);
        }
        File mcpJar = new File(args[0]);
        File oracleJar = new File(args[1]);
        File confDir = new File(args[2]);
        File outJar = new File(args[3]);

        Map<String, List<String>> methodCsv = loadCsv(new File(confDir, "methods.csv"));
        Map<String, List<String>> fieldCsv = loadCsv(new File(confDir, "fields.csv"));
        System.out.println("csv: methods=" + methodCsv.size() + " fields=" + fieldCsv.size());

        Map<String, ClassNode> mcp = readClasses(mcpJar);
        Map<String, ClassNode> oracle = readClasses(oracleJar);
        System.out.println("classes: mcp=" + mcp.size() + " oracle=" + oracle.size());

        final Map<String, String> fieldMap = new HashMap<>();
        final Map<String, String> methodMap = new HashMap<>();
        int ambiguousFields = 0;
        int ambiguousMethods = 0;
        int mappedFields = 0;
        int mappedMethods = 0;

        for (Map.Entry<String, ClassNode> e : mcp.entrySet()) {
            String owner = e.getKey();
            ClassNode o = oracle.get(owner);
            Set<String> oFields = new HashSet<>();
            Set<String> oMethods = new HashSet<>();
            if (o != null) {
                for (FieldNode f : o.fields) {
                    oFields.add(f.name + ":" + f.desc);
                }
                for (MethodNode m : o.methods) {
                    oMethods.add(m.name + m.desc);
                }
            }
            for (FieldNode f : e.getValue().fields) {
                String srg = pick(fieldCsv, oFields, f.name, ":" + f.desc);
                if (srg == null) {
                    ambiguousFields++;
                } else if (!srg.equals(f.name)) {
                    fieldMap.put(owner + "/" + f.name, srg);
                    mappedFields++;
                }
            }
            for (MethodNode m : e.getValue().methods) {
                String srg = pick(methodCsv, oMethods, m.name, m.desc);
                if (srg == null) {
                    ambiguousMethods++;
                } else if (!srg.equals(m.name)) {
                    methodMap.put(owner + "/" + m.name + m.desc, srg);
                    mappedMethods++;
                }
            }
        }
        System.out.println("remapeados: fields=" + mappedFields + " methods=" + mappedMethods);
        System.out.println("ambiguos (mantidos como estao): fields=" + ambiguousFields
                + " methods=" + ambiguousMethods);

        Remapper remapper = new Remapper() {
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
        try (ZipFile zip = new ZipFile(mcpJar);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outJar))) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry ze = en.nextElement();
                if (!ze.isDirectory()) {
                    entries.add(ze);
                }
            }
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry ze : entries) {
                byte[] data;
                try (InputStream is = zip.getInputStream(ze)) {
                    data = readAll(is);
                }
                String name = ze.getName();
                if (name.endsWith(".class")) {
                    ClassWriter cw = new ClassWriter(0);
                    new ClassReader(data).accept(new ClassRemapper(cw, remapper), 0);
                    data = cw.toByteArray();
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
        System.out.println("classes=" + classes + " recursos=" + kept);
        System.out.println("escrito: " + outJar.getAbsolutePath());
    }

    /**
     * Escolhe o nome SRG para um membro MCP.
     * Candidatos vem do CSV (nome MCP -> nomes SRG possiveis); o oraculo filtra
     * por proprietario e descriptor. Sem candidato unico, o membro permanece
     * como esta (caso correto para membros adicionados pelo Forge, que nao sao
     * obfuscados).
     */
    private static String pick(Map<String, List<String>> csv, Set<String> oracleMembers,
                               String name, String descSuffix) {
        List<String> candidates = csv.get(name);
        if (candidates == null) {
            return name;
        }
        for (String candidate : candidates) {
            if (oracleMembers.contains(candidate + descSuffix)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, ClassNode> readClasses(File jar) throws IOException {
        Map<String, ClassNode> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (en.isDirectory() || !en.getName().endsWith(".class")) {
                    continue;
                }
                byte[] raw;
                try (InputStream is = zip.getInputStream(en)) {
                    raw = readAll(is);
                }
                ClassNode cn = new ClassNode();
                new ClassReader(raw).accept(cn, 0);
                out.put(cn.name, cn);
            }
        }
        return out;
    }

    private static Map<String, List<String>> loadCsv(File file) throws IOException {
        Map<String, List<String>> map = new HashMap<>();
        boolean first = true;
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (first) {
                first = false;
                continue;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 2) {
                continue;
            }
            String searge = parts[0].trim();
            String name = parts[1].trim();
            if (searge.isEmpty() || name.isEmpty() || searge.equals(name)) {
                continue;
            }
            map.computeIfAbsent(name, k -> new ArrayList<>()).add(searge);
        }
        return map;
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
