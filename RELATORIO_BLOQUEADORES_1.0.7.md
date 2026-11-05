# BIG TP 1.0.7 — PRIMEIRA ENTREGA: BLOQUEADORES CONFIRMADOS

**Repositório:** `VulgoFerd/AUDITORIA_DBC`
**Commit-base analisado:** `284506a7241f6b72a6b1b67db41069645bec29e5`
**Data:** 2026-11-05
**Status da entrega 1.0.6:** **REPROVADA**
**Status desta entrega:** **BLOQUEADA** (nenhum artefato novo será gerado até revisão)

> Este documento atende ao item 13 da especificação 1.0.7: lista de bloqueadores,
> evidências, plano de correção, testes, dependências e riscos.
> Os três JARs de `284506a` permanecem preservados como evidência histórica.

---

## 0. COMO RETOMAR (handoff)

### Fontes preservadas neste repo
- `tools_src/` — cópia integral dos fontes que geraram os JARs de 1.0.6
  (`TpPatch.java`, `TpUtil.java`, `VerifyJar.java`, `ScanSites.java`, `JarHierarchy.java`, etc.)
- `AUDITORIA_PRE_IMPLEMENTACAO_1.0.6.md` — auditoria original

### Dependências reais encontradas na máquina
| Dependência | Caminho |
|---|---|
| Minecraft 1.7.10 recompilado (nomes MCP) | `C:\Users\Fernando\Desktop\OpenKeys\build\rfg\recompiled_minecraft-1.7.10.jar` |
| Forge 1.7.10-10.13.4.1614 (universal) | `C:\Users\Fernando\AppData\Roaming\.minecraft\libraries\net\minecraftforge\forge\1.7.10-10.13.4.1614-1.7.10\forge-1.7.10-10.13.4.1614-1.7.10.jar` |
| ASM 9.7.1 | `asm-9.7.1.jar`, `asm-tree`, `asm-analysis`, `asm-commons`, `asm-util` (em `tools/`) |
| Baseline JRMCore | `AUDITORIA_DBC/JRMCore-v1.3.51-original.jar` |
| Baseline DBC | `AUDITORIA_DBC/DragonBlockC-v1.4.85.jar` |
| Baseline NPCDBC | `AUDITORIA_DBC/npcdbc-1.2.1-hotfix1.jar` |

### Reproduzir a análise atual
```bat
cd tools
javac -source 1.8 -target 1.8 -cp ".;asm-9.7.1.jar;asm-tree-9.7.1.jar;asm-analysis-9.7.1.jar;asm-commons-9.7.1.jar;asm-util-9.7.1.jar" ScanSites.java VerifyJar.java JarHierarchy.java TpPatch.java TpUtil.java
java  -cp ".;asm-9.7.1.jar;asm-tree-9.7.1.jar;asm-analysis-9.7.1.jar;asm-commons-9.7.1.jar;asm-util-9.7.1.jar" ScanSites ..\AUDITORIA_DBC\JRMCore-v1.3.51-original.jar
```

### Assinaturas reais de NBT (confirmadas via `javap` no jar MCP)
```
net.minecraft.nbt.NBTTagCompound
  public boolean hasKey(String)
  public boolean hasKey(String, int)
  public NBTBase getTag(String)
  public int  getInteger(String)
  public long getLong(String)
  public void setInteger(String, int)
  public void setLong(String, long)
net.minecraft.nbt.NBTBase
  public abstract byte getId()   // TAG_Int = 3, TAG_Long = 4
```
Bytecode do mod usa nomes SRG (runtime 1.7.10): `func_74764_b` (hasKey),
`func_74781_a` (getTag), `func_74762_e` (getInteger), `func_74763_f` (getLong),
`func_74768_a` (setInteger), `func_74772_a` (setLong).

---

## 1. BLOQUEADORES CONFIRMADOS

