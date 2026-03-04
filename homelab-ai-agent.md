# Homelab AI Monitoring Agent (CT900)

> **Public documentation** — real IPs, tokens, and credentials have been replaced with placeholders. See private docs for live values.

## Overview

An AI-powered infrastructure monitoring agent running on Proxmox. Collects metrics from 25+ LXC containers every 15 minutes, analyses them using AWS Bedrock (Nova models), and delivers intelligent alerts and daily health reports via Telegram — with inline action buttons for acknowledge, snooze, on-demand analysis, and approved remediation actions.

**Design principles:**
- Read-only by default — the agent observes, it does not act without approval
- Cost-conscious — pre-summarisation keeps Bedrock token usage minimal (~$0.00004 per analysis)
- State-aware alerting — alerts fire once per breach, not on every check cycle
- Snapshot before action — every remediation takes a Proxmox snapshot first

---

## Infrastructure

| Component | Value |
|-----------|-------|
| Container | CT900 |
| Container IP | `YOUR_CT900_IP` |
| OS | Debian 12 |
| Python | 3.11.2 |
| Proxmox host | `YOUR_PROXMOX_IP` |
| Docker LXC | CT120 at `YOUR_CT120_IP` |
| n8n LXC | CT116 at `YOUR_CT116_IP:5678` |
| Private docs | CT901 at `YOUR_CT901_IP` |
| Romanian VPS | `YOUR_VPS_IP` |
| Agent path | /opt/homelab-agent/ |
| Venv | /opt/homelab-agent/venv/ |
| Database | /opt/homelab-agent/data/metrics.db |
| Logs | /opt/homelab-agent/logs/ |
| AWS region | eu-west-1 |
| AWS credentials | ~/.aws/credentials (shared credentials file) |

> Note: `config.yaml` has `region: us-east-1` but AWS CLI is configured to `eu-west-1`. Bedrock model IDs use `us.` prefix — verify which region is actually serving requests.

---

## Credentials & Tokens

| Secret | Value |
|--------|-------|
| Proxmox token ID | agent@pve!monitoring |
| Proxmox token secret | `YOUR_PROXMOX_TOKEN_SECRET` |
| Proxmox node | pve |
| Telegram bot token | `YOUR_TELEGRAM_BOT_TOKEN` |
| Telegram chat ID | `YOUR_TELEGRAM_CHAT_ID` |
| ntfy topic | `YOUR_NTFY_TOPIC` |
| AWS access key | see ~/.aws/credentials |

---

## File Structure

```
/opt/homelab-agent/
├── action_executor.py          # Approved remediation actions with snapshot safety
├── alert_monitor.py            # Threshold checks, state-aware alerts, stopped container detection
├── apprise_notifier.py         # Multi-channel notifications (Telegram + ntfy)
├── bedrock_analyzer.py         # SQLite → Bedrock → SQLite
├── metrics_api.py              # Flask API serving live metrics
├── metrics_collector.py        # Proxmox API → SQLite every 15 min
├── portfolio_doc_generator.py  # Generates public portfolio pages
├── push-page.sh                # Pushes private/public pages to CT901 and VPS
├── telegram_bot.py             # Bot, inline buttons, callback handler
├── test_apprise.py             # Apprise channel test script
├── config.yaml                 # All configuration
├── venv/                       # Python virtualenv
├── data/
│   └── metrics.db              # SQLite database
└── logs/
    ├── agent.log
    ├── analysis.log
    ├── api.log
    ├── metrics.log
    └── telegram.log
```

---

## Installed Packages

| Package | Version |
|---------|---------|
| apprise | 1.9.7 |
| boto3 | 1.42.55 |
| botocore | 1.42.55 |
| python-telegram-bot | 22.6 |
| requests | 2.32.5 |
| requests-oauthlib | 2.0.0 |

Standard library (no install needed): `sqlite3`, `json`, `yaml` (PyYAML), `logging`, `subprocess`

