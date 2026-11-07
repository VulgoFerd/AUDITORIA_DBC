@echo off
REM BigTP 1.1.1 - Forge 1.7.10 Server Launcher
REM Requer: Forge 1.7.10-10.13.4.1614 universal.jar na mesma pasta
REM Mods: JRMCore, DragonBlockC, NPCDBC na pasta mods/

set JAVA_HOME=
set JAVA_EXE=java

if not exist "forge-1.7.10-10.13.4.1614-universal.jar" (
    echo ERRO: forge-1.7.10-10.13.4.1614-universal.jar nao encontrado
    echo Baixe o Forge installer e rode: java -jar forge-installer.jar --installServer
    pause
    exit /b 1
)

if not exist "mods\JRMCore-v1.3.51-bigtp-1.1.1.jar" (
    echo AVISO: JARs do BigTP nao encontrados em mods/
    echo Copie os 3 JARs patched para a pasta mods/
)

if not exist "eula.txt" (
    echo eula=true > eula.txt
    echo EULA aceito automaticamente
)

echo Iniciando Forge 1.7.10 Server com BigTP 1.1.1...
echo Mods carregados:
dir /b mods\*.jar

%JAVA_EXE% -Xmx3G -Xms2G -jar forge-1.7.10-10.13.4.1614-universal.jar nogui

pause