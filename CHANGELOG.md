# CHANGELOG - DRAGON BLOCK C BIG TP PATCH

**Projeto:** VulgoFerd/AUDITORIA_DBC  
**JAR Base:** JRMCore-v1.3.51.jar  
**Minecraft:** 1.7.10  
**Forge:** 10.13.4.1614  

---

## [1.0.4] - 2026-11-04 - BIG TP 1.0.4 COMPLETO (TP 64-bit End-to-End)

### 🎯 Objetivo
Converter TP de `int` para `long` em todo o pipeline JRMCore/DragonBlockC + NPCDBC, eliminando o limite de 2.147.483.647.

### ✅ Adicionado

#### TpUtil - Autoridade Única TP 64-bit (Nova Classe Injetada)
- `getTP(NBTTagCompound)` - Leitura long com migração automática INT→LONG
- `writeTP(NBTTagCompound, long)` - Escrita long + espelho int saturado
- `addTP(EntityPlayer, long)` / `addTP(NBTTagCompound, long)` - Soma segura com `addTPSafe` (saturação Long.MAX/MIN_VALUE)
- `setMirror(EntityPlayer, int)` / `setMirror(NBTTagCompound, int)` - Escrita legada via delta seguro
- `getTPInt(EntityPlayer)` / `getTPInt(NBTTagCompound)` - TP como int saturado (0..Integer.MAX_VALUE)
- `clamp(long)` - Saturação para display/UI
- `tpShort(long)` - Formatação B/T/Q/Qi

#### JRMCoreH - Núcleo TP Long
- Campo estático `curTPLong:J` (autoridade 64-bit)
- `getTPTotal(Object)` / `getTPTotal(EntityPlayer)` - Leitura autoritativa
- `setTPTotal(Object, long)` - Escrita autoritativa + espelho
- `clampTP(long)` - Saturação para int
- `addTP(Object, long)` / `addTP(EntityPlayer, long)` - Delegação para TpUtil
- `setTPMirror(int, EntityPlayer)` / `setTPMirrorNbt(Object, int)` - Escrita espelho via delta
- `setTPTotalClient(long)` - Atualiza `curTPLong` + `curTP` no cliente
- `handleStats2` descriptor alterado para `(JIB[I)V`
- `expPls` - Sites de ganho usam `TpUtil.addTP`
- `jrmcExp` - 2 sites de ganho usam `TpUtil.addTP`
- `tpalgn` - 2 sites de ganho usam `TpUtil.addTP`
- `setLong` - Removido truncamento `l2i;i2l`

#### JRMCoreComTickH - Sincronia Servidor→Cliente
- `updatePlayersData` - `jrmcTpint` substituído por `clampTP(getTPTotal(NBT))`
- `sendPlayerData` - Envia `curTPLong` nos arrays de dados

#### JRMCorePacHanC - Handler Cliente
- `handleData` - `PUTSTATIC curTP:I` substituído por `setTPTotalClient(long)`
- `handleStats2` - Descritor `(JIB[I)V`, locais deslocados (long ocupa 2 slots), `PUTSTATIC curTP:I` → dual field update

#### JRMCorePacHanS - Handler Servidor
- `handleData` - `getInt jrmcTpint` → `clampTP(getTPTotal)`
- `handleTech` - 2 sites de ganho usam `TpUtil.addTP`
- `handleQuad/handleStats3/handleUpgrade/handleChar/handleTick` - NBT reescritos para `getTPInt`/`setTPMirrorNbt`

#### JRMCorePStats2 - Pacote Rede Long
- Campo `curTP:I` removido, adicionado `curTPLong:J`
- Construtor `(JIB[I)V` - `curTP` carregado como `LLOAD 1`
- `toBytes` - `writeInt` → `writeLong`
- `fromBytes` - `readInt` → `readLong`

#### JRMCorePStats2$Handler - Handler Rede
- `handleClientMessage` - `GETFIELD curTP:I` → `curTPLong:J`
- Chamada `PacHanC.handleStats2` atualizada para descritor long

#### DBCData (ComJrmcRacialSkill) - NBT Long
- `loadFromNBT` / `saveFromNBT` - Reescritos usando `TpUtil.getTP`/`writeTP`
- Sem cirurgia de stack (SWAP com long eliminado)

#### ComJrmctp - Comando /jrmctp
- `getTPTotal` / `setTPTotal` usam long nativo (com `I2L` onde necessário)

