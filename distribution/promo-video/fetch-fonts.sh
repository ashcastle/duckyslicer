#!/usr/bin/env bash
# Downloads the web fonts used by index.html from the npm registry into ./fonts
# (Pretendard, Roboto and JetBrains Mono — all SIL OFL 1.1 / Apache 2.0).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p fonts
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
(cd "$tmp" && npm pack --silent pretendard@1.3.9 @fontsource/roboto@5 @fontsource/jetbrains-mono@5 >/dev/null && for f in *.tgz; do mkdir "${f%.tgz}" && tar xzf "$f" -C "${f%.tgz}"; done)
for w in Regular Medium SemiBold Bold ExtraBold Black; do cp "$tmp"/pretendard-*/package/dist/web/static/woff2/Pretendard-$w.woff2 fonts/; done
cp "$tmp"/fontsource-roboto-*/package/files/roboto-latin-{400,500}-normal.woff2 fonts/
cp "$tmp"/fontsource-jetbrains-mono-*/package/files/jetbrains-mono-latin-{500,700}-normal.woff2 fonts/
ls fonts
