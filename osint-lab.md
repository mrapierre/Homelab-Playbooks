# OSINT Research Environment (CT200)

> **Public documentation** — real IPs and configs have been replaced with placeholders. See private docs for live values.

## Overview

An isolated research environment for open-source intelligence work. Kept network-separate from the main homelab to contain any risk from tools or sites accessed during investigations. Built on Ubuntu 22.04 with a non-root `osint` user, curated toolset, and three web interfaces (dashboard, SpiderFoot, nginx).

**Why isolated:** OSINT work involves visiting unknown sites, running aggressive scanners, and handling potentially hostile data. Keeping it in its own container prevents any compromise spreading to production services.

---

## Infrastructure

| Component | Value |
|-----------|-------|
| Container | CT200 |
| Container IP | `YOUR_CT200_IP` |
| OS | Ubuntu 22.04 |
| OSINT user | osint |
| Dashboard | http://YOUR_CT200_IP:3000 (Node.js) |
| SpiderFoot | http://127.0.0.1:5001 (localhost only) |
| Nginx | http://YOUR_CT200_IP:80 |
| Status | Intentionally stopped — start when needed |

---

## Running Services

| Service | Status | Description |
|---------|--------|-------------|
| nginx | Running | Reverse proxy / web server |
| osint-dashboard | Running | Node.js dashboard at port 3000 |
| spiderfoot | Running | SpiderFoot HX at 127.0.0.1:5001 |
| cron | Running | Scheduled tasks |
| postfix | Running | Mail transport (system) |

---

## Installed Tools

### CLI Tools (Go binaries — `~/go/bin/`)

| Tool | Purpose |
|------|---------|
| amass | Subdomain enumeration |
| subfinder | Fast passive subdomain discovery |
| httpx | HTTP probing and asset discovery |
| nuclei | Template-based vulnerability scanning |

### Python Tools (`~/.local/bin/`)

| Tool | Purpose |
|------|---------|
| sherlock | Username search across 300+ platforms |
| theHarvester | Email/domain reconnaissance |
| shodan | Shodan CLI |
| censys | Censys search CLI |
| playwright | Browser automation for scraping |
| tor-prompt | Tor integration |
| flask | Web framework (used by dashboard) |

### Git installs

| Tool | Location | Purpose |
|------|----------|---------|
| recon-ng | `~/bin/recon-ng` (wrapper) | Full recon framework |
| spiderfoot | `~/spiderfoot/` | OSINT automation platform |

---

## Systemd Service Files

### osint-dashboard.service

```ini
[Unit]
Description=OSINT Dashboard
After=network.target

[Service]
Type=simple
User=osint
WorkingDirectory=/home/osint/osint-dashboard
ExecStart=/usr/bin/node server.js
Restart=always

[Install]
WantedBy=multi-user.target
```

### spiderfoot.service

```ini
[Unit]
Description=SpiderFoot HX
After=network.target

[Service]
Type=simple
User=osint
WorkingDirectory=/home/osint/spiderfoot
ExecStart=/home/osint/spiderfoot/venv/bin/python /home/osint/spiderfoot/sf.py -l 127.0.0.1:5001
Restart=always

[Install]
WantedBy=multi-user.target
```

Note: SpiderFoot listens on **localhost only**. Access via SSH tunnel:

```bash
ssh -L 5001:127.0.0.1:5001 osint@YOUR_CT200_IP
# Then browse to http://localhost:5001
```

---

## PATH Configuration (`~/.bashrc`)

```bash
source ~/.osint-env 2>/dev/null || true
export PATH="$HOME/.local/bin:$HOME/go/bin:$HOME/bin:$PATH:/usr/local/go/bin"
```

Note: `source ~/.osint-env` was appended three times during setup — harmless but duplicated. On rebuild, add it only once.

---

## API Keys (`~/.osint-env`)

```bash
# HIBP_API_KEY=your_key_here
# SHODAN_API_KEY=your_key_here
# VIRUSTOTAL_API_KEY=your_key_here
# CENSYS_API_ID=your_key_here
# CENSYS_API_SECRET=your_key_here
```

---

## Rebuild CT200 from Scratch

### 1. Create container

```bash
pct create 200 local:vztmpl/ubuntu-22.04-standard_22.04-1_amd64.tar.zst \
  --hostname osint-lab \
  --memory 2048 \
  --cores 2 \
  --rootfs tank-storage:20 \
  --net0 name=eth0,bridge=vmbr0,ip=YOUR_CT200_IP/24,gw=YOUR_GATEWAY_IP \
  --unprivileged 1 \
  --features nesting=1 \
  --onboot 0
pct start 200
```

### 2. Base packages

```bash
pct enter 200
apt update && apt upgrade -y
apt install -y curl wget git python3 python3-pip python3-venv \
  build-essential sudo nginx cron postfix \
  software-properties-common apt-transport-https ca-certificates \
  gnupg lsb-release htop iotop nethogs
```

