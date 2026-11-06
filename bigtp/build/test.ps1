#requires -Version 5.1
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build\classes"

$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\javac.exe" } else { "javac.exe" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = @()
foreach ($dir in @("src\main", "stubs", "src\test")) {
    $path = Join-Path $root $dir
    if (Test-Path $path) {
        $sources += Get-ChildItem -Path $path -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    }
}

Write-Host "javac: $javac"
Write-Host "fontes: $($sources.Count)"
& $javac --release 8 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "falha ao compilar" }

Write-Host "java:  $java"
& $java -cp $classes bigtp.TestRunner
if ($LASTEXITCODE -ne 0) { throw "testes falharam (exit $LASTEXITCODE)" }
