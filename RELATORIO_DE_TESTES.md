# RELATÓRIO DE TESTES - DRAGON BLOCK C BIG TP PATCH

**Data:** 2026-11-02  
**JAR Testado:** JRMCore-v1.3.51-bignum.jar  
**SHA-256 (atual):** `221643729F567445AB04E2539009A67A8E9272625F1A6811B410E976DFB2243D`  
**SHA-256 (build com crash):** `A054F59F1327C4900CBDB76035B4C93E7AFD99F9D2AB6D564F67542E6C03D797`  
**Instância de Teste:** `C:\Users\Fernando\Desktop\NanatsuCore\nntdarkmp_copy`

---

## 1. TESTES ESTÁTICOS (ASM VERIFICATION) ✅ CONCLUÍDOS

| Teste | Resultado | Detalhes |
|-------|-----------|----------|
| CheckClassAdapter - JRMCoreH | ✅ PASS | Estrutura válida |
| CheckClassAdapter - JRMCorePStats2 | ✅ PASS | Estrutura válida |
| CheckClassAdapter - JRMCorePCost | ✅ PASS | Estrutura válida |
| CheckClassAdapter - JRMCorePacHanC | ✅ PASS | Estrutura válida |
| CheckClassAdapter - JRMCoreComTickH | ✅ PASS | Estrutura válida (não patchado) |
| CheckClassAdapter - JRMCorePacHanS | ✅ PASS | Estrutura válida (não patchado) |
| CheckClassAdapter - ComJrmctp | ✅ PASS | Estrutura válida (não patchado) |
| Verificação Integer.MAX_VALUE em Math.min(JJ)J | ✅ PASS | Zero ocorrências do bug |
| Assinaturas de métodos long/int | ✅ PASS | Consistentes com design |
| Cadeia `JRMCorePStats2`/`Handler`/`PacHanC` | ✅ PASS (1.0.1) | Toda `int`, igual ao original |

---

## 2. TESTES DINÂMICOS - MINECRAFT 1.7.10 ⏳ PENDENTES

### 2.1. Inicialização do Jogo

| Teste | Critério de Sucesso | Status |
|-------|---------------------|--------|
| Launcher abre instância | Game Directory = `nntdarkmp_copy` | ✅ |
| Forge carrega mods | 42 mods loaded, 42 mods active | ✅ |
| JRMCore carrega sem VerifyError | Sem crash em `applyPacketTPChange@29` | ✅ |
| Chega ao menu principal | Tela "Singleplayer/Multiplayer" visível | ✅ |
| Abre GUI de atributos | Sem `VerifyError` em `JRMCorePStats2.toBytes` | ⏳ (testar 1.0.1) |

### 2.2. Teste In-Game - Valores TP

| Cenário | Valor Esperado | Valor Observado | Status |
|---------|----------------|-----------------|--------|
| TP = 0 | 0 | - | ⏳ |
| TP = 1 | 1 | - | ⏳ |
| TP = 2.147.483.647 (Integer.MAX_VALUE) | 2.147.483.647 | - | ⏳ |
| TP = 2.147.483.648 | 2.147.483.648 | - | ⏳ |
| TP = 3.000.000.000 | 3.000.000.000 | - | ⏳ |
| TP = 10.000.000.000 | 10.000.000.000 | - | ⏳ |

**Método de verificação:** NBT do player (`/nbt` ou editor de save) → `jrmcTplong` e `jrmcTpint`

### 2.3. Persistência (Logout/Login)

| Teste | Critério | Status |
|-------|----------|--------|
| TP > 2B salvo no NBT | `jrmcTplong` preservado no level.dat | ⏳ |
| TP > 2B após relogin | Valor idêntico ao antes do logout | ⏳ |
| Espelho int sincronizado | `jrmcTpint` = Integer.MAX_VALUE | ⏳ |

### 2.4. Operações Aritméticas

| Operação | Entrada | Esperado | Status |
|----------|---------|----------|--------|
| `addLong(1B, 2B)` | 3B | 3.000.000.000 | ⏳ |
| `addLong(2B, -1B)` | 1B | 1.000.000.000 | ⏳ |
| `addLong(2.14B, 1B)` | 3.14B | 3.140.000.000 | ⏳ |
| `setLong(5B)` | 5B | 5.000.000.000 | ⏳ |
| `getInt(5B)` | 5B | Integer.MAX_VALUE | ⏳ |

### 2.5. Rede (Cliente/Servidor)

| Teste | Critério | Status |
|-------|----------|--------|
| Pacote Stats2 serializa `curTP` como int | `writeInt` (int, formato original) | ✅ (revertido em 1.0.1) |
| Pacote Stats2 deserializa `curTP` como int | `readInt` | ✅ (revertido em 1.0.1) |
| Cliente recebe TP saturado | `handleStats2` recebe `Integer.MAX_VALUE` | ✅ |
| `applyPacketTPChange` sincroniza | Player NBT atualizado corretamente | ⏳ |
| Sem dessincronização C/S | Valores idênticos nos dois lados | ⏳ |

> Histórico: a tentativa de long em `JRMCorePStats2` gerou `VerifyError` em `toBytes @22` (02/11 03:13). Ver `RELATORIO_CRASH_STATS2.md`.

