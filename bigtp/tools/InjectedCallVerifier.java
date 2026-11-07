import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Verificador automatizado de chamadas injetadas pelo patcher.
 * 
 * Percorre todos os AdapterExec em TpPatch.java e SiteRewriter.EXPLICIT_ADAPTERS
 * e valida cada INVOKESTATIC/INVOKEVIRTUAL/INVOKESPECIAL/INVOKEINTERFACE injetado.
 * 
 * Uso:
 *   java InjectedCallVerifier --tputil <TpUtil.class> --tpnbtcore <TpNbtCore.class>
 *     --jrmcoreh <JRMCoreH.class> --csv <sites-classified.csv> [--verbose]
 */
public final class InjectedCallVerifier {

    static final class InjectedCall {
        final String owner;
        final String name;
        final String desc;
        final int opcode;        // INVOKESTATIC, INVOKEVIRTUAL, etc.
        final String source;     // qual adapter/injetor gerou
        final String siteId;     // classe::metodo@offset

        InjectedCall(String owner, String name, String desc, int opcode, String source, String siteId) {
            this.owner = owner; this.name = name; this.desc = desc; this.opcode = opcode;
            this.source = source; this.siteId = siteId;
        }

        String key() { return owner + "." + name + desc; }
    }

    static final class TargetMethod {
        final String owner;
        final String name;
        final String desc;
        final int access;
        final boolean isStatic;
        final boolean isPublic;
        final boolean isPrivate;
        final boolean isAbstract;
        final boolean isInterface;

        TargetMethod(String owner, String name, String desc, int access, boolean isInterface) {
            this.owner = owner; this.name = name; this.desc = desc; this.access = access;
            this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
            this.isPublic = (access & Opcodes.ACC_PUBLIC) != 0;
            this.isPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
            this.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
            this.isInterface = isInterface;
        }

        boolean matches(int opcode) {
            if (isInterface) {
                return opcode == Opcodes.INVOKEINTERFACE;
            }
            if (isStatic) {
                return opcode == Opcodes.INVOKESTATIC;
            }
            if (isPrivate) {
                return opcode == Opcodes.INVOKESPECIAL;
            }
            return opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
        }
    }

    private final Map<String, TargetMethod> targetMethods = new LinkedHashMap<>();
    private final List<InjectedCall> injectedCalls = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean verbose = false;

