import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Motor de classificacao e reescrita de sites de TP.
 *
 * Cada site e classificado em uma categoria semantica:
 *  - READ: leitura pura (getTP, getTPInt)
 *  - WRITE_ABS: escrita absoluta (setTP, writeChecked)
 *  - WRITE_DELTA_ADD: ganho (gain, addTP, commandAdd)
 *  - WRITE_DELTA_SUB: gasto (spend, commandTake)
 *  - WRITE_COMPLEX: operacoes que nao sao simples delta (divisao, multiplicacao, etc.)
 *
 * Sites WRITE_COMPLEX exigem adaptadores explicitos por callsite.
 * O rewriter aborta se encontrar um WRITE_COMPLEX sem adaptador registrado.
 *
 * Uso: java SiteRewriter <jar-entrada> <jar-saida> <callsites.csv> [--dry-run]
 */
public final class SiteRewriter {

    // ============================================================ CLASSIFICACAO

    enum Semantica {
        READ,
        WRITE_ABS,
        WRITE_DELTA_ADD,
        WRITE_DELTA_SUB,
        WRITE_COMPLEX,
        STATE_LOCAL_READ,      // JRMCoreH.curTPLong / curTP leitura
        STATE_LOCAL_WRITE,     // JRMCoreH.curTPLong / curTP escrita
        STATE_NETWORK_READ,    // estado vindo da rede (PacHanC)
        UNKNOWN
    }

    static final class Site {
        final String clazz;
        final String method;
        final String desc;
        final int offset;
        final String key;          // "jrmcTpint", "jrmcTplong", "curTP", "curTPLong"
        final String operation;    // e.g. "nbt-read-int(func_74762_e)", "field-read(curTP:I)"
        final String opDesc;
        final String context;
        final Semantica sem;
        final String adapter;      // nome do adaptador ou null
        final boolean handled;

        Site(String c, String m, String d, int o, String k, String op, String od, String ctx, Semantica s, String a, boolean h) {
            clazz = c; method = m; desc = d; offset = o; key = k; operation = op; opDesc = od; context = ctx; sem = s; adapter = a; handled = h;
        }

        String id() { return clazz + "::" + method + "@" + offset; }
        String shortId() { return clazz.split("/")[clazz.split("/").length - 1] + "::" + method + "@" + offset; }
    }

    // ============================================================ REGISTRO DE ADAPTADORES

    // Adaptadores ja implementados em TpUtil (jao de bytecode)
    private static final Map<String, String> ADAPTER_MAP = new LinkedHashMap<>();

    static {
        // READ
        ADAPTER_MAP.put("NBT_READ_LONG", "TpUtil.getTP(NBTTagCompound)");          // getLong + migracao
        ADAPTER_MAP.put("NBT_READ_INT", "TpUtil.getTPInt(NBTTagCompound)");       // getInteger (espelho)
        ADAPTER_MAP.put("PLAYER_READ_LONG", "TpUtil.getTP(EntityPlayer)");        // via JRMCoreH.nbt + getTP
        ADAPTER_MAP.put("PLAYER_READ_INT", "TpUtil.getTPInt(EntityPlayer)");      // via JRMCoreH.nbt + getTPInt

        // WRITE_ABS
        ADAPTER_MAP.put("NBT_WRITE_LONG", "TpUtil.write(NBTTagCompound,long)");   // escrita absoluta
        ADAPTER_MAP.put("NBT_WRITE_CHECKED", "TpUtil.writeCheckedVoid(NBTTagCompound,long)"); // sem excecao
        ADAPTER_MAP.put("PLAYER_WRITE_LONG", "TpUtil.write(EntityPlayer,long)");
        ADAPTER_MAP.put("PLAYER_WRITE_CHECKED", "TpUtil.writeCheckedVoid(EntityPlayer,long)");

        // WRITE_DELTA_ADD
        ADAPTER_MAP.put("NBT_GAIN_LONG", "TpUtil.gainVoid(NBTTagCompound,long)");
        ADAPTER_MAP.put("PLAYER_GAIN_LONG", "TpUtil.gainVoid(EntityPlayer,long)");
        ADAPTER_MAP.put("COMMAND_ADD", "TpUtil.commandAdd(NBTTagCompound,long)");

        // WRITE_DELTA_SUB
        ADAPTER_MAP.put("NBT_SPEND_LONG", "TpUtil.spendVoid(NBTTagCompound,long)");
        ADAPTER_MAP.put("PLAYER_SPEND_LONG", "TpUtil.spendVoid(EntityPlayer,long)");
        ADAPTER_MAP.put("COMMAND_TAKE", "TpUtil.commandTake(NBTTagCompound,long)");

        // STATE LOCAL
        ADAPTER_MAP.put("STATE_APPLY_LOCAL", "TpUtil.applyLocal(long)");          // JRMCoreH.curTPLong + curTP
        ADAPTER_MAP.put("STATE_ACCEPT_NETWORK", "TpUtil.acceptNetwork(long)");    // rede -> local

        // LEGACY MIRROR
        ADAPTER_MAP.put("MIRROR", "TpUtil.mirror(long)");                         // saturate to int
    }

