# BIG TP 1.0.7 — núcleo de runtime

Base de código da correção 1.0.7. Substitui a implementação de `TpUtil` da 1.0.6.

## Estrutura

```
src/main/java/JinRyuu/JRMCore/
  TpLogic.java       lógica pura (sem Minecraft): faixa, ganho, gasto, overflow
  TpTagView.java     visão mínima de NBT (permite teste sem Minecraft)
  TpNbtCore.java     leitura/escrita/migração com tipo real da tag
  TpNbt.java         adaptador SRG -> NBT real do 1.7.10
  TpClientState.java único ponto que altera JRMCoreH.curTPLong/curTP
  TpNetwork.java     estado recebido da rede (delega a TpClientState)
  TpUtil.java        fachada: leitura, escrita absoluta, ganho, gasto, espelho
stubs/               NBT/JRMCore SRG para compilar e testar fora do jogo
src/test/java/bigtp/ testes executáveis com `java`
```

## Política numérica

- Invariante: `0 <= TP <= Long.MAX_VALUE`.
- Valor absoluto negativo: rejeitado (`NEGATIVE_VALUE`), saldo não muda.
- Ganho negativo: rejeitado (`NEGATIVE_AMOUNT`).
- Ganho que ultrapassa `Long.MAX_VALUE`: rejeitado (`OVERFLOW`), sem saturação silenciosa.
- Gasto acima do saldo: rejeitado (`INSUFFICIENT`), saldo não muda.
- Gasto negativo: rejeitado (`NEGATIVE_AMOUNT`).
- `jrmcTpint` é somente espelho saturado em `Integer.MAX_VALUE`.

## Política de NBT

- `jrmcTplong` (TAG_Long = 4) é a autoridade.
- `jrmcTpint` (TAG_Int = 3) é espelho legado.
- A presença da chave nunca é usada como prova de tipo: `getTag` + `getId` são consultados.
- `jrmcTplong` com INT: migra preservando o valor.
- Somente `jrmcTpint` com INT: migra para `jrmcTplong` e regrava o espelho.
- `jrmcTpint` com LONG: migra para `jrmcTplong`.
- Tipo inesperado: `INVALID_TYPE`, valor tratado como 0, save **não** é sobrescrito.
- Valor negativo armazenado: `NEGATIVE_STORED`, save **não** é sobrescrito.
- Migração é idempotente.

## Separação obrigatória

| Operação | Método | Toca `curTP`/`curTPLong`? |
|---|---|---|
| Ler NBT | `TpUtil.getTP`, `TpUtil.inspect` | não |
| Escrever NBT (absoluto) | `TpUtil.write` | não |
| Ganho em NBT | `TpUtil.gain` | não |
| Gasto em NBT | `TpUtil.spend` | não |
| Estado local do cliente | `TpUtil.applyLocal` | sim |
| Estado recebido da rede | `TpUtil.acceptNetwork` | sim |

## Testes

```powershell
pwsh -File build/test.ps1
```

Cobre: 0, 1, `Integer.MAX_VALUE`, `Integer.MAX_VALUE + 1`, 3e9, 1e10, 1e12,
`Long.MAX_VALUE`, overflow, gasto parcial/exato/excessivo, migração INT→LONG,
migração repetida, tipo incorreto, valor negativo, dois jogadores, round-trip
de serialização, separação de estado e espelho.

Testes que exigem o jogo (comando `/jrmctp`, logout/login, sync real, GUI,
NPCDBC) estão listados como **NÃO EXECUTADOS** até haver evidência real.
