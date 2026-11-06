# Big TP 1.0.7 - GPT Context Package for Evaluation

## Resumo do Projeto
Implementação completa do sistema Big TP 1.0.7 (64-bit TP) para JRMCore/DragonBlockC/NPCDBC (Minecraft 1.7.10), corrigindo 12 blockers críticos identificados na auditoria da versão 1.0.6.

## Arquivos Principais (Patched JARs - Candidate 1.0.7)
- `bigtp/dist/1.0.7-candidate/jars/JRMCore-patched.jar` - JRMCore v1.3.51 com 55 sites patched
- `bigtp/dist/1.0.7-candidate/jars/DBC-patched.jar` - DragonBlockC v1.4.85 com 38 sites patched  
- `bigtp/dist/1.0.7-candidate/jars/NPCDBC-patched.jar` - NPCDBC 1.2.1-hotfix1 com 4 sites patched

## Verificação SHA256
```
73C5DDC6D0E3B3088CEA564216BAED0F556BC8D06EFDFBA5DD8048EB9D2A94F6  DBC-patched.jar
201603625AE2C9DFF8F08F5EEDF10A2599486A40DA7A3A331B07512AB73BD3F6  JRMCore-patched.jar
D92C1B224637F03213CA4220CD1A0C5BD625367B2C8A96D2AA894609106AF2F3  NPCDBC-patched.jar
```

## Documentação
- `bigtp/dist/1.0.7-candidate/AUDITORIA-1.0.7.md` - Relatório detalhado dos 12 blockers
- `bigtp/dist/1.0.7-candidate/CHANGELOG-1.0.7.md` - Changelog formal
- `bigtp/dist/1.0.7-candidate/jars/MANIFEST.txt` - Metadata completo do build

## Ferramentas de Build/Análise (bigtp/tools/)
- `TpPatch.java` - Patcher principal fail-fast + transacional
- `SiteRewriter.java` - Engine classificação per-site (116 callsites)
- `SiteWindow.java` - Janela de contexto para pattern matching
- `HierarchyClassWriter.java` - Frame computation com fallbacks (COMPUTE_FRAMES/COMPUTE_MAXS)
- `HierarchyResolver.java` - Resolução de hierarquia ASM
- `VerifyJar.java` - Verificador bytecode (SRG alias retry, interface-aware)
- `TpSiteScan.java` - Scanner inventário callsites

## Código Fonte (bigtp/src/main/java/JinRyuu/JRMCore/)
- `TpUtil.java` - API pública unificada
- `TpNbt.java` - Glue NBT (player/global/rede)
- `TpNbtCore.java` - Lógica NBT pura (testável sem Minecraft)
- `TpLogic.java` - Aritmética pura (absolute, gain, spend, mirror, delta)
- `TpClientState.java` - Estado cliente volátil
- `TpNetwork.java` - Handlers rede
- `TpTagView.java` - View tipada CompoundTag

## Análise de Sites (bigtp/analysis/)
- `sites-classified.csv` - 78 sites JRMCore (77 handled, 1 SKIP false positive)
- `sites-dbc-classified.csv` - 38 sites DBC (38 handled)
- `sites-npcdbc-classified.csv` - 4 sites NPCDBC (4 handled)
- `site-windows.txt` - Janelas de contexto para patterns

## Testes
- **121 testes runtime**: 121/121 PASS
  - Boundary values: 0, 1, INT_MAX, INT_MAX+1, 3e9, 1e10, 1e12, Long.MAX, negativos
  - Migração: ausente, long, int legacy, migração, repetida, long-key-int, int-key-long
  - Tipo inválido: tag ID 8, preservação save, não cria int espúrio
  - Negativo armazenado: normalização 0, não sobrescreve
  - Round-trip: write → read preserva
  - TpLogic: absolute, gain, spend, mirror, delta - overflow/underflow
  - TpUtil NBT real: 2 jogadores, ganho, gasto, overflow, applyLocal, acceptNetwork
  - Multiplayer simulado: 2 EntityPlayer mocks, NBTs isolados
  - NPCDBC: DBCData.TP:I mirror, TPLong:J authority

## Decisões Arquiteturais Chave
| Decisão | Implementação |
|---------|---------------|
| Authority | `jrmcTplong` (NBT LONG, tag ID 4) |
| Mirror legado | `jrmcTpint` (NBT INT, tag ID 3) saturado em INT_MAX |
| Migração | Persistente + idempotente (remove legacy key) |
| Tipo inválido | Save preservado, log erro, não cria mirror |
| Estados | NBT / ClientState / Network **separados** |
| Classificação | 116 sites × 6 adapters explícitos (READ/WRITE_ABS/WRITE_DELTA_ADD/WRITE_DELTA_SUB/STATE_LOCAL/SKIP) |
| TpPatch | Fail-fast + output transacional (ATOMIC_MOVE) |
| /jrmctp | Totalmente LONG, hierarchy ASM rebuild |
| Build | Reproduzível (paths relativos, deps auto, JDK17→Java8) |
| getMaxTP() | 2000000000 (INT) fixo; paths long ignoram cap |
| JRMCoreH.Stats2 | Preservado; Stats2Long adicionado; 3 PacHanS → Stats2Long |
| JRMCorePStats2.curTPLong | Package-private/public (evita IllegalAccessError) |
| NPCDBC DBCData | TP:I mirror + TPLong:J authority |

## Limitações Conhecidas
1. VerifyJar em JARs patched: classpath MCP incompleto (classes Minecraft/Forge não disponíveis). JARs originais falham igual - **não regressão**.
2. Testes in-game: não executados (requer Minecraft 1.7.10 + Forge + mods).
3. 1 false positive SKIP em JRMCore (documentado em sites-classified.csv).

## Como Executar Testes
```powershell
cd bigtp
$asm='..\tools\asm-9.7.1.jar;..\tools\asm-tree-9.7.1.jar;..\tools\asm-analysis-9.7.1.jar;..\tools\asm-commons-9.7.1.jar;..\tools\asm-util-9.7.1.jar'
& "$env:JAVA_HOME\bin\java.exe" -cp "build\classes;build\tools;$asm;dist\1.0.7-candidate\jars\JRMCore-patched.jar;dist\1.0.7-candidate\jars\DragonBlockC-patched.jar;dist\1.0.7-candidate\jars\npcdbc-patched.jar" bigtp.TestRunner
```

## Commit Info
- Base: 11403dff7f3d1cb9313069e5e6e66c21a9ce5e17
- Java: 17.0.20.1 (compile) → 8 (target, major 52)
- ASM: 9.7.1