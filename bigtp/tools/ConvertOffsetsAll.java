import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

public class ConvertOffsetsAll {
    // Map of className -> methodName+desc -> List of (insnIndex, bytecodeOffset)
    static Map<String, Map<String, List<int[]>>> offsetMaps = new HashMap<>();

    public static void main(String[] args) throws Exception {
        // Process all three CSVs
        processCsv("bigtp/analysis/sites-classified.csv", "JRMCore-v1.3.51-bigtp.jar", "bigtp/analysis/sites-classified.csv");
        processCsv("bigtp/analysis/sites-dbc-classified.csv", "DragonBlockC-v1.4.85.jar", "bigtp/analysis/sites-dbc-classified.csv");
        processCsv("bigtp/analysis/sites-npcdbc-classified.csv", "npcdbc-1.2.1-hotfix1.jar", "bigtp/analysis/sites-npcdbc-classified.csv");
    }

    private static void processCsv(String csvPath, String jarPath, String outPath) throws Exception {
        File csvFile = new File(csvPath);
        File jarFile = new File("C:/Users/Fernando/Desktop/NanatsuCore/AUDITORIA_DBC/" + jarPath);
        File outFile = new File(outPath);

        // Build offset maps for this JAR
        offsetMaps.clear();
        buildOffsetMaps(jarFile);

        // Read CSV and convert offsets
        List<String> lines = Files.readAllLines(csvFile.toPath());
        List<String> outLines = new ArrayList<>();
        outLines.add(lines.get(0)); // header

        int converted = 0;
        int failed = 0;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] cols = parseCsvLine(line);
            if (cols.length < 11) {
                outLines.add(line);
                continue;
            }

            String clazz = cols[0];
            String method = cols[1];
            String desc = cols[2];
            int bytecodeOffset = Integer.parseInt(cols[3]);

            String methodKey = method + desc;
            Map<String, List<int[]>> classMap = offsetMaps.get(clazz);
            if (classMap == null) {
                System.err.println("Class not found in offset map: " + clazz);
                outLines.add(line);
                failed++;
                continue;
            }

            List<int[]> offsets = classMap.get(methodKey);
            if (offsets == null) {
                System.err.println("Method not found in offset map: " + clazz + "." + methodKey);
                outLines.add(line);
                failed++;
                continue;
            }

            // Find the instruction index for this bytecode offset
            int insnIndex = findInsnIndex(offsets, bytecodeOffset);
            if (insnIndex >= 0) {
                cols[3] = String.valueOf(insnIndex);
                System.out.println("Converted " + clazz + "." + method + "@" + bytecodeOffset + " -> insn index " + insnIndex);
                converted++;
            } else {
                System.err.println("Could not find exact offset " + bytecodeOffset + " for " + clazz + "." + methodKey);
                // Try to find closest
                int closest = findClosestInsnIndex(offsets, bytecodeOffset);
                if (closest >= 0) {
                    cols[3] = String.valueOf(closest);
                    System.out.println("  Using closest: insn index " + closest);
                    converted++;
                } else {
                    outLines.add(line);
                    failed++;
                    continue;
                }
            }

            outLines.add(joinCsvLine(cols));
        }

        Files.write(outFile.toPath(), outLines);
        System.out.println(jarPath + ": Converted: " + converted + ", Failed: " + failed);
        System.out.println("Written to " + outFile.getAbsolutePath());
    }

    private static void buildOffsetMaps(File jarFile) throws IOException {
        JarFile jar = new JarFile(jarFile);
        for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
            JarEntry entry = e.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                InputStream is = jar.getInputStream(entry);
                ClassReader cr = new ClassReader(is);
                OffsetCalculator calc = new OffsetCalculator();
                cr.accept(calc, 0);
                offsetMaps.put(calc.className, calc.methodOffsets);
                is.close();
            }
        }
        jar.close();
    }

    private static int findInsnIndex(List<int[]> offsets, int targetBytecodeOffset) {
        for (int[] pair : offsets) {
            if (pair[1] == targetBytecodeOffset) {
                return pair[0];
            }
        }
        return -1;
    }

    private static int findClosestInsnIndex(List<int[]> offsets, int targetBytecodeOffset) {
        int bestIdx = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int[] pair : offsets) {
            int diff = Math.abs(pair[1] - targetBytecodeOffset);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = pair[0];
            }
        }
        return bestDiff <= 5 ? bestIdx : -1; // Only accept if within 5 bytes
    }

    private static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }

    private static String joinCsvLine(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(',');
            String col = cols[i];
            if (col.contains(",") || col.contains("\"") || col.contains("\n")) {
                sb.append('"');
                sb.append(col.replace("\"", "\"\""));
                sb.append('"');
            } else {
                sb.append(col);
            }
        }
        return sb.toString();
    }

    private static class OffsetCalculator extends ClassVisitor {
        String className;
        Map<String, List<int[]>> methodOffsets = new HashMap<>();

        public OffsetCalculator() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            return new OffsetMethodVisitor(name, desc);
        }

        class OffsetMethodVisitor extends MethodVisitor {
            String methodKey;
            List<int[]> offsets = new ArrayList<>();
            int insnIndex = 0;
            int bytecodeOffset = 0;

            public OffsetMethodVisitor(String name, String desc) {
                super(Opcodes.ASM9);
                this.methodKey = name + desc;
            }

            @Override
            public void visitCode() {
                offsets.clear();
                insnIndex = 0;
                bytecodeOffset = 0;
            }

            @Override
            public void visitInsn(int opcode) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += 1;
                insnIndex++;
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += (opcode == Opcodes.BIPUSH) ? 2 : 3;
                insnIndex++;
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                boolean shortForm = var < 4 &&
                    (opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.ALOAD ||
                     opcode == Opcodes.ISTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE || opcode == Opcodes.DSTORE || opcode == Opcodes.ASTORE);
                bytecodeOffset += shortForm ? 1 : 2;
                insnIndex++;
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += 3;
                insnIndex++;
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += 3;
                insnIndex++;
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += 3;
                insnIndex++;
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object[] bsmArgs) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += 5;
                insnIndex++;
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += 3;
                insnIndex++;
            }

            @Override
            public void visitLabel(Label label) {
                // Labels don't consume bytecode
            }

            @Override
            public void visitLdcInsn(Object value) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                int size = 3;
                if (value instanceof Long || value instanceof Double) size = 3;
                bytecodeOffset += size;
                insnIndex++;
            }

            @Override
            public void visitIincInsn(int var, int increment) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                boolean shortForm = var < 256 && increment >= -128 && increment <= 127;
                bytecodeOffset += shortForm ? 3 : 6;
                insnIndex++;
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                int size = 3 + 4 * 3 + (max - min + 1) * 4; // approximate
                bytecodeOffset += size;
                insnIndex++;
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                int size = 3 + 4 + keys.length * 8; // approximate
                bytecodeOffset += size;
                insnIndex++;
            }

            @Override
            public void visitMultiANewArrayInsn(String desc, int dims) {
                offsets.add(new int[]{insnIndex, bytecodeOffset});
                bytecodeOffset += 4;
                insnIndex++;
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
            }

            @Override
            public void visitEnd() {
                methodOffsets.put(methodKey, offsets);
            }
        }
    }
}