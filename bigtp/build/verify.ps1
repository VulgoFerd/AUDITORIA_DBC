#requires -Version 5.1
# Verifica um JAR com o VerifyJar 1.0.7.
#
# Pre-requisitos:
#   build/tools.ps1 e build/classpath.ps1 executados;
#   ASM_JARS definido;
#   SRG_METHODS / SRG_FIELDS (opcionais) apontando para methods.csv/fields.csv.
#
# Uso:
#   powershell -File build/verify.ps1 -Jar ..\JRMCore-v1.3.51-original.jar
#   powershell -File build/verify.ps1 -Jar dist\1.0.7\JRMCore.jar -ExpectMajor 52 -Required required-jrmcore.txt
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$Required = "",
    [string]$Optional = "",
    [int]$ExpectMajor = -1,
    [string]$Log = ""
)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }

if (-not $env:ASM_JARS) { throw "Defina ASM_JARS." }
$cpFile = Join-Path $root "build\classpath.txt"
if (-not (Test-Path $cpFile)) { throw "build\classpath.txt ausente: rode build\classpath.ps1." }

$argsList = @("-Xmx3g", "-cp", "$root\build\tools;$env:ASM_JARS", "VerifyJar", "--jar", $Jar, "--cp-file", $cpFile)
if ($Required) { $argsList += @("--required", $Required) }
$optionalFile = if ($Optional) { $Optional } else { Join-Path $root "analysis\optional-deps.txt" }
if (Test-Path $optionalFile) { $argsList += @("--optional", $optionalFile) }
if ($ExpectMajor -gt 0) { $argsList += @("--expect-major", "$ExpectMajor") }
if ($env:SRG_METHODS) { $argsList += @("--srg-methods", $env:SRG_METHODS) }
if ($env:SRG_FIELDS) { $argsList += @("--srg-fields", $env:SRG_FIELDS) }

if ($Log) {
    & $java @argsList 2>&1 | Tee-Object -FilePath $Log
} else {
    & $java @argsList
}
exit $LASTEXITCODE
