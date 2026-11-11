#!/usr/bin/env bash
# 构建 mugshot-server 的 .deb 安装包
# 用法: ./build-deb.sh <版本号> <二进制路径> [架构，默认 arm64] [GUI 二进制路径]
# 产物: mugshot-server_<版本>_<架构>.deb
set -euo pipefail

VERSION="${1:?用法: ./build-deb.sh <版本号> <二进制路径> [架构] [GUI二进制]}"
BIN="${2:?缺少二进制路径}"
ARCH="${3:-arm64}"
GUI="${4:-}"
PKG="mugshot-server"
DIR="$(cd "$(dirname "$0")" && pwd)/deb"

if [[ ! -x "$BIN" ]]; then
  echo "错误: 二进制 $BIN 不存在或不可执行" >&2
  exit 1
fi
if [[ -n "$GUI" && ! -x "$GUI" ]]; then
  echo "错误: GUI 二进制 $GUI 不存在或不可执行" >&2
  exit 1
fi

# Debian Version 字段必须以数字开头，dev-xxx 之类前缀补 0
case "$VERSION" in
  [0-9]*) ;;
  *) VERSION="0$VERSION" ;;
esac

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# ---- 目录布局 ----
mkdir -p "$STAGE/DEBIAN"
mkdir -p "$STAGE/usr/bin"
mkdir -p "$STAGE/etc/systemd/system"
mkdir -p "$STAGE/usr/share/applications"
mkdir -p "$STAGE/usr/share/icons/hicolor/scalable/apps"
mkdir -p "$STAGE/usr/share/doc/$PKG"

# ---- 二进制、管理界面与 systemd 单元 ----
install -m 0755 "$BIN" "$STAGE/usr/bin/mugshot-server"
install -m 0755 "$DIR/mugshot-ctl" "$STAGE/usr/bin/mugshot-ctl"
install -m 0644 "$DIR/mugshot-server.service" "$STAGE/etc/systemd/system/mugshot-server.service"
# 菜单只保留一个"Mugshot 服务端"图形入口；mugshot-ctl 保留二进制供无桌面/SSH 时终端运行，不再注册菜单图标
if [[ -n "$GUI" ]]; then
  install -m 0755 "$GUI" "$STAGE/usr/bin/mugshot-gui"
  install -m 0644 "$DIR/mugshot-gui.desktop" "$STAGE/usr/share/applications/mugshot-gui.desktop"
  # 图标单一来源：GUI 包内的 icon.svg
  install -m 0644 "$DIR/../cmd/mugshot-gui/icon.svg" "$STAGE/usr/share/icons/hicolor/scalable/apps/mugshot.svg"
fi

# ---- 文档 ----
{
  echo "mugshot-server ($VERSION) for $ARCH"
  echo ""
  echo "mugshot-server 是后台服务，安装后由 systemd 自动运行并开机自启。"
  echo "应用菜单点\"Mugshot 服务端\"可打开图形界面（状态/设备/token/日志/启停）。"
  echo "无桌面或 SSH 场景可在终端运行: mugshot-ctl（无菜单图标）"
  echo ""
  echo "查看 token:  sudo cat /etc/mugshot/config.json"
  echo "服务状态:    systemctl status mugshot-server"
  echo "健康检查:    curl http://127.0.0.1:8080/health"
  echo "日志:        journalctl -u mugshot-server -f"
  echo "数据:        /var/lib/mugshot"
} > "$STAGE/usr/share/doc/$PKG/README"

# ---- 控制文件（替换版本/架构/安装大小占位符）----
SIZE_KB=$(( ($(stat -c%s "$BIN") / 1024) + 128 ))
if [[ -n "$GUI" ]]; then
  SIZE_KB=$(( SIZE_KB + $(stat -c%s "$GUI") / 1024 ))
fi
sed -e "s/@VERSION@/$VERSION/g" \
    -e "s/@ARCH@/$ARCH/g" \
    -e "s/@INSTALLED_SIZE@/$SIZE_KB/g" \
    "$DIR/control" > "$STAGE/DEBIAN/control"

# ---- 维护脚本（必须可执行）----
for script in postinst prerm postrm; do
  install -m 0755 "$DIR/$script" "$STAGE/DEBIAN/$script"
done

# ---- 打包（--root-owner-group 保证文件属主为 root，与构建机用户无关）----
OUT="$PWD/${PKG}_${VERSION}_${ARCH}.deb"
dpkg-deb --root-owner-group --build "$STAGE" "$OUT"
echo "已生成 $OUT"
