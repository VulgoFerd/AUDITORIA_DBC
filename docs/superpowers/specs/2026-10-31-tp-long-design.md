# TP em 64 bits — design

Data: 2026-10-31
Instância alvo: `C:\Users\Fernando\Desktop\NanatsuCore\nntdarkmp_copy` (nunca a original)
Mods na entrega: JRMCore 1.3.51, DragonBlockC 1.4.85, npcdbc 1.2.1-hotfix1

## Intenção

O TP do mod é a moeda de armazenamento do server. Como ele é `int`, satura em
2.147.483.647. Perto do teto o TP ganho passa a ser descartado e é preciso ir ao
banco converter TP em item-moeda e guardar em baú para liberar espaço.

Objetivo: o TP passa a ser saldo de 64 bits. O jogador acumula 10 bi, 100 bi, 1
trilhão — e gasta direto, sem passar pelo banco de moedas.

## Objetivos

- TP persistido, sincronizado e exibido em 64 bits; ganho não satura em 2,1 bi.
- `/jrmctp` aceita e aplica valores acima de 2,1 bi.
- Exibição: abaixo de 10 bi, número com separador; a partir de 10 bi, abreviado.
- Mundos já existentes (NBT com `jrmcTpint` int) continuam carregando.

## Não-objetivos

- Atributos (força, defesa, etc.) continuam `int`.
- Vida (HP) não muda: já é `float` do Minecraft.
- Custo de upgrade continua `int`: é ordens de grandeza menor que o saldo.
- Dano acima de 2,1 bi fica **fora desta entrega** (ver "Onda 2").
- Não mexer em Skript / boosts do server nem na economia do banco.

## Anti-objetivos (falha mesmo se "funcionar")

- Cliente e servidor com saldos diferentes.
- TP sumindo ao desconectar, trocar de dimensão, morrer ou salvar.
- Save antigo quebrando ao abrir o mundo.
- Duas fontes de verdade para o mesmo saldo.

## Restrições descobertas no código

| Peça | Estado atual | Onde |
|---|---|---|
| `JRMCoreH.curTP` | `public static int` | 10 classes |
| NBT `jrmcTpint` | `NBTTagInt` | 9 classes |
| `JRMCorePStats2` | `writeInt`/`readInt` | pacote de sync |
| `JRMCoreComTickH.dat11` | TP como **String** por tick | segundo canal de sync |
| `DBCData.saveFromNBT` | grava `jrmcTpint` de volta do cache `int TP` | npcdbc |
| `numSep(long)` | já recebe 64 bits | ~100 usos |
| `numSepShort(long)` | já abrevia (Thousand…Vigintillion) | 5 usos |
| Sítios de exibição de TP | já fazem cast `(long)curTP` | ~25 pontos |

### Três detalhes que mudam o plano

1. **A exibição já está quase pronta.** `numSep(long)` já aceita 64 bits e os
   sítios de TP já fazem `(long)curTP` — trocar o campo para `long` não quebra
   esses pontos.
2. **Existe um segundo canal de sync.** `JRMCoreComTickH.dat11[playerID]` monta
   o TP como texto (`"" + nbt.getInteger("jrmcTpint")`) e o cliente faz
   `Integer.parseInt`. Converter só o pacote faz o valor piscar a cada tick.
3. **O npcdbc escreve o TP de volta.** `DBCData.saveFromNBT` grava `jrmcTpint`
   a partir de um cache `int`. Se salvar depois de um ganho, sobrescreve o long.

## Abordagens consideradas

1. **`long` de ponta a ponta no mod** — campo, NBT, sync e telas em 64 bits.
   Um dono só do saldo. **Escolhida.**
2. **`long` + espelho `int` lido pelo código legado** — rejeitada: dois donos.
3. **Banco + buffer no V (sem tocar no mod)** — rejeitada: o ganho é clampado
   dentro do tick, antes de qualquer sistema externo ler (`wast` em
   `JRMCorePacHanS`), então ganho grande continua evaporando. Também exigiria
   toolchain nova e criaria duas fontes de verdade.

## Desenho escolhido

### 1. Armazenamento

