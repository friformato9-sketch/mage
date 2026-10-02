@echo off
cd /d "%~dp0"
REM MZ_HEAP caps the JVM heap (default 24g); lower it when other engines share the machine
if "%MZ_HEAP%"=="" set MZ_HEAP=24g
java -Dlog.file=magezero.log -Derrors.file=magezeroErrors.log -Dlog4j.configuration=file:log4j.properties -Xms2g -Xmx%MZ_HEAP% -XX:+UseZGC --add-opens=java.base/java.lang=ALL-UNNAMED -cp "lib\*" org.mage.magezero.MageZeroMain %*
