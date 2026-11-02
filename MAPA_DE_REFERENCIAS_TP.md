# MAPA DE REFERÊNCIAS TP - DRAGON BLOCK C 1.7.10

**Baseado em:** `report_tp.txt` (59 ocorrências de `jrmcTpint`) + análise de código via CFR + patcher ASM

---

## 1. CLASSIFICAÇÃO POR CATEGORIA

### 1.1. AUTORIDADE LONG (`jrmcTplong`)

| Classe | Método/Campo | Tipo | Descrição |
|--------|--------------|------|-----------|
| `JRMCoreH` | `curTPLong` | `static long` | Campo estático adicionado pelo patcher |
| `JRMCoreH` | `getLong` | `(LEntityPlayer;LString;)J` | Leitura autoritativa do player NBT "pres" |
| `JRMCoreH` | `setLong` | `(JLEntityPlayer;LString;)V` | Escrita autoritativa + espelho int saturado |
| `JRMCoreH` | `addLong` | `(JLEntityPlayer;LString;)J` | Soma delta, atualiza ambos, retorna novo valor |
| `JRMCoreH` | `getLongFromNBT` | `(LNBTTagCompound;LString;)J` | Helper: lê long de qualquer NBT |
| `JRMCoreH` | `setLongInNBT` | `(LNBTTagCompound;LString;J)V` | Helper: escreve long em qualquer NBT |
| `JRMCorePStats2` | `curTP` | `long` | Campo do pacote de stats (int→long) |
| `JRMCorePStats2` | `toBytes` | `writeLong` | Serializa `curTP` como long |
| `JRMCorePStats2` | `fromBytes` | `readLong` | Deserializa `curTP` como long |
| `JRMCorePacHanC` | `handleStats2` | `long` param | Processa `curTP` long do pacote recebido |

---

### 1.2. ESPELHO INT SATURADO (`jrmcTpint`)

| Classe | Método/Campo | Tipo | Descrição |
|--------|--------------|------|-----------|
| `JRMCoreH` | `getInt` | `(LEntityPlayer;LString;)I` | Lê preferindo `jrmcTplong`, fallback `jrmcTpint` |
| `JRMCoreH` | `setInt(int,...)` | `(ILEntityPlayer;LString;)V` | **Só** escreve `jrmcTpint` no player NBT "pres" |
| `JRMCoreH` | `setInt(short,...)` | `(SLEntityPlayer;LString;)V` | Variante short |
| `JRMCoreH` | `setInt(byte,...)` | `(BLEntityPlayer;LString;)V` | Variante byte |
| `JRMCoreH` | `setInt(float,...)` | `(FLEntityPlayer;LString;)V` | Variante float |
| `JRMCoreH` | `getIntFromNBT` | `(LNBTTagCompound;LString;)I` | Helper: lê int de qualquer NBT |
| `JRMCoreH` | `setIntInNBT` | `(LNBTTagCompound;LString;I)V` | Helper: escreve int em qualquer NBT |
| `JRMCoreH` | `applyPacketTPChange` | Helper | Escreve delta saturado no pacote `jrmcTpint` |
| `JRMCoreComTickH` | Múltiplos | `int` | **NÃO PATCHADO** - usa `jrmcTpint` original |
| `ComJrmctp` | Múltiplos | `int` | **NÃO PATCHADO** - command handler usa `jrmcTpint` |
| `JRMCorePacHanS` | Múltiplos | `int` | **NÃO PATCHADO** - packet handler server usa `jrmcTpint` |

---

### 1.3. INTERFACE LEGADA / COMPATIBILIDADE

| Classe | Método | Descritor | Status | Nota |
|--------|--------|-----------|--------|------|
| `JRMCoreH` | `getMaxTP` | `()I` | ✅ Patchado | Retorna `Integer.MAX_VALUE` |
| `JRMCoreH` | `techDBCtpc` | `([LString;)I` | Original | Comando TP |
| `JRMCoreH` | `skillTPCost` | `(LString;[LString;[[I)I` | Original | Custo skills |
| `JRMCoreH` | `getSkillTPCost` | `(IIZ)I` | Original | Custo skills |
| `JRMCoreH` | `getDBCSkillTPCost` | `(II)I` | Original | Custo DBC skills |
| `JRMCoreH` | `getNCSkillTPCost` | `(II)I` | Original | Custo NC skills |
| `JRMCoreH` | `tpalgn` | `(LEntityPlayer;II)V` | Original | Alinhamento TP |

---

### 1.4. REDE / PACOTES

| Classe | Método | Mudança | Descrição |
|--------|--------|---------|-----------|
| `JRMCorePStats2` | `curTP` field | `int`→`long` | Payload principal de TP no pacote |
| `JRMCorePStats2` | `toBytes` | `writeInt`→`writeLong` | Serialização long |
| `JRMCorePStats2` | `fromBytes` | `readInt`→`readLong` | Deserialização long |
| `JRMCorePStats2` | `<init>` | `(III[I)V`→`(JII[I)V` | Construtor com curTP long |
| `JRMCorePacHanC` | `handleStats2` | `(III[I...)V`→`(JII[I...)V` | Handler cliente recebe long |
| `JRMCoreH` | `applyPacketTPChange` | Novo | Processa pacote: delta long + sync espelho |

---

### 1.5. CÁLCULOS / CUSTOS KI

