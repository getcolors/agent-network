#!/usr/bin/env bash
set -euo pipefail

# One desired state, three colours, byte for byte. golden.sh is green's
# regression net against the committed goldens; this is the net across colours:
# each fixture is rendered by green, red, and blue into separate work
# directories and the trees must be identical — and the template trees each
# colour carries must be identical too, because the copies are the mechanism
# (red/resources and blue's embedded resources are copies of green's tree, not
# references to it).
#
# Four fixtures, one per advertised compute provider per keypair mode: the SSH
# Keypair Standard has two modes and parity means both keygen and opt-out hold
# in every colour, and providers are selected by template directory, so the
# Vultr tree and the DigitalOcean tree must both hold in every colour too.
#
# Renders resolve each colour's package from this working tree (the *_LIB_ROOT
# overrides), while green, once, red, and blue stay on their pins — a change
# that lands here passes parity before it is pushed or pinned anywhere.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

build_variant() {
  local variant=$1
  for colour in green red blue; do
    sed "s#WORKDIR#$tmp/$variant/$colour#" "$root/test/fixtures/$variant.yml" \
      > "$tmp/$variant-$colour.yml"
  done
  (cd "$root/green" && AGENT_NETWORK_LIB_ROOT="$root" ./green build -f "$tmp/$variant-green.yml" >/dev/null)
  (cd "$root/red" && AGENT_NETWORK_LIB_ROOT="$root/red" ./red build -f "$tmp/$variant-red.yml" >/dev/null)
  (cd "$root/blue" && uv run python -m package_agent_network_blue build -f "$tmp/$variant-blue.yml" >/dev/null)
  diff -r "$tmp/$variant/green" "$tmp/$variant/red"
  diff -r "$tmp/$variant/green" "$tmp/$variant/blue"
}

build_variant colors
build_variant optout
build_variant colors-digitalocean
build_variant optout-digitalocean

diff -r "$root/green/src/resources/io/github/getcolors/agent-network" "$root/red/resources"
diff -r "$root/green/src/resources/io/github/getcolors/agent-network" "$root/blue/src/package_agent_network_blue/resources"

echo "green, red, and blue Agent Network artifacts are byte-identical"
