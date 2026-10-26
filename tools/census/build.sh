#!/usr/bin/env bash
# Build the card-census engine, and prove the instrumentation is inert.
#
#   tools/census/build.sh [<main-repo>] [<outdir>]
#
# Archives shell/engine at the main repo's HEAD, patches a COPY with play
# counting, builds cmd/census and cmd/parity from that copy, and refuses to
# finish unless both produce the SAME result hash on a real payload as an
# unpatched build does. A counter that changes what it counts is worthless,
# and that is not something to take on trust -- it is one comparison.
set -euo pipefail

MAIN="${1:-$HOME/Documents/autobattle.online}"
OUT="${2:-$(mktemp -d)}"
HERE=$(cd "$(dirname "$0")" && pwd)

command -v go >/dev/null || { echo "go is not installed"; exit 1; }

ver=$(cd "$MAIN" && git show HEAD:shell/engine/engine.go | grep -oE 'engineVersion = "[^"]*"' | head -1)
echo "==> engine at main HEAD: $ver"

rm -rf "$OUT/clean" "$OUT/census"
mkdir -p "$OUT/clean" "$OUT/census"
(cd "$MAIN" && git archive HEAD shell/engine) | tar -x -C "$OUT/clean"
(cd "$MAIN" && git archive HEAD shell/engine) | tar -x -C "$OUT/census"

python3 -I "$HERE/patch_engine.py" "$OUT/census/shell/engine/engine.go"
mkdir -p "$OUT/census/shell/engine/cmd/census"
cp "$HERE/census_main.go" "$OUT/census/shell/engine/cmd/census/main.go"

build() {  # dir, pkg, out
  local d=$1 pkg=$2 o=$3
  ( cd "$d/shell/engine"
    local gv tc
    gv=$(awk '/^go /{print $2}' go.mod)
    tc=$(ls -d "$HOME/go/pkg/mod/golang.org/toolchain@v0.0.1-go${gv}."*/bin 2>/dev/null | head -1)
    PATH="$tc:$PATH" GOTOOLCHAIN=local GOFLAGS=-mod=mod go build -o "$o" "$pkg" )
}

echo "==> building"
build "$OUT/clean"  ./cmd/parity "$OUT/parity_clean"
build "$OUT/census" ./cmd/parity "$OUT/parity_census"
build "$OUT/census" ./cmd/census "$OUT/census_bin"

if [ -n "${CENSUS_VERIFY_PAYLOAD:-}" ] && [ -f "$CENSUS_VERIFY_PAYLOAD" ]; then
  a=$("$OUT/parity_clean"  "$CENSUS_VERIFY_PAYLOAD" --hash)
  b=$("$OUT/parity_census" "$CENSUS_VERIFY_PAYLOAD" --hash)
  if [ "$a" != "$b" ]; then
    echo "REFUSING: the instrumented engine does not agree with the clean one"
    echo "  clean:        $a"
    echo "  instrumented: $b"
    exit 1
  fi
  echo "==> inert: instrumented and clean agree ($a)"
else
  echo "==> WARNING: set CENSUS_VERIFY_PAYLOAD to a payload file to prove the"
  echo "    instrumentation is inert. Unverified counts should not be believed."
fi

echo "==> census binary: $OUT/census_bin"