### B1 — `TpUtil.getTP()` não valida o tipo da tag (CRÍTICO)
**Arquivo:** `tools_src/TpUtil.java:18-22`
```java
public static long getTP(NBTTagCompound tag) {
    if (tag == null) return 0L;
    if (tag.func_74764_b(KEY_LONG)) return tag.func_74763_f(KEY_LONG); // só presença
    return (long) tag.func_74762_e(KEY_INT);
}
```
**Problema:** presença da chave não prova o tipo. `NBTTagCompound.getLong()` em 1.7.10
retorna `0` quando a tag existe com tipo diferente (ex.: `jrmcTplong` como `TAG_String`).
Resultado: saldo lido como `0` sem erro → perda silenciosa de save.
**Correção exigida:** usar `getTag()` + `NBTBase.getId()`; aceitar `4` (LONG) e migrar `3` (INT);
tipo incorreto deve ser rejeitado explicitamente (log + não sobrescrever) ou tratado por política documentada.

---

### B2 — `TpUtil` altera estado global do cliente em escrita de NBT (CRÍTICO)
**Arquivo:** `tools_src/TpUtil.java:43-54` e `103-111`
```java
public static void setTP(NBTTagCompound tag, long v) {
    ...
    JRMCoreH.curTPLong = v;   // estado global
    JRMCoreH.curTP = clamp(v); // estado global
}
public static void writeTP(NBTTagCompound tag, long v) { ... mesmos writes globais ... }
```
**Problema:** gravar NBT de um jogador passa a sobrescrever o estado estático global.
Em servidor com dois jogadores, o último NBT gravado “vence” o `curTP` global; em
cliente, qualquer persistência de NPC/outro jogador corrompe a GUI local.
**Correção exigida:** separar explicitamente:
1. `TpNbt.read(tag)` / `TpNbt.write(tag, v)` — sem tocar em estáticos;
2. `ClientState.apply(v)` — única operação que escreve `curTPLong`/`curTP`;
3. `NetworkState.accept(v)` — aplica Estado recebido da rede (chama ClientState).

---

### B3 — `setMirror()` usa semântica de delta como regra universal (CRÍTICO)
**Arquivo:** `tools_src/TpUtil.java:79-97`
```java
public static void setMirror(NBTTagCompound tag, int v) {
    long cur = getTP(tag);
    long delta = (long)v - clamp(cur);
    setTP(tag, addTPSafe(cur, delta));
}
```
**Problema:** escritas legadas têm semânticas diferentes (absoluta, delta, gasto, ganho,
espelho). Aplicar delta em todas transforma escrita absoluta legada em corrupção quando
`cur > INT_MAX`. Exemplo observado em `jrmcExp`:
`getTPInt` (satura em 2147483647) → soma → `setTPMirror` (delta). O valor real acima de
INT_MAX é perdido.
**Correção exigida:** classificar **cada callsite** (ver seção 2) e gerar adaptador específico
(`writeAbsolute`, `addDelta`, `spend`, `gain`, `mirrorOnly`).

---

### B4 — Handlers misturam leitura LONG com escrita INT (CRÍTICO)
**Evidência no JAR patched (`ScanSites`):**
```
JRMCoreComTickH.updatePlayersData @1510  getTPTotal(Object)J      (LÊ LONG)
JRMCoreComTickH.updatePlayersData @1519  setTPMirrorNbt(Object;I) (GRAVA INT/delta)
JRMCorePacHanS.handleData        @595    getTPTotal(Object)J      (LÊ LONG)
JRMCorePacHanS.handleData        @637    setTPMirrorNbt(Object;I) (GRAVA INT/delta)
JRMCorePacHanS.handleQuad        ...     setTPMirrorNbt(Object;I) (só INT)
JRMCorePacHanS.handleStats3      ...     getTPInt + setTPMirrorNbt
JRMCorePacHanS.handleUpgrade     ...     getTPInt + setTPMirrorNbt
```
**Problema:** o caminho real de compra/treino continua em INT. Acima de 2.147.483.647 o
saldo é truncado/saturado antes da escrita.
**Correção exigida:** esses métodos precisam operar em `long` de ponta a ponta
(`getTPTotal`/`setTPTotal`/`spend`/`gain`), com aritmética `LADD/LSUB/LCMP`.

---

