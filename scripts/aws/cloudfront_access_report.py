#!/usr/bin/env python3
"""Summarize CloudFront v2 logs without printing IPs, user agents or raw paths."""

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import subprocess

GROUP = "NullnullStgCloudFrontAccessLogs"
PROBE = "/cloudfront-log-check-20260923"


def route(path):
    if path == PROBE:
        return "operator probe"
    if path == "/":
        return "home"
    if path in ("/feed", "/live", "/profile", "/login"):
        return path[1:]
    if path.startswith("/api/v1/health/"):
        return "API health"
    if path.startswith("/api/"):
        return "API other"
    if path.startswith("/assets/"):
        return "web asset"
    if path.startswith("/covers/"):
        return "cover image"
    if path.startswith("/posts/"):
        return "post page"
    if path.startswith("/trips/"):
        return "trip page"
    return "other page/asset"


def client_family(value):
    ua = value.lower()
    if any(word in ua for word in ("bot", "crawler", "spider", "headless")):
        return "bot/automation"
    if "curl/" in ua:
        return "curl/test"
    if "firefox/" in ua:
        return "Firefox"
    if "edg/" in ua:
        return "Edge"
    if "chrome/" in ua:
        return "Chrome"
    if "safari/" in ua:
        return "Safari"
    return "other/unknown"


def summarize(events):
    routes, statuses, clients, hours, countries, results = (Counter() for _ in range(6))
    ips = set()
    times = []
    for event in events:
        row = json.loads(event["message"])
        path = row.get("cs-uri-stem", "")
        routes[route(path)] += 1
        status = str(row.get("sc-status", "unknown"))
        statuses[status[0] + "xx" if len(status) == 3 and status.isdigit() else "unknown"] += 1
        clients[client_family(row.get("cs(User-Agent)", ""))] += 1
        result = row.get("x-edge-result-type", "unknown")
        results[result if result in ("Hit", "Miss", "RefreshHit", "Error", "LimitExceeded") else "other"] += 1
        country = row.get("c-country", "")
        countries[country if len(country) == 2 and country.isalpha() else "unknown"] += 1
        if row.get("c-ip"):
            ips.add(row["c-ip"])
        try:
            at = datetime.fromisoformat(row["date"] + "T" + row["time"] + "+00:00")
            times.append(at)
            hours[at.astimezone(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:00 KST")] += 1
        except (KeyError, ValueError):
            hours["unknown"] += 1
    return {
        "firstUtc": min(times).isoformat() if times else None,
        "lastUtc": max(times).isoformat() if times else None,
        "requests": len(events),
        "distinctIpEstimate": len(ips),
        "operatorProbeRequests": routes["operator probe"],
        "byRouteGroup": dict(sorted(routes.items())),
        "byStatusClass": dict(sorted(statuses.items())),
        "byClientFamily": dict(sorted(clients.items())),
        "byHour": dict(sorted(hours.items())),
        "byCountryCode": dict(sorted(countries.items())),
        "byCacheResult": dict(sorted(results.items())),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", help="AWS CLI profile with log read permission")
    parser.add_argument("--hours", type=int, default=24)
    args = parser.parse_args()
    if not 1 <= args.hours <= 720:
        parser.error("--hours must be between 1 and 720")
    now = datetime.now(timezone.utc)
    command = ["aws", "logs", "filter-log-events", "--region", "us-east-1",
               "--log-group-name", GROUP,
               "--start-time", str(int((now - timedelta(hours=args.hours)).timestamp() * 1000)),
               "--end-time", str(int(now.timestamp() * 1000)), "--output", "json"]
    if args.profile:
        command += ["--profile", args.profile]
    output = subprocess.run(command, check=True, capture_output=True, text=True)
    print(json.dumps(summarize(json.loads(output.stdout).get("events", [])),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
