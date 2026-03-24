#!/usr/bin/env python3
"""
simulate.py — Homelab agent simulation engine
Runs hypothetical metric states through the alert and action pipeline
without touching the live database.

Usage:
  # Scenario from file
  python3 simulate.py --scenario scenario.json

  # Inline JSON scenario
  python3 simulate.py --inline '{"CT110": {"mem_percent": 95}}'

  # Replay a past incident from metrics.db
  python3 simulate.py --replay <metrics_row_id>

  # Replay with current thresholds (regression test)
  python3 simulate.py --replay <metrics_row_id> --current-thresholds

  # Skip Bedrock AI analysis (faster, free)
  python3 simulate.py --scenario scenario.json --no-ai

  # Output raw JSON (for scripting)
  python3 simulate.py --scenario scenario.json --json

Environment: Run on CT900 inside the homelab-agent venv.
"""

import argparse
import json
import os
import sqlite3
import sys
import textwrap
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

# ---------------------------------------------------------------------------
# Paths (same conventions as the rest of the agent)
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).parent
CONFIG_PATH = BASE_DIR / "config.yaml"
DB_PATH = BASE_DIR / "data" / "metrics.db"


# ---------------------------------------------------------------------------
# Config loading
# ---------------------------------------------------------------------------

def load_config() -> dict:
    with open(CONFIG_PATH) as f:
        return yaml.safe_load(f)


def get_thresholds(config: dict, container_name: str) -> dict:
    """Return effective thresholds for a container, applying any overrides."""
    base = config["monitoring"]["thresholds"]
    overrides = config.get("monitoring", {}).get("container_overrides", {})
    effective = {
        "cpu_warning_percent":    base.get("cpu_warning_percent", 70),
        "cpu_critical_percent":   base.get("cpu_critical_percent", 90),
        "memory_warning_percent": base.get("memory_warning_percent", 80),
        "memory_critical_percent":base.get("memory_critical_percent", 95),
        "disk_warning_percent":   base.get("disk_warning_percent", 75),
        "disk_critical_percent":  base.get("disk_critical_percent", 90),
    }
    if container_name in overrides:
        effective.update(overrides[container_name])
    return effective


# ---------------------------------------------------------------------------
# Scenario building
# ---------------------------------------------------------------------------

# A default baseline snapshot — represents a healthy homelab state.
# Override any field via the scenario's "overrides" dict.
DEFAULT_BASELINE: dict[str, Any] = {
    "containers": {
        "CT900": {"vmid": 900, "name": "homelab-agent", "status": "running",
                  "cpu_percent": 12.0, "mem_percent": 34.0, "disk_percent": 42.0},
        "CT116": {"vmid": 116, "name": "n8n",            "status": "running",
                  "cpu_percent": 5.0,  "mem_percent": 28.0, "disk_percent": 20.0},
        "CT120": {"vmid": 120, "name": "docker",          "status": "running",
                  "cpu_percent": 18.0, "mem_percent": 55.0, "disk_percent": 60.0},
        "CT106": {"vmid": 106, "name": "pbs",             "status": "running",
                  "cpu_percent": 3.0,  "mem_percent": 22.0, "disk_percent": 35.0},
        "CT110": {"vmid": 110, "name": "sabnzbd",         "status": "running",
                  "cpu_percent": 8.0,  "mem_percent": 50.0, "disk_percent": 55.0},
        "CT113": {"vmid": 113, "name": "cloudflared",     "status": "running",
                  "cpu_percent": 4.0,  "mem_percent": 15.0, "disk_percent": 12.0},
        "CT200": {"vmid": 200, "name": "osint-lab",       "status": "stopped",
                  "cpu_percent": 0.0,  "mem_percent": 0.0,  "disk_percent": 48.0},
    },
    "vps": {
        "host":         "94.156.152.232",
        "reachable":    True,
        "cpu_percent":  22.0,
        "mem_percent":  58.0,
        "disk_percent": 45.0,
    },
    "nfs_mounts": {
        "/mnt/pve/nas/Expanded": True,
        "/mnt/pve/nas/Media":    True,
        "/mnt/pve/nas/Work":     True,
        "/mnt/pve/nas/Workspace":True,
    },
    "proxmox_host": {
        "cpu_percent":  8.0,
        "mem_percent":  62.0,
        "disk_percent": 86.0,   # known-state: display artefact post ZFS migration
    },
}


