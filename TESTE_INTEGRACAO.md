# BigTP 1.1.1 - Procedimento de Teste de Integração

## Pré-requisitos
- Java 8 (JDK ou JRE)
- Forge 1.7.10-10.13.4.1614 installer (`forge-1.7.10-10.13.4.1614-1.7.10-installer.jar`)
- 3+ GB RAM disponível
- Windows/Linux/macOS com interface gráfica (para clientes)

## 1. Preparar Servidor Dedicado

```bash
# Criar pasta do servidor
mkdir BigTP_Test_Server
cd BigTP_Test_Server

# Baixar Forge installer (se não tiver)
# https://files.minecraftforge.net/maven/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-1.7.10-installer.jar

# Instalar servidor
java -jar forge-1.7.10-10.13.4.1614-1.7.10-installer.jar --installServer

# Aceitar EULA
echo "eula=true" > eula.txt

# Criar pasta mods
mkdir mods

# Copiar os 3 JARs patched do BigTP 1.1.1
copy "C:\Users\Fernando\Desktop\NanatsuCore\AUDITORIA_DBC\bigtp\dist\1.1.1\jars\JRMCore-v1.3.51-bigtp-1.1.1.jar" mods\
copy "C:\Users\Fernando\Desktop\NanatsuCore\AUDITORIA_DBC\bigtp\dist\1.1.1\jars\DragonBlockC-v1.4.85-bigtp-1.1.1.jar" mods\
copy "C:\Users\Fernando\Desktop\NanatsuCore\AUDITORIA_DBC\bigtp\dist\1.1.1\jars\NPCDBC-v1.2.1-hotfix1-bigtp-1.1.1.jar" mods\
```

## 2. Iniciar Servidor

```bash
# Windows
java -Xmx3G -Xms2G -jar forge-1.7.10-10.13.4.1614-1.7.10-universal.jar nogui

# Linux/macOS
java -Xmx3G -Xms2G -jar forge-1.7.10-10.13.4.1614-1.7.10-universal.jar nogui
```

**Verificar nos logs:**
```
[Server thread/INFO] [JRMCore]: BigTP 1.1.1 packet registered: JRMCorePTpSync (ID 22)
[Server thread/INFO] [FML]: Registering message JinRyuu.JRMCore.p.JRMCorePTpSync
```

## 3. Preparar Clientes (2 instâncias separadas)

### Cliente A (Jogador principal)
```bash
# Pasta separada para cada cliente
mkdir BigTP_Client_A
cd BigTP_Client_A

# Instalar Forge client
java -jar forge-1.7.10-10.13.4.1614-1.7.10-installer.jar --installClient

# Copiar mesmos 3 mods para .minecraft/mods
copy "..\BigTP_Test_Server\mods\*" "%APPDATA%\.minecraft\mods\"
```

### Cliente B (Segundo jogador)
```bash
mkdir BigTP_Client_B
cd BigTP_Client_B

# Instalar Forge client (pasta separada)
java -jar forge-1.7.10-10.13.4.1614-1.7.10-installer.jar --installClient --dir BigTP_Client_B

# Copiar mods
copy "..\BigTP_Test_Server\mods\*" "BigTP_Client_B\.minecraft\mods\"
```

## 4. Executar Cenário de Teste

### Passo 1: Jogador A entra
- Iniciar Cliente A → Multiplayer → Direct Connect → `localhost:25565`
- Verificar: Login sem erros, TP sincronizado (deve ser 0 inicial)

### Passo 2: Servidor define 10.000.000.000 TP
```bash
# No console do servidor:
/jrmctp add JogadorA 10000000000
# ou
/jrmctp set JogadorA 10000000000
```
- **Verificar Cliente A**: Deve mostrar exatamente `10.000.000.000` (sem truncamento)

### Passo 3: A gasta 500.000.000
```bash
/jrmctp take JogadorA 500000000
```
- **Verificar Cliente A**: Deve mostrar `9.500.000.000`

### Passo 4: Jogador B entra
- Iniciar Cliente B → conectar no mesmo servidor
- **Verificar Cliente B**: Deve mostrar próprio saldo (0), NÃO o do Jogador A

### Passo 5: A ganha 1.000.000.000
```bash
/jrmctp add JogadorA 1000000000
```
- **Verificar Cliente A**: Deve mostrar `10.500.000.000`
- **Verificar Cliente B**: Ainda deve mostrar 0

### Passo 6: A troca de dimensão
- Jogador A: `/tp ~ ~ ~` (ou entrar no Nether/End via portal)
- **Verificar**: TP mantém `10.500.000.000` após mudança de dimensão

### Passo 7: A respawna (morrer e reviver)
- Jogador A: matar-se (lava, /kill, etc)
- **Verificar**: TP mantém `10.500.000.000` após respawn

### Passo 8: A desconecta e reconecta
- Sair do servidor → entrar novamente
- **Verificar**: TP mantém `10.500.000.000`

### Passo 9: Reiniciar servidor
- Parar servidor (Ctrl+C ou `stop`)
- Iniciar servidor novamente
- Jogador A entra
- **Verificar**: TP mantém `10.500.000.000` (persistência NBT)

## 5. Checklist de Validação

| Teste | Esperado | Resultado |
|-------|----------|-----------|
| Login inicial | TP = 0 | ☐ PASS / ☐ FAIL |
| Set 10B via comando | Cliente A vê 10.000.000.000 | ☐ PASS / ☐ FAIL |
| Take 500M | Cliente A vê 9.500.000.000 | ☐ PASS / ☐ FAIL |
| Cliente B entra | Cliente B vê 0 (isolado) | ☐ PASS / ☐ FAIL |
| Add 1B | Cliente A vê 10.500.000.000 | ☐ PASS / ☐ FAIL |
| Troca dimensão | TP mantido | ☐ PASS / ☐ FAIL |
| Respawn | TP mantido | ☐ PASS / ☐ FAIL |
| Reconnect | TP mantido | ☐ PASS / ☐ FAIL |
| Server restart | TP persistido (NBT) | ☐ PASS / ☐ FAIL |

## 6. Logs Importantes para Capturar

```
# Servidor - registro do pacote
[Server thread/INFO] [JRMCore]: BigTP 1.1.1 packet registered: JRMCorePTpSync (ID 22)

# Cliente - recebimento do pacote
[Client thread/INFO] [JRMCore]: TpNetwork.accept() received: 10000000000

# Servidor - envio de sync
[Server thread/DEBUG] [JRMCore]: TpUtil.syncToClient() sending TP=10000000000 to JogadorA
```

## 7. SHA256 dos JARs de Teste

```
0a1d129e6995a08f506fe367c54fc4623499b867481c478abb21eaa37f099a19  JRMCore-v1.3.51-bigtp-1.1.1.jar
854b4916de0a02ea827812163cc3ca3045149aa9e2f3d546d5ec4774678d7cfe  DragonBlockC-v1.4.85-bigtp-1.1.1.jar
5a421d43f647ab2ff9a160ac9ec76afa56ef7cff7ff85037a708cd026dfd612a  NPCDBC-v1.2.1-hotfix1-bigtp-1.1.1.jar
```

## Status

**NÃO EXECUTADO NESTE AMBIENTE** - Requer ambiente gráfico com 2 clientes + servidor dedicado.
Execute este procedimento localmente e reporte resultados.