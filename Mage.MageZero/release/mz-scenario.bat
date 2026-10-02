@echo off
cd /d "%~dp0"
REM Rules scenario runner: mz-scenario.bat <scenario.json> <out-dir>
java -Dlog.file=magezero-scenario.log -Derrors.file=magezeroErrors.log -Dlog4j.configuration=file:log4j.properties -Xms512m -Xmx4g --add-opens=java.base/java.lang=ALL-UNNAMED -cp "lib\*" org.mage.magezero.scenario.ScenarioMain %*
