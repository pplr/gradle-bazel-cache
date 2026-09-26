#!/usr/bin/env bash
# Verify every field number in our vendored proto subset against upstream.
#
# Our .proto is a hand-written subset of the Bazel Remote Execution API. Wire
# compatibility depends entirely on those field numbers being right, and a
# mistake would only surface as a silent cache miss against a real server.
#
# Run this when bumping the vendored protos, or on a schedule.
# Requires network access.
set -euo pipefail

UPSTREAM_URL="https://raw.githubusercontent.com/bazelbuild/remote-apis/main/build/bazel/remote/execution/v2/remote_execution.proto"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

echo "Fetching upstream remote_execution.proto ..."
curl -fsSL --max-time 60 -o "$TMP" "$UPSTREAM_URL"

FAIL=0
check() {
  local msg="$1" field="$2" ours="$3"
  local upstream
  upstream="$(awk "/^message $msg \{/,/^\}/" "$TMP" | grep -oP "(?<= )$field = \K[0-9]+" | head -1)"
  if [ "$upstream" = "$ours" ]; then
    printf '  ok   %s.%s = %s\n' "$msg" "$field" "$ours"
  else
    printf '  FAIL %s.%s: ours=%s upstream=%s\n' "$msg" "$field" "$ours" "${upstream:-missing}"
    FAIL=1
  fi
}

check Digest       hash              1
check Digest       size_bytes        2
check OutputFile   path              1
check OutputFile   digest            2
check OutputFile   is_executable     4
check ActionResult output_files      2
check ActionResult exit_code         4
check Command      arguments         1
check Command      output_paths      7
check Action       command_digest    1
check Action       input_root_digest 2
check Action       salt              9

if [ "$FAIL" -ne 0 ]; then
  echo
  echo "Field numbers diverge from upstream. Do NOT simply update the vendored"
  echo "proto: changing a field number changes every cache key this plugin"
  echo "produces. See docs/PROTOCOL.md and the golden vectors in CacheKeyMapperTest."
  exit 1
fi
echo "All field numbers match upstream."