#### Ferramentas
- `VerifyJar` - Validação estática com `--audit-descriptors` (346 classes, 3707 métodos, 0 frames inválidos, 0 refs curTP:I, 0 mismatches)
- `VerifyBytecode` - Verificação estrita com `CheckClassAdapter` + hierarchy ClassLoader
- `HierarchyClassWriter` - `COMPUTE_FRAMES` real via hierarquia serializada (120 classes), sem fallback

### 🔧 Corrigido
- Todos os 10 pontos de auditoria do commit `285d86a` resolvidos
- Overflow em `setMirror` → `addTPSafe` (saturating add)
- Anchor-on-removed-instruction eliminados (7 ocorrências)
- Slots locais, IINC, LocalVariableTable e frames de PStats2 corretos
- NBT migration com validação de tipo (tag 3=INT, 4=LONG) e persistência

### 📦 Artefato
| Item | Valor |
|------|-------|
| SHA-256 do JAR | `A461651CFF1C6C22BBA77368320B8E129B4CF67B1C120F6BEF610D071F4FA12C` |
| Classes patchadas | JRMCoreH, JRMCoreComTickH, JRMCorePacHanC, JRMCorePacHanS, JRMCorePStats2, JRMCorePStats2$Handler, ComJrmcRacialSkill, ComJrmctp, TpUtil (injected) |
| Validação estática | PASS (VerifyJar + VerifyBytecode) |
| Teste Minecraft | PENDENTE |

---

## [1.0.3] - 2026-11-03 - TP LONG INTERMEDIATE (v11)

### 🐞 Corrigido

#### `VerifyError: Bad type on operand stack` em `JRMCorePStats2.toBytes @22`

**Sintoma:** jogo abria e chegava ao menu; ao clicar em "melhorar atributo" (`03:13:12`), o mundo caía para o menu principal.

**Causa raiz:** o patch de `curTP int -> long` em `JRMCorePStats2` deixou a classe inconsistente em 3 pontos:

1. `toBytes` trocou `writeInt` por `writeLong` também na escrita de `curExp` (campo `int`), gerando `long_2nd` recebendo `integer`.
2. O construtor real é `(IIB[I)V`; o patcher assumia `(III[I)V`, então `PUTFIELD curTP` ficou com descritor `I` sobre campo `J`.
3. `JRMCorePStats2$Handler` e `JRMCorePacHanC.handleStats2` continuaram lendo/entregando `int`.

**Fix:** `patchJRMCorePStats2` agora é **SKIPPED**. `JRMCorePStats2` é DTO de rede que espelha o display `int`; long-izar exigiria alargar `Handler`, `PacHanC` e o estático `JRMCoreH.curTP`, sem ganho, pois o chamador já passa `int` saturado. A autoridade 64-bit permanece em `jrmcTplong` (`JRMCoreH`).

Relatório completo: `RELATORIO_CRASH_STATS2.md`.

#### Processo: patcher rodava `.class` obsoleto

`C:\temp\run_patch.bat` executava `java PatchBig` sem recompilar. O `.class` usado era de `02/11 00:40`; o fonte corrigido era de `02:24`. As correções nunca chegaram ao JAR.

**Fix:** fonte único em `artifacts/PatchBig.java` (espelho em `AUDITORIA_DBC/`), `javac -encoding UTF-8` obrigatório antes do patch, BOM removido do arquivo.

### 📦 Artefato

| Item | Valor |
|------|-------|
| SHA-256 do JAR | `221643729F567445AB04E2539009A67A8E9272625F1A6811B410E976DFB2243D` |
| Classes patchadas de fato | `JRMCoreH`, `JRMCorePCost` |
| Classes intactas por design | `JRMCorePStats2`, `JRMCorePacHanC`, `JRMCorePacHanS`, `JRMCoreComTickH`, `ComJrmctp` |

### ⚠️ Limitação conhecida

Acima de `Integer.MAX_VALUE`, a subtração de custo em `JRMCorePacHanS.handleUpgrade` opera sobre o espelho `jrmcTpint` saturado; `jrmcTplong` não é debitado. Não causa crash, mas o custo de melhoria deixa de ser cobrado nessa faixa. Corrigir exige patchear `JRMCorePacHanS`, que tem Mixin ativo.

---

## [1.0.0] - 2026-11-02 - BIG TP RELEASE CANDIDATE