---

## Running Services

| Service | Type | Status | Description |
|---------|------|--------|-------------|
| homelab-api | simple | Running | Metrics API (always-on) |
| homelab-telegram | simple | Running | Telegram bot (always-on) |
| homelab-metrics | oneshot | n8n triggered | Metrics collector |
| homelab-bedrock | oneshot | n8n triggered | Bedrock analyzer |
| homelab-alert | oneshot | n8n triggered | Alert monitor |

---

## Systemd Service Files

### homelab-telegram.service

```ini
[Unit]
Description=Homelab Telegram Bot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/homelab-agent/venv/bin/python3 /opt/homelab-agent/telegram_bot.py
WorkingDirectory=/opt/homelab-agent
Restart=always
RestartSec=30
StandardOutput=append:/opt/homelab-agent/logs/telegram.log
StandardError=append:/opt/homelab-agent/logs/telegram.log

[Install]
WantedBy=multi-user.target
```

### homelab-api.service

```ini
[Unit]
Description=Homelab Metrics API
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/homelab-agent
ExecStart=/opt/homelab-agent/venv/bin/python3 /opt/homelab-agent/metrics_api.py
Restart=always
RestartSec=10
StandardOutput=append:/opt/homelab-agent/logs/api.log
StandardError=append:/opt/homelab-agent/logs/api.log

[Install]
WantedBy=multi-user.target
```

### homelab-metrics.service (oneshot — triggered by n8n)

```ini
[Unit]
Description=Homelab Metrics Collector
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/opt/homelab-agent/venv/bin/python3 /opt/homelab-agent/metrics_collector.py
WorkingDirectory=/opt/homelab-agent
StandardOutput=append:/opt/homelab-agent/logs/metrics.log
StandardError=append:/opt/homelab-agent/logs/metrics.log

[Install]
WantedBy=multi-user.target
```

### homelab-bedrock.service (oneshot — triggered by n8n)

```ini
[Unit]
Description=Homelab AI Analysis (Bedrock)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/opt/homelab-agent/venv/bin/python3 /opt/homelab-agent/bedrock_analyzer.py --tier routine
WorkingDirectory=/opt/homelab-agent
StandardOutput=append:/opt/homelab-agent/logs/analysis.log
StandardError=append:/opt/homelab-agent/logs/analysis.log

[Install]
WantedBy=multi-user.target
```

### homelab-alert.service (oneshot — triggered by n8n)

```ini
[Unit]
Description=Homelab Alert Monitor
After=network.target

[Service]
Type=oneshot
User=root
WorkingDirectory=/opt/homelab-agent
ExecStart=/opt/homelab-agent/venv/bin/python3 /opt/homelab-agent/alert_monitor.py
StandardOutput=journal
StandardError=journal
```

---

## SSH Keys

CT900 requires passwordless SSH access to the following hosts:

```
root@YOUR_PROXMOX_IP     # Proxmox host — pct and pvesh commands
root@YOUR_CT120_IP       # Docker LXC — docker-status helper
root@YOUR_CT901_IP       # CT901 private docs — push workflow
root@YOUR_VPS_IP         # Romanian VPS — public site deploy
```

Generate and distribute:

```bash
ssh-keygen -t ed25519 -f /root/.ssh/id_ed25519 -N ""
ssh-copy-id root@YOUR_PROXMOX_IP
ssh-copy-id root@YOUR_CT120_IP
ssh-copy-id root@YOUR_CT901_IP
ssh-copy-id root@YOUR_VPS_IP
```

---

## n8n Workflows (CT116)

| Workflow | Schedule | Command |
|----------|----------|---------|
| Metrics Collector | Every 15 min | `systemctl start homelab-metrics` |
| Bedrock Analyzer | Every 6 hours | `systemctl start homelab-bedrock` |
| Alert Monitor | Every 15 min | `systemctl start homelab-alert` |

