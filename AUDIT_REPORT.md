# BigTP 1.1.1 Audit Report

**Date:** 2026-11-11
**Auditor:** OpenCode AI Agent
**Scope:** Full implementation verification across JRMCore, DBC, and NPCDBC mods
**Commit:** Parent 94faa2a, Submodule bd9a040

---

## Executive Summary

**CRITICAL: The BigTP 1.1.1 release artifacts are not reproducible from source and are functionally broken.**

All three distributed JARs (JRMCore, DBC, NPCDBC) are missing the core BigTP API fields/methods in `JRMCoreH` that 180 rewritten callsites depend on. The current `TpPatch` in the repository fails to build a complete JAR due to a `Frame.merge` error on `JRMCoreHC`.

---

## Verification Results

### SHA-256 Verification (All Match Declared Values)
| Artifact | SHA-256 | Status |
|----------|---------|--------|
| JRMCore-v1.3.51-bigtp-1.1.1.jar | `0a1d129e6995a08f506fe367c54fc4623499b867481c478abb21eaa37f099a19` | ✅ Match |
| DragonBlockC-v1.4.85-bigtp-1.1.1.jar | `854b4916de0a02ea827812163cc3ca3045149aa9e2f3d546d5ec4774678d7cfe` | ✅ Match |
| NPCDBC-v1.2.1-hotfix1-bigtp-1.1.1.jar | `5a421d43f647ab2ff9a160ac9ec76afa56ef7cff7ff85037a708cd026dfd612a` | ✅ Match |

### Callsite Classification
| Mod | Total Sites | Categories |
|-----|-------------|------------|
| JRMCore | 96 | READ, WRITE_ABS, GAIN, SPEND, STATE, EXPLICIT |
| DBC | 59 | All GUI `curTP` reads (DBCTalkGui) |
| NPCDBC | 25 | GUI reads + DBCData NBT I/O |
| **Total** | **180** | |

### jrmcTpint Residual Scan
| JAR | Classes with jrmcTpint refs |
|-----|----------------------------|
| JRMCore | 3 (ComJrmcRacialSkill, ComJrmctp, TpNbtCore) |
| DBC | 0 |
| NPCDBC | 0 |

---

## Critical Findings

### 1. Build Not Reproducible 🔴
**The current `TpPatch` fails to produce a JAR.**

```bash
ERROR writing class: JinRyuu/JRMCore/JRMCoreHC
  at org.objectweb.asm.Frame.merge(Frame.java:1311)
  at org.objectweb.asm.MethodWriter.computeAllFrames(MethodWriter.java:1612)
```

- `HierarchyClassWriter` with `COMPUTE_FRAMES` fails on major version 50 classes
- Fallback to `COMPUTE_MAXS` also fails
- No output JAR is generated
- **Distributed JARs were built with a different/working version of TpPatch**

### 2. JRMCoreH Missing BigTP API in Distributed JARs 🔴

| Field/Method | Expected | Actual in Distributed JAR |
|--------------|----------|---------------------------|
| `curTPLong` | `J` (long) | **MISSING** |
| `tpShort(J)S` | static method | **MISSING** |
| `clampTP(J)I` | static method | **MISSING** |
| `curTP` (original) | `I` (int) | **PRESENT** |

**Evidence:**
- Pre-compiled `build/classes/JinRyuu/JRMCore/JRMCoreH.class` **HAS** all three ✅
- Distributed `JRMCore-v1.3.51-bigtp-1.1.1.jar` **MISSING** all three ❌

### 3. Callsites Rewritten to Call Non-Existent API 🔴

**InjectedCallVerifier Errors:**

| Mod | Errors | Breakdown |
|-----|--------|-----------|
| **JRMCore** | 20 | 1 missing field (`curTPLong`) + 19 missing method calls |
| **DBC** | 41 | 1 missing field + 40 missing calls in `DBCTalkGui::func_73863_a` |
| **NPCDBC** | 5 | 1 missing field + 4 missing calls in `StatSheetGui::func_73876_c` |
| **Total** | **66** | |

