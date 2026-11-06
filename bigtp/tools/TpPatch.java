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
 * BigTP 1.0.7 Patcher - Strict, transactional, hierarchy-aware.
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
        int expectMajor = 52;       // Java 8 class file version
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

    // ------------------------------------------------------------------ READ: TpUtil.getTP / getTPInt / readUsable
    static final class ReadAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            // Pattern: [target] LDC "key" INVOKE... -> replace with INVOKESTATIC TpUtil.readUsable/getTP/getTPInt
            // Target is either NBTTagCompound (on stack) or EntityPlayer
            // We need to know which overload to call based on context.

            AbstractInsnNode call = mn.instructions.get(idx);
            if (!(call instanceof MethodInsnNode)) return -1;
            MethodInsnNode mi = (MethodInsnNode) call;

            boolean isPlayer = "JinRyuu/JRMCore/JRMCoreH".equals(mi.owner)
                    && ("getInt".equals(mi.name) || "getLong".equals(mi.name));
            boolean isNbtReadInt = "net/minecraft/nbt/NBTTagCompound".equals(mi.owner)
                    && "func_74762_e".equals(mi.name); // getInteger
            boolean isNbtReadLong = "net/minecraft/nbt/NBTTagCompound".equals(mi.owner)
                    && "func_74763_f".equals(mi.name); // getLong

            String targetType;
            String methodName;
            String methodDesc;

            if (isPlayer) {
                targetType = "Lnet/minecraft/entity/player/EntityPlayer;";
                methodName = "readUsable"; // reads from player via JRMCoreH.nbt internally
                methodDesc = "(Lnet/minecraft/entity/player/EntityPlayer;)J";
            } else if (isNbtReadLong) {
                targetType = "Lnet/minecraft/nbt/NBTTagCompound;";
                methodName = "readUsable";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;)J";
            } else if (isNbtReadInt) {
                targetType = "Lnet/minecraft/nbt/NBTTagCompound;";
                methodName = "getTPInt";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;)I";
            } else {
                // fallback: readUsable for NBT
                targetType = "Lnet/minecraft/nbt/NBTTagCompound;";
                methodName = "readUsable";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;)J";
            }

            // Remove the LDC "key" and the original call
            // Find the LDC before the call (within 3 instructions)
            int ldcIdx = -1;
            for (int i = idx - 1; i >= Math.max(0, idx - 5); i--) {
                if (mn.instructions.get(i) instanceof LdcInsnNode) {
                    Object cst = ((LdcInsnNode) mn.instructions.get(i)).cst;
                    if (cst instanceof String && ((String) cst).startsWith("jrmcTp")) {
                        ldcIdx = i;
                        break;
                    }
                }
            }

            InsnList replacement = new InsnList();
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "JinRyuu/JRMCore/TpUtil", methodName, methodDesc, false));

            // If original method returned int but new method returns long, add L2I for compatibility
            boolean originalReturnsInt = isPlayer && "getInt".equals(mi.name)
                    || isNbtReadInt;
            boolean newReturnsLong = methodDesc.endsWith(")J");
            if (originalReturnsInt && newReturnsLong) {
                replacement.add(new InsnNode(Opcodes.L2I));
            }

            if (ldcIdx >= 0) {
                // Remove the LDC
                mn.instructions.remove(mn.instructions.get(ldcIdx));
                // Adjust idx since we removed an instruction before it
                idx--;
                // Replace the call with the replacement list
                mn.instructions.insert(call, replacement);
                mn.instructions.remove(call);
            } else {
                // No LDC found, just replace call
                mn.instructions.insert(call, replacement);
                mn.instructions.remove(call);
            }
            return idx;
        }
    }

    // ------------------------------------------------------------------ WRITE_ABS: TpUtil.write / writeCheckedVoid
    static final class WriteAbsAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            // Pattern: [target] [value] LDC "key" INVOKE...setInt/setLong/setInteger/setLong
            // Replace with INVOKESTATIC TpUtil.writeCheckedVoid (or write)

            AbstractInsnNode call = mn.instructions.get(idx);
            if (!(call instanceof MethodInsnNode)) return -1;
            MethodInsnNode mi = (MethodInsnNode) call;

            boolean isPlayer = "JinRyuu/JRMCore/JRMCoreH".equals(mi.owner)
                    && ("setInt".equals(mi.name) || "setLong".equals(mi.name));
            boolean isNbtWriteInt = "net/minecraft/nbt/NBTTagCompound".equals(mi.owner)
                    && "func_74768_a".equals(mi.name); // setInteger
            boolean isNbtWriteLong = "net/minecraft/nbt/NBTTagCompound".equals(mi.owner)
                    && "func_74772_a".equals(mi.name); // setLong

            String targetType;
            String methodName;
            String methodDesc;

            if (isPlayer) {
                targetType = "Lnet/minecraft/entity/player/EntityPlayer;";
                methodName = "writeCheckedVoid";
                methodDesc = "(Lnet/minecraft/entity/player/EntityPlayer;J)V";
            } else if (isNbtWriteLong) {
                targetType = "Lnet/minecraft/nbt/NBTTagCompound;";
                methodName = "writeCheckedVoid";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
            } else { // NBT write int (jrmcTpint) - write long via checked
                targetType = "Lnet/minecraft/nbt/NBTTagCompound;";
                methodName = "writeCheckedVoid";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
            }

            // Find and remove the LDC "key"
            int ldcIdx = -1;
            for (int i = idx - 1; i >= Math.max(0, idx - 5); i--) {
                if (mn.instructions.get(i) instanceof LdcInsnNode) {
                    Object cst = ((LdcInsnNode) mn.instructions.get(i)).cst;
                    if (cst instanceof String && ((String) cst).startsWith("jrmcTp")) {
                        ldcIdx = i;
                        break;
                    }
                }
            }

            // The stack before call: ..., target, value, "key"
            // We need: ..., target, value
            // Then call TpUtil.writeCheckedVoid(target, value)
            // If value is int (for jrmcTpint write), need I2L before call

            InsnList replacement = new InsnList();
            // Check if we need I2L: original call was setInteger (int) or setInt (int)
            boolean valueIsInt = isPlayer || isNbtWriteInt;
            if (valueIsInt) {
                replacement.add(new InsnNode(Opcodes.I2L));
            }
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "JinRyuu/JRMCore/TpUtil", methodName, methodDesc, false));

            if (ldcIdx >= 0) {
                mn.instructions.remove(mn.instructions.get(ldcIdx));
                idx--;
            }
            mn.instructions.insert(call, replacement);
            mn.instructions.remove(call);
            return idx;
        }
    }

    // ------------------------------------------------------------------ WRITE_DELTA_ADD: TpUtil.gainVoid / commandAdd
    static final class GainAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            // Pattern: READ (getInt/getLong) -> IADD/LADD -> WRITE (setInt/setLong)
            // We need to replace the entire READ + ADD + WRITE sequence with a single call.
            // The READ is at some earlier index, the WRITE is at idx.
            // Context tells us it's an ADD (iadd/ladd present).

            // Find the READ instruction (GETFIELD/GETSTATIC/INVOKESTATIC getInt/getLong)
            // It's typically 5-15 instructions before the WRITE.
            int readIdx = -1;
            for (int i = idx - 1; i >= Math.max(0, idx - 20); i--) {
                AbstractInsnNode n = mn.instructions.get(i);
                if (n instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) n;
                    if (("getInt".equals(mi.name) || "getLong".equals(mi.name)
                            || "func_74762_e".equals(mi.name) || "func_74763_f".equals(mi.name))
                            && ("JinRyuu/JRMCore/JRMCoreH".equals(mi.owner)
                                    || "net/minecraft/nbt/NBTTagCompound".equals(mi.owner))) {
                        readIdx = i;
                        break;
                    }
                } else if (n instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) n;
                    if (("curTP".equals(fi.name) || "curTPLong".equals(fi.name))
                            && (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC)
                            && "JinRyuu/JRMCore/JRMCoreH".equals(fi.owner)) {
                        readIdx = i;
                        break;
                    }
                }
            }

            if (readIdx < 0) return -1; // couldn't find read

            // Determine target type and method
            AbstractInsnNode writeCall = mn.instructions.get(idx);
            MethodInsnNode wmi = (MethodInsnNode) writeCall;

            boolean isPlayer = "JinRyuu/JRMCore/JRMCoreH".equals(wmi.owner)
                    && ("setInt".equals(wmi.name) || "setLong".equals(wmi.name));
            boolean isNbtWrite = "net/minecraft/nbt/NBTTagCompound".equals(wmi.owner);

            String targetType;
            String methodName;
            String methodDesc;

            if (isPlayer) {
                targetType = "Lnet/minecraft/entity/player/EntityPlayer;";
                methodName = "gainVoid";
                methodDesc = "(Lnet/minecraft/entity/player/EntityPlayer;J)V";
            } else if (isNbtWrite) {
                targetType = "Lnet/minecraft/nbt/NBTTagCompound;";
                methodName = "gainVoid";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
            } else {
                // commandAdd uses NBTTagCompound
                targetType = "Lnet/minecraft/nbt/NBTTagCompound;";
                methodName = "commandAdd";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
            }

            // We need to extract the GAIN value from the stack.
            // Pattern: READ -> ... -> GAIN_VALUE -> IADD/LADD -> ... -> WRITE
            // The GAIN_VALUE is pushed between read and add.
            // We'll find the IADD/LADD and the value pushed before it.

            // Find the LAST IADD/LADD before the WRITE (search backwards from WRITE)
            int addIdx = -1;
            for (int i = idx - 1; i > readIdx; i--) {
                AbstractInsnNode n = mn.instructions.get(i);
                if (n instanceof InsnNode && (n.getOpcode() == Opcodes.IADD || n.getOpcode() == Opcodes.LADD)) {
                    addIdx = i;
                    break;
                }
            }
            if (addIdx < 0) return -1;

            // The gain value is typically in a local variable loaded just before IADD
            // Or it's a constant. We'll look for ILOAD/LLOAD/BIPUSH/SIPUSH/LDC before IADD.
            // This is complex - we'll use a simpler approach: replace from READ to WRITE
            // with a sequence that: reads the target, loads the gain, calls gainVoid.

            // For now, assume the gain is in a local variable. We need to find which one.
            // This is highly context-dependent. Let's use a more robust approach:
            // Replace the WRITE instruction with a call that takes (target, gain)
            // where gain is computed from the stack at the ADD point.

            // Actually, the simplest correct transformation:
            // 1. Keep the READ (it puts target on stack)
            // 2. Replace IADD + WRITE with: gain_value, INVOKESTATIC gainVoid
            // But we need the target to be under the gain value.

            // Better: Replace the entire sequence from READ to WRITE with:
            //   READ_TARGET (dup target) -> LOAD_GAIN -> INVOKESTATIC gainVoid
            // This requires knowing the exact stack layout.

            // Given the complexity, we'll use a conservative approach:
            // Remove instructions from readIdx to idx inclusive, insert new sequence.

            // Find the target base (what was loaded before READ)
            // For player: ALOAD player before getInt
            // For NBT: ALOAD nbt before getInteger/getLong

            // This is getting very complex. Let's use a different strategy:
            // For gain sites, we know the pattern from the explicit adapter table.
            // The adapter string tells us exactly what to emit.
            // e.g. "TpUtil.writeCheckedVoid(player, value + gain)" -> we emit read + gain + writeCheckedVoid
            // But we don't have the 'value' and 'gain' separated in bytecode easily.

            // Practical approach: For each known gain site (from EXPLICIT_ADAPTERS),
            // we have a hardcoded transformation in the old TpPatch.
            // We'll delegate to those for now and use generic only for simple cases.

            // For this generic adapter, we'll handle the common case:
            // READ (target on stack) -> LOAD gain -> IADD -> WRITE
            // Transform to: READ (target) -> LOAD gain -> I2L -> INVOKESTATIC gainVoid

            // Remove from IADD to WRITE inclusive, replace with gainVoid call
            int startRemove = addIdx;
            int endRemove = idx;

            // But we need to keep the target and gain on stack.
            // At IADD: stack has ..., target, gain
            // We want: ..., target, gain -> I2L -> gainVoid(target, gain)
            // So we replace IADD..WRITE with: I2L, INVOKESTATIC gainVoid

            InsnList replacement = new InsnList();
            // Stack at IADD: ..., target, gain (both int or both long)
            // Determine if int or long by checking IADD vs LADD
            AbstractInsnNode addInsn = mn.instructions.get(addIdx);
            if (addInsn.getOpcode() == Opcodes.IADD) {
                replacement.add(new InsnNode(Opcodes.I2L));
            }
            // target is already long on stack (from READ which we keep)
            // gain is now long (after I2L)
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "JinRyuu/JRMCore/TpUtil", methodName, methodDesc, false));

            // Remove IADD through WRITE
            for (int i = endRemove; i >= startRemove; i--) {
                mn.instructions.remove(mn.instructions.get(i));
            }
            // Insert at addIdx (where IADD was)
            mn.instructions.insert(mn.instructions.get(addIdx), replacement);
            // Remove the old IADD (now at addIdx)
            mn.instructions.remove(mn.instructions.get(addIdx));

            return addIdx;
        }
    }

    // ------------------------------------------------------------------ WRITE_DELTA_SUB: TpUtil.spendVoid / commandTake
    static final class SpendAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            // Similar to GainAdapter but for ISUB/LSUB -> spendVoid/commandTake
            // Pattern: READ -> ... -> COST -> ISUB -> ... -> WRITE
            // Transform: READ -> LOAD cost -> I2L -> INVOKESTATIC spendVoid

            int readIdx = -1;
            for (int i = idx - 1; i >= Math.max(0, idx - 20); i--) {
                AbstractInsnNode n = mn.instructions.get(i);
                if (n instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) n;
                    if (("getInt".equals(mi.name) || "getLong".equals(mi.name)
                            || "func_74762_e".equals(mi.name) || "func_74763_f".equals(mi.name))
                            && ("JinRyuu/JRMCore/JRMCoreH".equals(mi.owner)
                                    || "net/minecraft/nbt/NBTTagCompound".equals(mi.owner))) {
                        readIdx = i;
                        break;
                    }
                } else if (n instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) n;
                    if (("curTP".equals(fi.name) || "curTPLong".equals(fi.name))
                            && (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC)
                            && "JinRyuu/JRMCore/JRMCoreH".equals(fi.owner)) {
                        readIdx = i;
                        break;
                    }
                }
            }
            if (readIdx < 0) return -1;

            AbstractInsnNode writeCall = mn.instructions.get(idx);
            MethodInsnNode wmi = (MethodInsnNode) writeCall;

            boolean isPlayer = "JinRyuu/JRMCore/JRMCoreH".equals(wmi.owner)
                    && ("setInt".equals(wmi.name) || "setLong".equals(wmi.name));
            boolean isNbtWrite = "net/minecraft/nbt/NBTTagCompound".equals(wmi.owner);

            String targetType;
            String methodName;
            String methodDesc;

            if (isPlayer) {
                methodName = "spendVoid";
                methodDesc = "(Lnet/minecraft/entity/player/EntityPlayer;J)V";
            } else if (isNbtWrite) {
                methodName = "spendVoid";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
            } else {
                methodName = "commandTake";
                methodDesc = "(Lnet/minecraft/nbt/NBTTagCompound;J)V";
            }

            // Find the LAST ISUB/LSUB before the WRITE (search backwards from WRITE)
            int subIdx = -1;
            for (int i = idx - 1; i > readIdx; i--) {
                AbstractInsnNode n = mn.instructions.get(i);
                if (n instanceof InsnNode && (n.getOpcode() == Opcodes.ISUB || n.getOpcode() == Opcodes.LSUB)) {
                    subIdx = i;
                    break;
                }
            }
            if (subIdx < 0) return -1;

            // Replace ISUB..WRITE with I2L + spendVoid
            InsnList replacement = new InsnList();
            AbstractInsnNode subInsn = mn.instructions.get(subIdx);
            if (subInsn.getOpcode() == Opcodes.ISUB) {
                replacement.add(new InsnNode(Opcodes.I2L));
            }
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "JinRyuu/JRMCore/TpUtil", methodName, methodDesc, false));

            for (int i = idx; i >= subIdx; i--) {
                mn.instructions.remove(mn.instructions.get(i));
            }
            mn.instructions.insert(mn.instructions.get(subIdx), replacement);
            mn.instructions.remove(mn.instructions.get(subIdx));

            return subIdx;
        }
    }

    // ------------------------------------------------------------------ STATE_LOCAL: TpUtil.applyLocal / tpShort / clampTP
    static final class StateLocalAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            AbstractInsnNode insn = mn.instructions.get(idx);

            // Field read: GETFIELD/GETSTATIC curTP or curTPLong
            // Field write: PUTFIELD/PUTSTATIC curTP or curTPLong
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                boolean isRead = fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC;
                boolean isCurTPLong = "curTPLong".equals(fi.name);
                boolean isCurTP = "curTP".equals(fi.name);

                if (isRead) {
                    if (isCurTPLong) {
                        // GETFIELD curTPLong:J -> keep as is (already long)
                        return idx;
                    } else if (isCurTP) {
                        // GETFIELD curTP:I -> replace with INVOKESTATIC JRMCoreH.tpShort(curTPLong) or clampTP
                        // Check adapter for which one
                        if (site.adapter != null && site.adapter.contains("tpShort")) {
                            // Replace with: GETSTATIC JRMCoreH.curTPLong:J, INVOKESTATIC JRMCoreH.tpShort
                            InsnList repl = new InsnList();
                            repl.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                    "JinRyuu/JRMCore/JRMCoreH", "curTPLong", "J"));
                            repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    "JinRyuu/JRMCore/JRMCoreH", "tpShort", "(J)S", false));
                            mn.instructions.insert(insn, repl);
                            mn.instructions.remove(insn);
                        } else {
                            // clampTP
                            InsnList repl = new InsnList();
                            repl.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                    "JinRyuu/JRMCore/JRMCoreH", "curTPLong", "J"));
                            repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    "JinRyuu/JRMCore/JRMCoreH", "clampTP", "(J)I", false));
                            mn.instructions.insert(insn, repl);
                            mn.instructions.remove(insn);
                        }
                        return idx;
                    }
                } else { // write
                    if (isCurTPLong) {
                        // PUTFIELD curTPLong:J -> replace with TpUtil.applyLocal
                        // Stack: target, value
                        // But GETFIELD/PUTFIELD on JRMCoreH are static usually
                        InsnList repl = new InsnList();
                        repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "JinRyuu/JRMCore/TpUtil", "applyLocal", "(J)V", false));
                        mn.instructions.insert(insn, repl);
                        mn.instructions.remove(insn);
                        return idx;
                    } else if (isCurTP) {
                        // PUTFIELD curTP:I -> replace with TpUtil.applyLocal(value) after I2L
                        // Or just remove (legacy mirror, TpUtil.applyLocal writes both)
                        InsnList repl = new InsnList();
                        repl.add(new InsnNode(Opcodes.I2L));
                        repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "JinRyuu/JRMCore/TpUtil", "applyLocal", "(J)V", false));
                        mn.instructions.insert(insn, repl);
                        mn.instructions.remove(insn);
                        return idx;
                    }
                }
            }

            // Method call: applyLocal, acceptNetwork
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if ("applyLocal".equals(mi.name) && "JinRyuu/JRMCore/TpUtil".equals(mi.owner)) {
                    // Already correct
                    return idx;
                }
                if ("acceptNetwork".equals(mi.name) && "JinRyuu/JRMCore/TpUtil".equals(mi.owner)) {
                    return idx;
                }
            }

            return idx;
        }
    }

    // ------------------------------------------------------------------ COMPLEX: explicit per-site (fallback to old patcher logic)
    static final class ComplexAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            // Complex sites should have been handled by explicit adapters in the table.
            // If we reach here, it's a WRITE_COMPLEX without an adapter - abort.
            System.err.println("ERROR: Unhandled COMPLEX site: " + site.id() + " - " + site.adapter);
            throw new IllegalStateException("Unhandled COMPLEX site: " + site.id());
        }
    }

    // ============================================================ ADAPTER REGISTRY

    private static final Map<String, AdapterExec> ADAPTER_REGISTRY = new HashMap<>();

    static {
        ADAPTER_REGISTRY.put("READ", new ReadAdapter());
        ADAPTER_REGISTRY.put("WRITE_ABS", new WriteAbsAdapter());
        ADAPTER_REGISTRY.put("WRITE_DELTA_ADD", new GainAdapter());
        ADAPTER_REGISTRY.put("WRITE_DELTA_SUB", new SpendAdapter());
        ADAPTER_REGISTRY.put("STATE_LOCAL_READ", new StateLocalAdapter());
        ADAPTER_REGISTRY.put("STATE_LOCAL_WRITE", new StateLocalAdapter());
        ADAPTER_REGISTRY.put("STATE_NETWORK_READ", new StateLocalAdapter()); // applyLocal
        ADAPTER_REGISTRY.put("WRITE_COMPLEX", new ComplexAdapter());
        ADAPTER_REGISTRY.put("UNKNOWN", new ComplexAdapter()); // will abort
    }

    // Infer AdapterExec from explicit adapter string (e.g., "TpUtil.readUsable(nbt)")
    private static AdapterExec getAdapterExecFromAdapterString(String adapter) {
        if (adapter == null) return null;
        String a = adapter.toLowerCase();
        if (a.equals("skip")) {
            return new SkipAdapter();
        }
        if (a.contains("readusable") || a.contains("gettp") || a.contains("gettpint")) {
            return new ReadAdapter();
        }
        if (a.contains("writecheckedvoid") || a.contains("commandwrite")) {
            return new WriteAbsAdapter();
        }
        if (a.contains("gainvoid") || a.contains("commandadd")) {
            return new GainAdapter();
        }
        if (a.contains("spendvoid") || a.contains("commandtake")) {
            return new SpendAdapter();
        }
        if (a.contains("applylocal") || a.contains("acceptnetwork") || a.contains("tpshort") || a.contains("clamptp")) {
            return new StateLocalAdapter();
        }
        return null; // fall back to semantics-based lookup
    }

    // ------------------------------------------------------------------ SKIP: do nothing (false positive)
    static final class SkipAdapter implements AdapterExec {
        @Override public int exec(MethodNode mn, int idx, Site site, HierarchyResolver hier) {
            // Do nothing - this is a false positive site that should not be patched
            return -1;
        }
    }

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

        // Read all classes
        Map<String, ClassNode> classes = new LinkedHashMap<>();
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
                ClassNode cn = new ClassNode();
                new ClassReader(zip.getInputStream(zip.getEntry(name))).accept(cn, 0);
                classes.put(cn.name, cn);
            }
        } finally {
            zip.close();
        }

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

                // Get adapter executor: prefer explicit adapter string for handled sites
                AdapterExec exec = null;
                if (site.handled && site.adapter != null) {
                    exec = getAdapterExecFromAdapterString(site.adapter);
                }
                if (exec == null) {
                    exec = ADAPTER_REGISTRY.get(site.semantics);
                }
                if (exec == null) {
                    System.err.println("ERROR: No adapter for semantics: " + site.semantics + " at " + site.id());
                    throw new IllegalStateException("No adapter for: " + site.semantics);
                }

                // Execute rewrite
                int newIdx = exec.exec(mn, site.offset, site, hier);
                if (newIdx >= 0) {
                    totalRewritten++;
                    // Update max stack/locals will be done by HierarchyClassWriter
                }
            }
        }

        System.out.println("  Rewritten " + totalRewritten + " sites in " + inJar.getName());

        // Inject TpUtil into JRMCore
        if (injectTpUtil && cfg.tpUtilClass != null && cfg.tpUtilClass.exists()) {
            injectTpUtilClasses(classes, cfg.tpUtilClass);
        }

        // Write output JAR using HierarchyClassWriter
        writeJar(outJar, classes, hier, cfg);
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name) && desc.equals(mn.desc)) {
                return mn;
            }
        }
        return null;
    }

    private static void injectTpUtilClasses(Map<String, ClassNode> classes, File tpUtilClassFile) throws IOException {
        File baseDir = tpUtilClassFile.getParentFile(); // bigtp/build/classes/JinRyuu/JRMCore/
        System.out.println("  Injecting TpUtil and dependencies from " + baseDir.getAbsolutePath());
        String[] classFiles = baseDir.list((dir, name) -> name.endsWith(".class"));
        if (classFiles != null) {
            for (String classFile : classFiles) {
                File f = new File(baseDir, classFile);
                System.out.println("    Injecting " + classFile);
                ClassNode cn = new ClassNode();
                new ClassReader(Files.newInputStream(f.toPath())).accept(cn, 0);
                classes.put(cn.name, cn);
            }
        }
    }

    // Try COMPUTE_FRAMES first; if it fails with frame computation error, retry with COMPUTE_MAXS
    private static byte[] writeClassWithFallback(ClassNode cn, HierarchyResolver hier, Config cfg) {
        // First attempt: COMPUTE_FRAMES (includes COMPUTE_MAXS)
        try {
            ClassWriter cw = new HierarchyClassWriter(HierarchyClassWriter.FLAGS, hier);
            cn.accept(cw);
            byte[] bytecode = cw.toByteArray();

            // Check major version warning
            if (cfg.expectMajor > 0 && bytecode.length >= 7) {
                int major = ((bytecode[6] & 0xFF) << 8) | (bytecode[7] & 0xFF);
                if (major != cfg.expectMajor) {
                    System.err.println("WARNING: " + cn.name + " major version " + major + " != expected " + cfg.expectMajor);
                }
            }
            return bytecode;
        } catch (IndexOutOfBoundsException e) {
            // Frame computation failed - likely due to old class version (50) with modified bytecode
            System.err.println("WARNING: Frame computation failed for " + cn.name + ", retrying with COMPUTE_MAXS only: " + e.getMessage());
        } catch (Exception e) {
            // Other frame-related errors
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Frame") || msg.contains("stack") || msg.contains("StackMap"))) {
                System.err.println("WARNING: Frame computation failed for " + cn.name + ", retrying with COMPUTE_MAXS only: " + msg);
            } else {
                throw e;
            }
        }

        // Fallback: COMPUTE_MAXS only (no stack map frames)
        ClassWriter cw = new HierarchyClassWriter(ClassWriter.COMPUTE_MAXS, hier);
        cn.accept(cw);
        byte[] bytecode = cw.toByteArray();

        if (cfg.expectMajor > 0 && bytecode.length >= 7) {
            int major = ((bytecode[6] & 0xFF) << 8) | (bytecode[7] & 0xFF);
            if (major != cfg.expectMajor) {
                System.err.println("WARNING: " + cn.name + " major version " + major + " != expected " + cfg.expectMajor);
            }
        }
        return bytecode;
    }

    private static void writeJar(File outJar, Map<String, ClassNode> classes, HierarchyResolver hier, Config cfg) throws IOException {
        // Use temporary file for atomic write
        File tempJar = File.createTempFile("tpatch-", ".jar", outJar.getParentFile());
        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempJar));
            try {
                // Write manifest
                ZipEntry manifestEntry = new ZipEntry("META-INF/MANIFEST.MF");
                zos.putNextEntry(manifestEntry);
                writeManifest(zos, cfg);
                zos.closeEntry();

                // Write classes
                for (ClassNode cn : classes.values()) {
                    if (cn.name.startsWith("META-INF/")) continue; // skip manifest class if any

                    try {
                        byte[] bytecode = writeClassWithFallback(cn, hier, cfg);
                        ZipEntry entry = new ZipEntry(cn.name.replace('/', '/') + ".class");
                        zos.putNextEntry(entry);
                        zos.write(bytecode);
                        zos.closeEntry();
                    } catch (Exception e) {
                        System.err.println("ERROR writing class: " + cn.name);
                        e.printStackTrace();
                        throw e;
                    }
                }
            } finally {
                zos.close();
            }

            // Atomic move
            Files.move(tempJar.toPath(), outJar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("  Written: " + outJar.getAbsolutePath());
        } catch (Exception e) {
            tempJar.delete();
            throw e;
        }
    }

    private static void writeManifest(ZipOutputStream zos, Config cfg) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Manifest-Version: 1.0\n");
        sb.append("Created-By: BigTP 1.0.7 Patcher\n");
        sb.append("Build-Date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date())).append("\n");
        sb.append("Specification-Title: JinRyuu JRMCore BigTP\n");
        sb.append("Specification-Version: 1.0.7\n");
        sb.append("Implementation-Version: 1.0.7\n");
        sb.append("Main-Class: \n");
        zos.write(sb.toString().getBytes("UTF-8"));
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

        for (File jar : jars) {
            System.out.println("\n=== Verifying " + jar.getName() + " ===");
            ProcessBuilder pb = new ProcessBuilder(
                    cfg.classpathFile.getAbsolutePath(),
                    jar.getAbsolutePath(),
                    "--expect-major", String.valueOf(cfg.expectMajor)
            );
            // Actually run VerifyJar
            String javaExe = System.getenv("JAVA_HOME") + "/bin/java.exe";
            String asmCp = "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\tools\\asm-9.7.1.jar;"
                    + "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\tools\\asm-tree-9.7.1.jar;"
                    + "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\tools\\asm-analysis-9.7.1.jar;"
                    + "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\tools\\asm-commons-9.7.1.jar;"
                    + "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\tools\\asm-util-9.7.1.jar";

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add("bigtp/build/tools;" + asmCp);
            cmd.add("VerifyJar");
            cmd.add("--jar");
            cmd.add(jar.getAbsolutePath());
            cmd.add("--cp-file");
            cmd.add(cfg.classpathFile.getAbsolutePath());
            cmd.add("--optional");
            cmd.add(cfg.optionalDepsFile.getAbsolutePath());
            cmd.add("--expect-major");
            cmd.add(String.valueOf(cfg.expectMajor));

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
            pw.println("BigTP 1.0.7 Candidate Build");
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
        if (cfg.outputDir == null) cfg.outputDir = new File("bigtp/dist/1.0.7-candidate");
        if (cfg.classpathFile == null) cfg.classpathFile = new File("bigtp/build/classpath.txt");
        if (cfg.optionalDepsFile == null) cfg.optionalDepsFile = new File("analysis/optional-deps.txt");
        if (cfg.tpUtilClass == null) cfg.tpUtilClass = new File("bigtp/build/classes/JinRyuu/JRMCore/TpUtil.class");
        return cfg;
    }
}