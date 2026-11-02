# Mapa de limites numéricos — Dragon Block C / pack nntdarkmp

Instância analisada (somente leitura): `C:\Users\Fernando\nntdarkmp`
Artefatos: `C:\Users\Fernando\AppData\Local\Temp\opencode\dbc-re`
Jars decompilados: `JRMCore-v1.3.51`, `DragonBlockC-v1.4.85`, `npcdbc-1.2.1-hotfix1`, `JBRA-Client-v1.6.52`, `nntdark-CustomNPC-Plus-1.11.1`

Legenda de "tipo de armazenamento": onde o valor vive/serializa. É ele que define o teto real, não a constante que aparece no código.

---

## 1. TP (Training Points)

| Item | Detalhe |
|---|---|
| Teto de configuração | `JRMCoreH.getMaxTP()` → **2.000.000.000** (`MaxTP` campo linha 857) |
| Armazenamento canônico | NBT **int** `jrmcTpint` (44 pontos de referência) |
| Espelho no cliente | `JRMCoreH.curTP` — **int** (linha 517) |
| Sincronização | `JRMCorePStats2` → `writeInt` (int no pacote) |
| Display | `numSep(long)`; acima de 1e8 usa `numSepShort` → `BigInteger`. **A GUI já aguenta long.** |
| Tipo máximo teórico | 2.147.483.647 (int) |

### Pontos de clamp (todos em `JRMCoreH` salvo indicado)

| Local | O que faz |
|---|---|
| `expPls()` 6101 | limita ganho de TP ao `getMaxTP()` |
| kill TP 6722 / 6740 | clamp após ganho por kill |
| `tpalgn()` 6986 / 7016 | clamp de alinhamento/TP |
| `ComJrmctp` 56–60 / 114–115 | comando `/jrmctp` clampa em `getMaxTP()` |
| `attrCst*()` 5051, 5106–5155 | custo/gasto de TP na compra de atributos |
| `JRMCorePacHanS` 269–273 / 1051 / 1083 | leitura/gravação do TP vindo do cliente |
| `JRMCoreGuiScreen` 2696 | clamp de exibição/edição |

### Bugs encontrados no caminho de TP

1. **Overflow em ganho por kill** — `JRMCoreH:6721`:
   ```java
   int result = tp + tpGain * e;   // estoura antes de comparar
   if (result > maxTP) { ... }     // se wrapou para negativo, não clampa
   ```
   Resultado: TP pode ficar **negativo** com ganhos grandes.
2. **Compra de TP descarta excedente** — `JRMCorePacHanS:267–272`: o excedente é jogado em `wast` e perdido (sem crédito parcial).

### Observação crítica de economia
No pack atual (`config/jinryuujrmcore.cfg`): `Attribute Maximum = 500`, `TP amount gained = 1`, `TP gain/melee rate = 200`.
Custo para comprar **todo** o orçamento de atributos (500 × 6 = 3000 pontos) ≈ **3.369.945 TP** (um atributo até 500 ≈ 90.570 TP).
Ou seja: **o teto de 2e9 está ~600× acima de qualquer gasto possível no jogo normal.** Ele só é atingido por TP dado por comando/shop/script.
`skill_costs.cfg` do pack: custo máximo de skill = 60.000 TP.

**Consequência:** subir o teto de TP de 2.000.000.000 → 2.147.483.647 rende apenas **+7,4%**. Para números realmente grandes é preciso (a) TP em long e (b) subir o `tmx` dos atributos — senão o TP extra não tem onde ser gasto.

---

## 2. Atributos (Str/Agi/Vit/Int/...)

| Item | Detalhe |
|---|---|
| Config do pack | `Attribute Maximum = 500` (`tmx`) |
| Clamp da config | `JRMCoreConfig:413` limita `tmx` a **100 .. 1.000.000.000** |
| `checkLimit()` | `clamp(tmx, 100, 0x3B9ACA00)` → **1e9** |
| `getPlayerAttribute` 3712–3714 | aplica `checkLimit()` **a menos que** `OverAtrLimit` esteja true |
| `OverAtrLimit` (`JRMCoreConfig:494`) | **true no pack** → o clamp acima é ignorado |
| Escrita dura | `v2tBFN()` e `hdN7rK()` capam em **0x3B9ACA00 (1e9)** |
| Bloqueio de upgrade | `JRMCorePacHanS:1695` — recusa quando `to > JRMCoreConfig.tmx` |
| `JRMCoreH.MaxAttribute` | `= 1000000000` (linha 857) — **constante morta, nunca referenciada** |
| Bônus flat | em cada `getAttribute<Raça>()`: `atrLimit > 1000 ? 1000 : atrLimit` → **bônus plano capado em 1000** |

**Teto efetivo hoje: 500** (config). Matando o `tmx` o teto vira 1e9, mas com custo de TP astronômico — daí a ligação direta com o teto de TP.

Atenção: **npcdbc substitui `getPlayerAttribute` inteiro** via `MixinJRMCoreH` (`@Overwrite`, linhas 184–305). Qualquer patch ASM feito no método do JRMCore é **descartado em runtime** quando o npcdbc está instalado (que é o caso deste pack).

