#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

MC_VERSION=1.21.1
LOADER_VERSION=0.20.1
INSTALLER_VERSION=1.1.2
FABRIC_API_VERSION="${FABRIC_API_VERSION:-0.116.17+1.21.1}"
MOD_JAR="${1:-}"

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

JAVA_MAJOR=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
	echo "当前 Java 是 ${JAVA_MAJOR:-未知}，服务端需要 21+。"
	exit 1
fi

if [ -z "$MOD_JAR" ]; then
	MOD_JAR=$(find ../build/libs -type f -name 'coopcraft-*.jar' ! -name '*-sources.jar' 2>/dev/null | head -1 || true)
fi

if [ -z "$MOD_JAR" ] || [ ! -f "$MOD_JAR" ]; then
	echo "找不到 CoopCraft 的 jar。"
	echo "先在仓库根目录执行 ./gradlew build，或者显式传入路径："
	echo "  ./setup.sh /path/to/coopcraft-0.1.0.jar"
	exit 1
fi

mkdir -p mods logs

if [ ! -f fabric-installer.jar ]; then
	echo "下载 Fabric Installer ${INSTALLER_VERSION}..."
	curl -fsSL --retry 3 \
		"https://maven.fabricmc.net/net/fabricmc/fabric-installer/${INSTALLER_VERSION}/fabric-installer-${INSTALLER_VERSION}.jar" \
		-o fabric-installer.jar.part
	mv fabric-installer.jar.part fabric-installer.jar
fi

if [ ! -f fabric-server-launch.jar ] || [ ! -f server.jar ]; then
	echo "安装 Fabric 服务端 ${MC_VERSION} + Loader ${LOADER_VERSION}..."
	echo "第一次会下载 Minecraft 服务端和依赖，需要几分钟。"
	"$JAVA" -jar fabric-installer.jar server \
		-dir . \
		-mcversion "$MC_VERSION" \
		-loader "$LOADER_VERSION" \
		-downloadMinecraft
fi

API_ENCODED="${FABRIC_API_VERSION//+/%2B}"
if [ ! -f "mods/fabric-api-${FABRIC_API_VERSION}.jar" ]; then
	echo "下载 Fabric API ${FABRIC_API_VERSION}..."
	curl -fsSL --retry 3 \
		"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${API_ENCODED}/fabric-api-${API_ENCODED}.jar" \
		-o "mods/fabric-api-${FABRIC_API_VERSION}.jar.part"
	mv "mods/fabric-api-${FABRIC_API_VERSION}.jar.part" "mods/fabric-api-${FABRIC_API_VERSION}.jar"
fi

rm -f mods/coopcraft-*.jar
cp "$MOD_JAR" "mods/$(basename "$MOD_JAR")"

if [ ! -f server.properties ]; then
	cp server.properties.example server.properties
	echo "已生成 server.properties（默认开启白名单）"
fi

if [ ! -f eula.txt ]; then
	cat > eula.txt <<'EOF'
#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).
eula=false
EOF
fi

cat <<'EOF'

安装完成。下一步：

1. 同意 Minecraft EULA：把 eula.txt 里的 eula=false 改成 eula=true。
2. 启动服务端：
     ./start.sh
   想让它退出终端后继续跑：
     screen -S coopcraft ./start.sh
3. 在服务端控制台把自己设为管理员，再拉朋友进白名单：
     op <你的游戏ID>
     whitelist add <朋友的游戏ID>
4. 远程联机和 Tailscale 配置见 README.md。

服务端 mods 目录里需要同时有 CoopCraft 和 Fabric API，客户端也一样。
EOF