def build_scenario(overrides: dict, base: dict | None = None) -> dict:
    """
    Merge overrides onto the baseline to produce a full scenario snapshot.

    Overrides format:
      {
        "CT110": {"mem_percent": 95, "status": "running"},
        "vps":   {"reachable": false},
        "nfs_mounts": {"/mnt/pve/nas/Media": false}
      }
    Container keys can be vmid strings ("CT110"), names ("sabnzbd"), or
    vmid integers (110) — all are normalised.
    """
    import copy
    scenario = copy.deepcopy(base or DEFAULT_BASELINE)

    for key, values in overrides.items():
        # Normalise container key → "CTXXX" form
        ct_key = _normalise_container_key(key, scenario)
        if ct_key and ct_key in scenario["containers"]:
            scenario["containers"][ct_key].update(values)
        elif key == "vps":
            scenario["vps"].update(values)
        elif key == "nfs_mounts":
            scenario["nfs_mounts"].update(values)
        elif key == "proxmox_host":
            scenario["proxmox_host"].update(values)
        else:
            # Unknown key — add as a new container entry if it looks like one
            print(f"[warn] Unknown override key '{key}' — skipped.", file=sys.stderr)

    return scenario


def _normalise_container_key(key: str, scenario: dict) -> str | None:
    """Return the 'CTXXX' key matching the given identifier, or None."""
    key_str = str(key).upper()
    # Direct match
    if key_str in scenario["containers"]:
        return key_str
    # Try adding CT prefix (e.g. "110" → "CT110")
    ct_key = f"CT{key_str.lstrip('CT')}"
    if ct_key in scenario["containers"]:
        return ct_key
    # Try name match
    for k, v in scenario["containers"].items():
        if v.get("name", "").lower() == key.lower():
            return k
    return None


# ---------------------------------------------------------------------------
# Replay from metrics.db
# ---------------------------------------------------------------------------

def load_replay_snapshot(row_id: int) -> dict:
    """Pull a historical metrics snapshot from metrics.db by row id."""
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database not found at {DB_PATH}")
    conn = sqlite3.connect(DB_PATH)
    try:
        row = conn.execute(
            "SELECT metrics_json, timestamp FROM metrics WHERE id = ?", (row_id,)
        ).fetchone()
        if not row:
            raise ValueError(f"No metrics row with id={row_id}")
        raw = json.loads(row[0])
        ts = row[1]
        print(f"[replay] Loaded snapshot from {ts} (row id={row_id})")
        return raw, ts
    finally:
        conn.close()


def metrics_row_to_scenario(raw_metrics: dict) -> dict:
    """
    Convert a raw metrics_json blob (as stored by metrics_collector.py)
    into a scenario dict compatible with this simulator.
    """
    import copy
    scenario = copy.deepcopy(DEFAULT_BASELINE)

    containers = raw_metrics.get("containers", {})
    for vmid_str, data in containers.items():
        ct_key = f"CT{vmid_str}"
        if ct_key in scenario["containers"]:
            scenario["containers"][ct_key].update({
                "status":       data.get("status", "unknown"),
                "cpu_percent":  data.get("cpu", 0.0),
                "mem_percent":  data.get("mem", 0.0),
                "disk_percent": data.get("disk", 0.0),
            })
        else:
            # Container not in baseline — add it dynamically
            scenario["containers"][ct_key] = {
                "vmid":         int(vmid_str),
                "name":         data.get("name", ct_key),
                "status":       data.get("status", "unknown"),
                "cpu_percent":  data.get("cpu", 0.0),
                "mem_percent":  data.get("mem", 0.0),
                "disk_percent": data.get("disk", 0.0),
            }

    # VPS data if present
    if "vps" in raw_metrics:
        vps = raw_metrics["vps"]
        scenario["vps"].update({
            "reachable":    vps.get("reachable", True),
            "cpu_percent":  vps.get("cpu", 0.0),
            "mem_percent":  vps.get("mem", 0.0),
            "disk_percent": vps.get("disk", 0.0),
        })

    return scenario


# ---------------------------------------------------------------------------
# Alert evaluation
# ---------------------------------------------------------------------------

ALERT_TYPES = ["cpu", "memory", "disk", "status"]

# Known-state suppression — mirrors the known_states list in config.yaml.
# Simulation honours these to avoid false positives in replays.
KNOWN_STATE_SUPPRESSIONS = {
    "proxmox_host_disk": "Proxmox local-lvm disk shows ~87% in dashboard — "
                         "display artefact post ZFS migration, actual ~8%. Not a concern.",
}


