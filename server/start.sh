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

MEMORY="${MEMORY:-2G}"
mkdir -p logs

echo "Starting CoopCraft server with ${MEMORY} heap..."
exec "$JAVA" -Xms"$MEMORY" -Xmx"$MEMORY" -jar fabric-server-launch.jar nogui
