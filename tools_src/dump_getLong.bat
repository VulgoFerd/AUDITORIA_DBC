@echo off
cd /d "C:\Users\Fernando\Desktop\NanatsuCore\tools"
java -cp ".;asm-9.7.1.jar;asm-tree-9.7.1.jar;asm-util-9.7.1.jar;asm-commons-9.7.1.jar;asm-analysis-9.7.1.jar" DumpMethod "C:\Users\Fernando\Desktop\NanatsuCore\AUDITORIA_DBC\JRMCore-v1.3.51-original.jar" "JinRyuu/JRMCore/JRMCoreH" "getLong"