def evaluate_alerts(scenario: dict, config: dict) -> list[dict]:
    """
    Run threshold evaluation across all containers and infrastructure.
    Returns a list of alert dicts — same structure alert_monitor.py would emit.
    """
    alerts = []
    known_states = {ks["metric"]: ks["reason"]
                    for ks in config.get("known_states", [])}

    # Container alerts
    for ct_key, ct in scenario["containers"].items():
        name = ct.get("name", ct_key)
        status = ct.get("status", "unknown")
        thresholds = get_thresholds(config, name)

        # Status check — stopped containers that were previously running
        if status == "stopped":
            alerts.append({
                "container": name,
                "vmid":      ct.get("vmid"),
                "type":      "status",
                "severity":  "warning",
                "value":     "stopped",
                "threshold": "running",
                "message":   f"{name} ({ct_key}) is stopped.",
            })
            continue  # No resource metrics for stopped containers

        # CPU
        cpu = ct.get("cpu_percent", 0.0)
        if cpu >= thresholds["cpu_critical_percent"]:
            alerts.append(_make_alert(name, ct_key, ct, "cpu", "critical",
                                      cpu, thresholds["cpu_critical_percent"], "cpu_percent"))
        elif cpu >= thresholds["cpu_warning_percent"]:
            alerts.append(_make_alert(name, ct_key, ct, "cpu", "warning",
                                      cpu, thresholds["cpu_warning_percent"], "cpu_percent"))

        # Memory
        mem = ct.get("mem_percent", 0.0)
        if mem >= thresholds["memory_critical_percent"]:
            alerts.append(_make_alert(name, ct_key, ct, "memory", "critical",
                                      mem, thresholds["memory_critical_percent"], "mem_percent"))
        elif mem >= thresholds["memory_warning_percent"]:
            alerts.append(_make_alert(name, ct_key, ct, "memory", "warning",
                                      mem, thresholds["memory_warning_percent"], "mem_percent"))

        # Disk
        disk = ct.get("disk_percent", 0.0)
        if disk >= thresholds["disk_critical_percent"]:
            alerts.append(_make_alert(name, ct_key, ct, "disk", "critical",
                                      disk, thresholds["disk_critical_percent"], "disk_percent"))
        elif disk >= thresholds["disk_warning_percent"]:
            alerts.append(_make_alert(name, ct_key, ct, "disk", "warning",
                                      disk, thresholds["disk_warning_percent"], "disk_percent"))

    # VPS alerts
    vps = scenario.get("vps", {})
    if not vps.get("reachable", True):
        alerts.append({
            "container": "vps",
            "vmid": None,
            "type": "status",
            "severity": "critical",
            "value": "unreachable",
            "threshold": "reachable",
            "message": f"VPS {vps.get('host', 'unknown')} is unreachable.",
        })
    else:
        vps_thresholds = config.get("vps_monitor", {})
        for metric, field, warn_key, crit_key in [
            ("memory", "mem_percent", "memory_warning_percent", "memory_critical_percent"),
            ("disk",   "disk_percent","disk_warning_percent",   "disk_critical_percent"),
        ]:
            val = vps.get(field, 0.0)
            crit = vps_thresholds.get(crit_key, 90)
            warn = vps_thresholds.get(warn_key, 80)
            if val >= crit:
                alerts.append({"container": "vps", "vmid": None, "type": metric,
                                "severity": "critical", "value": val, "threshold": crit,
                                "message": f"VPS {metric} at {val:.1f}% (critical ≥{crit}%)"})
            elif val >= warn:
                alerts.append({"container": "vps", "vmid": None, "type": metric,
                                "severity": "warning", "value": val, "threshold": warn,
                                "message": f"VPS {metric} at {val:.1f}% (warning ≥{warn}%)"})

    # NFS mount alerts
    for mount, is_mounted in scenario.get("nfs_mounts", {}).items():
        if not is_mounted:
            alerts.append({
                "container": "nfs",
                "vmid": None,
                "type": "mount",
                "severity": "critical",
                "value": "unmounted",
                "threshold": "mounted",
                "message": f"NFS mount {mount} is not mounted.",
            })

    # Proxmox host disk — apply known-state suppression
    host_disk = scenario.get("proxmox_host", {}).get("disk_percent", 0.0)
    host_disk_suppressed = any(
        "local-lvm" in ks.get("metric", "") or "proxmox" in ks.get("metric", "").lower()
        for ks in config.get("known_states", [])
    )
    if host_disk >= 90 and not host_disk_suppressed:
        alerts.append({
            "container": "proxmox-host",
            "vmid": None,
            "type": "disk",
            "severity": "critical",
            "value": host_disk,
            "threshold": 90,
            "message": f"Proxmox host disk at {host_disk:.1f}%",
        })

    return alerts