### B5 — DESCRIPTOR MISMATCH em `Stats2` → `NoSuchMethodError` em runtime (CRÍTICO)
**Evidência no JAR patched:**
```
DECLARAÇÃO: JinRyuu/JRMCore/JRMCoreH.Stats2(Lnet/minecraft/entity/player/EntityPlayer;JIB[I)V
CHAMADAS  : JRMCorePacHanS.handleUpgrade @499 Stats2(...;IIB[I)V   <-- int
            JRMCorePacHanS.handleChar    @424 Stats2(...;IIB[I)V   <-- int
            JRMCorePacHanS.handleTick    @110 Stats2(...;IIB[I)V   <-- int
```
**Problema:** o patcher mudou a assinatura de `JRMCoreH.Stats2` para `long`, mas não
atualizou os 3 chamadores em `PacHanS`. Em runtime: `NoSuchMethodError`.
O `VerifyJar` atual **não detecta** (não resolve dono/descritor de métodos).
**Correção exigida:** patch dos chamadores + verificação de resolução de método no verificador.

---

### B6 — `ComJrmctp` não foi patchado (CRÍTICO para `/jrmctp`)
**Evidência no JAR patched (`ScanSites`): 4 sites `jrmcTpint` intactos**
```
ComJrmctp.func_71515_b @505 LDC jrmcTpint -> getInteger -> ISTORE 13
ComJrmctp.func_71515_b @532 LDC jrmcTpint -> ILOAD 14 -> setInteger
ComJrmctp.func_71515_b @577 LDC jrmcTpint -> getInteger -> ISTORE 13
ComJrmctp.func_71515_b @622 LDC jrmcTpint -> ILOAD 16 -> setInteger
```
**Problema:** comando admin continua INT (teto 2B), contrariando item 5 da especificação.
**Causa raiz:** `computeAllFrames` estoura `ArrayIndexOutOfBoundsException: -1`, var 13
reutilizado como referência de objeto e como TP int.
**Correção exigida:** reescrever o método ou isolar a transformação; **falha deve abortar o build**
(nunca cair para o original).

---

### B7 — Fallbacks silenciosos no `TpPatch` (CRÍTICO de processo)
**Arquivo:** `tools_src/TpPatch.java:140-147` e `199-211`
```java
} else if (name.equals(ComJrmctp + ".class")) {
    try { patched = patchComJrmctp(data); }
    catch (Exception e) {
        System.err.println("WARNING: ComJrmctp patching failed, using original");
        patched = data;             // <- fallback silencioso
    }
}
...
static void injectTpUtil(...) {
    if (!tpUtilClass.exists()) {
        System.out.println("WARNING: TpUtil.class not found"); return; // <- fallback silencioso
    }
}
```
**Correção exigida:** qualquer falha/ausência → exceção → build abortado; JAR só publicado
após todas as verificações (gravação transacional em arquivo temporário + move).

---

### B8 — Hierarquia ASM incorreta (CRÍTICO para frames)
**Arquivos:** `tools_src/JarHierarchy.java:31-36`, `tools_src/TpPatch.java:259-277`
```java
Set<String> parents = new HashSet<>();
if (cn.superName != null && !cn.superName.equals("java/lang/Object")) parents.add(cn.superName);
if (cn.interfaces != null) parents.addAll(cn.interfaces);   // mistura superclasse + interfaces
...
for (String p : parents) { if (superClass == null) superClass = p; } // pega "primeiro" de um Set
return "java/lang/Object"; // fallback silencioso
```
**Problemas:** (a) `Set` não tem ordem → superclasse escolhida de forma arbitrária;
(b) superclasse e interfaces no mesmo conjunto; (c) arrays não tratados; (d) classe
desconhecida cai em `Object` silenciosamente.
**Correção exigida:** modelo `{superName, interfaces[]}`; resolução correta de arrays;
fonte completa (JRMCore + DBC + NPCDBC + Minecraft recompilado + Forge); classe ausente → abortar.

---

### B9 — Verificadores com falso positivo conhecido (CRÍTICO de evidência)
**Arquivo:** `tools_src/VerifyJar.java:225-230`
```java
ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
    protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
};
```
**Problemas:**
- `getCommonSuperClass` sempre `Object` → frames “válidos” artificialmente;
- não usa `CheckClassAdapter`/`SimpleVerifier` (não valida tipos de operandos vs descritores);
- só acusa `curTP:I` em **leitura** (`GETSTATIC`) quando `--expect-long-curTP`; ignora escrita;
- não resolve dono/descritor de métodos (por isso B5 passou);
- não detecta entradas ZIP duplicadas, classes obrigatórias ausentes, dependências não resolvidas.
**Correção exigida:** verificador com classpath real, resolução de membros, `CheckClassAdapter`
(ou `Analyzer` + `SimpleVerifier`), checagem de ZIP e de classes obrigatórias.

