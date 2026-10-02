#!/bin/sh
# MZ_HEAP caps the JVM heap (default 24g); lower it when other engines share the machine
cd "$(dirname "$0")"
java -Dlog.file=magezero.log -Derrors.file=magezeroErrors.log -Dlog4j.configuration=file:log4j.properties -Xms2g -Xmx"${MZ_HEAP:-24g}" -XX:+UseZGC --add-opens=java.base/java.lang=ALL-UNNAMED -cp "lib/*" org.mage.magezero.MageZeroMain "$@"