def _make_alert(name, ct_key, ct, alert_type, severity, value, threshold, field) -> dict:
    label = {"cpu": "CPU", "memory": "memory", "disk": "disk"}.get(alert_type, alert_type)
    return {
        "container": name,
        "vmid":      ct.get("vmid"),
        "type":      alert_type,
        "severity":  severity,
        "value":     round(value, 1),
        "threshold": threshold,
        "message":   f"{name} ({ct_key}) {label} at {value:.1f}% "
                     f"({'critical' if severity == 'critical' else 'warning'} ≥{threshold}%)",
    }


# ---------------------------------------------------------------------------
# Action prediction
# ---------------------------------------------------------------------------

ACTION_RULES = [
    {
        "condition": lambda a: a["type"] == "memory" and a["severity"] == "critical",
        "action":    "restart_container",
        "reason_tpl": "{container} memory critical ({value}%) — restart to recover.",
        "gate":      "circuit_breaker",
    },
    {
        "condition": lambda a: a["type"] == "status" and a["value"] == "stopped"
                               and a["container"] not in {"osint-lab", "ollama", "reticulum"},
        "action":    "start_container",
        "reason_tpl": "{container} is stopped unexpectedly — start it.",
        "gate":      "approval",
    },
    {
        "condition": lambda a: a["type"] == "mount" and a["severity"] == "critical",
        "action":    "remount_nfs",
        "reason_tpl": "NFS mount down — attempt remount.",
        "gate":      "auto",
    },
    {
        "condition": lambda a: a["type"] == "disk" and a["severity"] == "critical"
                               and a["container"] not in {"proxmox-host"},
        "action":    "notify_disk_critical",
        "reason_tpl": "{container} disk critical ({value}%) — manual cleanup required.",
        "gate":      "notify_only",
    },
]


def predict_actions(alerts: list[dict]) -> list[dict]:
    """
    For each alert, determine whether action_executor.py would propose an action,
    and if so what kind, what gate it would hit, and what it would snapshot.
    """
    proposed = []
    for alert in alerts:
        for rule in ACTION_RULES:
            if rule["condition"](alert):
                reason = rule["reason_tpl"].format(**alert)
                action = {
                    "container": alert["container"],
                    "vmid":      alert["vmid"],
                    "action":    rule["action"],
                    "reason":    reason,
                    "gate":      rule["gate"],
                    "would_snapshot": rule["gate"] in {"circuit_breaker", "approval"},
                    "triggered_by": alert,
                }
                proposed.append(action)
                break  # One action per alert

    return proposed


# ---------------------------------------------------------------------------
# Bedrock analysis (optional)
# ---------------------------------------------------------------------------