---

## 3. Body / Ki / Stamina

| Item | Detalhe |
|---|---|
| Cálculo | `JRMCoreH.stat()` 5215–5254 |
| Tipo final | termina em casts `(int)` → **satura em 2.147.483.647** |
| Sincronização | `JRMCorePStats` → `writeInt` |

---

## 4. Custo de Ki (ataques)

| Item | Detalhe |
|---|---|
| `costEnAt()` | retorna **short** → teto 32.767 |
| Expressão | `(short)(x > getMaxTP() ? getMaxTP() : x)` — para `x` grande, **wrapa** |
| `JRMCoreH.Cost(int)` 6156 | `new JRMCorePCost((short) cost)` → `writeShort` |
| `DBCKiTech.KAkiEn` 588–592 | `short cost`; se negativo, passa no teste `curEnergy >= cost` |
| Servidor `handleCost` 1224–1246 | `fc = curEnergy - s` → short negativo **soma** ki |

**Efeito:** qualquer custo acima de 32.767 vira negativo ⇒ ataque **de graça** ou **ganhando ki** no servidor. É um bug de exploração, não só de limite.

---

## 5. Battle Power (BP)

| Item | Detalhe |
|---|---|
| Jogador | **long** de ponta a ponta (`JRMCoreH.bpc(...)`, `BPC_ME` / `BPC_ME2`) — OK |
| NPC DBC | guardado em `field_70728_aV` (`EntityLivingBase.experienceValue`, **int**) |
| `EntityNPC.BattlePower()` 86–88 | retorna int |
| `EntityJRMC.entity` 66–68 | converte esse int para long |

**Consequência:** BP de NPC está limitado a ~2.1e9, independente do BP do jogador. Inconsistência de escala entre player e NPC.

---

## 6. Dano

### 6.1 Dano por ataque de energia (DBC)
| Item | Detalhe |
|---|---|
| Campo | `EntityEnergyAtt.damage` é **double** (linha 87) |
| Aplicação | `(int)` em 1141, 1212, 1260, 1326, 1462, 1477 |
| Teto real | **2.147.483.647 por hit** |

### 6.2 Dano via npcdbc
| Item | Detalhe |
|---|---|
| Cálculo | `DBCUtils.calculateDBCStatDamage` 430–478 — **float** (teto bem mais alto) |
| Aplicação | `JRMCoreH.setInt((float) newHP, ...)` linha 523 → **satura em int** |
| GUI | `StatSheetGui:375/390` clampa exibição em `Integer.MAX_VALUE` |
| `DBCDataStats.getCurrentStat` 113 | satura float→int |

---

## 7. Outros tetos relevantes

| Onde | Limite |
|---|---|
| `JRMCoreConfig` `tmx` | 100 .. 1e9 |
| `atcm` (custo de atributo) | 0.75 no pack |
| `Start Minus` / `Minimum` | 140 / 16 |
| `skill_costs.cfg` | 60.000 TP máximo |
| `MaxAttribute` | 1e9 — **código morto** |

---

## 8. Resumo executivo — o que vale a pena mexer

| # | Mudança | Ganho | Esforço | Risco |
|---|---|---|---|---|
| 1 | `getMaxTP()` 2e9 → int max | +7,4% | **baixo** (1 constante, ASM já validado) | baixo |
| 2 | Corrigir overflow de `expPls`/kill TP | elimina TP negativo | baixo | baixo |
| 3 | Corrigir custo de ki short | elimina ki infinito | médio (short→int em 4+ classes + pacote) | médio |
| 4 | TP em **long** | > 2.1e9 | **alto** (44 sites NBT/packet/GUI) | alto |
| 5 | Subir/matar `tmx` | atributos > 500 | baixo (config) | **precisa de TP suficiente** |
| 6 | BP de NPC em long | consistência de escala | médio (mexe em `experienceValue`) | médio |
| 7 | Dano > int | hits > 2,1e9 | médio (`(int)` → `double`/long no apply) | médio |
| 8 | `getPlayerAttribute` | —— | **bloqueado** pelo `@Overwrite` do npcdbc | —— |

### Caminho recomendado (ordem)
1. TXM/`Attribute Maximum` (config) — não precisa de patch, é só editar o `.cfg`.
2. `getMaxTP()` (patch ASM trivial, já provado em `JRMCore-v1.3.51-maxTP.jar`).
3. Correções de bug (overflow de TP, custo de ki short).
4. Só depois, se realmente quiser > 2,1e9, migrar TP para long.

---

## Artefatos gerados
- `PatchMaxTP.java` — patcher ASM (funcional)
- `JRMCore-v1.3.51-maxTP.jar` — jar com `getMaxTP`/`MaxTP` = 2147483647, verificado
- `jrmcore/`, `npcdbc/`, `x_*` — fontes decompiladas

> Nada em `C:\Users\Fernando\nntdarkmp` foi modificado.
