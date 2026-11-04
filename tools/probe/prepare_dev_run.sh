#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

./gradlew -p tools/probe build

jar="tools/probe/build/libs/coopcraft-probe-0.1.0.jar"
mkdir -p build/probe-server/mods build/probe-client/mods
cp "$jar" build/probe-server/mods/
cp "$jar" build/probe-client/mods/
printf 'eula=true\n' > build/probe-server/eula.txt
if [ ! -f build/probe-server/server.properties ]; then
	printf 'online-mode=false\nwhite-list=false\nport=25565\n' > build/probe-server/server.properties
fi

echo "Prepared build/probe-server and build/probe-client."
echo "Start: ./gradlew runProbeServer   (server probe :39001)"
echo "Start: ./gradlew runProbeClient   (client probe :39002)"