### 🎯 Objetivo Principal
Eliminar limite de 2.147.483.647 (Integer.MAX_VALUE) de TP, elevando para 64-bit Long (~9 quintilhões).

### ✅ Adicionado

#### Arquitetura Dual Long/Int
- **`jrmcTplong`** - Autoridade 64-bit no NBT do player ("pres")
- **`jrmcTpint`** - Espelho saturado 32-bit para compatibilidade legada
- Regra: espelho **nunca** sobrescreve autoridade

#### JRMCoreH - Núcleo do Sistema TP
- Campo estático `curTPLong` (long)
- `getMaxTP()` → retorna `Integer.MAX_VALUE` (UI/compatibilidade)
- `getInt(EntityPlayer, String)` - Lê preferindo `jrmcTplong`, fallback `jrmcTpint`
- `setInt(int, EntityPlayer, String)` - Escreve **apenas** `jrmcTpint` (mirror only)
- `setInt(short/byte/float, ...)` - Variantes preservadas
- `getLong(EntityPlayer, String)` - Leitura autoritativa
- `setLong(long, EntityPlayer, String)` - Escrita autoritativa + espelho saturado
- `addLong(long delta, EntityPlayer, String)` - Soma delta, atualiza ambos, retorna long
- `getLongFromNBT(NBTTagCompound, String)` - Helper leitura long
- `setLongInNBT(NBTTagCompound, String, long)` - Helper escrita long
- `getIntFromNBT(NBTTagCompound, String)` - Helper leitura int
- `setIntInNBT(NBTTagCompound, String, int)` - Helper escrita int
- `applyPacketTPChange(NBTTagCompound, EntityPlayer)` - Processa pacote TP completo

#### JRMCorePStats2 - Pacote de Stats (Rede)
> ⚠️ **Revertido em 1.0.1.** As mudanças abaixo causaram `VerifyError` em `toBytes`. A classe voltou a ser `int`; ver `RELATORIO_CRASH_STATS2.md`.

- ~~Campo `curTP`: `int` → `long`~~
- ~~`toBytes`: `writeInt` → `writeLong` para `curTP`~~
- ~~`fromBytes`: `readInt` → `readLong` para `curTP`~~
- ~~Construtor: parâmetro `curTP` `int` → `long`~~

#### JRMCorePCost - Sistema Ki
- Campos `short` → `int` (custos de Ki)
- `toBytes`/`fromBytes`: `writeShort`/`readShort` → `writeInt`/`readInt`

#### JRMCorePacHanC - Handler Cliente
> ⚠️ **Nunca aplicado.** A condição do patcher exigia descritor `(III[I...)V`; o real é `(IIB[I...)V`. Classe permanece original.

- ~~`handleStats2`: parâmetro `curTP` `int` → `long`~~

### 🔧 Corrigido

#### VerifyError: Bad type on operand stack em `applyPacketTPChange@29`
**Causa:** `Integer.MAX_VALUE` (int) passado para `Math.min(JJ)J` que espera dois `long`
**Fix:** 3 ocorrências corrigidas em `PatchBig.java`:
- Linha 208 (`setLong`): `LdcInsnNode(Integer.MAX_VALUE)` → `LdcInsnNode((long) Integer.MAX_VALUE)`
- Linha 273 (`addLong`): Já estava correto
- Linha 383 (`applyPacketTPChange`): `LdcInsnNode(Integer.MAX_VALUE)` → `LdcInsnNode((long) Integer.MAX_VALUE)`

#### VerifyError: Bad type on operand stack em `addLong@43`
**Causa:** Índices de variáveis locais incorretos (long ocupa 2 slots)
**Fix:** Corrigidos índices 0-1 (delta), 2 (player), 3 (key), 4 (NBT), 5-6 (result), 7 (saturated int)

#### VerifyError: Operand stack underflow em `addLong`
**Causa:** Stack management incorreto (DUP2/LSTORE/LLOAD sequence)
**Fix:** Reescrito bytecode com stack management correto

#### Math.toIntExact lançava ArithmeticException (TP > 2.14B)
**Causa:** `toIntExact` falha quando valor > Integer.MAX_VALUE
**Fix:** Substituído por `Math.min(value, Integer.MAX_VALUE)` + `toIntExact` (satura em vez de estourar)