| Classe | Campo/Método | Antes | Depois | Nota |
|--------|--------------|-------|--------|------|
| `JRMCorePCost` | Múltiplos fields | `short` | `int` | Custos de Ki |
| `JRMCorePCost` | `toBytes` | `writeShort` | `writeInt` | Serialização |
| `JRMCorePCost` | `fromBytes` | `readShort` | `readInt` | Deserialização |
| `JRMCoreH` | `Cost` | `I2S` cast | Removido | Helper custo |
| `DBCKiTech` | `KAkiEn` | `I2S` casts | Removidos | Ki techniques |
| `JRMCorePCost` | `Handler` inner class | `short` fields | `int` fields | Packet handler |

---

## 2. FLUXO DE DADOS TP

```
┌─────────────────────────────────────────────────────────────────┐
│                        PLAYER NBT ("pres")                      │
│  ┌──────────────────┐         ┌──────────────────┐            │
│  │ jrmcTplong (J)   │ ←───►   │ jrmcTpint (I)    │            │
│  │ AUTORIDADE       │  sync   │ ESPELHO SATURADO │            │
│  │ 64-bit           │         │ capped at 2.14B  │            │
│  └────────┬─────────┘         └────────┬─────────┘            │
└───────────┼────────────────────────────┼───────────────────────┘
            │                            │
            ▼                            ▼
┌─────────────────────┐      ┌─────────────────────┐
│  NOVOS CONSUMIDORES │      │  CONSUMIDORES LEGADOS│
│  (long-aware)       │      │  (int-only)         │
├─────────────────────┤      ├─────────────────────┤
│ JRMCoreH.getLong()  │      │ JRMCoreComTickH     │
│ JRMCoreH.setLong()  │      │ ComJrmctp           │
│ JRMCoreH.addLong()  │      │ JRMCorePacHanS      │
│ JRMCorePStats2      │      │ NPCDBC Mixins       │
│ JRMCorePacHanC      │      │                     │
└─────────────────────┘      └─────────────────────┘
```

---

## 3. SINCRONIZAÇÃO PACOTE → PLAYER

**Método:** `JRMCoreH.applyPacketTPChange(NBTTagCompound packet, EntityPlayer player)`

```
1. Lê packet.jrmcTplong  → long packetLong
2. Lê packet.jrmcTpint   → int  packetInt
3. delta = packetLong - packetInt  (long)
4. deltaInt = saturate(delta)      (int, capped at Integer.MAX_VALUE)
5. Escreve packet.jrmcTpint = deltaInt  (atualiza pacote para próximo hop)
6. Chama JRMCoreH.addLong(delta, player, "jrmcTplong")
   → Lê player.jrmcTplong
   → Soma delta
   → Escreve novo player.jrmcTplong
   → Satura e escreve player.jrmcTpint
   → Retorna novo valor long
```

**Garantias:**
- Autoridade long **nunca** sobrescrita por espelho int
- Delta calculado em long (precisão total)
- Espelho int apenas para compatibilidade de exibição/pacotes legados
- Pacote carrega ambos: `jrmcTplong` (autoridade) + `jrmcTpint` (delta saturado)

---

## 4. MIGRAÇÃO DE SAVES ANTIGOS

| Cenário | Comportamento |
|---------|---------------|
| Save só tem `jrmcTpint` | `getLong()` retorna `jrmcTpint` como long (fallback) |
| Save tem `jrmcTplong` | `getLong()` retorna `jrmcTplong` (autoridade) |
| Save tem ambos | `jrmcTplong` é autoridade; `jrmcTpint` ignorado na leitura |
| `setLong()` chamado | Atualiza **ambos**: `jrmcTplong` = valor, `jrmcTpint` = saturado |
| `setInt()` chamado | Atualiza **apenas** `jrmcTpint` (não toca no long) |

---

## 5. PONTOS DE ATENÇÃO PARA TESTES

### 5.1. Valores Críticos
- `0` - Zero
- `1` - Mínimo positivo
- `2147483646` - Pré-limite
- `2147483647` - `Integer.MAX_VALUE` (limite int)
- `2147483648` - Primeiro valor long-only
- `3000000000` - ~3B (teste config alvo)
- `1000000000000` - 1T (stress)
- `Long.MAX_VALUE` - Limite absoluto

### 5.2. Operações Críticas
- `addLong` com delta positivo grande
- `addLong` com delta negativo (underflow protection)
- `setLong` com valor > Integer.MAX_VALUE
- `getInt` com valor > Integer.MAX_VALUE (deve saturar)
- Pacote com `jrmcTplong` > 2B recebido por cliente legacy
- Logout/login com TP > 2B

### 5.3. Compatibilidade NPCDBC
- Mixins não patchados devem continuar funcionando
- `JRMCoreComTickH` lê/escreve `jrmcTpint` → `JRMCoreH.setInt` sincroniza para `jrmcTplong`
- `JRMCorePacHanS` processa pacotes `jrmcTpint` → `applyPacketTPChange` processa ambos
- Nenhum `VerifyError` ou `ArrayIndexOutOfBoundsException` em `Frame.merge`

---

## 6. ARQUIVOS DE REFERÊNCIA

- `report_tp.txt` - Inventário completo das 59 ocorrências de `jrmcTpint`
- `tp-long-design.md` - Especificação de design original
- `PatchBig.java` - Implementação do patcher
- `VerifyClasses.java` - Verificação de bytecode

---

**Status:** Mapa completo para validação de testes. Todas as referências categorizadas e fluxo de dados documentado.