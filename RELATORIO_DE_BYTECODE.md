# RELATÓRIO DE VERIFICAÇÃO DE BYTECODE

**Data:** 2026-11-02  
**JAR Analisado:** JRMCore-v1.3.51-bignum.jar  
**SHA-256 Original:** `0614265D665B6E60456D460255C1488377CAD1200AD6BD9444128D835FC28946`  
**SHA-256 Modificado:** `A054F59F1327C4900CBDB76035B4C93E7AFD99F9D2AB6D564F67542E6C03D797`

---

## 1. RESUMO DA VERIFICAÇÃO

| Classe | Estrutura Básica | Math.min(JJ)J com int | Status |
|--------|------------------|----------------------|--------|
| JRMCoreH.class | ✅ Válida | ✅ Nenhum | **OK** |
| JRMCoreComTickH.class | ✅ Válida | ✅ Nenhum | **OK** |
| JRMCorePacHanS.class | ✅ Válida | ✅ Nenhum | **OK** |
| JRMCorePStats2.class | ✅ Válida | ✅ Nenhum | **OK** |
| JRMCorePCost.class | ✅ Válida | ✅ Nenhum | **OK** |
| JRMCorePacHanC.class | ✅ Válida | ✅ Nenhum | **OK** |
| ComJrmctp.class | ✅ Válida | ✅ Nenhum | **OK** |

**Todas as 7 classes modificadas passaram na verificação estrutural básica.**

---

## 2. CORREÇÕES APLICADAS NO PATCHER (PatchBig.java)

### 2.1. Constantes `Integer.MAX_VALUE` corrigidas para `long`

| Localização | Método | Linha (antes) | Linha (depois) | Status |
|-------------|--------|---------------|----------------|--------|
| `patchJRMCoreH` | `setLong` | 208 | 208 | ✅ Corrigido |
| `patchJRMCoreH` | `addLong` | 273 | 273 | ✅ Já estava correto |
| `patchJRMCoreH` | `applyPacketTPChange` | 383 | 383 | ✅ Corrigido |

**Alteração:**
```java
// ANTES (incorreto para Math.min(JJ)J)
new LdcInsnNode(Integer.MAX_VALUE)

// DEPOIS (correto - empurra constante long, 2 slots)
new LdcInsnNode((long) Integer.MAX_VALUE)
```

### 2.2. Verificação pós-correção

O script de verificação `VerifyClasses.java` confirmou:
- **Zero ocorrências** de `Integer.MAX_VALUE` (int) sendo usado como argumento para `Math.min(JJ)J`
- Todos os métodos transformados têm estrutura de classe válida
- Assinaturas dos métodos long/int estão consistentes

---

## 3. MÉTODOS PRINCIPAIS EM JRMCoreH.class

| Método | Descritor | Papel |
|--------|-----------|-------|
| `getInt` | `(LEntityPlayer;LString;)I` | Lê TP preferindo `jrmcTplong`, fallback `jrmcTpint` |
| `setInt(int,...)` | `(ILEntityPlayer;LString;)V` | Escreve **apenas** `jrmcTpint` (espelho) |
| `setInt(short,...)` | `(SLEntityPlayer;LString;)V` | Variante short |
| `setInt(byte,...)` | `(BLEntityPlayer;LString;)V` | Variante byte |
| `setInt(float,...)` | `(FLEntityPlayer;LString;)V` | Variante float |
| `getLong` | `(LEntityPlayer;LString;)J` | Leitura autoritativa de `jrmcTplong` |
| `setLong` | `(JLEntityPlayer;LString;)V` | Escrita autoritativa: `jrmcTplong` + espelho `jrmcTpint` |
| `addLong` | `(JLEntityPlayer;LString;)J` | Soma delta, atualiza ambos, retorna long |
| `getMaxTP` | `()I` | Retorna `Integer.MAX_VALUE` (UI/compatibilidade) |
| `getLongFromNBT` | `(LNBTTagCompound;LString;)J` | Helper: lê long de qualquer NBT |
| `setLongInNBT` | `(LNBTTagCompound;LString;J)V` | Helper: escreve long em qualquer NBT |
| `getIntFromNBT` | `(LNBTTagCompound;LString;)I` | Helper: lê int de qualquer NBT |
| `setIntInNBT` | `(LNBTTagCompound;LString;I)V` | Helper: escreve int em qualquer NBT |
| `applyPacketTPChange` | `(LNBTTagCompound;LEntityPlayer;)V` | Processa pacote TP: delta long + sincroniza espelho |

---

## 4. MUDANÇAS EM OUTRAS CLASSES

### JRMCorePStats2
- Campo `curTP`: `int` → `long`
- `toBytes`: `writeInt` → `writeLong` para `curTP`
- `fromBytes`: `readInt` → `readLong` para `curTP`
- Construtor: parâmetro `curTP` `int` → `long`

### JRMCorePCost
- Campos `short` → `int` (custos de Ki)
- `toBytes`/`fromBytes`: `writeShort`/`readShort` → `writeInt`/`readInt`

### JRMCorePacHanC
- `handleStats2`: parâmetro `curTP` `int` → `long` (descritor `(III[I...)V` → `(JII[I...)V`)

### ComJrmctp, JRMCoreComTickH, JRMCorePacHanS
- **Não modificados** (SKIPPED) para preservar compatibilidade com Mixins do NPCDBC

---

## 5. CLASSWRITER FLAGS

Todas as classes usam:
```java
ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
```

**Não usa `COMPUTE_FRAMES`** para evitar problemas de verificação de frames com mistura de tipos `int`/`long` em variáveis locais.

---

## 6. PRÓXIMOS PASSOS PARA VALIDAÇÃO COMPLETA

1. [ ] **Teste no Minecraft** - Iniciar jogo com `JRMCore-v1.3.51-bignum.jar` na instância `nntdarkmp_copy`
2. [ ] **Verificar menu principal** - Sem `VerifyError` em `applyPacketTPChange` ou `addLong`
3. [ ] **Teste in-game TP > 2B** - Verificar que TP pode exceder `Integer.MAX_VALUE`
4. [ ] **Persistência** - Logout/login preserva valores long
5. [ ] **Rede** - Cliente/servidor sincronizam TP corretamente
6. [ ] **Compatibilidade NPCDBC** - Mixins não quebram com classes não-patchadas

---

## 7. ARQUIVOS DE REFERÊNCIA

- `PatchBig.java` - Patcher principal (versão corrigida)
- `PatchBig_restored.java` - Versão original antes das correções
- `VerifyClasses.java` - Script de verificação de bytecode
- `JRMCore-v1.3.51-bignum.jar` - JAR de teste (SHA-256: `A054F59F1327C4900CBDB76035B4C93E7AFD99F9D2AB6D564F67542E6C03D797`)
- `JRMCore-v1.3.51-original.jar` - JAR baseline (SHA-256: `0614265D665B6E60456D460255C1488377CAD1200AD6BD9444128D835FC28946`)

---

**Conclusão da verificação estática: ✅ APROVADO**

Todas as correções de bytecode foram aplicadas e validadas. O teste decisivo agora é a execução real no Minecraft 1.7.10.