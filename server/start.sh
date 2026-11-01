#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

find_java() {
	if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
		printf '%s\n' "${JAVA_HOME}/bin/java"
		return
	fi

	for candidate in \
		/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java \
		/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java; do
		if [ -x "$candidate" ]; then
			printf '%s\n' "$candidate"
			return
		fi
	done

	if [ -x /usr/libexec/java_home ]; then
		local home
		home=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
		if [ -n "$home" ] && [ -x "$home/bin/java" ]; then
			printf '%s\n' "$home/bin/java"
			return
		fi
	fi

	command -v java
}

JAVA="$(find_java || true)"
if [ -z "${JAVA}" ] || ! "$JAVA" -version >/dev/null 2>&1; then
	echo "找不到 Java。先安装 JDK 21：brew install openjdk@21"
	exit 1
fi

if [ ! -f fabric-server-launch.jar ]; then
	echo "还没有安装服务端，请先运行 ./setup.sh /path/to/coopcraft-0.1.0.jar"
	exit 1
fi

if ! grep -qi '^eula=true' eula.txt 2>/dev/null; then
	echo "还没有同意 EULA。编辑 $(pwd)/eula.txt，把 eula=false 改成 eula=true。"
	exit 1
fi

MEMORY="${MEMORY:-4G}"
mkdir -p logs

# Aikar 风格 G1 参数（面向 4G 档位，可按需要调）
FLAGS=(
	-XX:+UseG1GC
	-XX:+ParallelRefProcEnabled
	-XX:MaxGCPauseMillis=200
	-XX:+UnlockExperimentalVMOptions
	-XX:+DisableExplicitGC
	-XX:+AlwaysPreTouch
	-XX:G1NewSizePercent=30
	-XX:G1MaxNewSizePercent=40
	-XX:G1HeapRegionSize=8M
	-XX:G1ReservePercent=20
	-XX:G1HeapWastePercent=5
	-XX:G1MixedGCCountTarget=4
	-XX:InitiatingHeapOccupancyPercent=15
	-XX:G1MixedGCLiveThresholdPercent=90
	-XX:G1RSetUpdatingPauseTimePercent=5
	-XX:SurvivorRatio=32
	-XX:+PerfDisableSharedMem
	-XX:MaxTenuringThreshold=1
)

echo "Starting CoopCraft server with ${MEMORY} heap..."
exec "$JAVA" -Xms"$MEMORY" -Xmx"$MEMORY" "${FLAGS[@]}" -jar fabric-server-launch.jar nogui
