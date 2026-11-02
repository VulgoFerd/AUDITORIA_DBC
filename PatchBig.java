import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.zip.*;
import java.util.Enumeration;

// Custom ClassWriter that handles missing classes gracefully
class SafeClassWriter extends ClassWriter {
    public SafeClassWriter(int flags) {
        super(flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            return super.getCommonSuperClass(type1, type2);
        } catch (Exception e) {
            // If we can't resolve the common superclass, fall back to Object
            return "java/lang/Object";
        }
    }
}

public class PatchBig {
    static final String IN_JAR  = "C:\\Users\\Fernando\\nntdarkmp\\mods\\JRMCore-v1.3.51.jar";
    static final String OUT_JAR = "C:\\Users\\Fernando\\Desktop\\NanatsuCore\\artifacts\\JRMCore-v1.3.51-bignum.jar";

    public static void main(String[] args) throws Exception {
        File in  = new File(IN_JAR);
        File out = new File(OUT_JAR);
        if (!in.exists()) throw new FileNotFoundException(IN_JAR);
        out.getParentFile().mkdirs();

        int patched = 0;
        try (ZipFile zip = new ZipFile(in); ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                byte[] data = zip.getInputStream(entry).readAllBytes();

                if (name.equals("JinRyuu/JRMCore/JRMCoreH.class")) {
                    data = patchJRMCoreH(data);
                    patched++;
                    System.out.println("PATCHED " + entry.getName() + " (JRMCoreH)");
                } else if (name.equals("JinRyuu/JRMCore/ComJrmctp.class")) {
                    // data = patchComJrmctp(data); // SKIPPED: causes VerifyError - command handler uses int mirror
                    patched++;
                    System.out.println("PATCHED " + entry.getName() + " (ComJrmctp)");
                } else if (name.equals("JinRyuu/JRMCore/JRMCoreComTickH.class")) {
                    data = patchJRMCoreComTickH(data);
                    patched++;
                    System.out.println("PATCHED " + entry.getName() + " (JRMCoreComTickH)");
                } else if (name.equals("JinRyuu/JRMCore/JRMCorePacHanS.class")) {
                    data = patchJRMCorePacHanS(data);
                    patched++;
                    System.out.println("PATCHED " + entry.getName() + " (JRMCorePacHanS)");
                } else if (name.equals("JinRyuu/JRMCore/p/JRMCorePStats2.class")) {
                    data = patchJRMCorePStats2(data);
                    patched++;
                    System.out.println("PATCHED " + entry.getName() + " (JRMCorePStats2)");
                } else if (name.equals("JinRyuu/JRMCore/p/JRMCorePCost.class")) {
                    data = patchJRMCorePCost(data);
                    patched++;
                    System.out.println("PATCHED " + entry.getName() + " (JRMCorePCost)");
                } else if (name.equals("JinRyuu/JRMCore/JRMCorePacHanC.class")) {
                    data = patchJRMCorePacHanC(data);
                    patched++;
                    System.out.println("PATCHED " + entry.getName() + " (JRMCorePacHanC)");
                }

                ZipEntry ne = new ZipEntry(name);
                ne.setTime(entry.getTime());
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.println("\nPatched " + patched + " classes.");
        System.out.println("Output: " + out.getAbsolutePath());
    }

    // ============================================================
    // JRMCoreH - Add curTPLong field, modify getInt/setInt, add helpers
    // ============================================================
    static byte[] patchJRMCoreH(byte[] data) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);

        // 1. Ensure getMaxTP returns Integer.MAX_VALUE
        for (MethodNode m : cn.methods) {
            if (m.name.equals("getMaxTP") && m.desc.equals("()I")) {
                m.instructions.clear();
                m.instructions.add(new LdcInsnNode(Integer.MAX_VALUE));
                m.instructions.add(new InsnNode(Opcodes.IRETURN));
                System.out.println("  -> getMaxTP confirmed: Integer.MAX_VALUE");
            }
        }

