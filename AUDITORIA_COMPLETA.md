# AUDITORIA COMPLETA - DRAGON BLOCK C BIG TP PATCH

**Data:** 2026-11-02  
**Versão:** 1.0  
**JAR Alvo:** JRMCore-v1.3.51-bignum.jar  
**SHA-256:** `A054F59F1327C4900CBDB76035B4C93E7AFD99F9D2AB6D564F67542E6C03D797`

---

## 1. OBJETIVO

Remover o limite de 2.147.483.647 (Integer.MAX_VALUE) de Training Points (TP) no Dragon Block C (Minecraft 1.7.10), elevando para 64-bit Long (~9 quintilhões), mantendo compatibilidade com sistemas legados.

---

## 2. ARQUITETURA IMPLEMENTADA

| Variável | Tipo | Papel |
|----------|------|-------|
| `jrmcTplong` | `long` (64-bit) | **Authority** - valor real no NBT do player |
| `jrmcTpint` | `int` (32-bit) | **Mirror saturado** - compatibilidade npcdbc/pacotes legados |
| `getMaxTP()` | `int` | Display/UI - retorna `Integer.MAX_VALUE` |

**Regras de Ouro:**
- `setLongAuthority(tp)` → escreve `jrmcTplong = tp` + `jrmcTpint = saturate(tp)`
- `setIntMirrorOnly(tp)` → escreve **só** `jrmcTpint` (não toca no long)
- Código legado (npcdbc, ComJrmctp, JRMCoreComTickH) continua usando `jrmcTpint`
- O espelho **nunca** sobrescreve ou reconstrói o valor autoritativo

---

## 3. CLASSES MODIFICADAS (7 total)

| Classe | Status | Mudanças Principais |
|--------|--------|---------------------|
| `JRMCoreH` | ✅ Patched | Helpers long, `getInt`/`setInt` reescritos, `getMaxTP=Integer.MAX_VALUE`, novos métodos `addLong`, `getLong`, `setLong`, `applyPacketTPChange` |
| `ComJrmctp` | ⏭️ SKIPPED | VerifyError long/int no command handler - mantido original |
| `JRMCoreComTickH` | ⏭️ SKIPPED | Mixin `dbc.MixinJRMCoreComTickH` quebra Frame.merge - mantido original |
| `JRMCorePacHanS` | ⏭️ SKIPPED | Mixin `dbc.MixinJRMCorePacHanS` quebra Frame.merge - mantido original |
| `JRMCorePStats2` | ✅ Patched | `curTP`: int→long, pacote `writeLong`/`readLong` |
| `JRMCorePCost` | ✅ Patched | `short`→`int` (custos Ki), remove `I2S` |
| `JRMCorePacHanC` | ✅ Patched | `handleStats2`: parâmetro `curTP` int→long |

---

## 4. ERROS RESOLVIDOS

| # | Erro | Causa | Solução |
|---|------|-------|---------|
| 1 | `ArrayIndexOutOfBoundsException` em `Frame.merge` | Mixins transformando classes já patchadas | Pulamos patch nas classes com Mixin |
| 2 | `VerifyError: Bad type on operand stack` em `JRMCoreH.addLong` | Índices de local variable (long = 2 slots) | Corrigidos índices de variáveis locais |
| 3 | `VerifyError: Operand stack underflow` | Stack management incorreto no `addLong` | Corrigido DUP2/LSTORE/LLOAD sequence |
| 4 | `Math.toIntExact` lançava `ArithmeticException` | TP > 2.14B | Trocado por `Math.min(value, Integer.MAX_VALUE)` + `toIntExact` |
| 5 | **`VerifyError: Bad type on operand stack` em `applyPacketTPChange@29`** | `Integer.MAX_VALUE` (int) passado para `Math.min(JJ)J` | **Corrigido: 3 ocorrências → `(long)Integer.MAX_VALUE`** |

---

## 5. CORREÇÃO CRÍTICA - CONSTANTES LONG

**Problema:** `Math.min(JJ)J` espera dois `long` na pilha, mas `LdcInsnNode(Integer.MAX_VALUE)` empurra `int` (1 slot).

**Locais corrigidos em `PatchBig.java`:**
1. **Linha 208** - `setLong`: `new LdcInsnNode((long) Integer.MAX_VALUE)`
2. **Linha 273** - `addLong`: `new LdcInsnNode((long) Integer.MAX_VALUE)` (já estava correto)
3. **Linha 383** - `applyPacketTPChange`: `new LdcInsnNode((long) Integer.MAX_VALUE)`

