#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-agent-network-green/green"
grep -q 'io.github.getcolors.agent-network.workflow/workflow' "$launcher"
grep -q 'def \^:private agent-network-sha' "$launcher"
[[ -L "$root/green/green" ]] && [[ $(readlink "$root/green/green") == ../skills/package-agent-network-green/green ]]
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cp "$launcher" "$tmp/green"; chmod +x "$tmp/green"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/colors.yml"
(cd "$tmp" && AGENT_NETWORK_LIB_ROOT="$root" ./green build >/dev/null)
[[ -f "$tmp/.colors/agent-network-fixture/compute/nodes/0/node-none.tf.json" ]]
[[ -f "$tmp/.colors/agent-network-fixture/agent-network-ansible/compose.yml" ]]
# The launcher walks up for colors.yml, so any subdirectory works.
mkdir -p "$tmp/nested/path"
(cd "$tmp/nested/path" && AGENT_NETWORK_LIB_ROOT="$root" ../../green build >/dev/null)
# The profile guard is the whole reason COLORS_PAR_PROFILE is refused: an
# overlay would point one deployment at another's state.
out=$(cd "$tmp" && AGENT_NETWORK_LIB_ROOT="$root" COLORS_PAR_PROFILE=wrong ./green build 2>&1 || true)
grep -q COLORS_PAR_PROFILE <<<"$out"
[[ ! -d "$tmp/.colors/wrong" ]]

# --- red -------------------------------------------------------------------
# Every Red record of the colors-compute pin matches green's deps.edn: the
# copied payload installs it through the root manifest and red/package.json
# tests against it.
fail(){ echo "launcher: FAIL — $*" >&2; exit 1; }
red_launcher="$root/skills/package-agent-network-red/red"
[[ -f $red_launcher ]] || fail 'red payload launcher is missing'
[[ -L "$root/red/red" ]] && [[ $(readlink "$root/red/red") == ../skills/package-agent-network-red/red ]] || fail 'red/red is not the payload symlink'
compute_sha=$(awk '/colors-compute\.git/ {found=1} found && match($0, /:git\/sha "[0-9a-f]{40}"/) {print substr($0, RSTART+10, 40); exit}' "$root/green/deps.edn")
[[ -n $compute_sha ]] || fail 'green/deps.edn carries no colors-compute pin'
grep -q "getcolors/colors-compute#$compute_sha" "$root/red/package.json" || fail 'red/package.json pins colors-compute at a different commit than green'
grep -q "getcolors/colors-compute#$compute_sha" "$root/package.json" || fail 'the root package.json pins colors-compute at a different commit than green'

# colors-compute-red declares the Red SDK as a peer, so a cold launcher cache
# installs the SDK only because PINS names it. The pin must be the one
# red/package.json tests against, and a cold cache must actually resolve it:
# the working-tree builds reuse red/node_modules and cannot see a missing peer.
red_sdk_sha=$(grep -oE '"red": "github:getcolors/red#[0-9a-f]{40}"' "$root/red/package.json" | grep -oE '[0-9a-f]{40}')
[[ -n $red_sdk_sha ]] || fail 'red/package.json carries no Red SDK pin'
grep -q "\"red\": \"github:getcolors/red#$red_sdk_sha\"" "$red_launcher" || fail 'red payload PINS the Red SDK at a different commit than red/package.json'
mkdir "$tmp/red-cold"
cp "$red_launcher" "$tmp/red-cold/red"; chmod +x "$tmp/red-cold/red"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/red-cold/colors.yml"
# One retry: a cold install fetches GitHub tarballs and a transient fetch
# failure is not a payload defect. Each attempt starts from empty caches.
cold_ok=0
for attempt in 1 2; do
  rm -rf "$tmp/red-cold/xdg" "$tmp/red-cold/bun" "$tmp/red-cold/.colors"
  if (cd "$tmp/red-cold" && XDG_CACHE_HOME="$tmp/red-cold/xdg" BUN_INSTALL_CACHE_DIR="$tmp/red-cold/bun" ./red build >"$tmp/red-cold/build.log" 2>&1); then cold_ok=1; break; fi
done
[[ $cold_ok == 1 ]] || { tail -5 "$tmp/red-cold/build.log" >&2; fail 'red payload does not build from a cold cache'; }
[[ -f "$tmp/red-cold/.colors/agent-network-fixture/compute/nodes/0/node-none.tf.json" ]] || fail 'cold red payload rendered nothing'
echo 'launcher: all checks passed'
