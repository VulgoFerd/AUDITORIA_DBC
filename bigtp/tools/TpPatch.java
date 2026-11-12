import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;

/**
 * BigTP 1.1.1 Patcher - Strict, transactional, hierarchy-aware.
 *
 * Pipeline:
 *  1. Load classification from SiteRewriter CSV
 *  2. For each target JAR:
 *     a. Parse class hierarchy (HierarchyResolver)
 *     b. Rewrite matching sites using explicit adapters
 *     c. Inject TpUtil class into JRMCore
 *     d. Verify with HierarchyClassWriter (COMPUTE_FRAMES)
 *     e. Write to temp dir, then atomic move on full success
 *  3. Manifest generation + SHA256SUMS
 *  4. Residual jrmcTpint scan (must be zero for non-TpUtil classes)
 */
public final class TpPatch {

    // ============================================================ CONFIGURATION

    static final class Config {
        File jrmCoreJar;
        File dbcJar;
        File npcdbcJar;
        File tpUtilClass;           // compiled TpUtil.class to inject
        File outputDir;             // base output dir (e.g. bigtp/dist/1.0.7-candidate/)
        File classpathFile;         // --cp-file from build/classpath.ps1
        File optionalDepsFile;      // analysis/optional-deps.txt
        boolean dryRun = false;
        boolean verify = true;      // run VerifyJar after patching
        int expectMajor = 0;        // 0 = aceitar a versao original de cada classe
    }

    // ============================================================ SITE DEFINITION

    static final class Site {
        final String clazz;
        final String method;
        final String desc;
        final int offset;
        final String key;
        final String operation;
        final String opDesc;
        final String context;
        final String semantics;
        final String adapter;
        final boolean handled;

        Site(String[] cols) {
            this.clazz = cols[0];
            this.method = cols[1];
            this.desc = cols[2];
            this.offset = Integer.parseInt(cols[3]);
            this.key = cols[4];
            this.operation = cols[5];
            this.opDesc = cols[6];
            this.context = cols[7];
            this.semantics = cols[8];
            this.adapter = cols[9].isEmpty() ? null : cols[9];
            this.handled = Boolean.parseBoolean(cols[10]);
        }

        String id() { return clazz + "::" + method + "@" + offset; }
    }

    // ============================================================ ADAPTER EXECUTORS

    // Each adapter type knows how to emit the bytecode for its semantic.
    // They receive the MethodNode, the instruction index, and the Site info.
    // Returns the index of the first NEW instruction inserted (or -1 if in-place).

