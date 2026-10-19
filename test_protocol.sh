#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v javac >/dev/null || { echo 'JDK javac is required' >&2; exit 1; }
command -v java >/dev/null || { echo 'JDK java is required' >&2; exit 1; }
# Build into a temporary directory to avoid touching the APK build tree.
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
javac -d "$test_dir" \
  src/com/xriku/edifiercontrol/Protocol.java \
  tests/com/xriku/edifiercontrol/ProtocolTest.java
java -cp "$test_dir" com.xriku.edifiercontrol.ProtocolTest
