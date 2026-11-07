@echo off
REM BigTP 1.1.1 - Forge 1.7.10 Server Launcher
REM Requer: Forge installer + mods na pasta mods/

set JAVA_EXE=java

REM 1. Verifica se ja existe o universal.jar (gerado pelo installer)
if exist "forge-1.7.10-10.13.4.1614-universal.jar" goto START_SERVER

REM 2. Procura o installer
if exist "forge-1.7.10-10.13.4.1614-installer.jar" (
    echo Forge installer encontrado. Instalando servidor...
    %JAVA_EXE% -jar forge-1.7.10-10.13.4.1614-installer.jar --installServer
    goto CHECK_UNIVERSAL
)

REM 3. Nenhum dos dois encontrado
echo ERRO: Forge installer nao encontrado.
echo Baixe: https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-installer.jar
echo Salve como forge-1.7.10-10.13.4.1614-installer.jar nesta pasta.
pause
exit /b 1

:CHECK_UNIVERSAL
if not exist "forge-1.7.10-10.13.4.1614-universal.jar" (
    echo ERRO: Instalacao falhou - universal.jar nao gerado.
    pause
    exit /b 1
)

:START_SERVER
if not exist "eula.txt" (
    echo eula=true > eula.txt
    echo EULA aceito automaticamente
)

if not exist "mods\JRMCore-v1.3.51-bigtp-1.1.1.jar" (
    echo AVISO: JARs do BigTP nao encontrados em mods/
    echo Copie os 3 JARs patched para a pasta mods/
)

echo Iniciando Forge 1.7.10 Server com BigTP 1.1.1...
echo Mods carregados:
dir /b mods\*.jar

%JAVA_EXE% -Xmx3G -Xms2G -jar forge-1.7.10-10.13.4.1614-universal.jar nogui

pause