    // Adaptadores explicitos por callsite (para WRITE_COMPLEX e todos os sites)
    // Chave: "ClasseInterna::metodo@offset" (ex: "JinRyuu/JRMCore/JRMCoreH::expPls@56")
    private static final Map<String, String> EXPLICIT_ADAPTERS = new LinkedHashMap<>();

    static {
        // ==================== JRMCoreH ====================
        // expPls: loop de ganho com multiplicacao (IMUL) -> WRITE_COMPLEX mas na verdade e WRITE_ABS (valor computado)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::expPls@56", "TpUtil.readUsable(player)");   // READ jrmcTpint
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::expPls@109", "TpUtil.writeCheckedVoid(player, value)"); // WRITE_ABS valor computado

        // jrmcExp: 4 sites de ganho (READ + WRITE_DELTA_ADD via IADD)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::jrmcExp@206", "TpUtil.readUsable(player)");  // READ site 1
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::jrmcExp@265", "TpUtil.writeCheckedVoid(player, value + gain)"); // WRITE_DELTA_ADD site 1
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::jrmcExp@311", "TpUtil.readUsable(player)");  // READ site 2
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::jrmcExp@359", "TpUtil.writeCheckedVoid(player, value + gain)"); // WRITE_DELTA_ADD site 2

        // tpalgn: 4 sites (2 READ + 2 WRITE_DELTA_ADD)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::tpalgn@31", "TpUtil.readUsable(player)");    // READ site 1
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::tpalgn@49", "TpUtil.writeCheckedVoid(player, value + gain)"); // WRITE_DELTA_ADD site 1
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::tpalgn@235", "TpUtil.readUsable(player)");   // READ site 2
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::tpalgn@253", "TpUtil.writeCheckedVoid(player, value + gain)"); // WRITE_DELTA_ADD site 2

        // Estado local (curTP)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::<clinit>@2638", "TpUtil.applyLocal(0)");    // init curTPLong=0, curTP=0
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreH::resetChar@113", "TpUtil.applyLocal(0)");    // reset curTPLong=0, curTP=0

        // ==================== PacHanS.handleData ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleData@596", "TpUtil.readUsable(nbt)");   // READ NBT
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleData@638", "TpUtil.writeCheckedVoid(nbt, value)"); // WRITE_ABS

        // ==================== PacHanS.handleQuad ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@791", "TpUtil.readUsable(nbt)");   // READ NBT
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@813", "TpUtil.writeCheckedVoid(nbt, value - 1)"); // WRITE_DELTA_SUB(1)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@978", "TpUtil.writeCheckedVoid(nbt, value - 1)"); // WRITE_DELTA_SUB(1)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@1080", "TpUtil.writeCheckedVoid(nbt, value)"); // WRITE_ABS (clamped)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@1086", "TpUtil.writeCheckedVoid(nbt, value)"); // WRITE_ABS (TPlimit)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@1192", "TpUtil.writeCheckedVoid(nbt, value - 1)"); // WRITE_DELTA_SUB(1)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@1294", "TpUtil.writeCheckedVoid(nbt, value)"); // WRITE_ABS (clamped)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleQuad@1300", "TpUtil.writeCheckedVoid(nbt, value)"); // WRITE_ABS (TPlimit2)

