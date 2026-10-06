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
BYTESTREAM_URL="https://raw.githubusercontent.com/googleapis/googleapis/master/google/bytestream/bytestream.proto"
REAPI="$(mktemp)"
BYTESTREAM="$(mktemp)"
trap 'rm -f "$REAPI" "$BYTESTREAM"' EXIT

echo "Fetching upstream remote_execution.proto ..."
curl -fsSL --max-time 60 -o "$REAPI" "$UPSTREAM_URL"
echo "Fetching upstream bytestream.proto ..."
curl -fsSL --max-time 60 -o "$BYTESTREAM" "$BYTESTREAM_URL"
TMP="$REAPI"

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

# gRPC envelopes. Not part of the keyspace, but a wrong number here is a
# request the server silently misreads.
check GetActionResultRequest    instance_name                    1
check GetActionResultRequest    action_digest                    2
check GetActionResultRequest    inline_stdout                    3
check GetActionResultRequest    inline_stderr                    4
check GetActionResultRequest    digest_function                  6
check UpdateActionResultRequest instance_name                    1
check UpdateActionResultRequest action_digest                    2
check UpdateActionResultRequest action_result                    3
check UpdateActionResultRequest digest_function                  5
check FindMissingBlobsRequest   instance_name                    1
check FindMissingBlobsRequest   blob_digests                     2
check FindMissingBlobsRequest   digest_function                  3
check FindMissingBlobsResponse  missing_blob_digests             2
check GetCapabilitiesRequest    instance_name                    1
check ServerCapabilities        cache_capabilities               1
check CacheCapabilities         digest_functions                 1
check CacheCapabilities         action_cache_update_capabilities 2
check ActionCacheUpdateCapabilities update_enabled               1

# DigestFunction.Value is a nested enum, so `check` cannot reach it.
SHA256_UPSTREAM="$(awk '/^message DigestFunction \{/,/^\}/' "$REAPI" | grep -oP '^\s+SHA256 = \K[0-9]+' | head -1)"
if [ "$SHA256_UPSTREAM" = "1" ]; then
  echo "  ok   DigestFunction.SHA256 = 1"
else
  echo "  FAIL DigestFunction.SHA256: ours=1 upstream=${SHA256_UPSTREAM:-missing}"
  FAIL=1
fi

TMP="$BYTESTREAM"
check ReadRequest   resource_name  1
check ReadRequest   read_offset    2
check ReadRequest   read_limit     3
check ReadResponse  data           10
check WriteRequest  resource_name  1
check WriteRequest  write_offset   2
check WriteRequest  finish_write   3
check WriteRequest  data           10
check WriteResponse committed_size 1

if [ "$FAIL" -ne 0 ]; then
  echo
  echo "Field numbers diverge from upstream. Do NOT simply update the vendored"
  echo "proto: changing a field number changes every cache key this plugin"
  echo "produces. See docs/PROTOCOL.md and the golden vectors in CacheKeyMapperTest."
  exit 1
fi
echo "All field numbers match upstream."
