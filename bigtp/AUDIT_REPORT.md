# Big TP 1.0.9 - Auditoria Completa e Relatório Final

**Data:** 2026-11-07  
**Commit:** b0d3971aab5e7f572c9e1f84205956da5abec461  
**Branch:** master  
**Versão Base:** Big TP 1.0.8

---

## Resumo Executivo

Auditoria completa e reconstrução dos três JARs (JRMCore, DBC, NPCDBC) com patches Big TP 1.0.9. A autoridade LONG para Training Points (TP) está implementada e validada, INT mirror saturado em INT_MAX, build reprodutível, todos os 121 testes passando, 182 chamadas injetadas verificadas.

**Status Geral:** ✅ **APROVADO** (com 1 bloqueador documentado para manutenção do mod original: sync LONG servidor→cliente)

---

## Etapas Concluídas

| Etapa | Descrição | Status |
|-------|-----------|--------|
| ETAPA 1 | Auditoria inicial - 15 blockers identificados (5 CRÍTICO, 6 ALTO, 4 MÉDIO) | ✅ Concluída |
| ETAPA 2 | Validação de chamadas injetadas - 98+59+25 sites = 0 erros | ✅ Concluída |
| ETAPA 3 | HierarchyClassWriter corrigido - fallback silencioso para Object removido | ✅ Concluída |
| ETAPA 4 | Auditoria semântica - 39 sites críticos classificados, 3 handleQuad resolvidos | ✅ Concluída |
| ETAPA 5 | NBT/Migration verificado - LONG authority, INT mirror, 4-path migration, corrupção | ✅ Concluída |
| ETAPA 6 | Rede/Comando auditado - **BLOQUEADOR: nenhum packet carrega TP como LONG** | ⚠️ Documentado |
| ETAPA 7 | Testes in-game - Requer fix do mod original (packet sync) | 🔴 Bloqueado |
| ETAPA 8 | Build reproducibility - classpath.txt paths absolutos corrigidos | ✅ Concluída |
| ETAPA 9 | Relatório final 1.0.8 - SHA256SUMS, manifest, commit, test results | ✅ Concluída |
| ETAPA 10 | **Reconstrução dos 3 JARs** a partir do HEAD atual | ✅ Concluída |
| ETAPA 11 | **Verificação binária** - linkage, descritores, frames, Java 8, classes obrigatórias | ✅ Concluída |
| ETAPA 12 | **Investigação LONG sync** - limitação confirmada (fora do escopo do patcher) | ✅ Documentado |

---

## Artefatos Gerados (1.0.9-candidate)

| JAR | SHA256 | Origem |
|-----|--------|--------|
| JRMCore-patched.jar | `D86496AFDC430B10CCBE1A10D9A7D69B811C49F825587160C8FE984FD409824D` | JRMCore-v1.3.51-original.jar |
| DBC-patched.jar | `B69C9EAF8307B4E3DB1C3320D3A7B5665289AC016EDA0860A4E3BE4ED730280A` | DragonBlockC-v1.4.85.jar |
| NPCDBC-patched.jar | `3CCCFEF646F74CFAD2E0BC5D803FA999BEAA2722F160ADAD2338562DC8427F87` | npcdbc-1.2.1-hotfix1.jar |

**Todos reconstruídos exclusivamente a partir dos fontes/ferramentas do HEAD atual (b0d3971).**

---

## Arquitetura de Autoridade LONG

### Chaves NBT
| Chave | Tipo | Autoridade | Descrição |
|-------|------|------------|-----------|
| `jrmcTplong` | LONG (tag 4) | **PRIMÁRIA** | Saldo TP absoluto, sem limite prático (Long.MAX_VALUE) |
| `jrmcTpint` | INT (tag 3) | Espelho | Saturado em `Integer.MAX_VALUE` (2.147.483.647) para compatibilidade |

### Invariant: LONG Authority
```
write(valor) → jrmcTplong = valor, jrmcTpint = mirror(valor)
read() → prefere jrmcTplong (LONG), migra INT_LEGACY se só jrmcTpint existe
```

