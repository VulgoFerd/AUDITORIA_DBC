# DBC Training Points Caps Remoção - Instalação e Rollback

## Visão geral
Este conjunto de artefatos remove/levanta os limites numéricos do Training Points (TP) do Dragon Block C (DBC) na instância Minecraft 1.7.10 `C:\Users\Fernando\nntdarkmp`, produzindo apenas artefatos **fora** da instância.

A abordagem principal altera a autoridade de TP de `int` para `long`, eleva `getMaxTP()` de 2.000.000.000 para `Integer.MAX_VALUE` (2.147.483.647), e introduz um espelho `curTPLong` (`jrmcTplong`) que é a fonte da verdade, enquanto `jrmcTpint` permanece como um espelho saturado para compatibilidade com mods (npcdbc, DBC).

**Nunca modifique a instância `C:\Users\Fernando\nntdarkmp`.** Todos os artefatos são entregues na pasta de trabalho `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\`.

## Artefatos entregues

### 1. JRMCore-v1.3.51-bignum.jar
Arquivo patchado em `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\JRMCore-v1.3.51-bignum.jar`.

Classes patchadas (6 de 1013 total):

| Classe | Alterações |
|--------|-----------|
| `JinRyuu/JRMCore/JRMCoreH` | `getMaxTP()` → `Integer.MAX_VALUE`; adicionado campo `curTPLong` (`long`); `getInt()` agora prefere ler `jrmcTplong`; `setInt()` agora escreve em ambos `jrmcTplong` e `jrmcTpint` |
| `JinRyuu/JRMCore/ComJrmctp` | Pendente: comando `/jrmctp` usa `JrmcTp.addN/addI` (não fully rewritten devido à complexidade do método) |
| `JinRyuu/JRMCore/JRMCoreComTickH` | Migração legacy @1352: set `jrmcTpint` a partir de `jrmcTp` short → now verifica `jrmcTplong` primeiro |
| `JinRyuu/JRMCore/JRMCorePacHanS` | `handleStats2`: int → conversão long; `handleQuad/handleTech`: escritas convertidas para `JrmcTp.addN`; `handleCost`: remoção de `(short)` wrap em `costEnAt` |
| `JinRyuu/JRMCore/p/JRMCorePStats2` | Formato de pacote: `curTP` escrito como int (32 bits inferiores do long) |
| `JinRyuu/JRMCore/p/JRMCorePCost` | Construtor `short` → `int`; custos agora `int` em vez de `short` |

### 2. Configuração atualizada
`C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\jinryuujrmcore.cfg`:
- `Attribute Maximum=100000` (era 500)
- `atcm=0.015` (ajustado do valor 0.75 original)
- `Start Minus=140` (mantido)
- `Minimum=16` (mantido)

**Math econômico:**
- Custo total de atributo ≈ `13.5 * tmx²`
- O cap de 2e9 TP originalmente ligava em `tmx ≈ 12.170`
- Com TP `long` e cap `Integer.MAX_VALUE`, o cap começa a binding em `tmx ≈ 12170` (ainda limitado pelo int max em cálculos intermediários)
- O plano `tmx = 100.000` está bem dentro da faixa segura: `attrCst(int)` seguro até `tmx ≈ 4.77e8`
- `attrCst(int[],int)` (soma até 1000 entradas) estoura perto `tmx ≈ 1e6`; `tmx = 100.000` é o default planejado (atcm tuning accordingly)

### 3. Documentação
Este arquivo `INSTALACAO.md`.

## Instruções de instalação

### Instalação

1. **Faça backup da instância:**
   - Copie `C:\Users\Fernando\nntdarkmp\mods\JRMCore-v1.3.51.jar` para um local seguro

2. **Substitua o jar patchado:**
   - Copie `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\JRMCore-v1.3.51-bignum.jar`
   - Para: `C:\Users\Fernando\nntdarkmp\mods\JRMCore-v1.3.51.jar`

3. **Atualize a configuração:**
   - Copie `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\jinryuujrmcore.cfg`
   - Para: `C:\Users\Fernando\nntdarkmp\config\jinryuujrmcore.cfg`
   - Opcional: ajuste `atcm` se necessário (padrão 0.015 para tmx=100000)

4. **Reinicie o Minecraft**
   - O servidor e clientes devem usar o **mesmo** jar patchado
   - O formato de pacote changed: `JRMCorePStats2` agora transporta `curTP` como int (bits inferiores do long)
   - A autoridade real é `NBT jrmcTplong` no servidor

### Rollback (reverter)

1. **Restaure o jar original:**
   - Copie o backup de passo 1 de volta para `C:\Users\Fernando\nntdarkmp\mods\JRMCore-v1.3.51.jar`

2. **Restaure a configuração original:**
   - Restore `C:\Users\Fernando\nntdarkmp\config\jinryuujrmcore.cfg` para os valores originais:
     - `Attribute Maximum=500`
     - `atcm=0.75`
     - `Start Minus=140`
     - `Minimum=16`

3. **Reinicie o Minecraft**

### O que mudou (resumo técnico)

- `getMaxTP()`: de 2.000.000.000 para `Integer.MAX_VALUE` (2.147.483.647) — +7.4% de ganho teórico
- `jrmcTplong` (`long`): autoridade nova; tags NBT `jrmcTplong` escritas em vez de/apenas além de `jrmcTpint`
- `jrmcTpint` (`int`): espelho saturado em `Integer.MAX_VALUE`; mantido para compatibilidade com npcdbc/DBC
- `getInt(EntityPlayer, String)`: agora retorna `jrmcTplong` se presente, senão fallback para `(long)(int)jrmcTpint`
- `setInt(int, EntityPlayer, String)`: escreve em ambos `jrmcTplong` (long) e `jrmcTpint` (int, saturado)
- Clamps neutralizados: sites que retornavam `getMaxTP()` quando `TP > int max` agora permitem TP até `Integer.MAX_VALUE`
- `costEnAt`: `(short)` casts removidos; custo agora `int` fluido sem wrap
- `JRMCorePCost`: construtor `short` → `int`; leitura/escrita `writeInt`/`readInt`
- `handleCost(short)` → `(int)` em `JRMCorePacHanS`/`JRMCorePacHanC` mais guarda negativa

## Limitações e notas

- Nada pode ser testado in-game neste ambiente; a verificação deve ocorrer em servidor Minecraft 1.7.10 com DBC e npcdbc instalados
- O npcdbc e o DBC podem exigir patches adicionais (ver abaixo)
- `JRMCoreComTickH` @1352 migração legacy: ainda não fully rewritten; verifique se NPCs iniciam com TP correto
- Comando `/jrmctp`: rewrite pendente; usa lógica antiga até que seja patchado
- Atributos planos (`attrCst`) ainda têm limite interno de 1000 entradas por método; `tmx=100000` dentro dos limites
- Fator `atcm` foi reduzido de 0.75 para 0.015 para manter o custo de upgrade equilibrado no novo teto
- O espelho `jrmcTpint` ainda satura em `Integer.MAX_VALUE`; TP além disso requer comandos/loja
- O cliente exibe `curTP` (int mirror); o valor real está em `jrmcTplong` no servidor

## Arquivos modificado fora da instância

- `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\JRMCore-v1.3.51-bignum.jar` — jar patchado
- `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\jinryuujrmcore.cfg` — config atualizada
- `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re\INSTALACAO.md` — este documento

## Arquivos que NÃO foram modificados (ler-only)

- `C:\Users\Fernando\nntdarkmp\mods\JRMCore-v1.3.51.jar` — original (backup criado durante instalação)
- `C:\Users\Fernando\nntdarkmp\config\jinryuujrmcore.cfg` — original (backup durante rollback)
- Qualquer outro arquivo dentro de `C:\Users\Fernando\nntdarkmp/`