@echo off
"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\jar.exe" tf "C:\Users\Fernando\.gradle\caches\retro_futura_gradle\mc-vanilla\1.7.10\client.jar" > C:\temp\jar_list.txt 2>&1
type C:\temp\jar_list.txt | findstr /n "^" | findstr "^[1-5][0-9]:"