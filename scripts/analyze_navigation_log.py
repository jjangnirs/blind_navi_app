#!/usr/bin/env python3
"""
SafeCross KR - Navigation Flight Log Analyzer
Reads and parses navigation_flight.log to evaluate:
- Route progress and trajectory statistics
- Cross-track error (CTE) and off-route behavior
- Reroute frequency and trigger causes
- Heading vs bearing alignment accuracy
- Step progression and guidance timing
"""

import sys
import re
import os
from collections import Counter
from datetime import datetime

def parse_log(file_path):
    if not os.path.exists(file_path):
        print(f"[ERROR] Log file not found: {file_path}")
        return

    routes = []
    gps_records = []
    progress_records = []
    pose_records = []
    reroute_triggers = []
    reroute_results = []
    step_changes = []
    approaches = []
    guidances = []
    finishes = []

    time_pattern = re.compile(r"^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\] \[(\w+)\] (.*)$")

    with open(file_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            m = time_pattern.match(line)
            if not m:
                continue
            ts_str, category, content = m.groups()
            try:
                ts = datetime.strptime(ts_str, "%Y-%m-%d %H:%M:%S.%f")
            except ValueError:
                continue

            entry = {"ts": ts, "raw": content}

            if category == "ROUTE_START":
                routes.append(entry)
            elif category == "GPS":
                # lat=35.15951 lon=126.85263 acc=4.2m spd=1.1m/s pvd=fused sig=90%
                acc_m = re.search(r"acc=([\d\.]+)m", content)
                spd_m = re.search(r"spd=([\d\.]+)m/s", content)
                sig_m = re.search(r"sig=(\d+)%", content)
                entry["acc"] = float(acc_m.group(1)) if acc_m else None
                entry["spd"] = float(spd_m.group(1)) if spd_m else None
                entry["sig"] = int(sig_m.group(1)) if sig_m else None
                gps_records.append(entry)
            elif category == "PROGRESS":
                # step=1/8 distAlong=12.3m remDist=340.5m nextM=15.0m CTE=2.1m status=ON_ROUTE
                step_m = re.search(r"step=(\d+)/(\d+)", content)
                along_m = re.search(r"distAlong=([\d\.]+)m", content)
                rem_m = re.search(r"remDist=([\d\.]+)m", content)
                cte_m = re.search(r"CTE=([\d\.]+)m", content)
                status_m = re.search(r"status=(\w+)", content)
                entry["step"] = int(step_m.group(1)) if step_m else None
                entry["total_steps"] = int(step_m.group(2)) if step_m else None
                entry["along"] = float(along_m.group(1)) if along_m else None
                entry["rem"] = float(rem_m.group(1)) if rem_m else None
                entry["cte"] = float(cte_m.group(1)) if cte_m else None
                entry["status"] = status_m.group(1) if status_m else "UNKNOWN"
                progress_records.append(entry)
            elif category == "POSE":
                # heading=87.2° targetBearing=90.0° diff=-2.8° pitch=12.0° aligned=true
                diff_m = re.search(r"diff=([+-]?[\d\.]+)°", content)
                aligned_m = re.search(r"aligned=(\w+)", content)
                entry["diff"] = float(diff_m.group(1)) if diff_m else None
                entry["aligned"] = aligned_m.group(1) == "true" if aligned_m else None
                pose_records.append(entry)
            elif category == "REROUTE_TRIGGER":
                reroute_triggers.append(entry)
            elif category in ("REROUTE_SUCCESS", "REROUTE_FAIL"):
                reroute_results.append(entry)
            elif category == "STEP_CHANGE":
                step_changes.append(entry)
            elif category == "APPROACH":
                approaches.append(entry)
            elif category == "GUIDANCE":
                guidances.append(entry)
            elif category == "FINISH":
                finishes.append(entry)

    print("==========================================================")
    print(" SafeCross KR - Navigation Flight Telemetry Summary")
    print("==========================================================")
    print(f"Log File: {file_path}")
    if routes:
        print(f"Total Routes Initiated: {len(routes)}")
        for r in routes[-3:]:
            print(f"  • [{r['ts'].strftime('%H:%M:%S')}] {r['raw']}")
    print()

    # 1. GPS Quality
    if gps_records:
        accuracies = [g["acc"] for g in gps_records if g["acc"] is not None]
        speeds = [g["spd"] for g in gps_records if g["spd"] is not None]
        avg_acc = sum(accuracies) / len(accuracies) if accuracies else 0.0
        max_acc = max(accuracies) if accuracies else 0.0
        min_acc = min(accuracies) if accuracies else 0.0
        avg_spd = sum(speeds) / len(speeds) if speeds else 0.0
        print(f"🛰️ GPS Quality ({len(gps_records)} fixes):")
        print(f"  - Accuracy: avg={avg_acc:.1f}m (min={min_acc:.1f}m, max={max_acc:.1f}m)")
        print(f"  - Speed: avg={avg_spd:.1f}m/s ({avg_spd*3.6:.1f}km/h)")
    else:
        print("🛰️ GPS Quality: No GPS records found.")
    print()

    # 2. Cross-Track Error & Route Adherence
    if progress_records:
        ctes = [p["cte"] for p in progress_records if p["cte"] is not None]
        off_routes = [p for p in progress_records if "OFF_ROUTE" in p["status"]]
        avg_cte = sum(ctes) / len(ctes) if ctes else 0.0
        max_cte = max(ctes) if ctes else 0.0
        print(f"🛤️ Route Adherence ({len(progress_records)} progress ticks):")
        print(f"  - Cross-Track Error (CTE): avg={avg_cte:.1f}m, max={max_cte:.1f}m")
        print(f"  - Off-route ticks: {len(off_routes)} / {len(progress_records)} ({len(off_routes)*100.0/len(progress_records):.1f}%)")
    print()

    # 3. Reroute Analysis
    print(f"🔄 Reroute Events:")
    print(f"  - Triggers detected: {len(reroute_triggers)}")
    for t in reroute_triggers:
        print(f"    • [{t['ts'].strftime('%H:%M:%S')}] {t['raw']}")
    if not reroute_triggers:
        print("    (No reroutes triggered - smooth continuous navigation!)")
    print()

    # 4. Heading & Orientation Alignment
    if pose_records:
        aligned_count = sum(1 for p in pose_records if p["aligned"] is True)
        diffs = [abs(p["diff"]) for p in pose_records if p["diff"] is not None]
        avg_diff = sum(diffs) / len(diffs) if diffs else 0.0
        print(f"🧭 Heading Alignment ({len(pose_records)} pose samples):")
        print(f"  - Aligned ratio: {aligned_count} / {len(pose_records)} ({aligned_count*100.0/len(pose_records):.1f}%)")
        print(f"  - Average absolute bearing diff: {avg_diff:.1f}°")
    print()

    # 5. Maneuver Step Progression
    print(f"🚶 Step Transitions ({len(step_changes)}):")
    for s in step_changes:
        print(f"  • [{s['ts'].strftime('%H:%M:%S')}] {s['raw']}")
    print()

    # 6. Guidance Announcements
    print(f"🔊 Spoken Guidance Summary ({len(guidances)} speech items):")
    cat_counts = Counter(re.search(r"cat=(\w+)", g["raw"]).group(1) for g in guidances if re.search(r"cat=(\w+)", g["raw"]))
    for cat, count in cat_counts.most_common():
        print(f"  - {cat}: {count} times")
    print("==========================================================")

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "scratch/navigation_flight.log"
    parse_log(target)
