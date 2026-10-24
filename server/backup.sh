#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

LEVEL_NAME=$(sed -n 's/^level-name=//p' server.properties 2>/dev/null | head -1)
LEVEL_NAME="${LEVEL_NAME:-world}"

if [ ! -d "$LEVEL_NAME" ]; then
	echo "找不到世界目录 ${LEVEL_NAME}，服务端至少要先成功启动过一次。"
	exit 1
fi

mkdir -p backups logs
TS=$(date +%Y%m%d-%H%M%S)
OUT="backups/${LEVEL_NAME}-${TS}.tar.gz"
SAVE_PAUSED=0
STATUS=0

if command -v screen >/dev/null 2>&1 && screen -ls 2>/dev/null | grep -q '\.coopcraft'; then
	echo "检测到 screen 会话，暂停存档并 flush..."
	screen -S coopcraft -X stuff $'save-off\r'
	screen -S coopcraft -X stuff $'save-all flush\r'
	SAVE_PAUSED=1
	sleep 3
else
	echo "没有检测到 screen 会话：如果服务器正在运行，建议停服后备份，避免世界文件不一致。"
fi

if tar -czf "$OUT" "$LEVEL_NAME"; then
	echo "备份完成：${OUT}"
else
	echo "备份失败"
	STATUS=1
fi

if [ "$SAVE_PAUSED" = 1 ]; then
	screen -S coopcraft -X stuff $'save-on\r'
fi

exit "$STATUS"
