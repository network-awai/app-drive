#!/bin/sh
# Local synthetic Otehon check using pinned, explicitly supplied repositories.
set -eu
if [ "$#" -ne 3 ]; then
  echo 'usage: verify-local.sh APEX_CHECKOUT KOTOBA_REPOSITORY KBB_CHECKOUT' >&2
  exit 2
fi
apex=$(cd "$1" && pwd -P)
kotoba=$(cd "$2" && pwd -P)
kbb=$(cd "$3" && pwd -P)
root=$(CDPATH='' cd "$(dirname "$0")/.." && pwd -P)
[ "$(git -C "$apex" rev-parse HEAD)" = 9d4d3a0d1da07c0096e59327fa4b44a49c168223 ]
[ "$(git -C "$kbb" rev-parse HEAD)" = 1675e6aab65d5a99e37840cc5012bc2eca6f4a07 ]
git -C "$apex" diff --quiet
git -C "$apex" diff --cached --quiet
git -C "$kbb" diff --quiet
git -C "$kbb" diff --cached --quiet
git -C "$kotoba" cat-file -e 'b7d31a1ee3aafdb4c33dd9f7d6b94ae423393b64^{commit}'
git -C "$kotoba" cat-file -e 'b2ae9f9fa598bc1b9a7462b68f93571af8540570^{commit}'
mkdir -p "$root/.agent-output/.agent-output"
out=$(mktemp -d "$root/.agent-output/drive-check.XXXXXX")
mkdir -p "$out/deps" "$out/test-src/drive"
cp "$apex/otehon/owner-store/package.json" "$apex/otehon/owner-store/package-lock.json" "$out/deps/"
npm ci --ignore-scripts --prefix "$out/deps"
node "$apex/otehon/owner-store/stage.mjs" "$kotoba" "$out/envelope"
node "$apex/otehon/owner-store/stage-dango.mjs" "$kotoba" "$out/dango"
cp "$root/otehon/test/drive/private_file_test.cljc" "$out/test-src/drive/private_file_test.cljs"
cp "$root/otehon/test/drive/private_file_delivery_test.cljc" "$out/test-src/drive/private_file_delivery_test.cljs"
cp "$root/otehon/test/drive/passkey_seal_test.cljc" "$out/test-src/drive/passkey_seal_test.cljs"
cp "$root/otehon/test/drive/local_revocation_host_test.cljc" "$out/test-src/drive/local_revocation_host_test.cljs"
cp "$root/otehon/test/drive/async_delivery_test.cljc" "$out/test-src/drive/async_delivery_test.cljs"
cp "$root/otehon/test/drive/snapshot_host_test.cljc" "$out/test-src/drive/snapshot_host_test.cljs"
cp "$root/otehon/test/drive/browser_delivery_host_test.cljc" "$out/test-src/drive/browser_delivery_host_test.cljs"
cd "$out/deps"
node "$kbb/cli.js" -cp "$out/dango:$out/envelope:$apex/otehon/owner-store/lib:$root/otehon/src:$out/test-src" \
  -e '(require (quote drive.browser-delivery-host-test))'
node "$kbb/cli.js" -cp "$out/dango:$out/envelope:$apex/otehon/owner-store/lib:$root/otehon/src:$out/test-src" \
  -e '(require (quote drive.snapshot-host-test))'
node "$kbb/cli.js" -cp "$out/dango:$out/envelope:$apex/otehon/owner-store/lib:$root/otehon/src:$out/test-src" \
  -e '(require (quote drive.async-delivery-test))'
node "$kbb/cli.js" -cp "$out/dango:$out/envelope:$apex/otehon/owner-store/lib:$root/otehon/src:$out/test-src" \
  -e '(require (quote drive.local-revocation-host-test))'
node "$kbb/cli.js" -cp "$out/dango:$out/envelope:$apex/otehon/owner-store/lib:$root/otehon/src:$out/test-src" \
  -e '(require (quote drive.private-file-test))'
node "$kbb/cli.js" -cp "$out/dango:$out/envelope:$apex/otehon/owner-store/lib:$root/otehon/src:$out/test-src" \
  -e '(require (quote drive.private-file-delivery-test))'
node "$kbb/cli.js" -cp "$out/dango:$out/envelope:$apex/otehon/owner-store/lib:$root/otehon/src:$out/test-src" \
  -e '(require (quote drive.passkey-seal-test))'
echo "Drive local test evidence: $out"