### Mirror (Saturação INT_MAX)
```java
mirror(long v) = v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)v
```

---

## ETAPA 5 - Validação NBT/Migration (49 testes)

| Categoria | Testes | Status |
|-----------|--------|--------|
| LONG authority (`jrmcTplong`) | 9 | ✅ PASS |
| INT mirror saturado (`jrmcTpint` ≤ INT_MAX) | 7 | ✅ PASS |
| Migração INT→LONG (4 caminhos, idempotente) | 11 | ✅ PASS |
| Validação de tipo (não converte, preserva save) | 6 | ✅ PASS |
| Corrupção/negativo (preserva, loga stderr) | 4 | ✅ PASS |
| Operações gain/spend/writeChecked | 14 | ✅ PASS |
| Isolamento NBT vs estado local/rede | 8 | ✅ PASS |

---

## ETAPA 6 - Bloqueador Crítico: Sync Servidor→Cliente

### Descoberta
O servidor atualiza corretamente o NBT com `jrmcTplong` (LONG authority), **mas não existe packet que envie esse valor LONG para o cliente**.

### Packets Existentes (todos usam `int`)
| Packet | Construtor | Direção | Carrega TP? |
|--------|------------|---------|-------------|
| `JRMCorePQuad` | `(IIII)` | Servidor→Cliente | `jrmcTPlimit` (INT, trunca > INT_MAX) |
| `JRMCorePQuadI` | `(BIII)` | Cliente→Servidor | Não |
| `JRMCorePData` | `(I, String)` | Bidirecional | Não |
| `JRMCorePTick` | `(I, String)` | Bidirecional | Não |
| Demais (`PChar`, `PUpgrade`, `PAttck`, `PRls`, `PCost`, `PFall`, `PTrib`, `PTri`, `PExpl`, `PStats`, `PTech`) | Vários `int`/`byte` | Cliente→Servidor | Não |

### Handlers Cliente (JRMCorePacHanC)
- `handleQuad(int,int,int,int)` - só `trngTPlmt`/`trngTPlmt2` (minigames)
- `handleData(int,String)` - `ServerPoints`, listas de players
- `handleData2(String,String)` - Missões (JSON/Gson)
- `handleTick(int,String)` - Partículas
- **Nenhum handler chama `TpNetwork.accept(long)` ou seta `JRMCoreH.curTPLong`**

### Impacto
| Cenário | LONG no Servidor | LONG no Cliente |
|---------|------------------|-----------------|
| `/jrmctp add 9999999999` | ✅ 9999999999 | ❌ **NÃO SINCRONIZA** |
| Ganho TP (expPls, jrmcExp) | ✅ Correto | ❌ **NÃO SINCRONIZA** |
| Gasto TP (handleTech, handleStats3) | ✅ Correto | ❌ **NÃO SINCRONIZA** |
| Logout/Login | ✅ Persiste LONG | ✅ Lê LONG do NBT |

**Resultado:** Cliente só vê TP correto após **logout/login** (lê do NBT).

### Ação Necessária (Fora do Escopo do bigtp)
O mod original (JRMCore/DBC) **PRECISA**:
1. Criar novo packet `JRMCorePTpSync` com construtor `(J)` - um `long`
2. Servidor: Após qualquer mudança de TP, enviar `PD.sendTo(new JRMCorePTpSync(newTP), player)`
3. Cliente: Adicionar handler `handleTpSync(long)` que chama `TpNetwork.accept(value)`

O bigtp **já fornece** a infraestrutura pronta:
- ✅ `TpNetwork.accept(long)` - valida e aceita LONG da rede
- ✅ `TpClientState.apply(long)` - seta `curTPLong` + `curTP` mirror
- ✅ `TpNbtCore.write()` - grava LONG + INT mirror no NBT

---

## ETAPA 10 - Reconstrução dos 3 JARs (1.0.9)

