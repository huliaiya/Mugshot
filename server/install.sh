#!/usr/bin/env bash
# mugshot-server 一键安装脚本（Ubuntu 22.04/24.04/26.04，ARM64 或 x86_64）
# 用法: sudo ./install.sh
#   可选环境变量:
#     MUGSHOT_LISTEN      监听地址，默认 :8080
#     MUGSHOT_REPO        GitHub 仓库 owner/repo，用于更新检查（默认 huliaiya/Mugshot）
set -euo pipefail

INSTALL_DIR="${MUGSHOT_INSTALL_DIR:-/opt/mugshot}"
SERVICE_NAME="mugshot-server"
SERVICE_USER="mugshot"
BIN_SRC="$(cd "$(dirname "$0")" && pwd)/mugshot-server"
UNIT_SRC="$(cd "$(dirname "$0")" && pwd)/mugshot-server.service"

if [[ "$(id -u)" != "0" ]]; then
  echo "请使用 sudo 运行本脚本" >&2
  exit 1
fi

if [[ ! -x "$BIN_SRC" ]]; then
  echo "未找到可执行文件 $BIN_SRC，请先构建或将本脚本与二进制放在同一目录" >&2
  exit 1
fi

echo "==> 创建系统用户 $SERVICE_USER（如已存在则跳过）"
id "$SERVICE_USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"

echo "==> 安装到 $INSTALL_DIR"
mkdir -p "$INSTALL_DIR/data"
install -m 0755 "$BIN_SRC" "$INSTALL_DIR/mugshot-server"
install -m 0644 "$UNIT_SRC" "/etc/systemd/system/$SERVICE_NAME.service"

echo "==> 生成配置文件 $INSTALL_DIR/config.json（如不存在；token 会自动生成并写入该文件）"
if [[ ! -f "$INSTALL_DIR/config.json" ]]; then
  cat > "$INSTALL_DIR/config.json" <<EOF
{
  "listen": "${MUGSHOT_LISTEN:-:8080}",
  "data_dir": "$INSTALL_DIR/data",
  "token": "",
  "report_rate_per_min": 30,
  "fresh_timeout_ms": 5000,
  "fresh_cooldown_ms": 5000,
  "retention_days": 7,
  "online_timeout_sec": 120,
  "github_repo": "${MUGSHOT_REPO:-huliaiya/Mugshot}",
  "update_auto_check": true
}
EOF
fi

chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
chmod 600 "$INSTALL_DIR/config.json"

echo "==> 启用并启动 systemd 服务"
systemctl daemon-reload
systemctl enable --now "$SERVICE_NAME.service"
sleep 2
systemctl --no-pager status "$SERVICE_NAME.service" || true

echo
echo "安装完成。常用排查命令："
echo "  systemctl status $SERVICE_NAME"
echo "  journalctl -u $SERVICE_NAME -f"
echo "  查看自动生成的 token:  sudo cat $INSTALL_DIR/config.json"
echo "  检查更新:              sudo -u $SERVICE_USER $INSTALL_DIR/mugshot-server --check-update"
echo "  手动更新:              curl -X POST -H 'Authorization: Bearer <token>' http://127.0.0.1:8080/admin/update"