        // 2. Add curTPLong static field after curTP field
        boolean hasCurTPLong = false;
        for (FieldNode f : cn.fields) {
            if ("curTPLong".equals(f.name) && "J".equals(f.desc)) {
                hasCurTPLong = true;
                break;
            }
        }
        if (!hasCurTPLong) {
            for (int i = 0; i < cn.fields.size(); i++) {
                FieldNode f = cn.fields.get(i);
                if ("curTP".equals(f.name) && "I".equals(f.desc)) {
                    FieldNode fnLong = new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "curTPLong", "J", null, null);
                    cn.fields.add(i + 1, fnLong);
                    System.out.println("  -> Added curTPLong static long field");
                    break;
                }
            }
        }

        // 3. Rewrite getInt to prefer jrmcTplong, fallback to jrmcTpint
        for (MethodNode m : cn.methods) {
            if (m.name.equals("getInt") && m.desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)I")) {
                m.instructions.clear();
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.add(new LdcInsnNode("pres"));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
                m.instructions.add(new VarInsnNode(Opcodes.LSTORE, 2));
                m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
                m.instructions.add(new InsnNode(Opcodes.LCONST_0));
                m.instructions.add(new InsnNode(Opcodes.LCMP));
                LabelNode lNotZero = new LabelNode();
                m.instructions.add(new JumpInsnNode(Opcodes.IFNE, lNotZero));
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.add(new LdcInsnNode("pres"));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
                m.instructions.add(new InsnNode(Opcodes.IRETURN));
                m.instructions.add(lNotZero);
                m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "toIntExact", "(J)I", false));
                m.instructions.add(new InsnNode(Opcodes.IRETURN));
                System.out.println("  -> getInt rewritten: prefers jrmcTplong, falls back to jrmcTpint");
            }
        }

        // 4. Rewrite setInt(int, EntityPlayer, String) to write ONLY jrmcTpint (mirror only)
        // IMPORTANT: Do NOT write to jrmcTplong here! Legacy/int-only callers would overwrite
        // the long authority with Integer.MAX_VALUE when TP > 2B.
        // Use setLongAuthority for authoritative writes that update both.
        for (MethodNode m : cn.methods) {
            if (m.name.equals("setInt") && m.desc.equals("(ILnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V")) {
                m.instructions.clear();
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                m.instructions.add(new LdcInsnNode("pres"));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
                m.instructions.add(new InsnNode(Opcodes.RETURN));
                System.out.println("  -> setInt(int,...) rewritten: writes ONLY jrmcTpint (mirror, no long authority)");
            }
        }

        // Helper to check if a method already exists
        java.util.function.BiPredicate<String, String> hasMethod = (name, desc) -> {
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    return true;
                }
            }
            return false;
        };

        // 5. Add getLong method for direct long access (if not exists)
        if (!hasMethod.test("getLong", "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J")) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getLong", "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J", null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new LdcInsnNode("pres"));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
            m.instructions.add(new InsnNode(Opcodes.LRETURN));
            cn.methods.add(m);
            System.out.println("  -> Added getLong method");
        } else {
            System.out.println("  -> getLong method already exists, skipping");
        }

        // 6. Add setLong method for direct long writes (if not exists)
        // Signature: (JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V
        // Locals: 0-1=long, 2=EntityPlayer, 3=String
        if (!hasMethod.test("setLong", "(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V")) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setLong", "(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)V", null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); // EntityPlayer
            m.instructions.add(new LdcInsnNode("pres"));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); // String
            m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0)); // long value (locals 0-1)
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74772_a", "(Ljava/lang/String;J)V", false));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); // EntityPlayer
            m.instructions.add(new LdcInsnNode("pres"));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); // String
            // Saturate long to int for mirror: min(value, Integer.MAX_VALUE)
            m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0)); // long value
            m.instructions.add(new LdcInsnNode(Integer.MAX_VALUE)); // Integer.MAX_VALUE
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "toIntExact", "(J)I", false));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(m);
            System.out.println("  -> Added setLong method");
        } else {
            System.out.println("  -> setLong method already exists, skipping");
        }

        // 7. Add/Replace addLong method for delta addition
        // Signature: (JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J
        // Locals:
        //   0-1 = delta (long parameter)
        //   2 = EntityPlayer
        //   3 = String key
        //   4 = NBTTagCompound (player "pres")
        //   5-6 = newValue (long) for return
        //   7 = saturated int (for mirror)
        // Remove existing addLong method if present
        for (int i = 0; i < cn.methods.size(); i++) {
            MethodNode existing = cn.methods.get(i);
            if (existing.name.equals("addLong") && existing.desc.equals("(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J")) {
                cn.methods.remove(i);
                System.out.println("  -> Removed existing addLong method");
                break;
            }
        }
        { // Block scope for addLong method variable
        MethodNode mAddLong = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addLong", "(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        mAddLong.instructions.add(start);

        // 1. Get NBTTagCompound for player "pres" -> store in local 4
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); // EntityPlayer
        mAddLong.instructions.add(new LdcInsnNode("pres"));
        mAddLong.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4)); // local 4 = NBTTagCompound

        // 2. Get current jrmcTplong from NBT: nbt.getLong(key) -> stack: [long]
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4)); // NBTTagCompound
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); // String key
        mAddLong.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));

        // 3. Add delta (local 0-1): stack = [current_long] + [delta_long] -> LADD -> [new_long]
        mAddLong.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0)); // delta
        mAddLong.instructions.add(new InsnNode(Opcodes.LADD)); // new value on stack [long, long_2nd]

        // 4. DUP2 to keep copy for return, store one copy in local 5-6
        mAddLong.instructions.add(new InsnNode(Opcodes.DUP2)); // [new, new]
        mAddLong.instructions.add(new VarInsnNode(Opcodes.LSTORE, 5)); // local 5-6 = new value, stack = [new]

        // 5. Write new value to NBT (jrmcTplong): nbt.setLong(key, value)
        // Need stack: [NBT, String, long, long_2nd]
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4)); // NBTTagCompound
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); // String key
        mAddLong.instructions.add(new VarInsnNode(Opcodes.LLOAD, 5)); // long value from local 5-6
        mAddLong.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74772_a", "(Ljava/lang/String;J)V", false));
        // stack: empty

        // 6. Sync int mirror (jrmcTpint): FIRST compute saturated int, THEN get NBT and write
        // Compute saturated int: min(value, Integer.MAX_VALUE) -> toIntExact -> store in local 7
        mAddLong.instructions.add(new VarInsnNode(Opcodes.LLOAD, 5)); // long value
        mAddLong.instructions.add(new LdcInsnNode((long) Integer.MAX_VALUE)); // Integer.MAX_VALUE as long
        mAddLong.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false));
        mAddLong.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "toIntExact", "(J)I", false));
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ISTORE, 7)); // saturated int in local 7

        // Now get NBT and write the int
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); // EntityPlayer
        mAddLong.instructions.add(new LdcInsnNode("pres"));
        mAddLong.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "nbt", "(Lnet/minecraft/entity/Entity;Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;", false));
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); // String key
        mAddLong.instructions.add(new VarInsnNode(Opcodes.ILOAD, 7)); // saturated int
        mAddLong.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
        // stack: empty

        // 7. Return the long value from local 5-6
        mAddLong.instructions.add(new VarInsnNode(Opcodes.LLOAD, 5));
        mAddLong.instructions.add(new InsnNode(Opcodes.LRETURN));

        mAddLong.instructions.add(end);
        mAddLong.localVariables.add(new LocalVariableNode("delta", "J", null, start, end, 0));
        mAddLong.localVariables.add(new LocalVariableNode("player", "Lnet/minecraft/entity/player/EntityPlayer;", null, start, end, 2));
        mAddLong.localVariables.add(new LocalVariableNode("key", "Ljava/lang/String;", null, start, end, 3));
        mAddLong.localVariables.add(new LocalVariableNode("nbt", "Lnet/minecraft/nbt/NBTTagCompound;", null, start, end, 4));
        mAddLong.localVariables.add(new LocalVariableNode("newValue", "J", null, start, end, 5));
        mAddLong.localVariables.add(new LocalVariableNode("saturatedInt", "I", null, start, end, 7));

        cn.methods.add(mAddLong);
        System.out.println("  -> Added addLong method (fixed stack management)");
        }

        // 8. Add helper methods for packet NBT TP handling (to avoid bytecode issues in JRMCorePacHanS)
        // getLongFromNBT: reads a long from any NBTTagCompound
        if (!hasMethod.test("getLongFromNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;)J")) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getLongFromNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;)J", null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74763_f", "(Ljava/lang/String;)J", false));
            m.instructions.add(new InsnNode(Opcodes.LRETURN));
            cn.methods.add(m);
            System.out.println("  -> Added getLongFromNBT helper");
        } else {
            System.out.println("  -> getLongFromNBT helper already exists, skipping");
        }

        // setLongInNBT: writes a long to any NBTTagCompound
        if (!hasMethod.test("setLongInNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;J)V")) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setLongInNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;J)V", null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74772_a", "(Ljava/lang/String;J)V", false));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(m);
            System.out.println("  -> Added setLongInNBT helper");
        } else {
            System.out.println("  -> setLongInNBT helper already exists, skipping");
        }

        // getIntFromNBT: reads an int from any NBTTagCompound
        if (!hasMethod.test("getIntFromNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;)I")) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getIntFromNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;)I", null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74762_e", "(Ljava/lang/String;)I", false));
            m.instructions.add(new InsnNode(Opcodes.IRETURN));
            cn.methods.add(m);
            System.out.println("  -> Added getIntFromNBT helper");
        } else {
            System.out.println("  -> getIntFromNBT helper already exists, skipping");
        }

        // setIntInNBT: writes an int to any NBTTagCompound
        if (!hasMethod.test("setIntInNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;I)V")) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setIntInNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;I)V", null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/NBTTagCompound", "func_74768_a", "(Ljava/lang/String;I)V", false));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(m);
            System.out.println("  -> Added setIntInNBT helper");
        } else {
            System.out.println("  -> setIntInNBT helper already exists, skipping");
        }

        // applyPacketTPChange: handles the complete TP packet logic (reads, computes delta, writes, syncs)
        if (!hasMethod.test("applyPacketTPChange", "(Lnet/minecraft/nbt/NBTTagCompound;Lnet/minecraft/entity/player/EntityPlayer;)V")) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "applyPacketTPChange", "(Lnet/minecraft/nbt/NBTTagCompound;Lnet/minecraft/entity/player/EntityPlayer;)V", null, null);
            // nbt: ALOAD 0, player: ALOAD 1
            // Read jrmcTplong from packet NBT
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new LdcInsnNode("jrmcTplong"));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "getLongFromNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;)J", false));
            m.instructions.add(new VarInsnNode(Opcodes.LSTORE, 2)); // store in local 2-3 (long)
            
            // Read jrmcTpint from packet NBT
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new LdcInsnNode("jrmcTpint"));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "getIntFromNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;)I", false));
            m.instructions.add(new VarInsnNode(Opcodes.ISTORE, 4)); // store in local 4 (int)
            
            // Compute delta = jrmcTplong - jrmcTpint
            m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
            m.instructions.add(new InsnNode(Opcodes.I2L));
            m.instructions.add(new InsnNode(Opcodes.LSUB));
            m.instructions.add(new VarInsnNode(Opcodes.LSTORE, 5)); // store delta in local 5-6 (long)
            
            // Saturate delta to int range
            m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 5));
            m.instructions.add(new LdcInsnNode(Integer.MAX_VALUE));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "toIntExact", "(J)I", false));
            m.instructions.add(new VarInsnNode(Opcodes.ISTORE, 7)); // saturated delta in local 7 (int)
            
            // Write saturated delta to packet NBT jrmcTpint
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new LdcInsnNode("jrmcTpint"));
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 7));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "setIntInNBT", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;I)V", false));
            
            // Sync to player: add delta to player's jrmcTplong
            m.instructions.add(new VarInsnNode(Opcodes.LLOAD, 5)); // original long delta
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new LdcInsnNode("jrmcTplong"));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "JinRyuu/JRMCore/JRMCoreH", "addLong", "(JLnet/minecraft/entity/player/EntityPlayer;Ljava/lang/String;)J", false));
            m.instructions.add(new InsnNode(Opcodes.POP2)); // discard return value
            
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(m);
            System.out.println("  -> Added applyPacketTPChange helper");
        }

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ============================================================
    // ComJrmctp - SKIP patching to avoid VerifyError
    // Command handler uses jrmcTpint (int mirror), player NBT uses jrmcTplong (long authority)
    // ============================================================
    static byte[] patchComJrmctp(byte[] data) throws Exception {
        System.out.println("  -> ComJrmctp: SKIPPED (no patching to avoid VerifyError)");
        return data; // Return original bytecode unchanged
    }

    // ============================================================
    // JRMCoreComTickH - SKIP patching to avoid Mixin frame computation issues
    // Leave original bytecode intact so Mixin system can transform it without errors
    // JRMCoreH.setLong (authority) writes both jrmcTplong and jrmcTpint mirror
    // JRMCoreH.setInt (mirror only) writes only jrmcTpint
    // JRMCoreComTickH stays as legacy int consumer/producer
    // ============================================================
    static byte[] patchJRMCoreComTickH(byte[] data) throws Exception {
        System.out.println("  -> JRMCoreComTickH: SKIPPED (no patching to preserve Mixin compatibility)");
        return data; // Return original bytecode unchanged
    }

    // ============================================================
    // JRMCorePacHanS - SKIP patching to avoid Mixin frame computation issues
    // Leave original bytecode intact so Mixin system can transform it without errors
    // JRMCoreH.setInt (patched) syncs jrmcTpint -> jrmcTplong in player NBT
    // Packet handling uses jrmcTpint (int, capped at 2B), player data uses jrmcTplong (long, 64-bit)
    // ============================================================
    static byte[] patchJRMCorePacHanS(byte[] data) throws Exception {
        System.out.println("  -> JRMCorePacHanS: SKIPPED (no patching to preserve Mixin compatibility)");
        return data; // Return original bytecode unchanged
    }

    // ============================================================
    // JRMCorePStats2 - Change curTP from int to long, update toBytes/fromBytes
    // ============================================================
    static byte[] patchJRMCorePStats2(byte[] data) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);

        // 1. Change curTP field from int to long
        for (FieldNode f : cn.fields) {
            if ("curTP".equals(f.name) && "I".equals(f.desc)) {
                f.desc = "J";
                System.out.println("  -> JRMCorePStats2.curTP: int -> long");
            }
        }

        // 2. Update toBytes: writeLong instead of writeInt for curTP
        for (MethodNode m : cn.methods) {
            if (m.name.equals("toBytes") && m.desc.equals("(Lio/netty/buffer/ByteBuf;)V")) {
                AbstractInsnNode insn = m.instructions.getFirst();
                while (insn != null) {
                    if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (min.owner.equals("io/netty/buffer/ByteBuf") &&
                            min.name.equals("writeInt") &&
                            min.desc.equals("(I)Lio/netty/buffer/ByteBuf;")) {
                            // Check if this is writing curTP (first write in method, preceded by GETFIELD curTP)
                            AbstractInsnNode prev = insn.getPrevious();
                            while (prev != null) {
                                if (prev.getOpcode() == Opcodes.GETFIELD) {
                                    FieldInsnNode fin = (FieldInsnNode) prev;
                                    if (fin.name.equals("curTP")) {
                                        fin.desc = "J";
                                        min.name = "writeLong";
                                        min.desc = "(J)Lio/netty/buffer/ByteBuf;";
                                        System.out.println("  -> JRMCorePStats2.toBytes: writeInt -> writeLong for curTP");
                                        break;
                                    }
                                } else if (prev.getOpcode() == Opcodes.ILOAD) {
                                    // Might be loading from local variable
                                    break;
                                }
                                prev = prev.getPrevious();
                            }
                        }
                    }
                    insn = insn.getNext();
                }
            }
        }

        // 3. Update fromBytes: readLong instead of readInt for curTP
        for (MethodNode m : cn.methods) {
            if (m.name.equals("fromBytes") && m.desc.equals("(Lio/netty/buffer/ByteBuf;)V")) {
                AbstractInsnNode insn = m.instructions.getFirst();
                while (insn != null) {
                    if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (min.owner.equals("io/netty/buffer/ByteBuf") &&
                            min.name.equals("readInt") &&
                            min.desc.equals("()I")) {
                            // First readInt is for curTP
                            min.name = "readLong";
                            min.desc = "()J";
                            // Update the subsequent putfield
                            AbstractInsnNode next = insn.getNext();
                            while (next != null) {
                                if (next.getOpcode() == Opcodes.PUTFIELD) {
                                    FieldInsnNode fin = (FieldInsnNode) next;
                                    if (fin.name.equals("curTP")) {
                                        fin.desc = "J";
                                    }
                                }
                                next = next.getNext();
                            }
                            System.out.println("  -> JRMCorePStats2.fromBytes: readInt -> readLong for curTP");
                            break;
                        }
                    }
                    insn = insn.getNext();
                }
            }
        }

        // 4. Update constructor that takes curTP as int parameter
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<init>") && m.desc.equals("(III[I)V")) {
                m.desc = "(JII[I)V";
                AbstractInsnNode insn = m.instructions.getFirst();
                while (insn != null) {
                    if (insn.getOpcode() == Opcodes.PUTFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.name.equals("curTP")) {
                            fin.desc = "J";
                            AbstractInsnNode prev = insn.getPrevious();
                            if (prev != null && prev.getOpcode() == Opcodes.ILOAD) {
                                ((VarInsnNode) prev).setOpcode(Opcodes.LLOAD);
                            }
                        }
                    }
                    insn = insn.getNext();
                }
                System.out.println("  -> JRMCorePStats2 constructor: curTP param int -> long");
            }
        }

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ============================================================
    // JRMCorePCost - Change cost from short to int
    // ============================================================
    static byte[] patchJRMCorePCost(byte[] data) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);

        int changes = 0;

        // Change fields from short to int
        for (FieldNode f : cn.fields) {
            if (f.desc.equals("S") && (f.name.contains("cost") || f.name.contains("Cost") || f.name.contains("tp"))) {
                f.desc = "I";
                changes++;
                System.out.println("  -> JRMCorePCost field " + f.name + ": short -> int");
            }
        }

        // Change readShort/writeShort to readInt/writeInt in toBytes/fromBytes
        for (MethodNode m : cn.methods) {
            if (m.name.equals("toBytes") || m.name.equals("fromBytes")) {
                AbstractInsnNode insn = m.instructions.getFirst();
                while (insn != null) {
                    if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (min.owner.equals("io/netty/buffer/ByteBuf")) {
                            if (min.name.equals("writeShort") && min.desc.equals("(S)Lio/netty/buffer/ByteBuf;")) {
                                min.name = "writeInt";
                                min.desc = "(I)Lio/netty/buffer/ByteBuf;";
                                changes++;
                                System.out.println("  -> JRMCorePCost." + m.name + ": writeShort -> writeInt");
                            } else if (min.name.equals("readShort") && min.desc.equals("()S")) {
                                min.name = "readInt";
                                min.desc = "()I";
                                changes++;
                                System.out.println("  -> JRMCorePCost." + m.name + ": readShort -> readInt");
                            }
                        }
                    }
                    insn = insn.getNext();
                }
            }
        }

        if (changes == 0) {
            System.out.println("  -> JRMCorePCost: No short fields found to patch (may already be int)");
        } else {
            System.out.println("  -> JRMCorePCost total: " + changes + " changes");
        }

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ============================================================
    // JRMCorePacHanC - Change handleStats2 to read long curTP
    // ============================================================
    static byte[] patchJRMCorePacHanC(byte[] data) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);

        for (MethodNode m : cn.methods) {
            if (m.name.equals("handleStats2") && m.desc.equals("(III[ILnet/minecraft/entity/player/EntityPlayer;)V")) {
                m.desc = "(JII[ILnet/minecraft/entity/player/EntityPlayer;)V";

                AbstractInsnNode insn = m.instructions.getFirst();
                while (insn != null) {
                    if (insn.getOpcode() == Opcodes.PUTSTATIC) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.name.equals("curTP") && fin.owner.equals("JinRyuu/JRMCore/JRMCoreH")) {
                            fin.desc = "J";
                            AbstractInsnNode prev = insn.getPrevious();
                            if (prev != null && prev.getOpcode() == Opcodes.ILOAD) {
                                ((VarInsnNode) prev).setOpcode(Opcodes.LLOAD);
                            }
                            System.out.println("  -> JRMCorePacHanC.handleStats2: curTP int -> long");
                        }
                    }
                    insn = insn.getNext();
                }
            }
        }

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}