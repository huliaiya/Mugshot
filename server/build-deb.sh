#!/usr/bin/env bash
# 构建 mugshot-server 的 .deb 安装包
# 用法: ./build-deb.sh <版本号> <二进制路径> [架构，默认 arm64]
# 产物: mugshot-server_<版本>_<架构>.deb
set -euo pipefail

VERSION="${1:?用法: ./build-deb.sh <版本号> <二进制路径> [架构]}"
BIN="${2:?缺少二进制路径}"
ARCH="${3:-arm64}"
PKG="mugshot-server"
DIR="$(cd "$(dirname "$0")" && pwd)/deb"

if [[ ! -x "$BIN" ]]; then
  echo "错误: 二进制 $BIN 不存在或不可执行" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# ---- 目录布局 ----
mkdir -p "$STAGE/DEBIAN"
mkdir -p "$STAGE/usr/bin"
mkdir -p "$STAGE/etc/systemd/system"
mkdir -p "$STAGE/usr/share/doc/$PKG"

# ---- 二进制与 systemd 单元 ----
install -m 0755 "$BIN" "$STAGE/usr/bin/mugshot-server"
install -m 0644 "$DIR/mugshot-server.service" "$STAGE/etc/systemd/system/mugshot-server.service"

# ---- 文档 ----
{
  echo "mugshot-server ($VERSION) for $ARCH"
  echo ""
  echo "安装后: sudo cat /etc/mugshot/config.json   # 查看自动生成的 token"
  echo "日志:   journalctl -u mugshot-server -f"
  echo "数据:   /var/lib/mugshot"
} > "$STAGE/usr/share/doc/$PKG/README"

# ---- 控制文件（替换版本/架构/安装大小占位符）----
SIZE_KB=$(( $(stat -c%s "$BIN") / 1024 + 128 ))
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
