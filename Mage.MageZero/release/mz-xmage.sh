#!/bin/sh
java -Xms2g -Xmx24g -XX:+UseZGC --add-opens=java.base/java.lang=ALL-UNNAMED -cp "lib/*" org.mage.magezero.MageZeroMain "$@"