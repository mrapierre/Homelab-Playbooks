# homelab-playbooks

Rebuild playbooks for my Proxmox homelab infrastructure. Each document covers a specific LXC container — what it does, how it's configured, and how to rebuild it from scratch.

All sensitive values (IPs, tokens, credentials) have been replaced with `YOUR_*` placeholders. Private versions with real values are maintained separately.

## Containers

| Playbook | Container | Description |
|----------|-----------|-------------|
| [homelab-ai-agent.md](homelab-ai-agent.md) | CT900 | AI monitoring agent — AWS Bedrock, Telegram, Proxmox metrics |
| [reticulum.md](reticulum.md) | CT108 | Reticulum mesh network over I2P — resilient backup access path |
| [osint-lab.md](osint-lab.md) | CT200 | Isolated OSINT research environment |

## Infrastructure Overview

- **Proxmox host** — single node, ~25 LXC containers
- **Romanian VPS** — Docker host, Nginx Proxy Manager, Hugo portfolio site
- **WireGuard** — VPN tunnel between home and VPS
- **AWS Bedrock** — AI analysis backend (Nova Micro/Lite/Pro tiered routing)
- **n8n** — workflow orchestration for scheduled agent tasks (CT116)
- **Telegram** — alert delivery and interactive bot interface

## Related

- Portfolio: [anthonyapierre.com](https://anthonyapierre.com)
- Project detail: [anthonyapierre.com/posts/homelab-ai-agent-project](https://anthonyapierre.com/posts/homelab-ai-agent-project/)