        // ==================== PacHanS.handleStats3 ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleStats3@325", "TpUtil.readUsable(nbt)");    // READ NBT
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleStats3@653", "TpUtil.writeCheckedVoid(nbt, value - cost)"); // WRITE_DELTA_SUB(cost)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleStats3@1023", "TpUtil.readUsable(nbt)");   // READ NBT
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleStats3@1202", "TpUtil.writeCheckedVoid(nbt, value - cost)"); // WRITE_DELTA_SUB(cost)

        // ==================== PacHanS.handleUpgrade ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleUpgrade@196", "TpUtil.readUsable(nbt)");    // READ NBT
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleUpgrade@259", "TpUtil.readUsable(nbt)");    // READ NBT (dup check)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleUpgrade@262", "TpUtil.writeCheckedVoid(nbt, value - cost)"); // WRITE_DELTA_SUB(cost)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleUpgrade@258", "TpUtil.writeCheckedVoid(nbt, value - cost)"); // DUP write
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleUpgrade@495", "TpUtil.readUsable(nbt)");    // READ NBT

        // ==================== PacHanS.handleChar ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleChar@417", "TpUtil.readUsable(nbt)");   // READ NBT
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleChar@664", "TpUtil.readUsable(nbt)");   // READ NBT (state 105 check)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleChar@709", "TpUtil.writeCheckedVoid(nbt, value - 20)"); // WRITE_DELTA_SUB(20)

        // ==================== PacHanS.handleTick ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTick@103", "TpUtil.readUsable(nbt)");   // READ NBT

        // ==================== PacHanS.handleTech ====================
        // Sites com IDIV (complex) + IADD -> gain sites com formula
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@431", "TpUtil.readUsable(player)");  // READ player TP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@440", "TpUtil.writeCheckedVoid(player, value + gain)"); // WRITE_DELTA_ADD(gain)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@469", "TpUtil.readUsable(player)");  // READ player TP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@478", "TpUtil.writeCheckedVoid(player, value + gain)"); // WRITE_DELTA_ADD(gain)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@594", "TpUtil.readUsable(player)");  // READ player TP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@609", "TpUtil.writeCheckedVoid(player, value - cost)"); // WRITE_DELTA_SUB(cost)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@728", "TpUtil.readUsable(player)");  // READ player TP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@745", "TpUtil.writeCheckedVoid(player, value - cost)"); // WRITE_DELTA_SUB(cost)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@823", "TpUtil.readUsable(player)");  // READ player TP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanS::handleTech@838", "TpUtil.writeCheckedVoid(player, value - cost)"); // WRITE_DELTA_SUB(cost)

        // ==================== ComJrmctp ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmctp::func_71515_b@506", "TpUtil.readUsable(nbt)");      // READ NBT (take)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmctp::func_71515_b@534", "TpUtil.commandWrite(nbt, value)"); // WRITE_ABS (clamped write)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmctp::func_71515_b@578", "TpUtil.readUsable(nbt)");      // READ NBT (add)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmctp::func_71515_b@624", "TpUtil.commandAdd(nbt, amount)"); // WRITE_DELTA_ADD (add)

        // ==================== ComJrmc ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmc::func_71515_b@320", "TpUtil.getTP(player)");         // READ player TP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmc::func_71515_b@335", "TpUtil.writeCheckedVoid(player, value - cost)"); // WRITE_DELTA_SUB(cost)

        // ==================== ComJrmcCheck ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmcCheck::func_71515_b@221", "TpUtil.getTP(player)");    // READ player TP

        // ==================== ComJrmcRacialSkill ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmcRacialSkill::func_71515_b@535", "TpUtil.writeCheckedVoid(nbt, value - cost)"); // WRITE_DELTA_SUB(cost)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/ComJrmcRacialSkill::canAfford@278", "TpUtil.getTPInt(nbt)");   // READ NBT mirror

        // ==================== JRMCoreComTickH.updatePlayersData ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreComTickH::updatePlayersData@1341", "TpUtil.readUsable(nbt)"); // hasKey check
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreComTickH::updatePlayersData@1354", "TpUtil.writeCheckedVoid(nbt, value)"); // WRITE_ABS (migration jrmcTp->jrmcTpint)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreComTickH::updatePlayersData@1366", "SKIP"); // FALSE POSITIVE: reads tpgbfokt, not TP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreComTickH::updatePlayersData@1512", "TpUtil.readUsable(nbt)");   // READ NBT (gain)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreComTickH::updatePlayersData@1521", "TpUtil.writeCheckedVoid(nbt, value + gain)"); // WRITE_DELTA_ADD(gain)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreComTickH::updatePlayersData@2492", "TpUtil.getTPInt(nbt)");    // READ NBT mirror (display)

        // ==================== PacHanC ====================
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanC::handleData@1011", "TpUtil.applyLocal(value)");  // STATE_LOCAL_WRITE (network->local)
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCorePacHanC::handleStats2@3", "TpUtil.applyLocal(value)");    // STATE_LOCAL_WRITE (packet->local)

        // ==================== GUI Display (JRMCore) ====================
        // curTP:I field reads - handled by generic patchGuiDisplay, converts to curTPLong:J + tpShort/clampTP
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiBars::renderTrainGui@213", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiBars::renderTrainGui@221", "JRMCoreH.clampTP(curTPLong)");  // compare
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiBars::renderTrainGui@243", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiSCM::func_73863_a@1351", "JRMCoreH.tpShort(curTPLong)");  // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiSCM::func_73863_a@1562", "JRMCoreH.clampTP(curTPLong)");  // compare
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@495", "JRMCoreH.clampTP(curTPLong)"); // button enable check
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@20255", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@20727", "JRMCoreH.clampTP(curTPLong)"); // button logic
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@24771", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@31044", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@31091", "JRMCoreH.clampTP(curTPLong)"); // button logic
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@32374", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@32421", "JRMCoreH.clampTP(curTPLong)"); // button logic
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@32575", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@32857", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@33233", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/JRMCore/JRMCoreGuiScreen::func_73863_a@33484", "JRMCoreH.clampTP(curTPLong)"); // button logic

        // ==================== DBC (DragonBlockC) ====================
        // All curTP:I reads for display in DBCTalkGui - handled by generic display rewrite
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::sklLrn@44", "JRMCoreH.clampTP(curTPLong)"); // compare
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::sklLrn@44", "JRMCoreH.clampTP(curTPLong)"); // compare (overload)
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@2159", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@2500", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@2797", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@3146", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@3639", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@3738", "JRMCoreH.clampTP(curTPLong)"); // compare
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@3991", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@4216", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@4748", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@5206", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@5360", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@5586", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@6091", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@6293", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@6530", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@7021", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@7223", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@7454", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@7959", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@8145", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@8370", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@9018", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@9204", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@9425", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@9983", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@10185", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@10413", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@10919", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@11073", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@11298", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@11789", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@11959", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@12190", "JRMCoreH.clampTP(curTPLong)"); // render
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@12681", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@12851", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("JinRyuu/DragonBC/common/Gui/DBCTalkGui::func_73863_a@13082", "JRMCoreH.clampTP(curTPLong)"); // render

        // ==================== NPCDBC ====================
        EXPLICIT_ADAPTERS.put("kamkeel/npcdbc/client/gui/dbc/StatSheetGui::func_73876_c@669", "JRMCoreH.tpShort(curTPLong)"); // display
        EXPLICIT_ADAPTERS.put("kamkeel/npcdbc/client/gui/dbc/StatSheetGui::func_73876_c@920", "JRMCoreH.clampTP(curTPLong)");  // calc
        EXPLICIT_ADAPTERS.put("kamkeel/npcdbc/data/dbcdata/DBCData::saveFromNBT@118", "TpUtil.writeCheckedVoid(nbt, value)"); // WRITE_ABS TP:I -> jrmcTpint
        EXPLICIT_ADAPTERS.put("kamkeel/npcdbc/data/dbcdata/DBCData::loadFromNBT@132", "TpUtil.readUsable(nbt)"); // READ NBT jrmcTpint -> TP:I
    }

    // ============================================================ CLASSIFICADOR

    private static Semantica classify(String clazz, String method, int offset, String key, String operation, String opDesc, String context) {
        // FIELD ACCESS (curTP / curTPLong)
        if (operation.startsWith("field-")) {
            if (operation.contains("READ")) {
                if ("curTPLong".equals(key)) return Semantica.STATE_LOCAL_READ;
                if ("curTP".equals(key)) return Semantica.STATE_LOCAL_READ; // legacy mirror read
            }
            if (operation.contains("WRITE")) {
                if ("curTPLong".equals(key)) return Semantica.STATE_LOCAL_WRITE;
                if ("curTP".equals(key)) return Semantica.STATE_LOCAL_WRITE; // legacy mirror write
            }
        }

        // NBT / PLAYER KEYED ACCESS (jrmcTpint / jrmcTplong)
        if (operation.contains("nbt-read") || operation.contains("jrmc-get-int") || operation.contains("jrmc-get-long")) {
            // Leitura
            if (opDesc.endsWith(")I")) return Semantica.READ;           // int result
            if (opDesc.endsWith(")J")) return Semantica.READ;           // long result
            return Semantica.READ;
        }
        if (operation.contains("nbt-write") || operation.contains("jrmc-set-int") || operation.contains("jrmc-set-long")) {
            // Escrita - precisa analisar o contexto para distinguir ABS vs DELTA
            return classifyWrite(context);
        }

        return Semantica.UNKNOWN;
    }

    private static Semantica classifyWrite(String context) {
        // Padrões de contexto para escrita:
        // - "var12 | ldc\"jrmcTpint\" | var14" -> WRITE_ABS (valor ja calculado)
        // - "var19 | var18 | isub | var11 | ldc\"jrmcTpint\"" -> WRITE_DELTA_SUB (read - cost)
        // - "var5 | ldc\"jrmcTpint\" | var14 | iadd" -> WRITE_DELTA_ADD (read + gain)
        // - "var23 | var13 | ldc\"jrmcTpint\"" (após IADD) -> WRITE_DELTA_ADD
        // - "var22 | var8 | 1 | isub | ldc\"jrmcTpint\"" -> WRITE_DELTA_SUB(1)
        // - "var15 | var13 | isub | var3 | ldc\"jrmcTpint\"" -> WRITE_DELTA_SUB
        // - "idiv | var12 | var3 | ldc\"jrmcTpint\"" -> WRITE_COMPLEX (divisao)
        // - "imul | var18 | var11 | ldc\"jrmcTpint\"" -> WRITE_COMPLEX (multiplicacao)

        String ctx = context.toLowerCase();

        // Detectar operacoes complexas primeiro
        if (ctx.contains("idiv") || ctx.contains("imul") || ctx.contains("ldiv") || ctx.contains("lmul")
                || ctx.contains("irem") || ctx.contains("lrem")) {
            return Semantica.WRITE_COMPLEX;
        }

        // Detectar delta SUB (subtracao)
        if (ctx.contains("isub") || ctx.contains("lsub")) {
            return Semantica.WRITE_DELTA_SUB;
        }

        // Detectar delta ADD (adicao)
        if (ctx.contains("iadd") || ctx.contains("ladd")) {
            return Semantica.WRITE_DELTA_ADD;
        }

        // Escrita absoluta (valor ja esta na pilha, sem aritmetica)
        return Semantica.WRITE_ABS;
    }

    // ============================================================ SCANNER

    private static List<Site> scanJar(File jarFile) throws IOException {
        List<Site> sites = new ArrayList<>();
        ZipFile zip = new ZipFile(jarFile);
        try {
            List<String> names = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.isDirectory() && en.getName().endsWith(".class")) {
                    names.add(en.getName());
                }
            }
            Collections.sort(names);
            for (String name : names) {
                ClassNode cn = new ClassNode();
                new ClassReader(zip.getInputStream(zip.getEntry(name))).accept(cn, 0);
                for (MethodNode mn : cn.methods) {
                    scanMethod(sites, cn.name, mn);
                }
            }
        } finally {
            zip.close();
        }
        return sites;
    }

    private static void scanMethod(List<Site> sites, String clazz, MethodNode mn) {
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];

            // FIELD ACCESS
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                if ("JinRyuu/JRMCore/JRMCoreH".equals(fi.owner)
                        && ("curTP".equals(fi.name) || "curTPLong".equals(fi.name))) {
                    String op = (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC)
                            ? "field-read(" + fi.name + ":" + fi.desc + ")"
                            : "field-write(" + fi.name + ":" + fi.desc + ")";
                    String key = fi.name;
                    Semantica sem = classify(clazz, mn.name, i, key, op, fi.desc, context(insns, i));
                    String adapter = EXPLICIT_ADAPTERS.get(clazz + "::" + mn.name + "@" + i);
                    sites.add(new Site(clazz, mn.name, mn.desc, i, key, op, fi.desc, context(insns, i), sem, adapter, adapter != null));
                }
                continue;
            }

            // METHOD CALL
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode mi = (MethodInsnNode) insn;
            String op = operation(mi);
            if (op == null) continue;
            String key = keyBefore(insns, i);
            if (key == null) continue;

            Semantica sem = classify(clazz, mn.name, i, key, op, mi.desc, context(insns, i));
            String adapter = EXPLICIT_ADAPTERS.get(clazz + "::" + mn.name + "@" + i);
            sites.add(new Site(clazz, mn.name, mn.desc, i, key, op, mi.desc, context(insns, i), sem, adapter, adapter != null));
        }
    }

    private static String operation(MethodInsnNode mi) {
        // NBTTagCompound
        if ("net/minecraft/nbt/NBTTagCompound".equals(mi.owner)) {
            switch (mi.name) {
                case "func_74762_e": return "nbt-read-int(" + mi.name + ")";   // getInteger
                case "func_74763_f": return "nbt-read-long(" + mi.name + ")";  // getLong
                case "func_74768_a": return "nbt-write-int(" + mi.name + ")";  // setInteger
                case "func_74772_a": return "nbt-write-long(" + mi.name + ")"; // setLong
                case "func_74781_a": return "nbt-get-tag(" + mi.name + ")";    // getTag
                case "func_74778_a": return "nbt-set-tag(" + mi.name + ")";    // setTag
                case "func_74764_b": return "nbt-has-key(" + mi.name + ")";    // hasKey
            }
        }
        // JRMCoreH
        if ("JinRyuu/JRMCore/JRMCoreH".equals(mi.owner)) {
            switch (mi.name) {
                case "getInt": return "jrmc-get-int(" + mi.name + ")";
                case "getLong": return "jrmc-get-long(" + mi.name + ")";
                case "setInt": return "jrmc-set-int(" + mi.name + ")";
                case "setLong": return "jrmc-set-long(" + mi.name + ")";
            }
        }
        return null;
    }

    private static String keyBefore(AbstractInsnNode[] insns, int idx) {
        for (int i = Math.max(0, idx - 8); i < idx; i++) {
            if (insns[i] instanceof LdcInsnNode) {
                Object c = ((LdcInsnNode) insns[i]).cst;
                if (c instanceof String) {
                    String s = (String) c;
                    if (s.startsWith("jrmcTp")) return s;
                }
            }
        }
        return null;
    }

    private static String context(AbstractInsnNode[] insns, int idx) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, idx - 10);
        for (int i = start; i < idx; i++) {
            AbstractInsnNode n = insns[i];
            if (n.getOpcode() < 0) continue;
            if (n instanceof LdcInsnNode) {
                sb.append("ldc\"").append(((LdcInsnNode)n).cst).append("\"");
            } else if (n instanceof VarInsnNode) {
                VarInsnNode v = (VarInsnNode)n;
                sb.append(opName(v.getOpcode())).append(" ").append(v.var);
            } else if (n instanceof InsnNode) {
                sb.append(opName(n.getOpcode()));
            } else if (n instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode)n;
                sb.append(f.getOpcode() == Opcodes.GETFIELD || f.getOpcode() == Opcodes.GETSTATIC ? "getfield" : "putfield")
                        .append(":").append(f.name).append(":").append(f.desc);
            } else if (n instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode)n;
                sb.append("call:").append(m.owner).append(".").append(m.name);
            } else {
                sb.append(opName(n.getOpcode()));
            }
            sb.append(" | ");
        }
        if (sb.length() > 3) sb.setLength(sb.length() - 3);
        return sb.toString();
    }

    private static String opName(int op) {
        switch (op) {
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.LLOAD: return "LLOAD";
            case Opcodes.ISTORE: return "ISTORE";
            case Opcodes.LSTORE: return "LSTORE";
            case Opcodes.IADD: return "IADD";
            case Opcodes.LADD: return "LADD";
            case Opcodes.ISUB: return "ISUB";
            case Opcodes.LSUB: return "LSUB";
            case Opcodes.IMUL: return "IMUL";
            case Opcodes.LMUL: return "LMUL";
            case Opcodes.IDIV: return "IDIV";
            case Opcodes.LDIV: return "LDIV";
            case Opcodes.I2L: return "I2L";
            case Opcodes.L2I: return "L2I";
            case Opcodes.ICONST_0: return "ICONST_0";
            case Opcodes.ICONST_1: return "ICONST_1";
            case Opcodes.ICONST_2: return "ICONST_2";
            case Opcodes.ICONST_3: return "ICONST_3";
            case Opcodes.ICONST_4: return "ICONST_4";
            case Opcodes.ICONST_5: return "ICONST_5";
            case Opcodes.BIPUSH: return "BIPUSH";
            case Opcodes.SIPUSH: return "SIPUSH";
            case Opcodes.LDC: return "LDC";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.PUTSTATIC: return "PUTSTATIC";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            case Opcodes.IF_ICMPEQ: return "IF_ICMPEQ";
            case Opcodes.IF_ICMPNE: return "IF_ICMPNE";
            case Opcodes.IF_ICMPLT: return "IF_ICMPLT";
            case Opcodes.IF_ICMPGE: return "IF_ICMPGE";
            case Opcodes.IF_ICMPGT: return "IF_ICMPGT";
            case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
            case Opcodes.IFEQ: return "IFEQ";
            case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFLT: return "IFLT";
            case Opcodes.IFGE: return "IFGE";
            case Opcodes.IFGT: return "IFGT";
            case Opcodes.IFLE: return "IFLE";
            default: return "OP(" + op + ")";
        }
    }

    // ============================================================ RELATORIO

    private static void printReport(List<Site> sites, PrintStream out) {
        // Agrupar por classe/metodo
        Map<String, List<Site>> byMethod = new LinkedHashMap<>();
        for (Site s : sites) {
            String key = s.clazz + "::" + s.method + s.desc;
            byMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        int total = sites.size();
        int handled = 0, unhandled = 0, complex = 0;
        for (Site s : sites) {
            if (s.handled) handled++;
            else if (s.sem == Semantica.WRITE_COMPLEX) complex++;
            else unhandled++;
        }

        out.println("========================================");
        out.println("SITE REWRITER REPORT");
        out.println("========================================");
        out.println("Total sites: " + total);
        out.println("  Handled (explicit adapter): " + handled);
        out.println("  WRITE_COMPLEX (need adapter): " + complex);
        out.println("  Other unhandled: " + unhandled);
        out.println();

        for (Map.Entry<String, List<Site>> e : byMethod.entrySet()) {
            out.println("--- " + e.getKey());
            for (Site s : e.getValue()) {
                String status = s.handled ? "OK" : (s.sem == Semantica.WRITE_COMPLEX ? "COMPLEX" : "UNHANDLED");
                out.printf("  @%-5d %-35s %-10s %-15s %s%n",
                        s.offset, s.operation, s.key, s.sem, status);
                if (s.adapter != null) {
                    out.printf("       ADAPTER: %s%n", s.adapter);
                }
                out.printf("       CTX: %s%n", s.context);
            }
        }

        // Lista de sites COMPLEX sem adaptador
        out.println();
        out.println("=== UNHANDLED COMPLEX SITES ===");
        for (Site s : sites) {
            if (!s.handled && s.sem == Semantica.WRITE_COMPLEX) {
                out.printf("%s@%d  %s  CTX: %s%n", s.clazz, s.offset, s.operation, s.context);
            }
        }

        // Lista de sites UNHANDLED nao complexos
        out.println();
        out.println("=== UNHANDLED SIMPLE SITES ===");
        for (Site s : sites) {
            if (!s.handled && s.sem != Semantica.WRITE_COMPLEX && s.sem != Semantica.UNKNOWN) {
                out.printf("%s@%d  %s  SEM: %s  CTX: %s%n", s.clazz, s.offset, s.operation, s.sem, s.context);
            }
        }
    }

    private static void writeCsv(List<Site> sites, File csvFile) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(csvFile), "UTF-8");
        try {
            w.write("classe,metodo,descritor,offset,chave,operacao,descritor_operacao,contexto,semantica,adaptador,handled\n");
            for (Site s : sites) {
                w.write(csv(s.clazz) + "," + csv(s.method) + "," + csv(s.desc) + ","
                        + s.offset + "," + csv(s.key) + "," + csv(s.operation) + ","
                        + csv(s.opDesc) + "," + csv(s.context) + ","
                        + s.sem + "," + csv(s.adapter != null ? s.adapter : "") + ","
                        + (s.handled ? "true" : "false") + "\n");
            }
        } finally {
            w.close();
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ============================================================ MAIN

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Uso: SiteRewriter <jar-entrada> <jar-saida> <callsites.csv> [--dry-run]");
            System.exit(2);
        }
        File inJar = new File(args[0]);
        File outJar = new File(args[1]);
        File csvFile = new File(args[2]);
        boolean dryRun = args.length > 3 && "--dry-run".equals(args[3]);

        System.out.println("Scanning: " + inJar);
        List<Site> sites = scanJar(inJar);
        System.out.println("Found " + sites.size() + " sites");

        printReport(sites, System.out);
        writeCsv(sites, csvFile);
        System.out.println("CSV written to: " + csvFile);

        if (dryRun) {
            System.out.println("DRY RUN - no JAR written");
            return;
        }

        // TODO: Implement actual rewriting using HierarchyClassWriter
        System.out.println("Rewriting not yet implemented in this version");
    }
}