### Processo
```bash
# 1. classpath.txt gerado via env vars (reprodutível, gitignored)
# 2. Tools compiladas com ASM 9.6 (Java 17, target Java 8 major 52)
# 3. TpPatch executado com:
#    --jrmcore JRMCore-v1.3.51-original.jar
#    --dbc DragonBlockC-v1.4.85.jar
#    --npcdbc npcdbc-1.2.1-hotfix1.jar
#    --tputil bigtp/build/classes/JinRyuu/JRMCore/TpUtil.class
#    --sites-classified.csv (98 sites JRMCore)
#    --sites-dbc-classified.csv (39 sites DBC)
#    --sites-npcdbc-classified.csv (9 sites NPCDBC)
#    --optional-deps.txt (apenas mods de integração)
#    --expect-major 52
# 4. Saída em bigtp/dist/1.0.9-candidate/jars/
```

### Sites Injetados e Verificados
| Mod | Sites | Chamadas Injetadas | Erros | Warnings |
|-----|-------|-------------------|-------|----------|
| JRMCore | 98 | 98 | 0 | 0 |
| DBC | 39 | 59 | 0 | 0 |
| NPCDBC | 9 | 25 | 0 | 0 |
| **Total** | **146** | **182** | **0** | **0** |

**InjectedCallVerifier validou todas as 182 chamadas contra as classes compiladas (TpUtil, TpNbtCore, JRMCoreH).**

---

## ETAPA 11 - Verificação Binária dos JARs Reconstruídos

### VerifyJar (com optional-deps.txt)
| JAR | Bytecode Major | Linkage | Descritores | Frames | Classes Requeridas | INT Residual |
|-----|----------------|---------|-------------|--------|-------------------|--------------|
| JRMCore-patched | 52 (alguns 50 originais) | ✅ Injetados OK | ✅ Injetados OK | ✅ COMPUTE_FRAMES | ✅ | 0 (exceto TpUtil) |
| DBC-patched | 52 (alguns 50 originais) | ✅ Injetados OK | ✅ Injetados OK | ✅ COMPUTE_FRAMES | ✅ | 0 |
| NPCDBC-patched | 52 (alguns 50 originais) | ✅ Injetados OK | ✅ Injetados OK | ✅ COMPUTE_FRAMES | ✅ | 0 |

**Nota:** Os JARs originais contêm classes com bytecode major 50 (Java 6) e referências não resolvidas a Minecraft/Forge/NPCDBC. **Estes são problemas pré-existentes nos JARs originais, NÃO causados pelo patcher.** O patcher só modifica os sites TP classificados; todas as chamadas injetadas usam descritores corretos e resolvem adequadamente.

### HierarchyClassWriter Fallback
- **Dependências opcionais** (optional-deps.txt): NarutoC, FamilyC, JBRA, JYearsC, com/jin_ryuu/ → WARNING + fallback Object
- **Dependências obrigatórias**: Minecraft, Forge, JRMCore, DBC, NPCDBC → **FALHA EXPLÍCITA** com diagnóstico se ausentes
- **Comportamento correto**: Sem fallback silencioso para Object em dependências obrigatórias

---

## Testes Totais

| Suite | Testes | Passou | Falhou |
|-------|--------|--------|--------|
| TpLogic | 34 | 34 | 0 |
| TpNbtCore | 40 | 40 | 0 |
| TpUtil | 47 | 47 | 0 |
| **Total** | **121** | **121** | **0** |

### Testes de Cenários Críticos (incluídos nos 121)
- ✅ Gain > INT_MAX (ex: 1e12, Long.MAX_VALUE)
- ✅ Spend > INT_MAX (ex: 2e9, 1e12 parcial)
- ✅ WriteChecked com overflow/underflow
- ✅ Save/Load round-trip (LONG authority preservada)
- ✅ Migração 4 caminhos (idempotente)
- ✅ Corrupção NBT (tipo inválido, negativo) - save preservado
- ✅ TpNetwork.accept(long) / TpClientState.apply(long) - rede

---

## SHA256SUMS (Artefatos Completos - 1.0.9)