#### ArrayIndexOutOfBoundsException em `Frame.merge` (Mixins)
**Causa:** Classes patchadas conflitando com Mixins do NPCDBC
**Fix:** Classes `JRMCoreComTickH`, `JRMCorePacHanS`, `ComJrmctp` **não patchadas** (SKIPPED)

### ⏭️ Não Modificado (Preservado para Compatibilidade)

| Classe | Motivo |
|--------|--------|
| `JRMCoreComTickH` | Mixin `dbc.MixinJRMCoreComTickH` quebra Frame.merge |
| `JRMCorePacHanS` | Mixin `dbc.MixinJRMCorePacHanS` quebra Frame.merge |
| `ComJrmctp` | Command handler usa `jrmcTpint` (int mirror) |
| `JRMCorePStats2` | DTO de rede int; long-izar quebrou `toBytes` (ver 1.0.1) |
| `JRMCorePacHanC` | `handleStats2` é int por contrato com `JRMCoreH.curTP` |

### 🛠️ Ferramentas e Infraestrutura

#### PatchBig.java
- Patcher ASM 9.7.1 estático (sem Mixin/sources)
- SafeClassWriter com fallback para `java/lang/Object`
- Flags: `COMPUTE_MAXS` apenas (sem `COMPUTE_FRAMES`)
- 7 classes processadas, 4 patchadas, 3 skipped

#### VerifyClasses.java
- Script de verificação de bytecode ASM
- CheckClassAdapter + verificação estrutural
- Detecção de constantes `Integer.MAX_VALUE` (int) em contexto `Math.min(JJ)J`
- Lista assinaturas de métodos TP-related

#### Documentação
- `AUDITORIA_COMPLETA.md` - Relatório completo
- `MAPA_DE_REFERENCIAS_TP.md` - Mapa de 59+ referências categorizadas
- `RELATORIO_DE_TESTES.md` - Planilha de testes estáticos/dinâmicos
- `RELATORIO_DE_BYTECODE.md` - Verificação ASM detalhada
- `INSTALACAO.md` - Instruções de instalação/rollback
- `LIMITES-NUMERICOS.md` - Análise de limites numéricos
- `tp-long-design.md` - Especificação de design

### 🔐 SHA-256 Hashes

| Artefato | SHA-256 |
|----------|---------|
| JRMCore-v1.3.51-original.jar | `0614265D665B6E60456D460255C1488377CAD1200AD6BD9444128D835FC28946` |
| JRMCore-v1.3.51-bignum.jar | `A054F59F1327C4900CBDB76035B4C93E7AFD99F9D2AB6D564F67542E6C03D797` |

---

## [0.9.0] - 2026-11-01 - PRÉ-RELEASE (CRASHES CONHECIDOS)

### Problemas Identificados
- `VerifyError applyPacketTPChange@29` - Integer.MAX_VALUE como int
- `VerifyError addLong@43` - Local variable indices
- `ArrayIndexOutOfBoundsException Frame.merge` - Mixin conflicts
- `ArithmeticException toIntExact` - TP > 2B

### Classes Parcialmente Patchadas
- JRMCoreH (com bugs de bytecode)
- JRMCorePStats2, JRMCorePCost, JRMCorePacHanC
- JRMCoreComTickH, JRMCorePacHanS, ComJrmctp (causando crashes)

---

## [0.1.0] - 2026-10-31 - INÍCIO DA AUDITORIA

### Entregáveis Iniciais
- Inventário de 59 ocorrências de `jrmcTpint` (`report_tp.txt`)
- Análise de limites numéricos (`LIMITES-NUMERICOS.md`)
- Design da arquitetura long/int (`tp-long-design.md`)
- Configuração alvo (`tmx=100000, atcm=0.015`)
- Repositório GitHub criado: https://github.com/VulgoFerd/AUDITORIA_DBC

---

## Próximos Passos (Pós-1.0.0)

- [ ] Teste real no Minecraft 1.7.10
- [ ] Validação TP > 2B in-game
- [ ] Teste de persistência (logout/login)
- [ ] Teste de rede (cliente/servidor)
- [ ] Validação compatibilidade NPCDBC
- [ ] Release final se todos os testes passarem

---

**Nota:** Versões 0.x são builds de desenvolvimento com crashes conhecidos. Versão 1.0.0 é o primeiro candidate com todas as correções de bytecode aplicadas e validadas estaticamente. O teste decisivo é a execução real no Minecraft.