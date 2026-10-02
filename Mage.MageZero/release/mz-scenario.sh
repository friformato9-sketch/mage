#!/bin/sh
# Rules scenario runner: mz-scenario.sh <scenario.json> <out-dir>
cd "$(dirname "$0")"
java -Xms512m -Xmx4g --add-opens=java.base/java.lang=ALL-UNNAMED -cp "lib/*" org.mage.magezero.scenario.ScenarioMain "$@"