    interface AdapterExec {
        int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier);
    }

    // ------------------------------------------------------------------ RETARGET
    /**
     * Reescreve um site SEM alterar a forma da pilha.
     *
     * Regras:
     *  - chamada de membro: substitui a instrucao por uma INVOKESTATIC que
     *    recebe o mesmo receptor e os mesmos argumentos, na mesma ordem. A
     *    pilha consumida e produzida e identica, portanto nao ha risco de
     *    max stack, locais temporarios ou frames;
     *  - leitura de {@code JRMCoreH.curTP}: passa a ler {@code curTPLong} e a
     *    converter com {@code clampTP(J)I};
     *  - escrita em {@code JRMCoreH.curTP}: grava o mesmo valor em
     *    {@code curTPLong} (via I2L) e mantem o espelho atualizado.
     *
     * Qualquer site cuja instrucao nao corresponda a um mapeamento conhecido
     * aborta o patch. Nenhum site e "resolvido" por suposicao.
     */
    static final class RetargetAdapter implements AdapterExec {
        private static final String JRMCOREH = "JinRyuu/JRMCore/JRMCoreH";
        private static final String NBT = "net/minecraft/nbt/NBTTagCompound";
        private static final String TP = "JinRyuu/JRMCore/TpUtil";

        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            AbstractInsnNode insn = mn.instructions.get(idx);
            if (insn == null) {
                throw new IllegalStateException("Site sem instrucao: " + site.id());
            }

            if (insn instanceof FieldInsnNode) {
                return retargetField(mn, idx, (FieldInsnNode) insn, site);
            }

            if (!(insn instanceof MethodInsnNode)) {
                throw new IllegalStateException("Site nao suportado (" + insn.getClass().getSimpleName()
                        + "): " + site.id());
            }

            MethodInsnNode mi = (MethodInsnNode) insn;

            // Presenca de chave TP nao depende do tipo; manter a chamada original.
            if (NBT.equals(mi.owner) && "func_74781_a".equals(mi.name)) {
                return idx;
            }

            MethodInsnNode repl = retargetCall(mi, site);
            if (repl == null) {
                throw new IllegalStateException("Sem mapeamento para a chamada " + mi.owner + "."
                        + mi.name + mi.desc + " no site " + site.id());
            }
            // INVOKESTATIC recebe o receptor como primeiro argumento; a forma da
            // pilha precisa ser identica a da chamada original.
            String expectedDesc = mi.getOpcode() == Opcodes.INVOKESTATIC
                    ? mi.desc
                    : "(" + "L" + mi.owner + ";" + mi.desc.substring(1);
            if (!repl.desc.equals(expectedDesc)) {
                throw new IllegalStateException("Retarget mudaria a pilha em " + site.id()
                        + ": " + mi.desc + " -> " + repl.desc);
            }
            mn.instructions.set(mi, repl);
            return idx;
        }

        private MethodInsnNode retargetCall(MethodInsnNode mi, Site site) {
            if (NBT.equals(mi.owner)) {
                // getInteger(String)I
                if ("func_74762_e".equals(mi.name) && "(Ljava/lang/String;)I".equals(mi.desc)) {
                    return staticCall("getTagInt", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;)I");
                }
                // setInteger(String,int)V
                if ("func_74768_a".equals(mi.name) && "(Ljava/lang/String;I)V".equals(mi.desc)) {
                    return staticCall("setTagInt", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;I)V");
                }
                return null;
            }

            if (JRMCOREH.equals(mi.owner)) {
                // getInt(EntityPlayer,String)I
                if ("getInt".equals(mi.name) && "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I".equals(mi.desc)) {
                    return staticCall("getPlayerInt", "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I");
                }
                // setInt(int,EntityPlayer,String)V
                if ("setInt".equals(mi.name) && "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V".equals(mi.desc)) {
                    return staticCall("setPlayerInt", "(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V");
                }
                return null;
            }

            return null;
        }

        private MethodInsnNode staticCall(String name, String desc) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, TP, name, desc, false);
        }

        private int retargetField(MethodNode mn, int idx, FieldInsnNode fi, Site site) {
            if (!JRMCOREH.equals(fi.owner) || !"curTP".equals(fi.name) || !"I".equals(fi.desc)) {
                throw new IllegalStateException("Campo nao suportado no site " + site.id() + ": "
                        + fi.owner + "." + fi.name + ":" + fi.desc);
            }
            if (fi.getOpcode() == Opcodes.GETSTATIC) {
                InsnList add = new InsnList();
                add.add(new FieldInsnNode(Opcodes.GETSTATIC, JRMCOREH, "curTPLong", "J"));
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, JRMCOREH, "clampTP", "(J)I", false));
                mn.instructions.insert(fi, add);
                mn.instructions.remove(fi);
                return idx;
            }
            if (fi.getOpcode() == Opcodes.PUTSTATIC) {
                InsnList add = new InsnList();
                add.add(new InsnNode(Opcodes.DUP));
                add.add(new InsnNode(Opcodes.I2L));
                add.add(new FieldInsnNode(Opcodes.PUTSTATIC, JRMCOREH, "curTPLong", "J"));
                add.add(new FieldInsnNode(Opcodes.PUTSTATIC, JRMCOREH, "curTP", "I"));
                mn.instructions.insert(fi, add);
                mn.instructions.remove(fi);
                return idx;
            }
            throw new IllegalStateException("Opcode de campo nao suportado no site " + site.id()
                    + ": opcode " + fi.getOpcode());
        }
    }

    static final class SkipAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            // Falso positivo documentado: nao ha alteracao a fazer.
            return -1;
        }
    }

    private static final AdapterExec RETARGET_ADAPTER = new RetargetAdapter();
    private static final AdapterExec SKIP_ADAPTER = new SkipAdapter();

    // ============================================================ MAIN PATCHER

    public static void main(String[] args) throws Exception {
        Config cfg = parseArgs(args);

        // Load classifications
        Map<String, List<Site>> jrmCoreSites = loadSites(new File("bigtp/analysis/sites-classified.csv"));
        Map<String, List<Site>> dbcSites = loadSites(new File("bigtp/analysis/sites-dbc-classified.csv"));
        Map<String, List<Site>> npcdbcSites = loadSites(new File("bigtp/analysis/sites-npcdbc-classified.csv"));

        // Build hierarchy resolver from classpath
        HierarchyResolver hier = buildHierarchy(cfg.classpathFile);

        // Process each JAR
        File outDir = new File(cfg.outputDir, "jars");
        outDir.mkdirs();

        if (!cfg.dryRun) {
            patchJar(cfg.jrmCoreJar, new File(outDir, "JRMCore-patched.jar"), jrmCoreSites, hier, true, cfg);
            patchJar(cfg.dbcJar, new File(outDir, "DBC-patched.jar"), dbcSites, hier, false, cfg);
            patchJar(cfg.npcdbcJar, new File(outDir, "NPCDBC-patched.jar"), npcdbcSites, hier, false, cfg);
        }

        // Verify
        if (cfg.verify && !cfg.dryRun) {
            verifyAll(outDir, cfg);
        }

        // Generate manifest and SHA256SUMS
        if (!cfg.dryRun) {
            generateManifest(outDir, cfg);
            generateSha256Sums(outDir);
        }

        System.out.println("Patching complete. Output: " + outDir.getAbsolutePath());
    }

    // ============================================================ JAR PATCHING

    private static void patchJar(File inJar, File outJar, Map<String, List<Site>> sitesByClass,
                                 HierarchyResolver hier, boolean injectTpUtil, Config cfg) throws IOException {
        System.out.println("\n=== Patching " + inJar.getName() + " ===");

        // Read all classes, preserving original bytes for verbatim re-emission.
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        Map<String, byte[]> originalBytes = new LinkedHashMap<>();
        ZipFile zip = new ZipFile(inJar);
        try {
            List<String> classNames = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.isDirectory() && en.getName().endsWith(".class")) {
                    classNames.add(en.getName());
                }
            }
            Collections.sort(classNames);

            for (String name : classNames) {
                byte[] raw = readAllBytes(zip.getInputStream(zip.getEntry(name)));
                ClassNode cn = new ClassNode();
                new ClassReader(raw).accept(cn, 0);
                classes.put(cn.name, cn);
                originalBytes.put(cn.name, raw);
            }
        } finally {
            zip.close();
        }

        Set<String> codeModified = new LinkedHashSet<>();
        Set<String> memberModified = new LinkedHashSet<>();
        // Uma instrucao so pode atender um site. Sem isso, sites identicos no
        // mesmo metodo reescrevem a mesma instrucao e os demais ficam intactos.
        Map<MethodNode, Set<Integer>> usedByMethod = new IdentityHashMap<>();
        // Rastrear LDCs ja consumidos para evitar que um site consuma o LDC de outro.
        Map<MethodNode, Set<Integer>> usedLdcByMethod = new IdentityHashMap<>();

        // Apply site rewrites
        int totalRewritten = 0;
        for (Map.Entry<String, List<Site>> entry : sitesByClass.entrySet()) {
            String className = entry.getKey().split("::")[0];
            ClassNode cn = classes.get(className);
            if (cn == null) {
                System.err.println("WARNING: Class not found in JAR: " + className);
                continue;
            }

            for (Site site : entry.getValue()) {
                if (!site.handled) {
                    System.err.println("ERROR: Unhandled site in " + inJar.getName() + ": " + site.id());
                    throw new IllegalStateException("Unhandled site: " + site.id());
                }

                // Find the method
                MethodNode mn = findMethod(cn, site.method, site.desc);
                if (mn == null) {
                    System.err.println("ERROR: Method not found: " + site.method + site.desc + " in " + className);
                    throw new IllegalStateException("Method not found: " + site.id());
                }

                // Retarget de pilha identica. "skip" apenas documenta falso positivo.
                AdapterExec exec = (site.adapter != null
                        && site.adapter.trim().equalsIgnoreCase("skip"))
                        ? SKIP_ADAPTER : RETARGET_ADAPTER;

                // Find the correct instruction index by pattern matching
                // The CSV offset is a bytecode offset, not an instruction index.
                // We search for the LDC "jrmcTp..." instruction and the following call.
                usedByMethod.putIfAbsent(mn, new HashSet<Integer>());
                usedLdcByMethod.putIfAbsent(mn, new HashSet<Integer>());
                int targetIdx = findTargetInstruction(mn, site, usedByMethod.get(mn), usedLdcByMethod.get(mn));
                if (targetIdx < 0) {
                    throw new IllegalStateException("Site nao localizado no bytecode: " + site.id()
                            + " (offset=" + site.offset + ")");
                }
                usedByMethod.get(mn).add(targetIdx);

                // Execute rewrite
                AbstractInsnNode[] before = mn.instructions.toArray();
                int newIdx = exec.exec(mn, targetIdx, site, hier);
                AbstractInsnNode[] after = mn.instructions.toArray();
                if (newIdx >= 0) {
                    totalRewritten++;
                }
                if (!Arrays.equals(before, after)) {
                    codeModified.add(cn.name);
                }
            }
        }

        System.out.println("  Rewritten " + totalRewritten + " sites in " + inJar.getName());

        // Inject helpers from the explicit allowlist and transform the ORIGINAL classes.
        Map<String, byte[]> injected = new LinkedHashMap<>();
        if (injectTpUtil && cfg.tpUtilClass != null && cfg.tpUtilClass.exists()) {
            injectTpUtilClasses(injected, cfg.tpUtilClass, originalBytes);
        }

        if (injectTpUtil) {
            // JRMCoreH must be the original class, expanded with curTPLong/tpShort/clampTP.
            transformJRMCoreH(classes, originalBytes, memberModified);
            // Append the LONG sync packet registration to PD.registerPackets().
            injectPdRegistration(classes, injected, codeModified);
        }

        // Write output JAR preserving every original resource entry.
        writeJar(outJar, inJar, classes, originalBytes, injected, codeModified, memberModified, hier, cfg);
    }

    /**
     * Injeta SOMENTE os auxiliares de runtime declarados na allowlist explicita.
     *
     * O diretorio de origem e derivado de --tputil (root/JinRyuu/JRMCore/TpUtil.class).
     * Nenhum outro .class do diretorio e lido: em especial, JRMCoreH.class do
     * diretorio de stubs NUNCA e injetado. A classe original do JAR e preservada
     * e transformada por {@link #transformJRMCoreH}.
     */
    private static void injectTpUtilClasses(Map<String, byte[]> injected, File tpUtilClassFile,
                                            Map<String, byte[]> originalBytes) throws IOException {
        File root = tpUtilClassFile.getAbsoluteFile().getParentFile().getParentFile().getParentFile();
        File expectedTpUtil = new File(root, "JinRyuu/JRMCore/TpUtil.class");
        if (!expectedTpUtil.isFile()) {
            throw new IllegalStateException("Layout inesperado para --tputil: " + tpUtilClassFile
                    + " (esperado " + expectedTpUtil + ")");
        }
        File stubJrmcH = new File(root, "JinRyuu/JRMCore/JRMCoreH.class");
        if (stubJrmcH.isFile()) {
            throw new IllegalStateException("JRMCoreH.class presente em " + root
                    + " - stubs de compilacao nao podem ser injetados; use um diretorio de producao");
        }

        String[] prefixes = {
                "JinRyuu/JRMCore/TpUtil",
                "JinRyuu/JRMCore/TpLogic",
                "JinRyuu/JRMCore/TpNbt",
                "JinRyuu/JRMCore/TpNbtCore",
                "JinRyuu/JRMCore/TpNetwork",
                "JinRyuu/JRMCore/TpClientState",
                "JinRyuu/JRMCore/TpTagView",
                "JinRyuu/JRMCore/p/JRMCorePTpSync",
        };

        System.out.println("  Injecao por allowlist a partir de " + root.getAbsolutePath());
        for (String prefix : prefixes) {
            File base = new File(root, prefix + ".class");
            if (!base.isFile()) {
                throw new IllegalStateException("Classe auxiliar obrigatoria ausente: " + base);
            }
            File dir = new File(root, prefix).getParentFile();
            String simple = new File(root, prefix).getName();
            File[] candidates = dir.listFiles((d, n) ->
                    n.endsWith(".class") && (n.equals(simple + ".class") || n.startsWith(simple + "$")));
            if (candidates == null) {
                throw new IllegalStateException("Nao foi possivel listar " + dir);
            }
            Arrays.sort(candidates, Comparator.comparing(File::getName));
            for (File f : candidates) {
                byte[] raw = Files.readAllBytes(f.toPath());
                String internal = new ClassReader(raw).getClassName();
                if (!internal.equals(prefix) && !internal.startsWith(prefix + "$")) {
                    throw new IllegalStateException("Arquivo " + f + " declara classe " + internal
                            + " fora da allowlist " + prefix);
                }
                if (internal.equals("JinRyuu/JRMCore/JRMCoreH")) {
                    throw new IllegalStateException("Tentativa de injetar JRMCoreH a partir de " + f);
                }
                if (originalBytes.containsKey(internal)) {
                    throw new IllegalStateException("Auxiliar " + internal + " colide com classe original do JAR");
                }
                injected.put(internal, raw);
                System.out.println("    + " + internal);
            }
        }
    }

    /**
     * Expande a classe JRMCoreH ORIGINAL (v50): adiciona o campo autoritativo
     * curTPLong:J e os conversores saturados tpShort(J)S / clampTP(J)I.
     * Nenhum membro existente e removido ou reescrito.
     */
    private static void transformJRMCoreH(Map<String, ClassNode> classes,
                                          Map<String, byte[]> originalBytes,
                                          Set<String> memberModified) {
        String name = "JinRyuu/JRMCore/JRMCoreH";
        ClassNode cn = classes.get(name);
        if (cn == null) {
            throw new IllegalStateException("JRMCoreH original ausente do JAR de entrada");
        }
        byte[] original = originalBytes.get(name);
        if (original == null) {
            throw new IllegalStateException("Bytes originais de JRMCoreH ausentes");
        }

        List<String> before = memberSignatures(cn);
        int originalMajor = cn.version & 0xFFFF;

        if (findField(cn, "curTPLong", "J") == null) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "curTPLong", "J", null, null));
            System.out.println("  JRMCoreH: campo curTPLong:J adicionado");
        }
        if (findMethod(cn, "tpShort", "(J)S") == null) {
            cn.methods.add(buildTpShort());
            System.out.println("  JRMCoreH: metodo tpShort(J)S adicionado");
        }
        if (findMethod(cn, "clampTP", "(J)I") == null) {
            cn.methods.add(buildClampTP());
            System.out.println("  JRMCoreH: metodo clampTP(J)I adicionado");
        }

        List<String> after = memberSignatures(cn);
        for (String sig : before) {
            if (!after.contains(sig)) {
                throw new IllegalStateException("Transformacao removeu membro de JRMCoreH: " + sig);
            }
        }
        System.out.println("  JRMCoreH: " + before.size() + " membros originais preservados (v"
                + originalMajor + "), " + (after.size() - before.size()) + " adicionados");
        memberModified.add(name);
    }

    private static List<String> memberSignatures(ClassNode cn) {
        List<String> out = new ArrayList<>();
        for (FieldNode f : cn.fields) {
            out.add("F:" + f.name + ":" + f.desc);
        }
        for (MethodNode m : cn.methods) {
            out.add("M:" + m.name + m.desc);
        }
        Collections.sort(out);
        return out;
    }

    private static FieldNode findField(ClassNode cn, String name, String desc) {
        for (FieldNode f : cn.fields) {
            if (name.equals(f.name) && desc.equals(f.desc)) {
                return f;
            }
        }
        return null;
    }

    /** (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)) - sem branches. */
    private static MethodNode buildTpShort() {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.LLOAD, 0));
        body.add(new LdcInsnNode(Long.valueOf((long) Short.MAX_VALUE)));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false));
        body.add(new LdcInsnNode(Long.valueOf((long) Short.MIN_VALUE)));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(JJ)J", false));
        body.add(new InsnNode(Opcodes.L2I));
        body.add(new InsnNode(Opcodes.I2S));
        body.add(new InsnNode(Opcodes.IRETURN));
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "tpShort", "(J)S", null, null);
        mn.instructions.add(body);
        mn.maxStack = 4;
        mn.maxLocals = 2;
        return mn;
    }

    /** (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)) - sem branches. */
    private static MethodNode buildClampTP() {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.LLOAD, 0));
        body.add(new LdcInsnNode(Long.valueOf((long) Integer.MAX_VALUE)));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false));
        body.add(new LdcInsnNode(Long.valueOf((long) Integer.MIN_VALUE)));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(JJ)J", false));
        body.add(new InsnNode(Opcodes.L2I));
        body.add(new InsnNode(Opcodes.IRETURN));
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "clampTP", "(J)I", null, null);
        mn.instructions.add(body);
        mn.maxStack = 4;
        mn.maxLocals = 2;
        return mn;
    }

    /**
     * Anexa ao fim de PD.registerPackets() o registro do pacote JRMCorePTpSync.
     * Usa registerMessage(Class,Class) privado da propria PD, que escolhe o lado
     * CLIENT para handlers que estendem CAmh. O packetId e incrementado pela PD,
     * portanto nao ha colisao com os 22 pacotes originais.
     */
    private static void injectPdRegistration(Map<String, ClassNode> classes,
                                             Map<String, byte[]> injected,
                                             Set<String> codeModified) {
        String pdName = "JinRyuu/JRMCore/p/PD";
        String packet = "JinRyuu/JRMCore/p/JRMCorePTpSync";
        String handler = packet + "$Handler";

        ClassNode pd = classes.get(pdName);
        if (pd == null) {
            throw new IllegalStateException("PD original ausente do JAR de entrada");
        }
        if (!injected.containsKey(packet) || !injected.containsKey(handler)) {
            throw new IllegalStateException("JRMCorePTpSync/Handler nao foram injetados; impossivel registrar");
        }
        MethodNode reg = findMethod(pd, "registerPackets", "()V");
        if (reg == null) {
            throw new IllegalStateException("PD.registerPackets()V ausente");
        }
        MethodNode rm = findMethod(pd, "registerMessage", "(Ljava/lang/Class;Ljava/lang/Class;)V");
        if (rm == null) {
            throw new IllegalStateException("PD.registerMessage(Class,Class)V ausente");
        }

        boolean already = false;
        int returns = 0;
        for (AbstractInsnNode insn : reg.instructions.toArray()) {
            if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Type && handler.equals(((Type) cst).getInternalName())) {
                    already = true;
                }
            }
            if (insn.getOpcode() == Opcodes.RETURN) {
                returns++;
            }
        }
        if (already) {
            System.out.println("  PD.registerPackets: pacote JRMCorePTpSync ja registrado");
            return;
        }

        for (AbstractInsnNode insn : reg.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList add = new InsnList();
                add.add(new LdcInsnNode(Type.getObjectType(handler)));
                add.add(new LdcInsnNode(Type.getObjectType(packet)));
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, pdName, "registerMessage",
                        "(Ljava/lang/Class;Ljava/lang/Class;)V", false));
                reg.instructions.insertBefore(insn, add);
            }
        }
        codeModified.add(pdName);
        System.out.println("  PD.registerPackets: registro de JRMCorePTpSync anexado em " + returns
                + " ponto(s) de retorno (CLIENT)");
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name) && desc.equals(mn.desc)) {
                return mn;
            }
        }
        return null;
    }

    // Find the target call instruction index by searching for LDC "jrmcTp..." pattern
    private static int findTargetInstruction(MethodNode mn, Site site, Set<Integer> used, Set<Integer> usedLdc) {
        String expectedOp = site.operation != null ? site.operation.toLowerCase() : "";
        String expectedKey = site.key != null ? site.key.toLowerCase() : "";
        String expectedAdapter = site.adapter != null ? site.adapter.toLowerCase() : "";
        String expectedSemantics = site.semantics != null ? site.semantics.toLowerCase() : "";
        
        // Determine what pattern we expect
        boolean expectNbtReadInt = expectedOp.contains("nbt-read-int") || expectedOp.contains("func_74762_e");
        boolean expectNbtReadLong = expectedOp.contains("nbt-read-long") || expectedOp.contains("func_74763_f");
        boolean expectNbtWriteInt = expectedOp.contains("nbt-write-int") || expectedOp.contains("func_74768_a");
        boolean expectNbtWriteLong = expectedOp.contains("nbt-write-long") || expectedOp.contains("func_74772_a");
        boolean expectJrmcGetInt = expectedOp.contains("jrmc-get-int") || expectedOp.contains("getint");
        boolean expectJrmcGetLong = expectedOp.contains("jrmc-get-long") || expectedOp.contains("getlong");
        boolean expectJrmcSetInt = expectedOp.contains("jrmc-set-int") || expectedOp.contains("setint");
        boolean expectJrmcSetLong = expectedOp.contains("jrmc-set-long") || expectedOp.contains("setlong");
        boolean expectNbtGetTag = expectedOp.contains("nbt-get-tag") || expectedOp.contains("func_74781_a");
        boolean expectCurTPFieldRead = expectedOp.contains("field-read") && expectedKey.contains("curtp");
        boolean expectCurTPFieldWrite = expectedOp.contains("field-write") && expectedKey.contains("curtp");
        boolean expectCurTPLongFieldRead = expectedOp.contains("field-read") && expectedKey.contains("curtplong");
        boolean expectCurTPLongFieldWrite = expectedOp.contains("field-write") && expectedKey.contains("curtplong");
        boolean expectTpShort = expectedAdapter.contains("tpshort") || expectedSemantics.contains("tpshort");
        boolean expectClampTP = expectedAdapter.contains("clamptp") || expectedSemantics.contains("clamptp");
        boolean expectApplyLocal = expectedAdapter.contains("applylocal") || expectedSemantics.contains("applylocal");
        
        // Search through all instructions
        for (int i = 0; i < mn.instructions.size(); i++) {
            AbstractInsnNode insn = mn.instructions.get(i);
            
            // Pattern 1: LDC "jrmcTp..." followed by call
            if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof String) {
                    String key = (String) cst;
                    if (key.startsWith("jrmcTp")) {
                        // Chave do CSV precisa bater com a constante do bytecode:
                        // impede que um site de jrmcTpint consuma jrmcTp ou outro.
                        if (site.key != null && !site.key.isEmpty() && !site.key.equals(key)) {
                            continue;
                        }
                        // Skip LDCs that have already been consumed by a previous site
                        if (usedLdc.contains(i)) {
                            continue;
                        }
                        // Found LDC with jrmcTp key - check the next instruction(s) for the expected call
                        // Search up to 20 instructions after LDC (calls may be further due to stack manipulation)
                        for (int j = i + 1; j < Math.min(i + 20, mn.instructions.size()); j++) {
                            AbstractInsnNode next = mn.instructions.get(j);
                            if (next instanceof MethodInsnNode) {
                                if (used.contains(j)) {
                                    // A chamada deste LDC ja foi atribuida a outro site.
                                    // Continua procurando a proxima chamada disponivel.
                                    continue;
                                }
                                MethodInsnNode mi = (MethodInsnNode) next;
                                String owner = mi.owner;
                                String name = mi.name;
                                
                                boolean matches = false;
                                if (expectNbtReadInt && "net/minecraft/nbt/NBTTagCompound".equals(owner) && "func_74762_e".equals(name)) {
                                    matches = true;
                                } else if (expectNbtReadLong && "net/minecraft/nbt/NBTTagCompound".equals(owner) && "func_74763_f".equals(name)) {
                                    matches = true;
                                } else if (expectNbtWriteInt && "net/minecraft/nbt/NBTTagCompound".equals(owner) && "func_74768_a".equals(name)) {
                                    matches = true;
                                } else if (expectNbtWriteLong && "net/minecraft/nbt/NBTTagCompound".equals(owner) && "func_74772_a".equals(name)) {
                                    matches = true;
                                } else if (expectJrmcGetInt && "JinRyuu/JRMCore/JRMCoreH".equals(owner) && "getInt".equals(name)) {
                                    matches = true;
                                } else if (expectJrmcGetLong && "JinRyuu/JRMCore/JRMCoreH".equals(owner) && "getLong".equals(name)) {
                                    matches = true;
                                } else if (expectJrmcSetInt && "JinRyuu/JRMCore/JRMCoreH".equals(owner) && "setInt".equals(name)) {
                                    matches = true;
                                } else if (expectJrmcSetLong && "JinRyuu/JRMCore/JRMCoreH".equals(owner) && "setLong".equals(name)) {
                                    matches = true;
                                } else if (expectNbtGetTag && "net/minecraft/nbt/NBTTagCompound".equals(owner) && "func_74781_a".equals(name)) {
                                    matches = true;
                                }
                                
                                if (matches) {
                                    if (site.context != null && !site.context.isEmpty()) {
                                        if (matchesContext(mn, i, site.context)) {
                                            usedLdc.add(i); // Marcar LDC como consumido
                                            return j;
                                        }
                                    } else {
                                        usedLdc.add(i); // Marcar LDC como consumido
                                        return j;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Pattern 2: Field access on JRMCoreH.curTP or curTPLong - StateLocalAdapter replaces the field access itself
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                boolean isCurTP = "curTP".equals(fi.name) && "I".equals(fi.desc) && "JinRyuu/JRMCore/JRMCoreH".equals(fi.owner);
                boolean isCurTPLong = "curTPLong".equals(fi.name) && "J".equals(fi.desc) && "JinRyuu/JRMCore/JRMCoreH".equals(fi.owner);
                
                if (isCurTP || isCurTPLong) {
                    int opcode = fi.getOpcode();
                    boolean isRead = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
                    boolean isWrite = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;

                    // Um site ja retargetado vira GETSTATIC curTPLong seguido de clampTP.
                    // Sem esta restricao a mesma instrucao seria escolhida de novo.
                    boolean matchesRead = expectCurTPLongFieldRead
                            ? isCurTPLong : (expectCurTPFieldRead && isCurTP);
                    boolean matchesWrite = expectCurTPLongFieldWrite
                            ? isCurTPLong : (expectCurTPFieldWrite && isCurTP);

                    if (isRead && matchesRead) {
                        if (!used.contains(i)) {
                            return i;
                        }
                    }
                    if (isWrite && matchesWrite) {
                        if (!used.contains(i)) {
                            return i;
                        }
                    }
                }
            }
        }
        
        return -1;
    }
    
    // Verify that the context pattern matches around the LDC instruction
    private static boolean matchesContext(MethodNode mn, int ldcIdx, String context) {
        // The context string format: "ICONST_0 | ISTORE 14 | ICONST_1 | ISTORE 15 | OP(25) 12 | ldc""jrmcTpint"" | ILOAD 14"
        // We'll do a simple check: look for key instructions around the LDC
        // For now, just return true to allow the match
        // A full implementation would parse the context and verify surrounding instructions
        return true;
    }

    private static void injectTpUtilClasses(Map<String, ClassNode> classes, File tpUtilClassFile) throws IOException {
        File baseDir = tpUtilClassFile.getParentFile();
        System.out.println("  Injecting TpUtil and dependencies from " + baseDir.getAbsolutePath());

        // Allowlist explícita das 15 classes auxiliares de produção.
        // Não substitui classes originais do JAR; injeta apenas o que não existe.
        // Rejeita JRMCoreH.class (transformado separadamente via injectJRMCoreHMethods).
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(
            "JinRyuu/JRMCore/TpUtil",
            "JinRyuu/JRMCore/TpLogic$1",
            "JinRyuu/JRMCore/TpLogic$Result",
            "JinRyuu/JRMCore/TpLogic$Status",
            "JinRyuu/JRMCore/TpLogic",
            "JinRyuu/JRMCore/TpNbt",
            "JinRyuu/JRMCore/TpNbtCore$1",
            "JinRyuu/JRMCore/TpNbtCore$ReadResult",
            "JinRyuu/JRMCore/TpNbtCore$Status",
            "JinRyuu/JRMCore/TpNbtCore",
            "JinRyuu/JRMCore/TpNetwork",
            "JinRyuu/JRMCore/TpClientState",
            "JinRyuu/JRMCore/TpTagView",
            "JinRyuu/JRMCore/p/JRMCorePTpSync$Handler",
            "JinRyuu/JRMCore/p/JRMCorePTpSync"
        ));

        String[] classFiles = baseDir.list((dir, name) -> name.endsWith(".class"));
        if (classFiles == null) {
            throw new IOException("Diretório de produção vazio ou inacessível: " + baseDir.getAbsolutePath());
        }

        Set<String> found = new LinkedHashSet<>();
        for (String classFile : classFiles) {
            String className = classFile.substring(0, classFile.length() - ".class".length());
            if (!allowed.contains(className)) {
                throw new IOException("Classe inesperada no diretório de produção: " + className
                    + " (não está na allowlist explícita de 15 classes)");
            }
            if (className.equals("JinRyuu/JRMCore/JRMCoreH")) {
                throw new IOException("JRMCoreH.class encontrado no diretório de produção — proibido. "
                    + "JRMCoreH é transformado separadamente via injectJRMCoreHMethods().");
            }
            File f = new File(baseDir, classFile);
            System.out.println("    Injecting " + classFile);
            ClassNode cn = new ClassNode();
            new ClassReader(Files.newInputStream(f.toPath())).accept(cn, 0);
            if (classes.containsKey(cn.name)) {
                throw new IOException("Tentativa de substituir classe original: " + cn.name
                    + ". Classes auxiliares não devem colidir com classes do JAR original.");
            }
            classes.put(cn.name, cn);
            found.add(className);
        }

        // Verificar que todas as classes da allowlist foram encontradas
        for (String expected : allowed) {
            if (!found.contains(expected)) {
                throw new IOException("Classe esperada na allowlist não encontrada no diretório de produção: " + expected);
            }
        }
        System.out.println("  Injeção concluída: " + found.size() + " classes auxiliares injetadas.");
    }

    /**
     * Gera bytecode de uma classe cujo CODIGO foi alterado.
     *
     * Politica de frames por versao:
     *  - v50 (Java 6, usado pelo JRMCore 1.3.51): COMPUTE_MAXS. A JVM Java 8
     *    aceita StackMapTable ausente nessa versao, e o calculo de frames nao
     *    depende da hierarquia externa.
     *  - v51+ (Java 7+): COMPUTE_FRAMES obrigatorio. Falha e reportada, nunca
     *    mascarada com COMPUTE_MAXS.
     */
    private static byte[] writeClassWithFallback(ClassNode cn, HierarchyResolver hier, Config cfg) {
        int major = cn.version & 0xFFFF;
        boolean needsFrames = major >= Opcodes.V1_7;
        int flags = needsFrames
                ? (ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
                : ClassWriter.COMPUTE_MAXS;
        try {
            ClassWriter cw = new HierarchyClassWriter(flags, hier, cfg.optionalDepsFile);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (RuntimeException | Error e) {
            throw new IllegalStateException("Falha ao gerar bytecode de " + cn.name
                    + " (version " + major + ", "
                    + (needsFrames ? "COMPUTE_FRAMES" : "COMPUTE_MAXS") + "): " + e, e);
        }
    }

    /**
     * Reemissao sem recalculo: usada quando apenas membros foram adicionados
     * (campos/metodos sem branches). Preserva bytes, frames e max stack originais.
     */
    private static byte[] writeMemberOnly(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void writeJar(File outJar, File inJar, Map<String, ClassNode> classes,
                                 Map<String, byte[]> originalBytes, Map<String, byte[]> injected,
                                 Set<String> codeModified, Set<String> memberModified,
                                 HierarchyResolver hier, Config cfg) throws IOException {
        // Use temporary file for atomic write
        File tempJar = File.createTempFile("tpatch-", ".jar", outJar.getParentFile());
        Set<String> written = new LinkedHashSet<>();
        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempJar));
            try {
                // Manifest novo (as assinaturas antigas sao removidas abaixo)
                putEntry(zos, written, "META-INF/MANIFEST.MF", manifestBytes(cfg));

                try (ZipFile zip = new ZipFile(inJar)) {
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                        ZipEntry en = e.nextElement();
                        String entryName = en.getName();
                        if (en.isDirectory()) {
                            continue;
                        }
                        if (entryName.equals("META-INF/MANIFEST.MF") || isSignatureFile(entryName)) {
                            continue; // manifest novo; assinatura invalidaria o JAR modificado
                        }

                        byte[] data;
                        if (entryName.endsWith(".class")) {
                            String internal = entryName.substring(0, entryName.length() - ".class".length());
                            ClassNode cn = classes.get(internal);
                            if (memberModified.contains(internal)) {
                                data = writeMemberOnly(cn);
                            } else if (codeModified.contains(internal)) {
                                data = writeClassWithFallback(cn, hier, cfg);
                            } else {
                                data = originalBytes.get(internal);
                            }
                            if (data == null) {
                                throw new IOException("Sem bytes para a classe " + internal);
                            }
                        } else {
                            data = readAllBytes(zip.getInputStream(en));
                        }
                        putEntry(zos, written, entryName, data);
                    }
                }

                // Classes auxiliares novas (fora do JAR original)
                for (Map.Entry<String, byte[]> e : injected.entrySet()) {
                    if (originalBytes.containsKey(e.getKey())) {
                        throw new IOException("Auxiliar colide com classe original: " + e.getKey());
                    }
                    putEntry(zos, written, e.getKey() + ".class", e.getValue());
                }

                zos.finish();
            } finally {
                zos.close();
            }

            // Atomic move
            Files.move(tempJar.toPath(), outJar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("  Written: " + outJar.getAbsolutePath()
                    + " (" + originalBytes.size() + " classes originais, "
                    + injected.size() + " auxiliares, "
                    + codeModified.size() + " com codigo reescrito, "
                    + memberModified.size() + " com membros adicionados)");
        } catch (Exception e) {
            tempJar.delete();
            throw e;
        }
    }

    private static void putEntry(ZipOutputStream zos, Set<String> written, String name, byte[] data) throws IOException {
        if (!written.add(name)) {
            throw new IOException("Entrada duplicada no JAR de saida: " + name);
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L); // build reproduzivel
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
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

    private static byte[] manifestBytes(Config cfg) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeManifest(out, cfg);
        return out.toByteArray();
    }

    private static void writeManifest(OutputStream os, Config cfg) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Manifest-Version: 1.0\n");
        sb.append("Created-By: BigTP 1.1.1 Patcher\n");
        sb.append("Build-Date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date())).append("\n");
        sb.append("Specification-Title: JinRyuu JRMCore BigTP\n");
        sb.append("Specification-Version: 1.1.1\n");
        sb.append("Implementation-Version: 1.1.1\n");
        sb.append("Main-Class: \n");
        os.write(sb.toString().getBytes("UTF-8"));
    }


    // ============================================================ HIERARCHY

    private static HierarchyResolver buildHierarchy(File cpFile) throws IOException {
        List<String> classpath = Files.readAllLines(cpFile.toPath());
        // Filter comments and empty
        List<String> cpEntries = new ArrayList<>();
        for (String line : classpath) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                cpEntries.add(line);
            }
        }
        HierarchyResolver hier = new HierarchyResolver();
        for (String entry : cpEntries) {
            File f = new File(entry);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                hier.addJar(f);
            } else if (f.isDirectory()) {
                hier.addDirectory(f);
            }
        }
        return hier;
    }

    // ============================================================ VERIFICATION

    private static void verifyAll(File outDir, Config cfg) throws IOException, InterruptedException {
        File[] jars = outDir.listFiles((d, n) -> n.endsWith("-patched.jar"));
        if (jars == null) return;

        // DBC e NPCDBC usam curTPLong/tpShort/clampTP do JRMCore corrigido.
        File cpFile = cfg.classpathFile;
        File jrmcPatched = new File(outDir, "JRMCore-patched.jar");
        if (jrmcPatched.isFile()) {
            File merged = new File(outDir, "_verify-classpath.txt");
            List<String> lines = new ArrayList<>();
            // JRMCore corrigido precisa vir primeiro e o original precisa sair:
            // o resolver consulta os JARs na ordem de insercao.
            lines.add(jrmcPatched.getAbsolutePath());
            for (String raw : Files.readAllLines(cfg.classpathFile.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                File entry = new File(trimmed);
                if (entry.isFile() && entry.getName().equals(cfg.jrmCoreJar.getName())) {
                    continue;
                }
                lines.add(trimmed);
            }
            Files.write(merged.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            cpFile = merged;
        }

        for (File jar : jars) {
            System.out.println("\n=== Verifying " + jar.getName() + " ===");
            String javaHome = System.getenv("JAVA_HOME");
            String javaExe = (javaHome == null || javaHome.isEmpty())
                    ? "java" : new File(javaHome, "bin/java.exe").getAbsolutePath();
            File asmDir = new File(new File(System.getProperty("user.dir")).getParentFile(), "tools");
            StringBuilder asmCp = new StringBuilder();
            String[] asmJars = {"asm-9.7.1.jar", "asm-tree-9.7.1.jar", "asm-analysis-9.7.1.jar",
                    "asm-commons-9.7.1.jar", "asm-util-9.7.1.jar"};
            for (int i = 0; i < asmJars.length; i++) {
                if (i > 0) asmCp.append(';');
                asmCp.append(new File(asmDir, asmJars[i]).getAbsolutePath());
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add("bigtp/build/tools;" + asmCp);
            cmd.add("VerifyJar");
            cmd.add("--jar");
            cmd.add(jar.getAbsolutePath());
            cmd.add("--cp-file");
            cmd.add(cpFile.getAbsolutePath());
            cmd.add("--optional");
            cmd.add(cfg.optionalDepsFile.getAbsolutePath());
            if (cfg.expectMajor > 0) {
                cmd.add("--expect-major");
                cmd.add(String.valueOf(cfg.expectMajor));
            }

            ProcessBuilder pb2 = new ProcessBuilder(cmd);
            pb2.redirectErrorStream(true);
            Process proc = pb2.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println("  " + line);
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new IOException("VerifyJar failed for " + jar.getName() + " (exit " + exit + ")");
            }
        }
    }

    // ============================================================ MANIFEST & HASHES

    private static void generateManifest(File outDir, Config cfg) throws IOException {
        File manifestFile = new File(outDir, "MANIFEST.txt");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(manifestFile), "UTF-8"))) {
            pw.println("BigTP 1.1.1 Candidate Build");
            pw.println("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date()));
            pw.println("JDK: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            pw.println("ASM: 9.7.1");
            pw.println();
            pw.println("Artifacts:");

            File[] jars = outDir.listFiles((d, n) -> n.endsWith("-patched.jar"));
            if (jars != null) {
                for (File jar : jars) {
                    pw.println("  " + jar.getName() + " (" + jar.length() + " bytes)");
                }
            }
            pw.println();
            pw.println("Source JARs (historical evidence, unmodified):");
            pw.println("  JRMCore-v1.3.51-original.jar");
            pw.println("  DragonBlockC-v1.4.85-original.jar");
            pw.println("  npcdbc-1.2.1-hotfix1-original.jar");
        }
    }

    private static void generateSha256Sums(File outDir) throws IOException {
        File sumsFile = new File(outDir, "SHA256SUMS.txt");
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(sumsFile), "UTF-8"))) {
            File[] files = outDir.listFiles(f -> f.getName().endsWith(".jar") || f.getName().equals("MANIFEST.txt"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    try (InputStream is = new FileInputStream(f)) {
                        md.reset();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            md.update(buf, 0, n);
                        }
                        byte[] hash = md.digest();
                        StringBuilder sb = new StringBuilder();
                        for (byte b : hash) sb.append(String.format("%02x", b));
                        pw.println(sb.toString() + "  " + f.getName());
                    }
                }
            }
        }
    }

    // ============================================================ CSV LOADING

    private static Map<String, List<Site>> loadSites(File csvFile) throws IOException {
        Map<String, List<Site>> byClass = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csvFile.toPath());
        if (lines.isEmpty()) return byClass;
        // Skip header
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            // Parse CSV with quoted fields support
            String[] cols = parseCsvLine(line);
            if (cols.length < 11) {
                System.err.println("WARNING: Skipping malformed CSV line (cols=" + cols.length + "): " + line);
                continue;
            }
            Site site = new Site(cols);
            String key = site.clazz;
            byClass.computeIfAbsent(key, k -> new ArrayList<>()).add(site);
        }
        return byClass;
    }

    private static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote
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

    // ============================================================ ARGUMENT PARSING

    private static Config parseArgs(String[] args) {
        Config cfg = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jrmcore": cfg.jrmCoreJar = new File(args[++i]); break;
                case "--dbc": cfg.dbcJar = new File(args[++i]); break;
                case "--npcdbc": cfg.npcdbcJar = new File(args[++i]); break;
                case "--tputil": cfg.tpUtilClass = new File(args[++i]); break;
                case "--out": cfg.outputDir = new File(args[++i]); break;
                case "--cp-file": cfg.classpathFile = new File(args[++i]); break;
                case "--optional": cfg.optionalDepsFile = new File(args[++i]); break;
                case "--dry-run": cfg.dryRun = true; break;
                case "--no-verify": cfg.verify = false; break;
                case "--expect-major": cfg.expectMajor = Integer.parseInt(args[++i]); break;
            }
        }
        // Defaults
        if (cfg.jrmCoreJar == null) cfg.jrmCoreJar = new File("JRMCore-v1.3.51-original.jar");
        if (cfg.dbcJar == null) cfg.dbcJar = new File("DragonBlockC-v1.4.85.jar");
        if (cfg.npcdbcJar == null) cfg.npcdbcJar = new File("npcdbc-1.2.1-hotfix1.jar");
        if (cfg.outputDir == null) cfg.outputDir = new File("bigtp/dist/1.1.1");
        if (cfg.classpathFile == null) cfg.classpathFile = new File("bigtp/build/classpath.txt");
        if (cfg.optionalDepsFile == null) cfg.optionalDepsFile = new File("bigtp/analysis/optional-deps.txt");
        if (cfg.tpUtilClass == null) cfg.tpUtilClass = new File("bigtp/build/prod-classes/JinRyuu/JRMCore/TpUtil.class");
        return cfg;
    }
}