n8n connects to CT900 via SSH and triggers the oneshot systemd services. Pause workflows in n8n UI before doing maintenance.

---

## config.yaml Template

```yaml
proxmox:
  host: "YOUR_PROXMOX_IP"
  port: 8006
  node_name: "pve"
  token_id: "agent@pve!monitoring"
  token_secret: "YOUR_PROXMOX_TOKEN_SECRET"
  verify_ssl: false

aws:
  region: "us-east-1"
  models:
    routine: "us.amazon.nova-micro-v1:0"
    daily: "us.amazon.nova-lite-v1:0"
    alert: "us.amazon.nova-pro-v1:0"
  max_tokens: 1000
  temperature: 0.1

telegram:
  bot_token: "YOUR_TELEGRAM_BOT_TOKEN"
  chat_id: "YOUR_TELEGRAM_CHAT_ID"
  daily_report_hour: 8
  daily_report_minute: 0

monitoring:
  collection_interval_minutes: 15
  analysis_interval_hours: 6
  thresholds:
    cpu_warning_percent: 70
    cpu_critical_percent: 90
    memory_warning_percent: 80
    memory_critical_percent: 95
    disk_warning_percent: 75
    disk_critical_percent: 90
  container_overrides:
    cloudflared:
      disk_warning_percent: 88
      disk_critical_percent: 95
    radarr:
      disk_warning_percent: 85
      disk_critical_percent: 95
  restart_ignore:
    - ollama
    - reticulum
    - proxmox-backup-server
    - jellyfin
    - osint-lab
    - jellyseerr
    - homarr
    - AI-Lab

database:
  path: "/opt/homelab-agent/data/metrics.db"
  retention_days: 30

logging:
  level: "INFO"
  file: "/opt/homelab-agent/logs/agent.log"
  max_size_mb: 10
  backup_count: 3

apprise:
  enabled: true
  telegram:
    enabled: true
    bot_token: "YOUR_TELEGRAM_BOT_TOKEN"
    chat_id: "YOUR_TELEGRAM_CHAT_ID"
  ntfy:
    enabled: true
    url: "https://ntfy.sh"
    topic: "YOUR_NTFY_TOPIC"
```

---

## Proxmox API Token Setup

```bash
pveum user add agent@pve
pveum role add MonitoringRole -privs "VM.Audit Datastore.Audit Sys.Audit"
pveum role add AgentOperator -privs "VM.PowerMgmt VM.Snapshot VM.Console"
pveum aclmod / -user agent@pve -role MonitoringRole
pveum aclmod /vms -user agent@pve -role AgentOperator
pveum user token add agent@pve monitoring --privsep=0
# Copy token secret — only shown once
```

> Note: ACL assignments do NOT carry over when a token is regenerated. Reapply both `pveum aclmod` commands after every token rotation. Use single quotes around `'agent@pve!monitoring'` in bash to avoid `!` history expansion.

---

## Rebuild CT900 from Scratch

### 1. Create container

```bash
pct create 900 local:vztmpl/debian-12-standard_12.12-1_amd64.tar.zst \
  --hostname homelab-agent \
  --memory 1024 \
  --cores 2 \
  --rootfs tank-storage:8 \
  --net0 name=eth0,bridge=vmbr0,ip=YOUR_CT900_IP/24,gw=YOUR_GATEWAY_IP \
  --unprivileged 1 \
  --features nesting=1 \
  --onboot 1
pct start 900
```

### 2. Enable SSH

```bash
pct exec 900 -- sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
pct exec 900 -- sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
pct exec 900 -- systemctl restart ssh
pct exec 900 -- passwd root
```

### 3. Install dependencies

```bash
pct enter 900
apt-get update && apt-get install -y python3 python3-pip python3-venv \
  git sqlite3 openssh-client awscli curl

mkdir -p /opt/homelab-agent/data /opt/homelab-agent/logs
python3 -m venv /opt/homelab-agent/venv
source /opt/homelab-agent/venv/bin/activate
pip install requests urllib3 pyyaml boto3 \
  "python-telegram-bot==22.6" \
  "apprise==1.9.7"
```

