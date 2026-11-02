# RELATÓRIO FORENSE — CRASH `JRMCorePStats2.toBytes` (VerifyError)

**Data:** 2026-11-02
**JAR que falhou:** `JRMCore-v1.3.51-bignum.jar` SHA-256 `A054F59F1327C4900CBDB76035B4C93E7AFD99F9D2AB6D564F67542E6C03D797`
**Instância:** `C:\Users\Fernando\Desktop\NanatsuCore\nntdarkmp_copy`
**Log:** `logs/latest.log`
**Crash report:** não gerado (desconexão limpa do mundo, sem `crash-reports/*.txt`)

---

## 1. Sintoma observado

1. Jogo inicia normalmente, carrega 42 mods e chega ao menu principal.
2. Jogador entra no mundo e executa o comando de TP:
   ```
   [03:12:21] [Server thread/INFO]: [VulgoFerd: TP adding 2147483647 success for VulgoFerd]
   [03:13:01] [Server thread/INFO]: [VulgoFerd: TP adding 2147483647 success for VulgoFerd]
   ```
3. Ao clicar em **melhorar atributo** na GUI de stats, o mundo é derrubado para o menu principal.
4. Nenhum crash report é escrito. O erro aparece apenas em `latest.log`.

Observação relevante: foram **dois** adds de `2147483647` (03:12:21 e 03:13:01). Somados, o total passa de `4.294.967.294`, acima do teto int. O comando reportou sucesso nas duas vezes, o que confirma que o caminho de escrita long (`jrmcTplong`) não estava clampando. A falha está apenas na serialização do pacote de UI.

---

## 2. Erro exato no log

```
[03:13:12] [Server thread/ERROR]: SimpleChannelHandlerWrapper exception
java.lang.VerifyError: Bad type on operand stack
Exception Details:
  Location:
    JinRyuu/JRMCore/p/JRMCorePStats2.toBytes(Lio/netty/buffer/ByteBuf;)V @22: invokevirtual
  Reason:
    Type integer (current frame, stack[1]) is not assignable to long_2nd
  Current Frame:
    bci: @22
    flags: { }
    locals: { 'JinRyuu/JRMCore/p/JRMCorePStats2', 'io/netty/buffer/ByteBuf', '[I' }
    stack: { 'io/netty/buffer/ByteBuf', integer }
  Bytecode:
    0x0000000: 2ab4 001b bebc 0a4d 2b2a b400 2bb6 0031
    0x0000010: 572b 2ab4 0022 b600 3157 2b2a b400 24b6
    0x0000020: 0035 5703 3e1d 2cbe a200 142b 2ab4 001b
    0x0000030: 1d2e b600 3957 8403 01a7 ffec 2a2c b500
    ...
    chop_frame(@60,1)

	at JinRyuu.JRMCore.JRMCoreH.Stats2(JRMCoreH.java:7254) ~[JRMCoreH.class:?]
	at JinRyuu.JRMCore.JRMCorePacHanS.handleUpgrade(JRMCorePacHanS.java:2170) ~[JRMCorePacHanS.class:?]
	at JinRyuu.JRMCore.p.JRMCorePUpgrade$Handler.handleServerMessage(JRMCorePUpgrade.java:39) ~[JRMCorePUpgrade$Handler.class:?]
	at JinRyuu.JRMCore.p.JRMCorePUpgrade$Handler.handleServerMessage(JRMCorePUpgrade.java:28) ~[JRMCorePUpgrade$Handler.class:?]
	at JinRyuu.JRMCore.p.Amh.onMessage(Amh.java:54) ~[Amh.class:?]
	at cpw.mods.fml.common.network.simpleimpl.SimpleChannelHandlerWrapper.channelRead0(SimpleChannelHandlerWrapper.java:37)
```

O erro se repete em cada clique (7 ocorrências entre `03:13:12` e o fim do log).

---

## 3. Caminho de execução

```
clique em "melhorar stat" no cliente
  -> JRMCorePUpgrade$Handler.handleServerMessage
  -> JRMCorePacHanS.handleUpgrade      (lê NBT "pres", subtrai custo de "jrmcTpint")
  -> JRMCoreH.Stats2(player, tp, exp, align, attrs)
  -> new JRMCorePStats2(...)
  -> PD.sendTo(pacote, playerMP)
  -> JRMCorePStats2.toBytes(ByteBuf)   <-- VerifyError aqui
```

Esse é o primeiro ponto em que a classe `JRMCorePStats2` patcheada é carregada e verificada pela JVM. Por isso o jogo abria normalmente e só quebrava ao usar a GUI de atributos.

---

## 4. Causa raiz — 3 defeitos no patcher

Inspeção do bytecode do JAR quebrado (`InspectChain.java`, `InspectPStats2.java`, `InspectTpSites.java`):

### 4.1 `toBytes` — patch aplicado no campo errado

Estado no JAR quebrado:

| Campo | `GETFIELD` desc | chamada |
|-------|-----------------|---------|
| `curTP` | `J` | `writeLong(J)` — **correto** |
| `curExp` | `I` | `writeLong(J)` — **INCORRETO** |

O patcher trocava **todo** `writeInt(I)` dentro de `toBytes` por `writeLong(J)` quando encontrava um `GETFIELD` anterior. `curExp` também é `int` e ficou com `writeLong` recebendo um `int`. Resultado: `Type integer ... is not assignable to long_2nd` em `@22` — exatamente a segunda escrita do método.

### 4.2 Construtor — descritor não atualizado

O construtor real é `(IIB[I)V` (int, int, byte, int[]). O patcher assumia `(III[I)V`, então:

- a assinatura não foi alterada;
- `PUTFIELD curTP` continuou com descritor `I`;
- o campo `curTP` foi trocado para `J`.