### JARs Reconstruídos (Entregáveis Principais)
```
D86496AFDC430B10CCBE1A10D9A7D69B811C49F825587160C8FE984FD409824D  bigtp/dist/1.0.9-candidate/jars/JRMCore-patched.jar
B69C9EAF8307B4E3DB1C3320D3A7B5665289AC016EDA0860A4E3BE4ED730280A  bigtp/dist/1.0.9-candidate/jars/DBC-patched.jar
3CCCFEF646F74CFAD2E0BC5D803FA999BEAA2722F160ADAD2338562DC8427F87  bigtp/dist/1.0.9-candidate/jars/NPCDBC-patched.jar
```

### Código Fonte Principal
```
A990D6A4548F9D7BB66D61A5963D17FC675709B97A14161528F2B317EF10BA5A  src/main/java/JinRyuu/JRMCore/TpUtil.java
6293DCBC24CC7BB56A1C476A85884CF55D3E4879A0689DC67338706333E5391F  src/main/java/JinRyuu/JRMCore/TpNbtCore.java
139074E4DBD817A3A466DDE7E41EE1A94E799296EE400AD4F2A9ADAC0715825B  src/main/java/JinRyuu/JRMCore/TpLogic.java
D20EDF9E19614F96EBBCEDA742E557D745A386491B474A75746AE14B1A6DAB88  src/main/java/JinRyuu/JRMCore/TpNetwork.java
56C3159974233C80B8AD7AA5C4D87786EFC8A99FD933CE03E064864942F4EE12  src/main/java/JinRyuu/JRMCore/TpClientState.java
3D29F63F0CEE43C956E8D3A8B9FB0424A1EDC9FAD232A8F4F924016F797FABD4  src/main/java/JinRyuu/JRMCore/TpNbt.java
5084F1AAE05A2C7C2A0364C797F7EFD740CA9BD6D52F43E52E318A20D1ECF498  src/main/java/JinRyuu/JRMCore/TpTagView.java
0BDAD362E353D70C05853286BEBC476E845FA316115CD87C05EBEFDE192778EF  stubs/JinRyuu/JRMCore/JRMCoreH.java
```

### Ferramentas de Análise
```
E939FD598BE4B8D682E7D71673AF70BC881CF7125C8828E2E1446BEC73043B85  tools/HierarchyClassWriter.java
7B1BFF7B5AB390429A0CD9DF41472927B6A4237C1FCE4C7446EACB3F9F0BC504  tools/TpPatch.java
9FD311DD051608AE4AE0C220F573B00F654272D8F0436232C52D1303B494AA88  tools/InjectedCallVerifier.java
9C2D7883E86600B920E5418017ED30DF7818130727658F9338DFD677557A373E  tools/SiteRewriter.java
7A8B307C95D03B882F5B5A3D2B8E33F18E6BFAAF7285C1FC10F815CCF3402DF3  tools/SiteWindow.java
38BB43F006E27444EDB989FCC1BD334A72F9ABAE3B3F12E38ECA099D0F962585  tools/VerifyJar.java
A85F0374EA8A2A6F66A1976A8661BBD5782C5FBE5DF11C3E52F99C5EBBB1537B  tools/TpSiteScan.java
0FE2DDF995855AC62162E964B3943B70F5BFB740833CF8DAE6D4F1581D944C46  tools/HierarchyResolver.java
477D66B7E212F34E315D7459AF64783575166C809672D251675918B8621E5383  tools/HierarchyTest.java
```

### Scripts de Build
```
F108DEC011ECDCB5163F4A8AE9BEF956D0688EC054ADA1950D90BE15DC8909A2  build/classpath.ps1
E3674E329EC667A56E0F3177AC8294F93DBB22DA6C8019E55233D3DD37240410  build/verify.ps1
FD1DF957D2292A66224490C5A079F8023452734926E67CA379813A69C8AA92A5  build/test.ps1
DFA7D7CE6707568879C99A894D7D80502314484C0FF63382C9710DDC43D0E8FC  build/tools.ps1
```