### 3. Install Go 1.21

```bash
wget https://go.dev/dl/go1.21.6.linux-amd64.tar.gz
tar -C /usr/local -xzf go1.21.6.linux-amd64.tar.gz
echo 'export PATH=$PATH:/usr/local/go/bin' >> /etc/profile
source /etc/profile
rm go1.21.6.linux-amd64.tar.gz
go version
```

### 4. Fix Node.js — CRITICAL, do this before dashboard

```bash
apt purge -y nodejs libnode-dev nodejs-doc npm
apt autoremove -y
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs
node -v   # Must show v20.x.x
```

### 5. Create osint user

```bash
useradd -m -s /bin/bash osint && passwd osint
usermod -aG sudo osint
```

### 6. Switch to osint user — configure PATH

```bash
su - osint

cat >> ~/.bashrc << 'EOF'
export PATH="$HOME/.local/bin:$HOME/go/bin:$HOME/bin:$PATH:/usr/local/go/bin"
source ~/.osint-env 2>/dev/null || true
EOF
source ~/.bashrc
```

### 7. Install Python tools

```bash
pip3 install --user sherlock-project theHarvester photon shodan censys playwright
```

### 8. Install Go tools

```bash
go install -v github.com/owasp-amass/amass/v4@latest
go install -v github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest
go install -v github.com/projectdiscovery/httpx/cmd/httpx@latest
go install -v github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest
```

### 9. Install recon-ng

```bash
cd ~
git clone https://github.com/lanmaster53/recon-ng.git
cd recon-ng && pip3 install --user -r REQUIREMENTS
mkdir -p ~/bin
cat > ~/bin/recon-ng << 'EOF'
#!/bin/bash
cd ~/recon-ng && python3 recon-ng.py "$@"
EOF
chmod +x ~/bin/recon-ng
```

### 10. Install SpiderFoot

```bash
cd ~
git clone https://github.com/smicallef/spiderfoot.git
cd spiderfoot
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
deactivate
```

### 11. Create systemd services

```bash
cat > /etc/systemd/system/spiderfoot.service << 'EOF'
[Unit]
Description=SpiderFoot HX
After=network.target

[Service]
Type=simple
User=osint
WorkingDirectory=/home/osint/spiderfoot
ExecStart=/home/osint/spiderfoot/venv/bin/python /home/osint/spiderfoot/sf.py -l 127.0.0.1:5001
Restart=always

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/osint-dashboard.service << 'EOF'
[Unit]
Description=OSINT Dashboard
After=network.target

[Service]
Type=simple
User=osint
WorkingDirectory=/home/osint/osint-dashboard
ExecStart=/usr/bin/node server.js
Restart=always

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable spiderfoot osint-dashboard nginx
systemctl start spiderfoot osint-dashboard nginx
```

### 12. API keys

```bash
cat > /home/osint/.osint-env << 'EOF'
# HIBP_API_KEY=
# SHODAN_API_KEY=
# VIRUSTOTAL_API_KEY=
# CENSYS_API_ID=
# CENSYS_API_SECRET=
EOF
chmod 600 /home/osint/.osint-env
chown osint:osint /home/osint/.osint-env
```

### 13. Verify

```bash
su - osint
~/verify-tools.sh
```

---

## Common Commands

```bash
# Username reconnaissance
sherlock <username>

# Subdomain discovery
subfinder -d example.com
amass enum -d example.com

# Chain subdomain → HTTP probe
subfinder -d example.com | httpx -title -status-code

# Email/domain harvesting
theHarvester -d example.com -b google,bing,crtsh -l 100

# Vulnerability scanning
nuclei -u https://example.com

# SpiderFoot (access via SSH tunnel)
ssh -L 5001:127.0.0.1:5001 osint@YOUR_CT200_IP
# Browse to http://localhost:5001
```

---

## Service Management

```bash
# Status
systemctl status osint-dashboard spiderfoot nginx

# Restart
systemctl restart osint-dashboard spiderfoot

# Logs
journalctl -u spiderfoot -f
journalctl -u osint-dashboard -f
```

---

## Lessons Learned

- **Node.js version is critical** — Ubuntu 22.04 default is too old. Purge and replace with NodeSource v20 BEFORE installing the dashboard. Silent npm failures otherwise.
- **Go PATH must come before system PATH** — `$HOME/go/bin` must appear before `/usr/bin` or system Go tools shadow the installed versions.
- **recon-ng pip install is broken upstream** — always use git clone + wrapper script.
- **SpiderFoot listens localhost only** — access via SSH tunnel, not direct browser. Intentional security.
- **`.osint-env` sourced 3x in .bashrc** — harmless but messy. On rebuild, add it only once.
- **SpiderFoot has its own venv** — do not install its dependencies into system Python or the osint user's pip. Use `~/spiderfoot/venv/` exclusively.
- **Container intentionally stopped** — in agent `restart_ignore` list. Start manually: `pct start 200`