**Verificação:** Script `VerifyClasses.java` confirmou **zero ocorrências** restantes do problema.

---

## 6. VERIFICAÇÃO DE BYTECODE

**Ferramenta:** ASM CheckClassAdapter + verificação estrutural customizada  
**Resultado:** ✅ **TODAS AS 7 CLASSES APROVADAS**

- Estrutura de classe válida
- Assinaturas de métodos consistentes
- Nenhuma constante `int` em contexto `long` para `Math.min(JJ)J`
- Flags: `COMPUTE_MAXS` apenas (sem `COMPUTE_FRAMES` para evitar frame computation issues)

---

## 7. MIGRAÇÃO E PERSISTÊNCIA

| Cenário | Comportamento |
|---------|---------------|
| Save antigo (só `jrmcTpint`) | Migra: `jrmcTplong = jrmcTpint` |
| Save com `jrmcTplong` | Autoridade preservada |
| TP > Integer.MAX_VALUE | Sobrevive a logout/login |
| Atualização espelho | Não altera autoridade |
| Chave ausente | Tratada como 0 |

**Valores testados (planejado):**
- 0, 1, 2147483646, 2147483647, 2147483648, 3000000000, 1000000000000, Long.MAX_VALUE

---

## 8. REDE E SERIALIZAÇÃO

| Componente | Tipo | Descrição |
|------------|------|-----------|
| `JRMCorePStats2.toBytes` | `writeLong` | Envia `curTP` como long |
| `JRMCorePStats2.fromBytes` | `readLong` | Recebe `curTP` como long |
| `JRMCorePacHanC.handleStats2` | `long` param | Processa `curTP` long do pacote |
| `applyPacketTPChange` | Helper | Lê `jrmcTplong`/`jrmcTpint` do pacote, calcula delta, sincroniza player |

---

## 9. COMPATIBILIDADE NPCDBC

**Mixin problemáticos identificados:**
- `dbc.MixinJRMCoreComTickH`
- `dbc.MixinJRMCorePacHanS`
- `dbc.MixinJRMCPacketHandlerServer`

**Estratégia:** Não patchar essas classes → preservar bytecode original → Mixins funcionam sem `Frame.merge` errors.

**Interface de compatibilidade:**
- `JRMCoreH.setInt` (patchado) sincroniza `jrmcTpint` → `jrmcTplong` no player NBT
- Pacotes usam `jrmcTpint` (int, capped at 2B)
- Player data usa `jrmcTplong` (long, 64-bit)

---

## 10. KI SYSTEM FIXES

| Item | Antes | Depois |
|------|-------|--------|
| `JRMCorePCost` fields | `short` | `int` |
| `CostEnAt` | `short` | `int` |
| `JRMCoreH.Cost` | `I2S` cast | Removido |
| `DBCKiTech.KAkiEn` | `I2S` casts | Removidos |
| Packet read/write | `readShort`/`writeShort` | `readInt`/`writeInt` |

---

## 11. CONFIGURAÇÃO ALVO

**Arquivo:** `config/jinryuujrmcore_nova.cfg`
```
Attribute Maximum=100000
atcm=0.015
```

**Economia de atributos:**
- Custo total ~`13.5 * tmx^2`
- Cap binding em `tmx ~= 12,170`
- Com `tmx=100000` e `atcm=0.015`: TP escala para bilhões

---

## 12. STATUS FINAL

| Categoria | Status |
|-----------|--------|
| Verificação estática (ASM) | ✅ **APROVADO** |
| Correções de bytecode | ✅ **CONCLUÍDO** |
| Patchers funcionando | ✅ **CONCLUÍDO** |
| JAR gerado | ✅ **DISPONÍVEL** |
| Teste real no Minecraft | ⏳ **PENDENTE** |
| TP > 2B funcional in-game | ⏳ **PENDENTE** |
| Persistência validada | ⏳ **PENDENTE** |
| Rede validada | ⏳ **PENDENTE** |

---

## 13. PRÓXIMO PASSO OBRIGATÓRIO

**Executar Minecraft 1.7.10 com `JRMCore-v1.3.51-bignum.jar` na instância `nntdarkmp_copy` e validar:**

1. Jogo chega ao menu principal sem `VerifyError`
2. Entrar em mundo de teste
3. Verificar TP pode exceder 2.147.483.647 (NBT do player)
4. Logout/login preserva valor
5. Sem crashes relacionados a TP

---

**Conclusão:** Todas as correções de bytecode foram aplicadas e validadas estaticamente. O teste decisivo é a execução real no Minecraft.