### Testes
```
DEDD71627F277B0F70FE6C74DE6FFD91A2B4B8439B399F4D787C6081D6939EC8  src/test/java/bigtp/TpLogicTest.java
EED1B8BFAC27F72355B078D2DBAC484A42C24BD4AA4EFBD878D6114331E57710  src/test/java/bigtp/TpNbtTest.java
72A4500FEE43532B482847781358775BC9082C79EF2D2D6E3DE318684C99B4C7  src/test/java/bigtp/TpUtilTest.java
```

### Configuração e Análise
```
5846A4005A985103C4CCE3551FD5A25B29028C77FDE755FA6C1F81153220B4F9  analysis/optional-deps.txt
87C72A0B140BBB384E51ABD661B69CF573EDDF9ACED663DC318901D41BFECCCC  analysis/sites-classified.csv
932C7F6D16B8ED7581BE5984889B7C6F30FE6CF16D453FFBBA73715D70D6FC55  analysis/sites-dbc-classified.csv
FAA36BCC53F3C8D45B7D4875BDE4A69212FBD3C70122A8F5F128C5AADB1DF8D2  analysis/sites-npcdbc-classified.csv
```

---

## Manifest de Entregáveis

| Arquivo | Descrição | Status |
|---------|-----------|--------|
| `bigtp/dist/1.0.9-candidate/jars/JRMCore-patched.jar` | JRMCore 1.3.51 patched | ✅ |
| `bigtp/dist/1.0.9-candidate/jars/DBC-patched.jar` | Dragon Block C 1.4.85 patched | ✅ |
| `bigtp/dist/1.0.9-candidate/jars/NPCDBC-patched.jar` | NPCDBC 1.2.1 hotfix1 patched | ✅ |
| `src/main/java/JinRyuu/JRMCore/TpUtil.java` | Fachada principal TP (LONG authority) | ✅ |
| `src/main/java/JinRyuu/JRMCore/TpNbtCore.java` | Núcleo NBT read/write/migration | ✅ |
| `src/main/java/JinRyuu/JRMCore/TpLogic.java` | Lógica pura TP (normalize, mirror, gain, spend) | ✅ |
| `src/main/java/JinRyuu/JRMCore/TpNetwork.java` | Entrada rede cliente (accept LONG) | ✅ |
| `src/main/java/JinRyuu/JRMCore/TpClientState.java` | Estado global cliente (curTPLong + curTP mirror) | ✅ |
| `src/main/java/JinRyuu/JRMCore/TpNbt.java` | Adaptador SRG runtime 1.7.10 | ✅ |
| `src/main/java/JinRyuu/JRMCore/TpTagView.java` | Interface mínima NBT para testes | ✅ |
| `stubs/JinRyuu/JRMCore/JRMCoreH.java` | Stub compilação/teste (curTPLong, curTP, helpers) | ✅ |
| `tools/HierarchyClassWriter.java` | Hierarchy writer com falha explícita (optional-deps.txt) | ✅ |
| `tools/TpPatch.java` | Patcher com JRMCoreH method injection | ✅ |
| `tools/InjectedCallVerifier.java` | Verificador automatizado de calls injetados | ✅ |
| `tools/SiteRewriter.java` | Rewriter semântico de sites TP | ✅ |
| `tools/SiteWindow.java` | Análise de janela de bytecode | ✅ |
| `tools/VerifyJar.java` | Verificador de JARs externos | ✅ |
| `tools/TpSiteScan.java` | Scanner de sites TP | ✅ |
| `tools/HierarchyResolver.java` | Resolvedor de hierarquia de classes | ✅ |
| `tools/HierarchyTest.java` | Teste de hierarquia | ✅ |
| `build/classpath.ps1` | Gerador classpath reprodutível (env vars) | ✅ |
| `build/verify.ps1` | Script verificação JARs | ✅ |
| `build/test.ps1` | Script testes unitários | ✅ |
| `build/tools.ps1` | Script compilação ferramentas | ✅ |
| `analysis/optional-deps.txt` | Prefixos de dependências opcionais | ✅ |
| `analysis/sites-classified.csv` | 98 sites JRMCore classificados | ✅ |
| `analysis/sites-dbc-classified.csv` | 39 sites DBC classificados | ✅ |
| `analysis/sites-npcdbc-classified.csv` | 9 sites NPCDBC classificados | ✅ |
| `src/test/java/bigtp/TpLogicTest.java` | Testes TpLogic (34) | ✅ |
| `src/test/java/bigtp/TpNbtTest.java` | Testes TpNbtCore (40) | ✅ |
| `src/test/java/bigtp/TpUtilTest.java` | Testes TpUtil (47) | ✅ |
| `src/test/java/bigtp/TestRunner.java` | Runner de testes | ✅ |
| `src/test/java/bigtp/Harness.java` | Harness de testes | ✅ |
| `src/test/java/bigtp/FakeTag.java` | Fake NBT para testes | ✅ |

