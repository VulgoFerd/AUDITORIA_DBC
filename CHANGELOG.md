# CHANGELOG - DRAGON BLOCK C BIG TP PATCH

**Projeto:** VulgoFerd/AUDITORIA_DBC  
**JAR Base:** JRMCore-v1.3.51.jar  
**Minecraft:** 1.7.10  
**Forge:** 10.13.4.1614  

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
- Campo `curTP`: `int` → `long`
- `toBytes`: `writeInt` → `writeLong` para `curTP`
- `fromBytes`: `readInt` → `readLong` para `curTP`
- Construtor: parâmetro `curTP` `int` → `long`

#### JRMCorePCost - Sistema Ki
- Campos `short` → `int` (custos de Ki)
- `toBytes`/`fromBytes`: `writeShort`/`readShort` → `writeInt`/`readInt`

#### JRMCorePacHanC - Handler Cliente
- `handleStats2`: parâmetro `curTP` `int` → `long` (descritor atualizado)

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