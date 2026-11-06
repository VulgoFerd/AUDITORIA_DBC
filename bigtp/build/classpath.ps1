#requires -Version 5.1
# Gera build/classpath.txt com o classpath real de verificacao 1.7.10.
#
# Entradas lidas do ambiente (sem caminhos absolutos no script):
#   MC_JAR      Minecraft 1.7.10 recompilado (nomes MCP)
#   FORGE_JAR   forge universal 1.7.10 (SRG, opcional)
#   LIBS_DIR    diretorio de bibliotecas do .minecraft (opcional)
#
# Saida: build/classpath.txt (uma entrada por linha, ignorado pelo git).
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root "build"
$out = Join-Path $build "classpath.txt"
if (-not (Test-Path $build)) { New-Item -ItemType Directory -Force -Path $build | Out-Null }

$entries = New-Object System.Collections.Generic.List[string]

if (-not $env:MC_JAR -or -not (Test-Path $env:MC_JAR)) {
    throw "Defina MC_JAR com o Minecraft 1.7.10 recompilado."
}
$entries.Add((Resolve-Path $env:MC_JAR).Path)

if ($env:FORGE_JAR) {
    if (-not (Test-Path $env:FORGE_JAR)) { throw "FORGE_JAR inexistente: $($env:FORGE_JAR)" }
    $entries.Add((Resolve-Path $env:FORGE_JAR).Path)
}

if ($env:LIBS_DIR -and (Test-Path $env:LIBS_DIR)) {
    Get-ChildItem $env:LIBS_DIR -Recurse -Filter *.jar |
        Sort-Object FullName |
        ForEach-Object { $entries.Add($_.FullName) }
}

# JARs de outros mods (DBC/NPCDBC) necessarios para resolver referencias cruzadas.
if ($env:EXTRA_JARS) {
    foreach ($p in ($env:EXTRA_JARS -split ';')) {
        $t = $p.Trim()
        if (-not $t) { continue }
        if (-not (Test-Path $t)) { throw "EXTRA_JARS inexistente: $t" }
        $entries.Add((Resolve-Path $t).Path)
    }
}

[System.IO.File]::WriteAllLines($out, $entries, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "classpath: $($entries.Count) entradas em $out"