---

### B10 — Build não reproduzível
**Evidências:**
- `tools_src/compile_all_java8.bat` e `compile_all.bat` fixam `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\...` e `C:\Users\Fernando\Desktop\NanatsuCore\tools`;
- `TpPatch` lê `hierarchy.bin` pré-gerado (`loadHierarchy`) sem regenerar;
- `hierarchy.bin` foi gerado só com JRMCore + NPCDBC (849 classes), **sem** DBC nem Minecraft/Forge;
- fontes não estavam no repositório até esta entrega.
**Correção exigida:** scripts relativos, JDK por `JAVA_HOME`, geração automática de hierarquia a
partir do classpath completo, saída em diretório separado, build a partir de clone limpo.

---

### B11 — Ausência de testes automatizados
Nenhum teste existe para: 0, 1, `INT_MAX`, `INT_MAX+1`, 3e9, 1e10, 1e12, `Long.MAX_VALUE`,
overflow, gasto parcial/exato/excessivo, migração INT→LONG, tipo incorreto, migração repetida,
dois jogadores, serialização, `/jrmctp`, logout/login, NPCDBC.

### B12 — Hashes e documentação desatualizados
Não existe `SHA256SUMS.txt`, `docs/INSTALACAO.md`, changelog nem relatório de testes com
hashes dos artefatos finais. `AUDITORIA_PRE_IMPLEMENTACAO_1.0.6.md` descreve o estado pré-implementação.

---

## 2. TABELA DE CALLSITES LEGADOS (`jrmcTpint`) — CLASSIFICAÇÃO PENDENTE

> Classificação inicial por leitura de bytecode. A coluna **Semântica** precisa ser
> confirmada no código-fonte/bytecode completo antes de gerar adaptadores.

| Classe | Método | Operação | Semântica provável | Adaptador proposto |
|---|---|---|---|---|
| JRMCoreH | `expPls` | getInt → setInt | ganho (delta) | `gain(player, long)` |
| JRMCoreH | `jrmcExp` | getInt ×2 → setInt ×2 | ganho (delta) | `gain(player, long)` |
| JRMCoreH | `tpalgn` | getInt → setInt | gasto/ajuste | revisar fluxo; `spend` ou `writeAbsolute` |
| JRMCoreH | `getInt/setInt` | NBT direto | leitura/escrita genérica | `TpNbt.read/write` |
| JRMCoreH | `setLong` | removeu `L2I;I2L` | absoluta | `TpNbt.write` |
| JRMCoreComTickH | `updatePlayersData` @1340/1352 | migração SHORT→INT | migração | `migrate` |
| JRMCoreComTickH | `updatePlayersData` @1511/1519 | getInt/setInt | espelho/persistência | `TpNbt.read` + `TpNbt.write` |
| JRMCoreComTickH | `updatePlayersData` @2491 | getInt display | leitura | `TpNbt.read` |
| JRMCorePacHanS | `handleData` | getInt → setInt | gasto absoluto | `spend` |
| JRMCorePacHanS | `handleQuad` | setInt (5 sites) | ajuste absoluto | `writeAbsolute` (confirmar) |
| JRMCorePacHanS | `handleTech` | getInt/setInt (5 pares) | gasto | `spend` |
| JRMCorePacHanS | `handleStats3` | getInt/setInt | gasto | `spend` |
| JRMCorePacHanS | `handleUpgrade` | getInt/setInt | gasto | `spend` |
| JRMCorePacHanS | `handleChar` | getInt/setInt | gasto | `spend` |
| JRMCorePacHanS | `handleTick` | getInt | leitura | `TpNbt.read` |
| JRMCorePacHanC | `handleData` case 11 | `parseInt` → `curTP` | estado de rede | `NetworkState.accept` |
| JRMCorePacHanC | `handleStats2` | putstatic `curTP` | estado de rede | `NetworkState.accept` |
| PStats2 / Handler | `toBytes/fromBytes/ctor` | `writeInt/readInt` | protocolo | `long` |
| ComJrmc | `func_71515_b` | getInt/setInt | leitura + escrita absoluta | revisar |
| ComJrmcCheck | `func_71515_b` | getInt | leitura | `read` |
| ComJrmcRacialSkill | `func_71515_b` | setInteger | escrita absoluta | `write` |
| ComJrmcRacialSkill | `canAfford` | getInteger | leitura | `read` |
| ComJrmctp | `func_71515_b` | getInt/setInt ×2 | leitura + escrita absoluta | `read`/`write` |
| DBCData | `saveFromNBT` | setInteger | persistência absoluta | `write` |
| DBCData | `loadFromNBT` | getInteger | leitura | `read` |
| DBCTalkGui | 39 refs `curTP:I` | leitura display | leitura | `curTPLong` + clamp |
| StatSheetGui | 2 refs `curTP:I` | leitura display | leitura | `curTPLong` + clamp |