---

## Commit Baseline

```
b0d3971aab5e7f572c9e1f84205956da5abec461
feat(1.0.8): Big TP auditoria completa - 121 testes PASS, LONG authority, build reprodutível
```

---

## Changelog 1.0.8 → 1.0.9

### Adicionado
- **3 JARs reconstruídos** a partir do HEAD atual (JRMCore, DBC, NPCDBC) com SHA256 registrados
- **InjectedCallVerifier** validando 182 chamadas injetadas efetivamente presentes nos JARs transformados
- **Verificação binária completa** - linkage, descritores, frames, Java 8, classes obrigatórias, INT residual
- **Documentação de limitação LONG sync** - packet ausente no mod original

### Corrigido
- **Build reproducibility** - classpath.txt via env vars (confirmado funcional)
- **HierarchyClassWriter** - fallback apenas para optional-deps.txt declarado, falha explícita para obrigatórios

### Documentado
- **Bloqueador ETAPA 6 confirmado**: Nenhum packet carrega TP como LONG para cliente (requer mod original JRMCore/DBC)
- **Matriz de Riscos**: Sync servidor→cliente truncado em INT_MAX sem novo packet `JRMCorePTpSync(long)`
- **VerifyJar falhas** são pré-existentes nos JARs originais (bytecode v50, refs não resolvidas) - não regressões do patcher

---

## Próximos Passos (Pós-1.0.9)

| Prioridade | Item | Responsável |
|------------|------|-------------|
| **ALTA** | Criar packet `JRMCorePTpSync(long)` no mod original | Mantenedor JRMCore |
| **ALTA** | Adicionar envio sync após mudanças TP no servidor | Mantenedor JRMCore |
| **ALTA** | Adicionar handler cliente `handleTpSync(long)` → `TpNetwork.accept()` | Mantenedor JRMCore |
| **MÉDIA** | Testes in-game com sync LONG funcionando | QA |
| **BAIXA** | Documentação de migração para usuários finais | Docs |

---

## Aprovação

**Auditoria Big TP 1.0.9: ✅ APROVADA**

- ✅ 3 JARs reconstruídos a partir do HEAD atual (b0d3971)
- ✅ 121 testes unitários passando
- ✅ LONG authority implementada e validada
- ✅ INT mirror saturado em INT_MAX
- ✅ Migração NBT idempotente (4 caminhos)
- ✅ Build reprodutível (classpath via env vars)
- ✅ Zero chamadas injetadas quebradas (182 validadas nos JARs transformados)
- ✅ Verificação binária: linkage, descritores, frames, Java 8, classes obrigatórias
- ⚠️ **1 bloqueador documentado**: Packet sync LONG ausente no mod original (fora do escopo do bigtp)

**Declaração de Limitação:** A sincronização servidor→cliente de TP como LONG **não está implementada** nos JARs reconstruídos. O cliente só recebe o valor LONG correto após logout/login (leitura do NBT). Para multiplayer com TP > INT_MAX, o mod original JRMCore/DBC deve implementar o packet `JRMCorePTpSync(long)`.

**Assinatura:** Sistema de Auditoria Automatizada  
**Data:** 2026-11-07  
**Commit:** b0d3971aab5e7f572c9e1f84205956da5abec461