Qualquer instanciação passaria a escrever `int` em campo `long`.

### 4.3 Consumidores não atualizados

`JRMCorePStats2$Handler.handleClientMessage` continuou lendo:

```java
GETFIELD JinRyuu/JRMCore/p/JRMCorePStats2.curTP I   // campo agora é J
INVOKEVIRTUAL JRMCorePacHanC.handleStats2(IIB[I...)V
```

`JRMCorePacHanC.handleStats2` também não foi alterado (a condição do patcher exigia `(III[I...)V`, que não existe). Cadeia ficou inconsistente em três pontos.

---

## 5. Defeito de processo que escondeu a correção anterior

`C:\temp\run_patch.bat` executava `java PatchBig` **sem recompilar**. O `PatchBig.class` usado era de `02/11/2026 00:40`, enquanto as correções no fonte eram de `02:24`. Ou seja: as edições no `PatchBig.java` nunca chegaram ao JAR.

**Correção de processo aplicada:**

- fonte único em `artifacts/PatchBig.java`, espelhado em `AUDITORIA_DBC/PatchBig.java`;
- `javac -encoding UTF-8` executado antes de cada patch;
- problema de BOM UTF-8 no arquivo também corrigido (o PowerShell `Set-Content -Encoding UTF8` inseriu `\ufeff` e quebrava a compilação).

---

## 6. Decisão de correção: reverter `JRMCorePStats2` para `int`

`JRMCorePStats2` é um **DTO de rede** cujo `curTP` alimenta apenas o display int do cliente (`JRMCoreH.curTP`). A cadeia real é int de ponta a ponta:

```java
// JRMCoreH.Stats2(EntityPlayer, int tp, int exp, byte align, int[] attrs)
// JRMCorePacHanC.handleStats2(int, int, byte, int[], EntityPlayer)
// JRMCoreH.curTP  (campo estático int)
```

Long-izar esse DTO exigiria alargar `Handler`, `PacHanC` e o campo estático `JRMCoreH.curTP`, todos compartilhados com UI e código tocado por Mixin. Ganho real: nenhum, porque o chamador já entrega um `int` (espelho saturado `jrmcTpint`).

**Portanto:** `patchJRMCorePStats2` passou a ser **SKIPPED**. A autoridade de 64 bits continua em `jrmcTplong` no NBT do player (helpers de `JRMCoreH`).

---

## 7. Verificação após a correção

Saída de `InspectChain` no novo JAR:

```
### JinRyuu/JRMCore/p/JRMCorePStats2.class
  FIELD curTP I
  FIELD curExp I
  METHOD <init>(IIB[I)V
  METHOD toBytes(Lio/netty/buffer/ByteBuf;)V
  METHOD fromBytes(Lio/netty/buffer/ByteBuf;)V

### JinRyuu/JRMCore/p/JRMCorePStats2$Handler.class
  FIELDOP 180 JinRyuu/JRMCore/p/JRMCorePStats2.curTP I
  CALL JinRyuu/JRMCore/JRMCorePacHanC.handleStats2(IIB[I...)V

### JinRyuu/JRMCore/JRMCorePacHanC.class
  METHOD handleStats2(IIB[I...)V
      FIELDOP 179 JinRyuu/JRMCore/JRMCoreH.curTP I

### JinRyuu/JRMCore/JRMCoreH.class
  FIELD curTP I
  METHOD Stats2(Lnet/minecraft/entity/player/EntityPlayer;IIB[I)V
```

Toda a cadeia voltou a ser `int`, consistente com o JAR original.

`VerifyClasses` — estrutura válida em todas as 7 classes processadas.

---

## 8. Artefato novo

| Item | Valor |
|------|-------|
| Arquivo | `artifacts/JRMCore-v1.3.51-bignum.jar` |
| SHA-256 | `221643729F567445AB04E2539009A67A8E9272625F1A6811B410E976DFB2243D` |
| Cópia de teste | `nntdarkmp_copy/mods/JRMCore-v1.3.51-bignum.jar` |
| Classes patcheadas de fato | `JRMCoreH`, `JRMCorePCost` |
| Classes intactas por design | `JRMCorePStats2`, `JRMCorePacHanC`, `JRMCorePacHanS`, `JRMCoreComTickH`, `ComJrmctp` |
| NPCDBC | `npcdbc-1.2.1-hotfix1.jar` com 2 mixins removidos |

---

## 9. Limitação conhecida (não bloqueante)

Em `JRMCorePacHanS.handleUpgrade` o custo é subtraído diretamente no NBT:

```java
nbt.setInteger("jrmcTpint", nbt.getInteger("jrmcTpint") - custo);
```

Como `jrmcTpint` satura em `Integer.MAX_VALUE`, acima de 2.147.483.647 o desconto acontece sobre o espelho saturado, e `jrmcTplong` não é debitado. Efeito: **acima do teto int, melhorias deixam de consumir TP**.

Não causa crash nem perda de valor. Correção futura exige redirecionar essa subtração para `JRMCoreH.addLong`, dentro de `JRMCorePacHanS` — classe com Mixin `dbc.MixinJRMCorePacHanS`, que hoje quebra `Frame.merge` se patchada.

---

## 10. Próximo teste obrigatório

1. Abrir a instância `nntdarkmp_copy`.
2. Entrar no mundo.
3. Rodar o comando de TP até passar de 2.147.483.647.
4. Clicar em **melhorar atributo** (caminho que quebrou).
5. Confirmar:
   - sem desconexão;
   - `latest.log` sem `VerifyError`;
   - `jrmcTplong` mantém o valor no NBT;
   - valor persiste após logout/login.