---

## 3. PLANO DE CORREÇÃO (por arquivo e método)

### 3.1 `TpUtil.java` — reescrever
1. `TpLogic` (puro, sem MC): `clamp`, `addChecked`, `subChecked`, `spendChecked`, invariante `0..Long.MAX_VALUE`.
2. `TpNbt.read(NBTTagCompound)`: `getTag` + `getId`; LONG=4 aceito; INT=3 migrado e regravado; tipo inválido → erro explícito.
3. `TpNbt.write(NBTTagCompound, long)`: grava `jrmcTplong` + espelho `jrmcTpint` saturado; **não** toca estáticos.
4. `ClientState.apply(long)`: única escrita de `curTPLong`/`curTP`.
5. `NetworkState.accept(long)`: valida faixa e delega a `ClientState`.
6. Adaptadores semânticos: `gain`, `spend`, `writeAbsolute`, `mirrorOnly` — sem regra universal.
7. Política explícita: TP nunca negativo; overflow satura em `Long.MAX_VALUE`; gasto > saldo retorna falha e não altera.

### 3.2 `TpPatch.java` — endurecer
1. Remover `try/catch` de fallback (B7); toda falha propaga.
2. `injectTpUtil`: ausência → exceção.
3. Verificar classes obrigatórias por JAR; ausência → abortar.
4. Contar padrões ASM encontrados; quantidade inesperada → abortar.
5. Detectar classe já patchada (ex.: `curTPLong` já existe) → abortar com mensagem.
6. Escrita transacional: gerar em `.tmp`, validar, depois `Files.move(ATOMIC_MOVE)`.
7. Regenerar hierarquia automaticamente a partir do classpath completo.

### 3.3 `HierarchyClassWriter` (nova implementação única + testes)
- Modelo `ClassInfo { String superName; String[] interfaces; boolean isInterface; }`.
- `getCommonSuperClass` correto para classes, arrays e interfaces.
- Classe desconhecida → exceção (sem fallback `Object`).
- Testes: classe vs subclasse, classe vs interface, array vs array, array vs Object, primitivos.

### 3.4 `ComJrmctp`
- Auditar fluxo completo: parsing (`Long.parseLong`), validação, definição absoluta,
  ganho (`add`), remoção (`set`), persistência, sync, mensagens.
- Nenhuma operação INT no caminho real.
- `Long.MAX_VALUE`, trilhões e `> INT_MAX` testados.
- Erro explícito ao jogador quando não aplicável.
- Implementação: reescrever o método `func_71515_b` (controle de fluxo conhecido) em vez de
  remendar var 13; se via ASM, usar slots novos e `COMPUTE_FRAMES` com hierarquia real.

### 3.5 Verificadores
- `VerifyJar` v2: classpath real; `CheckClassAdapter`/`SimpleVerifier`; resolução de
  `GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC`; referências NBT; descritores e callsites;
  existência de métodos de destino; frames; ZIP duplicado; classes obrigatórias; deps não resolvidas.
- `getCommonSuperClass` real (sem `Object` fixo).
- Executar `-Xverify:all` em JVM isolada quando aplicável; documentar o que não foi validado.

### 3.6 Build
- `build/` com scripts relativos e `%JAVA_HOME%`.
- Etapas: compilar fontes → gerar hierarquia (MC + Forge + 3 mods) → patch JRMCore → patch DBC → patch NPCDBC → verificar cada JAR → verificar protocolo entre os 3 → hashes → saída em `dist/1.0.7/`.
- Falha em qualquer etapa → sem artefato final.

---

## 4. TESTES A IMPLEMENTAR

