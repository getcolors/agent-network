terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "~> 2.0" }
  }
}

provider "digitalocean" {
  # token comes from DIGITALOCEAN_TOKEN in the environment
}

locals {
  ssh_sources  = ["0.0.0.0/0"]
  http_sources = ["0.0.0.0/0"]
  stun_sources = ["0.0.0.0/0"]
}

# The region's account-default VPC, discovered at plan time. This package
# creates no VPC and pins no UUID: the droplet joins whatever `default-<region>`
# is, and the validator refuses digitalocean-vpc-uuid and digitalocean-vpc-cidr
# so desired state cannot quietly start owning one.
data "digitalocean_vpc" "default" {
  name = "default-ams3"
}

# The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable. Never reference a
# literal key id here in keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "agent-network-digitalocean-fixture"
  public_key = trimspace(file("/home/build-placeholder/.ssh/agent-network-digitalocean-fixture.pub"))
}

# Every label derives from one resolved name (Compute Name Standard §3), which
# defaults to the profile. Templates never branch on whether an override was
# supplied — that decision was made once, in Clojure.
resource "digitalocean_droplet" "agent_network" {
  # `name` is the console label and updates in place; cloud-init also sets the
  # guest hostname from it at creation, and a later rename never revisits that,
  # so a changed name takes effect on the next create rather than repairing a
  # running host. `region`, `image` and `vpc_uuid` are ForceNew: editing any of
  # them destroys the droplet and its disk. `size` alone resizes in place.
  name     = "agent-network-digitalocean-fixture"
  region   = "ams3"
  size     = "s-2vcpu-4gb"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = data.digitalocean_vpc.default.id
  # IPv6 is off rather than unmanaged, for the same reason as on Vultr: Docker
  # will happily publish on a v6 address, and firewall rules and source CIDRs
  # can diverge from their v4 counterparts; for a single-node box one family is
  # one set of rules to get right instead of two.
  ipv6     = false
  # SSH keys are ids or fingerprints already in the account, and ForceNew:
  # changing the key set destroys and recreates the droplet instead of
  # re-authorizing it. Rotation is a rebuild, never an edit on a machine whose
  # disk you intend to keep.
  ssh_keys = [digitalocean_ssh_key.machine.id]
  # Wait for ssh before starting Ansible.
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
    private_key = file("/home/build-placeholder/.ssh/agent-network-digitalocean-fixture")
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle { prevent_destroy = true }
}

# The provider firewall is the load-bearing layer, and it mirrors the Vultr
# rule set exactly: 22 from the SSH sources, 80 and 443 from the HTTP sources,
# STUN over UDP from the STUN sources, nothing else. Ansible manages no ufw
# for these ports. A rule with no source is not "closed" to DigitalOcean but
# an API error, so the list-driven rules are emitted only when there is a
# source to name; an empty http-sources or stun-sources list means that
# service is simply not public.
resource "digitalocean_firewall" "agent_network" {
  name        = "agent-network-digitalocean-fixture-firewall"
  droplet_ids = [digitalocean_droplet.agent_network.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = local.ssh_sources
  }
  # 80 carries only the redirect to 443. Certificate issuance uses TLS-ALPN-01
  # on 443, so closing 80 would not break ACME — it would only strip the
  # redirect. 443 carries the dashboard, the management and signal gRPC
  # streams, the relay WebSocket, the API and the embedded IdP — the combined
  # server multiplexes all of them behind Traefik.
  dynamic "inbound_rule" {
    for_each = length(local.http_sources) > 0 ? ["80", "443"] : []
    content {
      protocol         = "tcp"
      port_range       = inbound_rule.value
      source_addresses = local.http_sources
    }
  }
  # The only UDP this deployment publishes. STUN is bundled into the combined
  # server, so there is no coturn container and no legacy 49152-65535 relay
  # range: relayed traffic rides the WebSocket on 443.
  dynamic "inbound_rule" {
    for_each = length(local.stun_sources) > 0 ? ["3478"] : []
    content {
      protocol         = "udp"
      port_range       = inbound_rule.value
      source_addresses = local.stun_sources
    }
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  lifecycle { prevent_destroy = true }
}

output "params" {
  value = {
    provider = "digitalocean"
    ip       = digitalocean_droplet.agent_network.ipv4_address
    user     = "root"
    sudoer   = "root"
    name     = "agent-network-digitalocean-fixture"
    ssh_key_id = digitalocean_ssh_key.machine.id
  }
}