def call_bedrock(scenario: dict, alerts: list[dict], actions: list[dict], config: dict) -> str:
    """
    Send the simulated state to Bedrock Nova Lite for an AI narrative.
    Uses the same model routing as bedrock_analyzer.py.
    """
    import boto3

    alert_summary = "\n".join(f"  - [{a['severity'].upper()}] {a['message']}" for a in alerts) \
                    or "  - No alerts"
    action_summary = "\n".join(
        f"  - {a['action']} on {a['container']} (gate: {a['gate']})" for a in actions
    ) or "  - No actions proposed"

    stopped = [k for k, v in scenario["containers"].items() if v.get("status") == "stopped"]
    running_summary = ", ".join(
        f"{v['name']} CPU={v['cpu_percent']}% MEM={v['mem_percent']}% DISK={v['disk_percent']}%"
        for k, v in scenario["containers"].items() if v.get("status") == "running"
    )

    prompt = f"""You are the homelab AI agent analyst. A simulation has been run against the following hypothetical infrastructure state.

SIMULATED CONTAINER STATES:
Running: {running_summary}
Stopped: {', '.join(stopped) if stopped else 'none'}

VPS: reachable={scenario['vps']['reachable']}, mem={scenario['vps']['mem_percent']}%, disk={scenario['vps']['disk_percent']}%

ALERTS THAT WOULD FIRE:
{alert_summary}

ACTIONS THAT WOULD BE PROPOSED:
{action_summary}

Provide a concise analysis covering:
1. The most critical concern in this scenario and why
2. Whether the proposed actions are appropriate or if anything is missing
3. Any cascading risks (e.g. if CT110 OOMs, what else is affected?)
4. A single recommended next step for the operator

Be direct and specific. Keep response under 250 words."""

    aws_profile = config.get("aws", {}).get("profile", "homelab")
    session = boto3.Session(profile_name=aws_profile)
    client = session.client("bedrock-runtime", region_name="us-east-1")

    body = {
        "messages": [{"role": "user", "content": [{"text": prompt}]}],
        "inferenceConfig": {"maxTokens": 400, "temperature": 0.3},
    }

    response = client.invoke_model(
        modelId="us.amazon.nova-lite-v1:0",
        body=json.dumps(body),
        contentType="application/json",
        accept="application/json",
    )
    result = json.loads(response["body"].read())
    return result["output"]["message"]["content"][0]["text"]


# ---------------------------------------------------------------------------
# Report generation
# ---------------------------------------------------------------------------

def build_report(scenario: dict, alerts: list[dict], actions: list[dict],
                 ai_narrative: str | None, meta: dict) -> dict:
    critical = [a for a in alerts if a["severity"] == "critical"]
    warnings  = [a for a in alerts if a["severity"] == "warning"]

    return {
        "simulation": {
            "timestamp":         datetime.now(timezone.utc).isoformat(),
            "scenario_source":   meta.get("source", "unknown"),
            "replay_row_id":     meta.get("replay_row_id"),
            "replay_timestamp":  meta.get("replay_timestamp"),
            "ai_analysis":       meta.get("ai_enabled", True),
        },
        "summary": {
            "containers_total":   len(scenario["containers"]),
            "containers_running": sum(1 for v in scenario["containers"].values()
                                     if v.get("status") == "running"),
            "containers_stopped": sum(1 for v in scenario["containers"].values()
                                     if v.get("status") == "stopped"),
            "alerts_critical":    len(critical),
            "alerts_warning":     len(warnings),
            "actions_proposed":   len(actions),
        },
        "alerts":  alerts,
        "actions": actions,
        "ai_narrative": ai_narrative,
    }


