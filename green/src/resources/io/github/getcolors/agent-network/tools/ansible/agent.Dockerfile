# The isolated agent: NetBird client + headless Claude Code, built on this
# host from pinned inputs. The base image is pinned by digest and both
# payloads by exact version, so the goldens' claim about what runs here is a
# claim about bytes, not about whatever a floating tag meant that day.
#
# The NetBird client tarball is verified against the checksums file published
# with the same release before anything from it is executed.
FROM <{ agent-network-agent-base-image }>

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl iproute2 iptables procps \
 && rm -rf /var/lib/apt/lists/*

ARG NETBIRD_VERSION=<{ agent-network-netbird-client-version }>
RUN set -eu; \
    arch=amd64; \
    base="https://github.com/netbirdio/netbird/releases/download/v${NETBIRD_VERSION}"; \
    curl -fsSL -o /tmp/netbird.tar.gz \
      "${base}/netbird_${NETBIRD_VERSION}_linux_${arch}.tar.gz"; \
    curl -fsSL -o /tmp/checksums.txt "${base}/netbird_${NETBIRD_VERSION}_checksums.txt"; \
    grep "netbird_${NETBIRD_VERSION}_linux_${arch}.tar.gz" /tmp/checksums.txt \
      | sed 's#  .*#  /tmp/netbird.tar.gz#' | sha256sum -c -; \
    tar -xzf /tmp/netbird.tar.gz -C /usr/local/bin netbird; \
    chmod 0755 /usr/local/bin/netbird; \
    rm -f /tmp/netbird.tar.gz /tmp/checksums.txt

RUN npm install -g @anthropic-ai/claude-code@<{ agent-network-claude-code-version }>

COPY agent-entry.sh /usr/local/bin/agent-entry.sh
RUN chmod 0755 /usr/local/bin/agent-entry.sh

# Versions the acceptance suite prints; a mismatch with desired state fails it.
RUN netbird version > /etc/agent-versions \
 && claude --version >> /etc/agent-versions || true

ENTRYPOINT ["/usr/local/bin/agent-entry.sh"]