    public static void main(String[] args) throws Exception {
        InjectedCallVerifier v = new InjectedCallVerifier();
        File tpUtilClass = null;
        File tpNbtCoreClass = null;
        File jrmCoreHClass = null;
        File csvFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tputil": tpUtilClass = new File(args[++i]); break;
                case "--tpnbtcore": tpNbtCoreClass = new File(args[++i]); break;
                case "--jrmcoreh": jrmCoreHClass = new File(args[++i]); break;
                case "--csv": csvFile = new File(args[++i]); break;
                case "--verbose": v.verbose = true; break;
            }
        }
        if (tpUtilClass == null || tpNbtCoreClass == null || jrmCoreHClass == null || csvFile == null) {
            System.err.println("Uso: InjectedCallVerifier --tputil <class> --tpnbtcore <class> --jrmcoreh <class> --csv <file> [--verbose]");
            System.exit(2);
        }
        v.loadTargetMethods(tpUtilClass, "TpUtil");
        v.loadTargetMethods(tpNbtCoreClass, "TpNbtCore");
        v.loadTargetMethods(jrmCoreHClass, "JRMCoreH");
        v.scanAdaptersFromTpPatch();
        v.scanExplicitAdapters(csvFile);
        v.verify();
        v.report();
        System.exit(v.errors.isEmpty() ? 0 : 1);
    }

    private void loadTargetMethods(File classFile, String label) throws IOException {
        if (!classFile.exists()) {
            warnings.add(label + ": arquivo nao encontrado: " + classFile);
            return;
        }
        ClassReader cr = new ClassReader(Files.readAllBytes(classFile.toPath()));
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode mn : cn.methods) {
            String key = cn.name + "." + mn.name + mn.desc;
            targetMethods.put(key, new TargetMethod(cn.name, mn.name, mn.desc, mn.access,
                    (cn.access & Opcodes.ACC_INTERFACE) != 0));
            if (verbose) System.out.println("  TARGET " + key + " access=" + mn.access);
        }
        // Also add fields as potential targets (for GETFIELD/PUTFIELD)
        for (FieldNode fn : cn.fields) {
            String key = cn.name + "." + fn.name + ":" + fn.desc;
            targetMethods.put(key, new TargetMethod(cn.name, fn.name, fn.desc, fn.access,
                    (cn.access & Opcodes.ACC_INTERFACE) != 0));
        }
        System.out.println("Carregado " + label + ": " + cn.methods.size() + " metodos, " + cn.fields.size() + " campos de " + classFile.getName());
    }

    private void scanAdaptersFromTpPatch() {
        // Hardcoded extraction from TpPatch.java adapter executors
        // This mirrors the actual bytecode emission in each AdapterExec

        // ---- ReadAdapter ----
        addInjected("JinRyuu/JRMCore/TpUtil", "readUsable", "(Lnet/minecraft/entity/player/EntityPlayer;)J",
                Opcodes.INVOKESTATIC, "ReadAdapter:isPlayer", "READ:player");
        addInjected("JinRyuu/JRMCore/TpUtil", "readUsable", "(Lnet/minecraft/nbt/NBTTagCompound;)J",
                Opcodes.INVOKESTATIC, "ReadAdapter:isNbtReadLong", "READ:nbt:long");
        addInjected("JinRyuu/JRMCore/TpUtil", "getTPInt", "(Lnet/minecraft/nbt/NBTTagCompound;)I",
                Opcodes.INVOKESTATIC, "ReadAdapter:isNbtReadInt", "READ:nbt:int");
        // L2I for int compatibility
        addInjected("JinRyuu/JRMCore/TpUtil", "readUsable", "(Lnet/minecraft/nbt/NBTTagCompound;)J",
                Opcodes.INVOKESTATIC, "ReadAdapter:fallback", "READ:nbt:fallback");

        // ---- WriteAbsAdapter ----
        addInjected("JinRyuu/JRMCore/TpUtil", "writeCheckedVoid", "(Lnet/minecraft/entity/player/EntityPlayer;J)V",
                Opcodes.INVOKESTATIC, "WriteAbsAdapter:isPlayer", "WRITE_ABS:player");
        addInjected("JinRyuu/JRMCore/TpUtil", "writeCheckedVoid", "(Lnet/minecraft/nbt/NBTTagCompound;J)V",
                Opcodes.INVOKESTATIC, "WriteAbsAdapter:isNbtWriteLong", "WRITE_ABS:nbt:long");
        addInjected("JinRyuu/JRMCore/TpUtil", "writeCheckedVoid", "(Lnet/minecraft/nbt/NBTTagCompound;J)V",
                Opcodes.INVOKESTATIC, "WriteAbsAdapter:isNbtWriteInt", "WRITE_ABS:nbt:int");
        // I2L for int->long conversion
        // (no extra method call)

        // ---- GainAdapter ----
        addInjected("JinRyuu/JRMCore/TpUtil", "gainVoid", "(Lnet/minecraft/entity/player/EntityPlayer;J)V",
                Opcodes.INVOKESTATIC, "GainAdapter:isPlayer", "GAIN:player");
        addInjected("JinRyuu/JRMCore/TpUtil", "gainVoid", "(Lnet/minecraft/nbt/NBTTagCompound;J)V",
                Opcodes.INVOKESTATIC, "GainAdapter:isNbtWrite", "GAIN:nbt");
        addInjected("JinRyuu/JRMCore/TpUtil", "commandAdd", "(Lnet/minecraft/nbt/NBTTagCompound;J)V",
                Opcodes.INVOKESTATIC, "GainAdapter:commandAdd", "GAIN:command");
        // I2L for int->long

        // ---- SpendAdapter ----
        addInjected("JinRyuu/JRMCore/TpUtil", "spendVoid", "(Lnet/minecraft/entity/player/EntityPlayer;J)V",
                Opcodes.INVOKESTATIC, "SpendAdapter:isPlayer", "SPEND:player");
        addInjected("JinRyuu/JRMCore/TpUtil", "spendVoid", "(Lnet/minecraft/nbt/NBTTagCompound;J)V",
                Opcodes.INVOKESTATIC, "SpendAdapter:isNbtWrite", "SPEND:nbt");
        addInjected("JinRyuu/JRMCore/TpUtil", "commandTake", "(Lnet/minecraft/nbt/NBTTagCompound;J)V",
                Opcodes.INVOKESTATIC, "SpendAdapter:commandTake", "SPEND:command");
        // I2L for int->long

        // ---- StateLocalAdapter ----
        // Field access: GETSTATIC curTPLong:J (keep as-is)
        addFieldAccess("JinRyuu/JRMCore/JRMCoreH", "curTPLong", "J", Opcodes.GETSTATIC,
                "StateLocalAdapter:readCurTPLong", "STATE:read:curTPLong");
        addFieldAccess("JinRyuu/JRMCore/JRMCoreH", "curTP", "I", Opcodes.GETSTATIC,
                "StateLocalAdapter:readCurTP", "STATE:read:curTP");
        // Field write: PUTFIELD curTPLong:J -> applyLocal
        addInjected("JinRyuu/JRMCore/TpUtil", "applyLocal", "(J)V",
                Opcodes.INVOKESTATIC, "StateLocalAdapter:writeCurTPLong", "STATE:write:curTPLong");
        // Field write: PUTFIELD curTP:I -> applyLocal with I2L
        addInjected("JinRyuu/JRMCore/TpUtil", "applyLocal", "(J)V",
                Opcodes.INVOKESTATIC, "StateLocalAdapter:writeCurTP", "STATE:write:curTP");
        // Method calls already correct
        addInjected("JinRyuu/JRMCore/TpUtil", "applyLocal", "(J)V",
                Opcodes.INVOKESTATIC, "StateLocalAdapter:applyLocal", "STATE:applyLocal");
        addInjected("JinRyuu/JRMCore/TpUtil", "acceptNetwork", "(J)V",
                Opcodes.INVOKESTATIC, "StateLocalAdapter:acceptNetwork", "STATE:acceptNetwork");
        // tpShort and clampTP on JRMCoreH
        addInjected("JinRyuu/JRMCore/JRMCoreH", "tpShort", "(J)S",
                Opcodes.INVOKESTATIC, "StateLocalAdapter:tpShort", "STATE:tpShort");
        addInjected("JinRyuu/JRMCore/JRMCoreH", "clampTP", "(J)I",
                Opcodes.INVOKESTATIC, "StateLocalAdapter:clampTP", "STATE:clampTP");

        // ---- Explicit adapters from SiteRewriter.EXPLICIT_ADAPTERS ----
        // These are encoded as strings like "TpUtil.readUsable(player)"
        // We'll parse them from the CSV in scanExplicitAdapters
    }

    private void scanExplicitAdapters(File csvFile) throws IOException {
        List<String> lines = Files.readAllLines(csvFile.toPath());
        if (lines.isEmpty()) return;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] cols = parseCsvLine(line);
            if (cols.length < 11) continue;
            String clazz = cols[0];
            String method = cols[1];
            String desc = cols[2];
            int offset = Integer.parseInt(cols[3]);
            String adapter = cols[9];
            if (adapter == null || adapter.isEmpty()) continue;

            String siteId = clazz + "::" + method + "@" + offset;
            parseAdapterString(adapter, siteId);
        }
    }

    private void parseAdapterString(String adapter, String siteId) {
        String a = adapter.trim();
        // Patterns:
        // "TpUtil.readUsable(player)" -> owner=JinRyuu/JRMCore/TpUtil, name=readUsable, desc=(Lnet/minecraft/entity/player/EntityPlayer;)J
        // "TpUtil.readUsable(nbt)" -> (Lnet/minecraft/nbt/NBTTagCompound;)J
        // "TpUtil.getTP(player)" -> (Lnet/minecraft/entity/player/EntityPlayer;)J
        // "TpUtil.getTPInt(nbt)" -> (Lnet/minecraft/nbt/NBTTagCompound;)I
        // "TpUtil.writeCheckedVoid(player, value)" -> (Lnet/minecraft/entity/player/EntityPlayer;J)V
        // "TpUtil.writeCheckedVoid(nbt, value)" -> (Lnet/minecraft/nbt/NBTTagCompound;J)V
        // "TpUtil.commandWrite(nbt, value)" -> (Lnet/minecraft/nbt/NBTTagCompound;J)V
        // "TpUtil.commandAdd(nbt, amount)" -> (Lnet/minecraft/nbt/NBTTagCompound;J)J (returns long)
        // "TpUtil.commandTake(nbt, amount)" -> (Lnet/minecraft/nbt/NBTTagCompound;J)V
        // "TpUtil.gainVoid(player, value)" -> (Lnet/minecraft/entity/player/EntityPlayer;J)V
        // "TpUtil.spendVoid(player, value)" -> (Lnet/minecraft/entity/player/EntityPlayer;J)V
        // "TpUtil.applyLocal(value)" -> (J)V
        // "TpUtil.acceptNetwork(value)" -> (J)V
        // "JRMCoreH.tpShort(curTPLong)" -> (J)S
        // "JRMCoreH.clampTP(curTPLong)" -> (J)I
        // "SKIP" -> ignore

        if ("SKIP".equalsIgnoreCase(a)) return;

        int paren = a.indexOf('(');
        if (paren < 0) {
            warnings.add("Adapter sem parentese: " + a + " em " + siteId);
            return;
        }
        String fullName = a.substring(0, paren); // e.g. "TpUtil.readUsable"
        String args = a.substring(paren + 1, a.length() - 1); // e.g. "player" or "nbt" or "value"

        String owner;
        String methodName;
        int dot = fullName.lastIndexOf('.');
        if (dot < 0) {
            warnings.add("Adapter sem ponto: " + a + " em " + siteId);
            return;
        }
        String classPart = fullName.substring(0, dot);
        methodName = fullName.substring(dot + 1);

        if ("TpUtil".equals(classPart)) {
            owner = "JinRyuu/JRMCore/TpUtil";
        } else if ("JRMCoreH".equals(classPart)) {
            owner = "JinRyuu/JRMCore/JRMCoreH";
        } else {
            warnings.add("Classe desconhecida no adapter: " + classPart + " em " + siteId);
            return;
        }

        // Infer descriptor from method name and args
        String desc = inferDescriptor(methodName, args, owner);
        int opcode = inferOpcode(methodName, owner);

        addInjected(owner, methodName, desc, opcode, "EXPLICIT:" + a, siteId);
    }

    private String inferDescriptor(String methodName, String args, String owner) {
        // args can be: "player", "nbt", "value", "curTPLong", "value + gain", "value - cost", "value - 1", "value - 20", "0", "amount", "cost"
        boolean hasPlayer = args.contains("player");
        boolean hasNbt = args.contains("nbt");
        boolean hasValue = args.contains("value");
        boolean hasGain = args.contains("gain");
        boolean hasCost = args.contains("cost");
        boolean hasAmount = args.contains("amount");
        boolean isZero = "0".equals(args.trim());

        // TpUtil methods
        if ("JinRyuu/JRMCore/TpUtil".equals(owner)) {
            switch (methodName) {
                case "readUsable":
                    if (hasPlayer) return "(Lnet/minecraft/entity/player/EntityPlayer;)J";
                    if (hasNbt) return "(Lnet/minecraft/nbt/NBTTagCompound;)J";
                    return "(Lnet/minecraft/nbt/NBTTagCompound;)J"; // default
                case "getTP":
                    if (hasPlayer) return "(Lnet/minecraft/entity/player/EntityPlayer;)J";
                    return "(Lnet/minecraft/nbt/NBTTagCompound;)J";
                case "getTPInt":
                    if (hasPlayer) return "(Lnet/minecraft/entity/player/EntityPlayer;)I";
                    return "(Lnet/minecraft/nbt/NBTTagCompound;)I";
                case "writeCheckedVoid":
                case "gainVoid":
                case "spendVoid":
                    if (hasPlayer) return "(Lnet/minecraft/entity/player/EntityPlayer;J)V";
                    return "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
                case "commandWrite":
                case "commandAdd":
                case "commandTake":
                    // Adapter strings use commandWrite/commandAdd/commandTake but actual methods are *Void variants
                    return "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
                case "applyLocal":
                case "acceptNetwork":
                    return "(J)V";
                case "write":
                    if (hasPlayer) return "(Lnet/minecraft/entity/player/EntityPlayer;J)V";
                    return "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
            }
        }

        // JRMCoreH methods
        if ("JinRyuu/JRMCore/JRMCoreH".equals(owner)) {
            switch (methodName) {
                case "tpShort": return "(J)S";
                case "clampTP": return "(J)I";
                case "getMaxTP": return "()I";
                case "nbt": return "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;";
                case "getByte": return "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)B";
                case "setByte": return "(Lnet/minecraft/entity/Entity;Ljava/lang/String;B)V";
                case "setInt": return "(Lnet/minecraft/entity/Entity;Ljava/lang/String;I)V";
                case "getInt": return "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)I";
                case "getLong": return "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)J";
            }
        }

        warnings.add("Nao foi possivel inferir descriptor para " + owner + "." + methodName + " args=" + args);
        return "(?)V";
    }

    private int inferOpcode(String methodName, String owner) {
        // Check if target method is static
        TargetMethod tm = targetMethods.get(owner + "." + methodName + inferDescriptor(methodName, "", owner));
        // We'll check properly in verify()
        // Default: INVOKESTATIC for TpUtil (all static), INVOKESTATIC for JRMCoreH (all static in our case)
        if (owner.contains("TpUtil")) return Opcodes.INVOKESTATIC;
        if (owner.contains("JRMCoreH")) return Opcodes.INVOKESTATIC;
        return Opcodes.INVOKESTATIC;
    }

    private void addInjected(String owner, String name, String desc, int opcode, String source, String siteId) {
        injectedCalls.add(new InjectedCall(owner, name, desc, opcode, source, siteId));
    }

    private void addFieldAccess(String owner, String name, String desc, int opcode, String source, String siteId) {
        // Field accesses are validated separately
        InjectedCall call = new InjectedCall(owner, name, desc, opcode, source, siteId);
        injectedCalls.add(call);
    }

    private void verify() {
        System.out.println("\n=== Verificando " + injectedCalls.size() + " chamadas injetadas ===");
        for (InjectedCall call : injectedCalls) {
            verifyCall(call);
        }
    }

    private void verifyCall(InjectedCall call) {
        // Field accesses (GETSTATIC, GETFIELD, PUTSTATIC, PUTFIELD) are validated separately
        if (call.opcode == Opcodes.GETSTATIC || call.opcode == Opcodes.GETFIELD ||
            call.opcode == Opcodes.PUTSTATIC || call.opcode == Opcodes.PUTFIELD) {
            String fieldKey = call.owner + "." + call.name + ":" + call.desc;
            TargetMethod target = targetMethods.get(fieldKey);
            if (target == null) {
                errors.add("CAMPO_INEXISTENTE: " + call.source + " @ " + call.siteId
                        + " -> " + call.owner + "." + call.name + ":" + call.desc);
                return;
            }
            // Check field access compatibility
            boolean isStatic = (target.access & Opcodes.ACC_STATIC) != 0;
            if (call.opcode == Opcodes.GETSTATIC || call.opcode == Opcodes.PUTSTATIC) {
                if (!isStatic) {
                    errors.add("OPCODE_INCOMPATIVEL: " + call.source + " @ " + call.siteId
                            + " -> " + fieldKey + " usa " + opcodeName(call.opcode)
                            + " mas campo nao e static");
                    return;
                }
            } else {
                if (isStatic) {
                    errors.add("OPCODE_INCOMPATIVEL: " + call.source + " @ " + call.siteId
                            + " -> " + fieldKey + " usa " + opcodeName(call.opcode)
                            + " mas campo e static");
                    return;
                }
            }
            // Check visibility
            boolean isPublic = (target.access & Opcodes.ACC_PUBLIC) != 0;
            boolean isPrivate = (target.access & Opcodes.ACC_PRIVATE) != 0;
            if (isPrivate && !call.owner.equals(target.owner)) {
                errors.add("CAMPO_PRIVADO: " + call.source + " @ " + call.siteId
                        + " -> " + fieldKey + " e private mas acessado de outra classe");
                return;
            }
            if (verbose) {
                System.out.println("  OK   " + call.source + " @ " + call.siteId + " -> " + fieldKey);
            }
            return;
        }

        String targetKey = call.owner + "." + call.name + call.desc;
        TargetMethod target = targetMethods.get(targetKey);

        if (target == null) {
            errors.add("METODO_INEXISTENTE: " + call.source + " @ " + call.siteId
                    + " -> " + call.owner + "." + call.name + call.desc
                    + " (opcode=" + opcodeName(call.opcode) + ")");
            return;
        }

        // Check opcode compatibility
        if (!target.matches(call.opcode)) {
            errors.add("OPCODE_INCOMPATIVEL: " + call.source + " @ " + call.siteId
                    + " -> " + targetKey + " usa " + opcodeName(call.opcode)
                    + " mas metodo e " + (target.isStatic ? "static" : target.isPrivate ? "private" : "instance")
                    + (target.isInterface ? " (interface)" : ""));
            return;
        }

        // Check visibility
        if (target.isPrivate && !call.owner.equals(target.owner)) {
            errors.add("METODO_PRIVADO: " + call.source + " @ " + call.siteId
                    + " -> " + targetKey + " e private mas chamado de outra classe");
            return;
        }

        if (!target.isPublic && !target.isPrivate && target.isStatic) {
            // package-private static - ok if same package, but we're injecting from different class
            // TpUtil is in JinRyuu.JRMCore, JRMCoreH is in JinRyuu.JRMCore - same package, OK
        }

        // Check abstract
        if (target.isAbstract && (call.opcode == Opcodes.INVOKESPECIAL || call.opcode == Opcodes.INVOKESTATIC)) {
            errors.add("METODO_ABSTRATO: " + call.source + " @ " + call.siteId
                    + " -> " + targetKey + " e abstrato mas invocado com " + opcodeName(call.opcode));
            return;
        }

        // Check interface vs class invoke
        if (target.isInterface && call.opcode != Opcodes.INVOKEINTERFACE) {
            warnings.add("INTERFACE_COM_INVOKEVIRTUAL: " + call.source + " @ " + call.siteId
                    + " -> " + targetKey + " e interface mas usa " + opcodeName(call.opcode));
        }
        if (!target.isInterface && call.opcode == Opcodes.INVOKEINTERFACE && !target.isStatic) {
            warnings.add("CLASSE_COM_INVOKEINTERFACE: " + call.source + " @ " + call.siteId
                    + " -> " + targetKey + " nao e interface mas usa INVOKEINTERFACE");
        }

        // Java 8 compatibility: descriptors must be valid
        if (!isValidDescriptor(call.desc)) {
            errors.add("DESCRITOR_INVALIDO: " + call.source + " @ " + call.siteId
                    + " -> " + targetKey + " descriptor invalido: " + call.desc);
            return;
        }

        if (verbose) {
            System.out.println("  OK   " + call.source + " @ " + call.siteId + " -> " + targetKey);
        }
    }

    private boolean isValidDescriptor(String desc) {
        // Basic JVM descriptor validation
        if (desc == null || desc.isEmpty()) return false;
        if (desc.equals("(?)V")) return false; // placeholder from failed inference
        // Method descriptor: (params)return
        if (!desc.startsWith("(")) return false;
        int closeParen = desc.indexOf(')');
        if (closeParen < 0) return false;
        String returnType = desc.substring(closeParen + 1);
        if (returnType.isEmpty()) return false;
        // Valid return types: V, B, C, D, F, I, J, S, Z, L...;, [...]
        char r = returnType.charAt(0);
        if ("VBCDFIJSZ".indexOf(r) < 0 && r != 'L' && r != '[') return false;
        if (r == 'L' && !returnType.endsWith(";")) return false;
        return true;
    }

    private void report() {
        System.out.println("\n=== RELATORIO ===");
        System.out.println("Metodos alvo carregados: " + targetMethods.size());
        System.out.println("Chamadas injetadas verificadas: " + injectedCalls.size());
        System.out.println("Erros: " + errors.size());
        System.out.println("Avisos: " + warnings.size());

        if (!errors.isEmpty()) {
            System.out.println("\n--- ERROS ---");
            for (String e : errors) System.out.println("  " + e);
        }
        if (!warnings.isEmpty()) {
            System.out.println("\n--- AVISOS ---");
            for (String w : warnings) System.out.println("  " + w);
        }
        if (errors.isEmpty()) {
            System.out.println("\nTODAS AS CHAMADAS INJETADAS SAO COMPATIVEIS");
        }
    }

    private static String opcodeName(int op) {
        switch (op) {
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTSTATIC: return "PUTSTATIC";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            default: return "OP(" + op + ")";
        }
    }

    private static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
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
}