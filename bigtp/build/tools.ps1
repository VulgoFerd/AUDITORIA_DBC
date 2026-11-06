#requires -Version 5.1
# Compila e executa as ferramentas de analise. Sem caminhos absolutos.
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\tools"
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\javac.exe" } else { "javac.exe" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }

$asm = $env:ASM_JARS
if (-not $asm) {
    throw "Defina ASM_JARS com os caminhos de asm-9.7.1.jar, asm-tree, asm-analysis, asm-util e asm-commons."
}

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "tools") -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -cp $asm -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "falha ao compilar ferramentas" }

Write-Host "ferramentas compiladas em $classes"
