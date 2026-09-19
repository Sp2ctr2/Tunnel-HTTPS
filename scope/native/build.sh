#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
HTML="${1:-$ROOT/Tunnel_Scope.html}"
OUT="${2:-$ROOT/Tunnel-Scope-linux-x86_64}"
command -v cc >/dev/null || { echo 'A C compiler is required.' >&2; exit 1; }
pkg-config --exists webkit2gtk-4.1 gtk+-3.0 || { echo 'Install WebKitGTK 4.1 and GTK 3 development packages.' >&2; exit 1; }
[ -f "$HTML" ] || { echo "Missing viewer: $HTML" >&2; exit 1; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cp "$HTML" "$TMP/Tunnel_Scope.html"
printf '%s\n' '<gresources><gresource prefix="/tunnel/scope"><file> Tunnel_Scope.html </file></gresource></gresources>' | sed 's/> Tunnel_Scope.html </>Tunnel_Scope.html</' > "$TMP/resources.xml"
glib-compile-resources "$TMP/resources.xml" --sourcedir="$TMP" --generate-source --target="$TMP/resource.c"
cc -O2 -Wall -Wextra "$ROOT/native/main.c" "$TMP/resource.c" $(pkg-config --cflags --libs webkit2gtk-4.1 gtk+-3.0) -o "$OUT"
strip "$OUT"
echo "Built native host: $OUT"
