#!/usr/bin/env bash
# Standalone failure-injection harness for ClaimBarter's purchase-rollback
# protocol. Copies the four real sources fresh from the repo WORKING TREE
# (never the repo itself), compiles them with the harness's Bukkit/
# GriefPrevention stubs and driver, and runs every injection twice - once per
# PlayerInventory.mirrorSemantics value. Exits non-zero if compilation fails
# or if any injection run fails.
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${CLAIMBARTER_REPO:-$(cd "$HARNESS_DIR/../../../.." && pwd)}"
SRC_DIR="$HARNESS_DIR/src"
OUT_DIR="$HARNESS_DIR/out"
CONFIG_YML="$REPO_ROOT/src/main/resources/config.yml"

rm -rf "$SRC_DIR" "$OUT_DIR"
mkdir -p "$SRC_DIR/io/github/diegot4l/claimbarter" "$OUT_DIR"

for f in InvalidSettingException BarterSettings Messages BarterService; do
    cp "$REPO_ROOT/src/main/java/io/github/diegot4l/claimbarter/$f.java" \
       "$SRC_DIR/io/github/diegot4l/claimbarter/$f.java"
done

mapfile -t JAVA_FILES < <(find "$HARNESS_DIR/stubs" "$SRC_DIR" "$HARNESS_DIR/driver" -name '*.java' | sort)

echo "Compiling ${#JAVA_FILES[@]} source files..." >&2
javac --release 21 -Xlint:-options -d "$OUT_DIR" "${JAVA_FILES[@]}"

java -cp "$OUT_DIR" io.github.diegot4l.claimbarter.Harness "$CONFIG_YML"