All rewritten callsites invoke `JRMCoreH.tpShort(J)S` and `JRMCoreH.clampTP(J)I` which **do not exist** in the final JAR.

### Root Cause

1. **`injectTpUtilClasses`** correctly injects pre-compiled classes from `build/classes/` (including correct `JRMCoreH.class`)
2. **`injectJRMCoreHMethods`** explicitly **skips** method injection:
   ```java
   // "Skipping JRMCoreH method injection (using TpUtil.mirror instead)"
   ```
3. **`HierarchyClassWriter`** fails on version 50 classes during JAR write
4. The pre-compiled `JRMCoreH` with correct API is **never successfully written** to output JAR

---

## Impact Assessment

### Runtime Behavior
Any execution path touching patched code will crash:
- `NoSuchFieldError: curTPLong`
- `NoSuchMethodError: JRMCoreH.tpShort(J)S`
- `NoSuchMethodError: JRMCoreH.clampTP(J)I`

### Affected Code Paths
- TP gain/spend/command handling (JRMCore, DBC, NPCDBC)
- NBT read/write for TP persistence (DBCData, ComJrmctp)
- GUI display of TP values (DBCTalkGui, StatSheetGui, JRMCoreGui*)
- Network sync (JRMCorePTpSync packet)
- Migration from INT to LONG TP storage

---

## Recommendations

### Immediate (Blocking)
1. **Fix `TpPatch` build failure** - Resolve `Frame.merge` error on `JRMCoreHC`
   - Option A: Add `JRMCoreHC` to optional-deps.txt
   - Option B: Improve fallback logic in `writeClassWithFallback`
   - Option C: Skip `JRMCoreHC` modification entirely

2. **Ensure JRMCoreH API injection** - Remove the skip in `injectJRMCoreHMethods` or verify pre-compiled class injection works

3. **Reproduce build** - Verify `TpPatch` produces working JARs from original sources

### Validation
1. Run `InjectedCallVerifier` on rebuilt JARs - must report **0 errors**
2. Run `ScanJrmcTpint` - must report **0 classes** with residual refs (except TpUtil)
3. Integration test: dedicated server + 2 clients, verify TP gain/spend/sync/persist

### Process
1. Tag working `TpPatch` commit used for 1.1.1 release
2. Document build environment (Forge 1.7.10 universal JAR source)
3. Add CI pipeline to prevent regression

---

## Files Examined

### Core Source (bigtp/src/main/java/JinRyuu/JRMCore/)
- `p/JRMCorePTpSync.java` - LONG packet with writeLong/readLong
- `TpUtil.java` - Sync facade (writeAndSync/gainAndSync/spendAndSync/syncToClient)
- `TpNetwork.java`, `TpClientState.java`, `TpLogic.java`
- `TpNbtCore.java`, `TpNbt.java`, `TpTagView.java`

### Patcher (bigtp/tools/)
- `TpPatch.java` (2935 lines) - Main patcher with rewrite logic
- `ModifyPD.java` - PD packet registration (index 22)
- `HierarchyClassWriter.java` - Custom ClassWriter with hierarchy resolver
- `InjectedCallVerifier.java` - Verification tool
- `ScanJrmcTpint.java` - Residual scanner

### Analysis Data
- `bigtp/analysis/sites-classified.csv` (96 JRMCore)
- `bigtp/analysis/sites-dbc-classified.csv` (59 DBC)
- `bigtp/analysis/sites-npcdbc-classified.csv` (25 NPCDBC)
- `bigtp/analysis/optional-deps.txt`
- `bigtp/dist/1.1.1/jars/SHA256SUMS.txt`

### Build Artifacts
- `bigtp/build/classes/JinRyuu/JRMCore/JRMCoreH.class` (917 bytes, **has correct API**)
- `bigtp/dist/1.1.1/jars/*.jar` (3 final JARs, **missing API**)

---

## Conclusion

**BigTP 1.1.1 is not a working release.** The artifacts pass SHA-256 verification but fail functional verification. The build process is broken and cannot be reproduced from the current source tree.

**Do not deploy these JARs to production.** They will crash at runtime when any BigTP-patched code executes.