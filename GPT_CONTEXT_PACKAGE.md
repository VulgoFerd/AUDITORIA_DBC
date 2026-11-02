# CONTEXTO PARA GPT - Dragon Block C TP Long Patch (Minecraft 1.7.10)

## 🎯 OBJETIVO
Remover limite de 2.147.483.647 (Integer.MAX_VALUE) de TP (Training Points) no Dragon Block C, elevando para 64-bit Long (~9 quintilhões).

## 🏗️ ARQUITETURA
| Variável | Tipo | Papel |
|----------|------|-------|
| `jrmcTplong` | `long` (64-bit) | **Authority** - valor real no NBT do player |
| `jrmcTpint` | `int` (32-bit) | **Mirror saturado** - compatibilidade npcdbc/pacotes legados |
| `getMaxTP()` | `int` | Display/UI - retorna `Integer.MAX_VALUE` (assinatura `()I` não muda) |

**Regras:**
- `setLongAuthority(tp)` → escreve `jrmcTplong = tp` + `jrmcTpint = saturate(tp)`
- `setIntMirrorOnly(tp)` → escreve **só** `jrmcTpint` (não toca no long)
- Código legado (npcdbc, ComJrmctp, JRMCoreComTickH) continua usando `jrmcTpint`

## 🛠️ TOOLCHAIN
- **ASM 9.7.1** + **CFR 0.152** + **JDK 17**
- **Patch estático** (sem Mixin/sources — não temos `forgeBin`/`srg-named jar`)
- **SafeClassWriter** ignora classes ausentes no classpath do patcher

## 📦 ARQUIVOS PRINCIPAIS
```
C:\Users\Fernando\Desktop\NanatsuCore\
├── artifacts\
│   ├── PatchBig.java                    # ← PATCHER PRINCIPAL (36KB, 615 linhas)
│   ├── JRMCore-v1.3.51-original.jar     # Baseline (2.560.555 bytes)
│   └── JRMCore-v1.3.51-bignum.jar       # Output patched (2.562.605 bytes)
├── config\
│   └── jinryuujrmcore_nova.cfg          # Config alvo: tmx=100000, atcm=0.015
└── nntdarkmp_copy\                       # Instância de teste
    ├── mods\JRMCore-v1.3.51-bignum.jar
    └── config\jinryuujrmcore.cfg        # ← Copia da config acima
```

## 🔧 CLASSES PATCHADAS (7 total)

| Classe | Mudança | Status |
|--------|---------|--------|
| `JRMCoreH` | Helpers long, `getInt`/`setInt` reescritos, `getMaxTP=Integer.MAX_VALUE` | ✅ Patched |
| `ComJrmctp` | **SKIPPED** - VerifyError long/int no command handler | ⏭️ Original |
| `JRMCoreComTickH` | **SKIPPED** - Mixin `dbc.MixinJRMCoreComTickH` quebra Frame.merge | ⏭️ Original |
| `JRMCorePacHanS` | **SKIPPED** - Mixin `dbc.MixinJRMCorePacHanS` quebra Frame.merge | ⏭️ Original |
| `JRMCorePStats2` | `curTP`: int→long, pacote `writeLong`/`readLong` | ✅ Patched |
| `JRMCorePCost` | `short`→`int` (custos Ki), remove `I2S` | ✅ Patched |
| `JRMCorePacHanC` | `handleStats2`: parâmetro `curTP` int→long | ✅ Patched |

## ⚠️ ERROS JÁ RESOLVIDOS
1. **`ArrayIndexOutOfBoundsException` em `Frame.merge`** → Pulamos patch nas classes com Mixin (JRMCoreComTickH, JRMCorePacHanS)
2. **`VerifyError: Bad type on operand stack` em `JRMCoreH.addLong`** → Corrigido índices de local variable (long = 2 slots)
3. **`VerifyError: Operand stack underflow`** → Corrigido stack management no `addLong`
4. **`Math.toIntExact` lançava `ArithmeticException` quando TP > 2.14B** → Trocado por `Math.min(value, Integer.MAX_VALUE)` + `toIntExact` (satura em vez de estourar)

## 🔴 ERRO ATUAL (Bloqueador)
```
VerifyError: Bad type on operand stack
Location: JinRyuu/JRMCore/JRMCoreH.applyPacketTPChange @29: invokestatic
Reason: Type integer (current frame, stack[2]) is not assignable to long_2nd
Current Frame:
  locals: { NBTTagCompound, EntityPlayer, long, long_2nd, integer, long, long_2nd }
  stack: { long, long_2nd, integer }
```

**Causa:** Em `applyPacketTPChange` (e também em `setLong`/`addLong`), chamo `Math.min(JJ)J` mas empurro constante **int** via `new LdcInsnNode(Integer.MAX_VALUE)`. O stack fica `{long, long_2nd, integer}` em vez de `{long, long_2nd, long, long_2nd}`.

**Locais exatos no PatchBig.java:**
- Linha ~208 (dentro de `setLong`)
- Linha ~264 (dentro de `addLong`)  
- Linha ~357 (dentro de `applyPacketTPChange`)

## ✅ CORREÇÃO NECESSÁRIA
Trocar **todos os 3** `new LdcInsnNode(Integer.MAX_VALUE)` por:
```java
new LdcInsnNode((long) Integer.MAX_VALUE)  // empurra LONG constant (2 slots)
```

## 🧪 COMANDOS DE TESTE
```bat
# Compilar patcher
C:\temp\compile_patch.bat

# Rodar patcher (gera artifacts/JRMCore-v1.3.51-bignum.jar)
C:\temp\run_patch.bat

# Copiar para instância de teste
copy "C:\Users\Fernando\Desktop\NanatsuCore\artifacts\JRMCore-v1.3.51-bignum.jar" "C:\Users\Fernando\Desktop\NanatsuCore\nntdarkmp_copy\mods\JRMCore-v1.3.51-bignum.jar"

# Testar: Launcher oficial → Game Directory = C:\Users\Fernando\Desktop\NanatsuCore\nntdarkmp_copy
```

## 📋 PRÓXIMOS PASSOS APÓS CORREÇÃO
1. Aplicar fix das 3 constantes `Integer.MAX_VALUE` → `(long)Integer.MAX_VALUE`
2. Recompilar + rodar patcher
3. Copiar JAR para instância de teste
4. Verificar se jogo carrega sem VerifyError
5. Validar in-game: TP passa de 2.147.483.647 (checar NBT do player com `/nbt` ou save)

## 📎 ANEXOS RELEVANTES
- **PatchBig.java completo** (ver arquivo `artifacts/PatchBig.java`)
- **Crash report completo** (ver `nntdarkmp_copy/crash-reports/crash-2026-11-01_21.27.57-client.txt`)
- **Config alvo** (`config/jinryuujrmcore_nova.cfg`):
  ```
  Attribute Maximum=100000
  atcm=0.015
  ```

---

**Resumo para o GPT:** "Preciso corrigir 3 ocorrências de `LdcInsnNode(Integer.MAX_VALUE)` para `LdcInsnNode((long)Integer.MAX_VALUE)` no patcher ASM para que `Math.min(JJ)J` receba long constants em vez de int. O erro é VerifyError em `applyPacketTPChange@29` com stack `{long, long_2nd, integer}`."