- Nova chave `jrmcTplong` (`NBTTagLong`) é a **fonte de verdade**.
- `jrmcTpint` continua existindo como **derivado write-only**:
  `jrmcTpint = (int) min(jrmcTplong, Integer.MAX_VALUE)`.
- Regra dura: o código do mod **nunca lê `jrmcTpint` de volta**.
- `DBCData.saveFromNBT` passa a **não gravar** `jrmcTpint`; `loadFromNBT` lê o
  long quando existir.
- Migração preguiçosa: se `jrmcTplong` não existe, semear a partir de
  `jrmcTpint` e gravar o long. Sem comando, sem versão de save.

### 2. Memória e sincronização

- `JRMCoreH.curTP`: `int` → `long`. Mudar o tipo muda o descritor do campo para
  `J`, então toda classe que referencia `curTP` precisa ser reescrita junto.
- `Integer.parseInt` → `Long.parseLong` nos dois caminhos (pacote e string).
- `JRMCorePStats2`: campo e serialização em `writeLong`/`readLong`.
- `dat11` passa a carregar o valor long como string.
- `getMaxTP()` deixa de limitar saldo; ganho soma direto no long, com teto
  apenas em `Long.MAX_VALUE`.

### 3. Exibição

Novo helper `JRMCoreH.tpShort(long)` com corte em 10.000.000.000.

- `< 10 bi` → `numSep` (separador de milhar, como hoje)
- `>= 10 bi` → sufixo curto, 1 casa decimal truncada:
  `B` = 10⁹, `T` = 10¹², `Q` = 10¹⁵, `Qi` = 10¹⁸

Os ~25 sítios de TP trocam `numSep((long)curTP)` por `tpShort(curTP)`. Sítios
de custo continuam com `numSep`. Sem tooltip/hover.

### 4. Superfície de patch (tudo-ou-nada)

| Jar | Classes |
|---|---|
| JRMCore | `JRMCoreH`, `ComJrmctp`, `ComJrmc`, `ComJrmcCheck`, `ComJrmcRacialSkill`, `JRMCoreComTickH`, `JRMCorePacHanS`, `JRMCorePacHanC`, `JRMCorePStats2`(+`Handler`), `JRMCoreGuiScreen`, `JRMCoreGuiBars`, `JRMCoreGuiSCM` |
| DBC | `DBCTalkGui` |
| npcdbc | `DBCData`, `StatSheetGui` |

`curTP` é campo público estático: mudar o tipo quebra em link-time
(`NoSuchFieldError`) quem não for reescrito. Não existe rollback parcial — os
três jars viajam juntos.

### 5. Rollback

Reverter = restaurar os três jars originais de uma vez. O jar antigo ignora
`jrmcTplong` e lê `jrmcTpint`, que continua válido até 2,1 bi. Saldo acima
disso é truncado no rollback — aceito e documentado.

## Onda 2 (fora desta entrega): dano

`jrmcDam` e `jrmcEnergyDam` continuam `int`. Mudar o descritor amarra o
`@Inject` do `MixinJRMCoreH` do npcdbc (que grava `jrmcDam(...;B)I` na
anotação e usa `CallbackInfoReturnable<Integer>`), elimina o rollback por jar e
o ganho é hipotético hoje — o dano já nasce `int` na origem.

Retomar a onda 2 só depois de responder: de onde viria um hit de 10 bi?

## Decisões do construtor (revisar)

1. Descartar a abordagem de banco + buffer (motivo na seção Abordagens).
2. `jrmcTplong` é verdade; `jrmcTpint` é espelho write-only e **nenhum código
   lê** o espelho.
3. Migração preguiçosa com semeadura única; sem flag de versão.
4. Reescrita do Fieldref `curTP` de `I` para `J` nos três jars, como uma
   entrega só.
5. Sufixos `B`/`T`/`Q`/`Qi`, uma casa decimal truncada.
6. Dano fica para a onda 2.

## O que fica para quem constrói

Lista exata dos sítios de exibição e de leitura/escrita, ordem de gravação no
NBT, implementação do ASM writer por classe e plano de teste com os valores
2,1 bi / 10 bi / 1 T / 1 Q, incluindo desconectar-reconectar, morte, `/reload`
e abertura de save antigo.
