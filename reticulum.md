# Reticulum Mesh Network (CT108)

> **Public documentation** — real IPs and addresses have been replaced with placeholders. See private docs for live values.

## Overview

A resilient backup communication path to the homelab that operates independently of Cloudflare and standard IP routing. Built after the November 2025 Cloudflare global outage exposed single-point-of-failure risk. Uses Reticulum over I2P for location-independent, censorship-resistant connectivity from a Windows client to Proxmox infrastructure.

**Why it exists:** When Cloudflare goes down, all DNS entries stop resolving. This provides a completely independent path in — no DNS, no fixed IPs, no Cloudflare dependency.

---

## Infrastructure

| Component | Value |
|-----------|-------|
| Container | CT108 |
| Container IP | `YOUR_CT108_IP` |
| TCP port | 4965 |
| I2P address | `YOUR_I2P_B32_ADDRESS` |
| RNS version | 1.0.4 |
| rnsh identity | `~/.reticulum/storage/identities/rnsh.default` |
| Status | Intentionally stopped — start when needed |

> ⚠️ **I2P address will change if container is rebuilt** — i2pd generates a new keypair on fresh install. Update Windows client config after any rebuild.

---

## Running Services

| Service | Status | Description |
|---------|--------|-------------|
| i2pd | Running | I2P router daemon |
| reticulum | Running | Reticulum network stack (rnsd) |
| ssh | Running | OpenBSD SSH server |
| cron | Running | Scheduled tasks |

Note: `rnsh` depends on `reticulum.service` being fully up. May need manual start or a longer `RestartSec`.

---

## How It Works

```
Windows Client
    │
    ├─ Path 1: TCP via WireGuard → YOUR_CT108_IP:4965 (fast, ~100Mbps)
    │
    └─ Path 2: I2P → YOUR_I2P_B32_ADDRESS (slow ~100KB/s, works anywhere)
                            │
                        CT108 Gateway
                            │
                    Reticulum mesh
                            │
                   rnsh → shell access
```

Reticulum automatically tries TCP first (when WireGuard is active) and falls back to I2P. No manual switching needed.

---

## Server Config (`~/.reticulum/config`)

```ini
[reticulum]
  enable_transport = True
  share_instance = Yes
  instance_name = default
  loglevel = 4

[interfaces]

  [[Default Interface]]
    type = AutoInterface
    enabled = Yes

  [[I2P Gateway]]
    type = I2PInterface
    enabled = yes
    connectable = yes

  [[TCP Server Interface]]
    type = TCPServerInterface
    enabled = Yes
    mode = gateway
    listen_ip = 0.0.0.0
    listen_port = 4965
```

---

## Systemd Service Files

### reticulum.service

```ini
[Unit]
Description=Reticulum Network Stack Daemon
After=multi-user.target

[Service]
Type=simple
Restart=always
RestartSec=3
User=root
ExecStart=/usr/local/bin/rnsd --service

[Install]
WantedBy=multi-user.target
```

### rnsh.service

```ini
[Unit]
Description=Reticulum Network Shell Listener
After=reticulum.service
Requires=reticulum.service

[Service]
Type=simple
Restart=always
RestartSec=3
User=root
ExecStart=/usr/local/bin/rnsh -l -n -b 0

[Install]
WantedBy=multi-user.target
```

Note: `-l` = listen, `-n` = no auth required, `-b 0` = allow all incoming connections.

---

## Windows Client Config

File: `C:\Users\<username>\.reticulum\config`

```ini
[reticulum]
  enable_transport = No
  share_instance = Yes
  shared_instance_port = 37428

[interfaces]

  [[I2P Home]]
    type = I2PInterface
    enabled = yes
    peers = YOUR_I2P_B32_ADDRESS

  [[Home via WireGuard]]
    type = TCPClientInterface
    enabled = yes
    target_host = YOUR_CT108_IP
    target_port = 4965
```

---

## Rebuild CT108 from Scratch

### 1. Create container

```bash
pct create 108 local:vztmpl/debian-12-standard_12.12-1_amd64.tar.zst \
  --hostname reticulum \
  --memory 512 \
  --cores 1 \
  --rootfs tank-storage:4 \
  --net0 name=eth0,bridge=vmbr0,ip=YOUR_CT108_IP/24,gw=YOUR_GATEWAY_IP \
  --unprivileged 1 \
  --onboot 0
pct start 108
```

### 2. Install dependencies

```bash
pct enter 108
apt-get update && apt-get install -y python3 python3-pip i2pd
pip3 install rns rnsh --break-system-packages
python3 -c "import RNS; print(RNS.__version__)"  # Should show 1.0.4 or higher
```

### 3. Configure Reticulum

```bash
mkdir -p ~/.reticulum
nano ~/.reticulum/config
# Paste the server config from above
```

### 4. Create rnsh identity

```bash
rnsh -l -n -b 0 &
sleep 5
kill %1
ls ~/.reticulum/storage/identities/   # Should show rnsh.default
```

### 5. Create systemd services

```bash
cat > /etc/systemd/system/reticulum.service << 'EOF'
[Unit]
Description=Reticulum Network Stack Daemon
After=multi-user.target

[Service]
Type=simple
Restart=always
RestartSec=3
User=root
ExecStart=/usr/local/bin/rnsd --service

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/rnsh.service << 'EOF'
[Unit]
Description=Reticulum Network Shell Listener
After=reticulum.service
Requires=reticulum.service

[Service]
Type=simple
Restart=always
RestartSec=3
User=root
ExecStart=/usr/local/bin/rnsh -l -n -b 0

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable i2pd reticulum rnsh
```

### 6. Start in correct order

```bash
systemctl start i2pd
# WAIT 3-5 minutes for I2P tunnels to build before starting Reticulum
sleep 180
systemctl start reticulum
sleep 10
systemctl start rnsh
```

### 7. Get new I2P address after rebuild

```bash
curl http://127.0.0.1:7070 2>/dev/null | grep -i "b32\|address"
# Or:
journalctl -u i2pd | grep -i "b32\|tunnel\|address" | tail -20
```

Update `peers =` in the Windows client config with the new address.

### 8. Verify

```bash
rnstatus
systemctl status i2pd reticulum rnsh
```

---

## Service Management

```bash
# Status
systemctl status i2pd reticulum rnsh

# Start in correct order
systemctl start i2pd && sleep 180 && systemctl start reticulum rnsh

# Stop
systemctl stop rnsh reticulum i2pd

# Logs
journalctl -u reticulum -f
journalctl -u i2pd -f
journalctl -u rnsh -f

# Check interfaces
rnstatus
```

---

## Lessons Learned

- **i2pd must start before Reticulum** — if rnsd starts before I2P tunnels are established it cannot create the I2PInterface. Always start i2pd first and wait 3-5 minutes.
- **rnstatus returns nothing immediately** — rnsd takes time to fully initialise. Wait 30-60 seconds before running rnstatus.
- **rnsh identity is `rnsh.default`** — not `rnsh` as some documentation suggests.
- **rnsh flags matter** — the working command is `rnsh -l -n -b 0`. Without `-n` and `-b 0` connections are refused.
- **I2P address changes on rebuild** — fresh i2pd install = new keypair = new .b32.i2p address. Always update Windows client after rebuild.
- **Not for streaming** — I2P bandwidth is ~50-500 kbps. Use WireGuard for real throughput. Reticulum/I2P is emergency shell access only.
- **Container intentionally stopped** — in agent `restart_ignore` list. Start manually: `pct start 108`