def print_report(report: dict) -> None:
    """Pretty-print the simulation report to stdout."""
    sim = report["simulation"]
    summ = report["summary"]
    alerts = report["alerts"]
    actions = report["actions"]
    ai = report.get("ai_narrative")

    W = "\033[93m"   # yellow
    R = "\033[91m"   # red
    G = "\033[92m"   # green
    B = "\033[94m"   # blue
    DIM = "\033[2m"
    RESET = "\033[0m"
    BOLD = "\033[1m"

    print()
    print(f"{BOLD}{'─'*62}{RESET}")
    print(f"{BOLD}  HOMELAB SIMULATION REPORT{RESET}")
    print(f"  {DIM}{sim['timestamp']}{RESET}")
    if sim.get("replay_row_id"):
        print(f"  {DIM}Replay of metrics row {sim['replay_row_id']} "
              f"({sim.get('replay_timestamp', 'unknown')}){RESET}")
    else:
        print(f"  {DIM}Source: {sim['scenario_source']}{RESET}")
    print(f"{'─'*62}")

    # Summary
    print(f"\n{BOLD}  Summary{RESET}")
    run_col = G if summ["containers_stopped"] == 0 else W
    print(f"  Containers   {run_col}{summ['containers_running']} running{RESET}, "
          f"{summ['containers_stopped']} stopped  "
          f"(total {summ['containers_total']})")
    crit_col = R if summ["alerts_critical"] > 0 else G
    warn_col = W if summ["alerts_warning"] > 0 else G
    print(f"  Alerts       {crit_col}{summ['alerts_critical']} critical{RESET}, "
          f"{warn_col}{summ['alerts_warning']} warning{RESET}")
    act_col = B if summ["actions_proposed"] > 0 else DIM
    print(f"  Actions      {act_col}{summ['actions_proposed']} proposed{RESET}")

    # Alerts
    if alerts:
        print(f"\n{BOLD}  Alerts{RESET}")
        for a in alerts:
            sev_col = R if a["severity"] == "critical" else W
            sev_tag = f"{sev_col}[{a['severity'].upper():8}]{RESET}"
            print(f"  {sev_tag} {a['message']}")
    else:
        print(f"\n  {G}No alerts would fire.{RESET}")

    # Actions
    if actions:
        print(f"\n{BOLD}  Proposed actions{RESET}")
        for ac in actions:
            snap = "  📸 snapshot first" if ac["would_snapshot"] else ""
            gate_col = R if ac["gate"] == "circuit_breaker" else (
                       W if ac["gate"] == "approval" else G)
            print(f"  {gate_col}[{ac['gate']:16}]{RESET}  {ac['action']}  →  {ac['container']}{snap}")
            print(f"  {DIM}               {ac['reason']}{RESET}")
    else:
        print(f"\n  {DIM}No actions proposed.{RESET}")

    # AI narrative
    if ai:
        print(f"\n{BOLD}  AI analysis (Bedrock Nova Lite){RESET}")
        wrapped = textwrap.fill(ai, width=60, initial_indent="  ", subsequent_indent="  ")
        print(wrapped)

    print(f"\n{'─'*62}\n")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Homelab simulation engine — test hypothetical states safely.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""
        Examples:
          # Quick inline scenario
          python3 simulate.py --inline '{"CT110": {"mem_percent": 95}}'

          # Scenario file with AI analysis
          python3 simulate.py --scenario scenarios/oom_cascade.json

          # Replay past incident without AI
          python3 simulate.py --replay 42 --no-ai

          # Machine-readable JSON output
          python3 simulate.py --inline '{"vps": {"reachable": false}}' --json
        """)
    )
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument("--scenario", metavar="FILE",
                        help="Path to a scenario JSON file")
    source.add_argument("--inline",  metavar="JSON",
                        help="Inline JSON scenario overrides")
    source.add_argument("--replay",  metavar="ROW_ID", type=int,
                        help="Replay a metrics.db row by id")

    p.add_argument("--no-ai",  action="store_true",
                   help="Skip Bedrock AI analysis (faster, no cost)")
    p.add_argument("--json",   action="store_true",
                   help="Output raw JSON instead of human-readable report")
    p.add_argument("--config", metavar="PATH", default=str(CONFIG_PATH),
                   help=f"Path to config.yaml (default: {CONFIG_PATH})")
    return p.parse_args()


def main():
    args = parse_args()

    # Load config
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"[error] config.yaml not found at {config_path}", file=sys.stderr)
        sys.exit(1)
    with open(config_path) as f:
        config = yaml.safe_load(f)

    meta: dict[str, Any] = {"ai_enabled": not args.no_ai}
    replay_ts = None

    # Build scenario
    if args.replay:
        raw, replay_ts = load_replay_snapshot(args.replay)
        scenario = metrics_row_to_scenario(raw)
        meta.update({"source": "replay", "replay_row_id": args.replay,
                     "replay_timestamp": replay_ts})
    elif args.inline:
        try:
            overrides = json.loads(args.inline)
        except json.JSONDecodeError as e:
            print(f"[error] Invalid JSON: {e}", file=sys.stderr)
            sys.exit(1)
        scenario = build_scenario(overrides)
        meta["source"] = "inline"
    else:
        scenario_path = Path(args.scenario)
        if not scenario_path.exists():
            print(f"[error] Scenario file not found: {scenario_path}", file=sys.stderr)
            sys.exit(1)
        with open(scenario_path) as f:
            scenario_def = json.load(f)
        # Support both {"overrides": {...}} and bare {"CT110": {...}}
        overrides = scenario_def.get("overrides", scenario_def)
        scenario = build_scenario(overrides)
        meta["source"] = str(scenario_path)

    # Evaluate
    alerts  = evaluate_alerts(scenario, config)
    actions = predict_actions(alerts)

    # Optional AI analysis
    ai_narrative = None
    if not args.no_ai:
        try:
            ai_narrative = call_bedrock(scenario, alerts, actions, config)
        except Exception as e:
            print(f"[warn] Bedrock call failed: {e}  (continuing without AI analysis)",
                  file=sys.stderr)

    # Build and output report
    report = build_report(scenario, alerts, actions, ai_narrative, meta)

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_report(report)


if __name__ == "__main__":
    main()
