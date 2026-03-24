# Homelab-Playbooks

AI-powered infrastructure monitoring for a self-hosted Proxmox homelab. Monitors 25+ LXC containers using AWS Bedrock, delivers intelligent alerts, and provides a native Android management interface.

Full project write-up: [anthonyapierre.com/projects/homelab-ai-agent-project](https://anthonyapierre.com/projects/homelab-ai-agent-project/)

---

## What's in this repo

### Agent (`/`)

Python services running on CT900 (Debian LXC inside Proxmox). Collects metrics every 15 minutes, analyses them via AWS Bedrock with tiered Nova model routing, and delivers alerts via Firebase Cloud Messaging, ntfy, and Telegram.

| File | Purpose |
|---|---|
| `metrics_collector.py` | Proxmox + Docker metrics to SQLite |
| `metrics_api.py` | Flask REST API on port 5050 |
| `bedrock_analyzer.py` | SQLite to AWS Bedrock analysis |
| `alert_monitor.py` | Threshold checks and alert dispatch |
| `action_executor.py` | Approved remediation with snapshot safety |
| `baseline_computer.py` | Nightly 30-day baseline computation |
| `fcm_notifier.py` | Firebase Cloud Messaging push |
| `apprise_notifier.py` | Multi-channel notifications |
| `mount_watchdog.py` | NFS mount health and auto-recovery |
| `vps_monitor.py` | Romanian VPS health and home agent watchdog |
| `monthly_audit.py` | Nova Pro monthly deep audit |
| `config.yaml.template` | Sanitised config template - copy to config.yaml |

### Simulation engine (`/simulate/`)

Runs the full alert and action pipeline against hypothetical infrastructure states without touching anything live. Accepts JSON scenarios, natural language queries (converted via Bedrock Micro), or historical replays from the metrics database.

```bash
# Quick test
python3 simulate.py --scenario scenarios/sabnzbd_oom.json --no-ai

# Natural language query
python3 simulate.py --inline '{"CT110": {"mem_percent": 95}}' --no-ai

# With AI narrative (costs ~$0.000003)
python3 simulate.py --scenario scenarios/nas_power_loss.json
```

Included scenarios: SABnzbd OOM, VPS down, NAS power loss, cascade stress, monitoring gap, disk pressure.

The simulation endpoint is also available via the API at `POST /simulate` for use from the Android app.

### Android app (`/AndroidApp/`)

Native Kotlin/Jetpack Compose dashboard for the Samsung S21. Communicates with the agent API via Cloudflare tunnel.

Five screens: Dashboard (live container grid with start/stop/reboot controls), Costs (Bedrock spend breakdown), Services (launcher for 22 self-hosted apps), Ask/Simulate (natural language queries and scenario simulation), Alerts (history with ACK and snooze).

Push notifications via Firebase Cloud Messaging with inline action buttons.

Full write-up: [anthonyapierre.com/projects/android-app-portfolio](https://anthonyapierre.com/projects/android-app-portfolio/)

---

## Setup

### Prerequisites

- Proxmox VE host with LXC containers
- Python 3.11+ container for the agent (2 cores, 2GB RAM minimum)
- AWS account with Bedrock Nova model access enabled in `us-east-1`
- AWS CLI configured with a named profile
- Firebase project with Cloud Messaging enabled
- n8n instance for workflow scheduling (or systemd timers)
- Cloudflare tunnel for external API access

### Agent setup

```bash
# Clone and enter the repo
git clone https://github.com/mrapierre/Homelab-Playbooks
cd Homelab-Playbooks

# Create virtual environment
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Copy and edit config
cp config.yaml.template config.yaml
nano config.yaml
```

### Config

Copy `config.yaml.template` to `config.yaml` and fill in:

- Proxmox host IP and SSH details
- AWS profile name and region
- Firebase service account path and FCM token path
- Alert thresholds (defaults provided)
- Telegram bot token and chat ID
- ntfy topic URL
- VPS host and credentials

`config.yaml` and `firebase-service-account.json` are excluded from this repo via `.gitignore` and must never be committed.

### Android app setup

1. Open `/AndroidApp/` in Android Studio
2. Obtain `google-services.json` from your Firebase project console
3. Place it at `app/google-services.json` (excluded from git)
4. Update `BASE_URL` in `MainActivity.kt` to point to your agent API endpoint
5. Build and sideload the APK to your device

---

## Architecture

```
Proxmox Host (192.168.0.x)
+-- CT900 - Agent (metrics_collector, metrics_api, bedrock_analyzer,
|           alert_monitor, action_executor, baseline_computer,
|           apprise_notifier, fcm_notifier, mount_watchdog, vps_monitor)
+-- CT116 - n8n (workflow orchestration)
+-- CT120 - Docker host
+-- CT106 - Proxmox Backup Server

         Cloudflare Tunnel
               |
   metrics.your-domain.com
         /          \
  Android App    AWS Bedrock
               (us-east-1)

Romanian VPS - external watchdog + portfolio site
```

---

## Cost

Running 24/7 against 25+ containers:

| Model | Usage | Cost |
|---|---|---|
| Nova Micro | Routine checks every 6h | ~$0.00004/run |
| Nova Lite | Daily summaries | ~$0.0002/run |
| Nova Pro | Monthly audit | ~$0.02/run |
| **Total** | | **~$0.025/month** |

---

## Security notes

- Proxmox API token is read-only globally, write access scoped to `/vms` only
- AWS credentials use CLI profile, no hardcoded keys
- `config.yaml`, `firebase-service-account.json`, `google-services.json`, and `data/fcm_token.txt` are all excluded from git
- Cloudflare tunnel exposes the metrics API without opening firewall ports
- Consider adding Cloudflare Access to the tunnel endpoint if the API is sensitive

---

## Related

- Portfolio: [anthonyapierre.com](https://anthonyapierre.com)
- Android app page: [anthonyapierre.com/projects/android-app-portfolio](https://anthonyapierre.com/projects/android-app-portfolio/)
- Agent page: [anthonyapierre.com/projects/homelab-ai-agent-project](https://anthonyapierre.com/projects/homelab-ai-agent-project/)