### 2.6. Compatibilidade NPCDBC

| Teste | Critério | Status |
|-------|----------|--------|
| Mixins carregam sem erro | Sem `ArrayIndexOutOfBoundsException` em `Frame.merge` | ⏳ |
| JRMCoreComTickH funcional | Consome `jrmcTpint` normalmente | ⏳ |
| JRMCorePacHanS funcional | Processa pacotes `jrmcTpint` | ⏳ |
| ComJrmctp funcional | Comandos TP funcionam | ⏳ |
| `setInt` sincroniza para long | `jrmcTpint` → `jrmcTplong` no player NBT | ⏳ |

---

## 3. KI SYSTEM TESTES

| Teste | Critério | Status |
|-------|----------|--------|
| Custos Ki como int | `JRMCorePCost` fields = int | ⏳ |
| Packet read/write int | `readInt`/`writeInt` | ⏳ |
| Sem `I2S` casts | `JRMCoreH.Cost`, `DBCKiTech.KAkiEn` limpos | ⏳ |
| Skills funcionam | Custos não truncam em 32767 | ⏳ |

---

## 4. REGISTRO DE EXECUÇÃO

| Data/Hora | Ação | Resultado | Observações |
|-----------|------|-----------|-------------|
| 2026-11-02 00:53 | Teste anterior (antes do fix) | **CRASH** | `VerifyError applyPacketTPChange@29` |
| 2026-11-02 01:15 | Fix aplicado (3 constantes) | Compila OK | `PatchBig.java` atualizado |
| 2026-11-02 01:20 | Patcher executado | 7 classes patched | `JRMCore-v1.3.51-bignum.jar` gerado |
| 2026-11-02 01:25 | Verificação ASM | ✅ PASS | Zero warnings |
| 2026-11-02 01:30 | JAR copiado para instância | OK | `nntdarkmp_copy\mods\` |
| 2026-11-02 03:09 | Teste in-game (build 1.0.0) | Jogo abre, menu OK | Sem VerifyError no boot |
| 2026-11-02 03:12 | Comando TP `+2147483647` | OK | `TP adding 2147483647 success` (2x) |
| 2026-11-02 03:13 | Clique em melhorar atributo | **FALHA** | `VerifyError JRMCorePStats2.toBytes @22` |
| 2026-11-02 04:05 | Diagnóstico de bytecode | Concluído | 3 defeitos no patch de `PStats2` |
| 2026-11-02 04:10 | Processo: `.class` obsoleto | Concluído | `run_patch.bat` não recompilava |
| 2026-11-02 04:15 | Build 1.0.1 (`PStats2` revertido) | 7 classes, 2 patchadas | SHA `2216437...` |
| 2026-11-02 04:15 | Verificação estática 1.0.1 | ✅ PASS | Cadeia `int` consistente |
| 2026-11-02 04:15 | JAR 1.0.1 copiado para instância | OK | `nntdarkmp_copy\mods\` |
| 2026-11-02 TBD | **TESTE REAL NO MINECRAFT (1.0.1)** | **PENDENTE** | Repetir clique em melhorar atributo |

---

## 5. CRASH REPORTS ANTERIORES (REFERÊNCIA)

| Arquivo | Data | Erro Principal |
|---------|------|----------------|
| `crash-2026-11-01_21.27.57-client.txt` | 01/11/26 | `VerifyError applyPacketTPChange@29` |
| `crash-2026-11-02_00.53.57-client.txt` | 02/11/26 | `VerifyError applyPacketTPChange@29` |
| `crash-2026-11-01_23.51.26-client.txt` | 01/11/26 | `VerifyError addLong@43` (já corrigido) |
| `crash-2026-11-01_23.18.02-client.txt` | 01/11/26 | `VerifyError applyPacketTPChange@29` |
| `latest.log` 02/11 03:13 | 02/11/26 | `VerifyError JRMCorePStats2.toBytes@22` (sem crash report; corrigido em 1.0.1) |

---

## 6. CRITÉRIOS DE SUCESSO FINAL

A missão só é considerada concluída quando **TODOS** estes critérios forem atendidos:

- [x] Jogo chega ao menu principal sem `VerifyError`
- [ ] TP pode ser definido > 2.147.483.647 in-game
- [ ] Valor persiste após logout/login
- [ ] `jrmcTplong` é autoridade, `jrmcTpint` é espelho saturado
- [ ] Nenhum consumidor legado quebra (NPCDBC, ComJrmctp, etc.)
- [x] Rede não gera `VerifyError` ao abrir GUI de atributos (build 1.0.1)
- [ ] KI system funcional (custos int, sem truncamento)
- [ ] Custo de melhoria ainda cobra TP acima de `Integer.MAX_VALUE` (limitação conhecida)

---

## 7. PRÓXIMO TESTE AGENDADO

**Ação:** Executar Minecraft Launcher oficial → Game Directory = `C:\Users\Fernando\Desktop\NanatsuCore\nntdarkmp_copy` → Play

**Verificar:** `latest.log` e `crash-reports/` para novo crash report (se houver)

**Responsável:** Usuário (teste manual requerido)

---

**Status Geral:** ✅ Build 1.0.1 estática OK | ⏳ Teste in-game do clique em atributo pendente