### 4. Configure AWS credentials

```bash
aws configure
# Access key: YOUR_AWS_ACCESS_KEY
# Secret key: YOUR_AWS_SECRET_KEY
# Region: eu-west-1
# Output: json
```

### 5. Generate SSH key and authorise hosts

```bash
ssh-keygen -t ed25519 -f /root/.ssh/id_ed25519 -N ""
ssh-copy-id root@YOUR_PROXMOX_IP
ssh-copy-id root@YOUR_CT120_IP
ssh-copy-id root@YOUR_CT901_IP
ssh-copy-id root@YOUR_VPS_IP
```

### 6. Upload scripts and config

Upload via WinSCP to `/opt/homelab-agent/`:
- All `.py` files
- `config.yaml` (with real credentials filled in)
- `push-page.sh`

### 7. Install systemd services

Upload or recreate all five service files in `/etc/systemd/system/` then:

```bash
systemctl daemon-reload
systemctl enable homelab-telegram homelab-api
systemctl start homelab-telegram homelab-api
```

### 8. Test

```bash
cd /opt/homelab-agent && source venv/bin/activate
python3 metrics_collector.py
python3 bedrock_analyzer.py
python3 alert_monitor.py
systemctl status homelab-telegram homelab-api
```

### 9. Re-enable n8n workflows

Log into n8n and re-activate the three workflows.

---

## Database Schema & Useful Queries

```sql
-- Active alerts
SELECT container_name, alert_type, severity, first_seen
FROM alert_state WHERE resolved = 0;

-- Clear orphaned stopped-container alerts
DELETE FROM alert_state WHERE alert_type = 'container_stopped';

-- Recent Bedrock analyses
SELECT created_at, model_id, input_tokens, output_tokens
FROM analyses ORDER BY id DESC LIMIT 5;

-- Action history
SELECT container_name, action_type, status, proposed_at, result
FROM proposed_actions ORDER BY id DESC LIMIT 10;

-- Monthly cost summary
SELECT model_id, SUM(input_tokens), SUM(output_tokens), COUNT(*)
FROM analyses
WHERE created_at >= date('now', 'start of month')
GROUP BY model_id;
```

---

## Service Management

```bash
# Status
systemctl status homelab-telegram homelab-api

# Restart
systemctl restart homelab-telegram
systemctl restart homelab-api

# Run manually
cd /opt/homelab-agent && source venv/bin/activate
python3 metrics_collector.py
python3 bedrock_analyzer.py
python3 alert_monitor.py

# Logs
journalctl -u homelab-telegram -f
tail -f /opt/homelab-agent/logs/metrics.log
tail -f /opt/homelab-agent/logs/analysis.log
```

---

## Lessons Learned

- **AWS region mismatch** — config.yaml says `us-east-1`, AWS CLI configured to `eu-west-1`. Bedrock model IDs use `us.` prefix. Confirm which region is actually serving requests.
- **Telegram flood control** — creating a new Bot() instance per container in a loop triggers flood control instantly. Use a single shared Bot instance per cycle.
- **Bedrock code fences** — Nova models occasionally wrap output in markdown fences. Strip these before using the response.
- **Orphaned alert_state records** — if alert_monitor.py crashes mid-cycle, partial records cause re-alerts. Fix: `DELETE FROM alert_state WHERE alert_type = 'container_stopped'`
- **Pre-summarisation saves ~90% cost** — always summarise metrics before sending to Bedrock.
- **ACL rotation** — after regenerating Proxmox token, always reapply ACLs. Use single quotes in bash for token IDs containing `!`.
- **n8n triggers oneshot services** — metrics, bedrock, and alert services are Type=oneshot. Only telegram and api run continuously. Pause n8n workflows before maintenance.
