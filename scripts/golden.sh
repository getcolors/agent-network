#!/usr/bin/env bash
set -euo pipefail

# agent-network is a single colour, so there is no parity harness. This is the
# regression net in its place: render every fixture and diff against committed
# output.
#
# Two fixtures, because the SSH Keypair Standard has two modes and a package
# conforms only if both hold. `colors.yml` is keygen mode (no vultr-ssh-keys):
# the compute template must declare the profile-named vultr_ssh_key resource
# and reference it by attribute. `optout.yml` supplies an explicit key id and
# must render the historical shape, byte for byte, creating nothing.
#
# Keygen paths are rendered from a fixed placeholder home on :build, never from
# $HOME, so these goldens mean the same thing on every workstation.
#
#   ./scripts/golden.sh            check
#   ./scripts/golden.sh --accept   regenerate after an intended change

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

accept=0
[[ ${1:-} == --accept ]] && accept=1

status=0
for variant in colors optout; do
  fixture="$tmp/$variant.yml"
  sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/$variant.yml" > "$fixture"
  (cd "$root/green" && AGENT_NETWORK_LIB_ROOT="$root" ./green build -f "$fixture" >/dev/null)

  profile=$(sed -n 's/^profile: //p' "$fixture")
  actual="$tmp/work/$profile"
  golden="$root/test/resources/golden/local/$profile"

  # No rendered artefact may carry a real secret into a committed golden.
  # Checked before --accept copies anything. POSIX grep on purpose: a missing
  # binary inside `if` is simply false, so the guard must not depend on one
  # that may be absent.
  if grep -rEq 'BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_|sk-ant-api' "$actual"; then
    echo "golden: a credential-shaped value was rendered in $profile" >&2; exit 1
  fi
  # The one operator secret must reach the host as an Ansible lookup resolved
  # at execution time, never as a value templated into generated output. If
  # this expression stops appearing, something started rendering the secret
  # itself and the next `bb golden:accept` would commit it.
  if ! grep -q "lookup('env','COLORS_PAR_ANTHROPIC_API_KEY')" "$actual/agent-network-ansible/main.yml"; then
    echo "golden: $profile no longer renders COLORS_PAR_ANTHROPIC_API_KEY as a lookup" >&2; exit 1
  fi

  # The host-generated secrets are substituted on the host at install time. If
  # a real value ever reaches the rendered config, the next `bb golden:accept`
  # would commit this deployment's store encryption key to git.
  for ph in __RELAY_AUTH_SECRET__ __SESSION_COOKIE_ENCRYPTION_KEY__ __DATASTORE_ENCRYPTION_KEY__; do
    if ! grep -q "$ph" "$actual/agent-network-ansible/config.yaml"; then
      echo "golden: $profile no longer renders $ph as a placeholder" >&2; exit 1
    fi
  done
  if ! grep -q "__PROXY_TOKEN__" "$actual/agent-network-ansible/proxy.env"; then
    echo "golden: $profile no longer renders __PROXY_TOKEN__ as a placeholder" >&2; exit 1
  fi

  # A build that reached the real ~/.ssh would leak the operator's home into
  # committed bytes and make the goldens workstation-specific.
  if grep -rq "$HOME/.ssh" "$actual"; then
    echo "golden: $profile rendered a real home directory; build must use the placeholder" >&2; exit 1
  fi
  # SSH Config Standard §6: the local stage takes the address, the user and the
  # alias as Ansible extra-vars, never through Selmer, so its rendered playbook
  # carries no address at all. A dotted quad here means someone templated a
  # run-time fact and the goldens stopped being workstation-independent.
  if grep -rEq '([0-9]{1,3}\.){3}[0-9]{1,3}' "$actual/agent-network-ansible-local"; then
    echo "golden: $profile rendered an address into the local ssh_config stage" >&2; exit 1
  fi

  if [[ $accept == 1 ]]; then
    rm -rf "$golden"; mkdir -p "$(dirname "$golden")"; cp -a "$actual" "$golden"; continue
  fi
  [[ -d "$golden" ]] || { echo "golden missing for $profile; inspect build then run bb golden:accept" >&2; exit 1; }
  diff -ru "$golden" "$actual" || status=1
done

exit "$status"