### 4.1 Unitários puros (sem Minecraft)
`TpLogicTest`: 0, 1, `INT_MAX`, `INT_MAX+1`, 3e9, 1e10, 1e12, `Long.MAX_VALUE`,
overflow positivo, gasto parcial, exato, excessivo, valores negativos, invariante.

### 4.2 NBT (com `NBTTagCompound` real)
`TpNbtTest`: LONG válido; INT legado; migração INT→LONG; migração repetida (idempotente);
LONG com tipo incorreto (string) rejeitado; INT com tipo incorreto; ausência de chaves;
serialização/desserialização round-trip.

### 4.3 Estado e rede
`ClientStateTest` / `NetworkStateTest`: `apply` atualiza `curTPLong` e espelho;
`TpNbt.write` **não** altera estáticos; dois jogadores com saldos diferentes não se contaminam.

### 4.4 Integração (servidor Minecraft real — exige evidência)
- `/jrmctp set 3000000000`, `10000000000`, `1000000000000`, `Long.MAX_VALUE`;
- logout/login preserva valor;
- ganho e gasto acima de `INT_MAX`;
- sync cliente/servidor (GUI mostra valor correto);
- NPCDBC: `DBCData` salva/carrega `long`;
- DBC: `DBCTalkGui` exibe valor acima de `INT_MAX`.

> Sem log/captura real do servidor, testes in-game serão declarados **NÃO EXECUTADOS**.

---

## 5. RISCOS AINDA NÃO RESOLVIDOS

1. **Protocolo misto:** JRMCore/DBC/NPCDBC precisam ser recompilados com o **mesmo** protocolo;
   um JAR antigo junto com um novo causa corrupção de pacote (`readLong` vs `readInt`).
2. **Assinaturas SRG vs MCP:** verificação de callsites contra classes Minecraft exige mapa
   SRG↔MCP; o jar recompilado usa MCP. Planejar uso de `joined.srg`/dados de deobfuscação
   do Forge para o verificador de membros externos.
3. **Runtime 1.7.10:** `jrmcTplong` já pode existir em saves de versões anteriores com valor
   truncado; a migração não consegue recuperar o valor original perdido — apenas impedir nova perda.
4. **`curTP` espelho:** consumidores antigos de terceiros (mods não auditados) podem ler
   `curTP:I`; acima de `INT_MAX` verão valor saturado (documentar).
5. **`/jrmctp`:** reescrever `func_71515_b` manualmente exige validação contra o fluxo
   original; risco de regressão em mensagens/permissões.
6. **Testes in-game** dependem de ambiente gráfico/servidor; automação completa é limitada.
7. **JARs históricos** (`*-tp-long-v*.jar`, `*-bignum.jar`) permanecem no repo; devem ser
   marcados como históricos para não serem confundidos com artefatos aprovados.

---

## 6. ESTADO DOS ARTEFATOS

| Artefato | Papel | Situação |
|---|---|---|
| `JRMCore-v1.3.51-original.jar` | baseline | preservado |
| `DragonBlockC-v1.4.85.jar` | baseline | preservado |
| `npcdbc-1.2.1-hotfix1.jar` | baseline | preservado |
| `*-patched.jar` (commit `284506a`) | entrega 1.0.6 | **histórico / reprovado** |
| `*-tp-long-v*.jar`, `*-bignum.jar` | tentativas anteriores | histórico |
| 1.0.7 | nova entrega | **não iniciada** (aguardando revisão deste relatório) |

---

## 7. CRITÉRIOS DE CONCLUSÃO 1.0.7 (checklist)

- [ ] B1 tipo NBT validado (LONG=4, INT=3, inválido rejeitado)
- [ ] B2 separação NBT / estado cliente / rede
- [ ] B3 adaptadores semânticos por callsite (tabela seção 2 confirmada)
- [ ] B4 handlers LONG ponta a ponta
- [ ] B5 `Stats2` chamadores atualizados (sem mismatch)
- [ ] B6 `/jrmctp` 100% LONG
- [ ] B7 zero fallback silencioso; build transacional
- [ ] B8 hierarquia ASM correta e completa
- [ ] B9 verificadores sem falso positivo
- [ ] B10 build reproduzível de clone limpo
- [ ] B11 testes unitários + integração com evidência
- [ ] B12 hashes dos artefatos finais + docs + changelog

**Enquanto qualquer item acima estiver aberto, a versão 1.0.7 permanece BLOQUEADA.**
