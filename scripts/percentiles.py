#!/usr/bin/env python3
"""Exact percentiles from the per-request binary log (24-byte records, LITTLE-endian).

layout: 0 int64 startNanos | 8 int64 latencyNanos | 16 int32 cuX100
        20 int16 threadId  | 22 int8 phaseId      | 23 int8 packed
Window phase ids are the odd ones: 1=LOAD, 3=R-A-strong, 5=R-B-eventual,
7=BG-strong, 9=BG-eventual, 11=BW (BatchWriteItem), 13=TW (TransactWriteItems).
Batch phase names in summary.json carry the batch size as a suffix (BG-strong-100, TW-83).
"""
import gzip, struct, sys, json, os, array

WINDOW = {1: "LOAD", 3: "R-A-strong", 5: "R-B-eventual",
          7: "BG-strong", 9: "BG-eventual", 11: "BW", 13: "TW"}

def percentiles(path, pcts):
    raw = gzip.open(path, "rb").read()
    n = len(raw) // 24
    assert len(raw) % 24 == 0, f"{path}: {len(raw)%24} trailing bytes"
    buckets = {p: array.array("q") for p in WINDOW}
    cu = {p: array.array("i") for p in WINDOW}
    unpack = struct.Struct("<q").unpack_from
    for i in range(0, len(raw), 24):
        ph = raw[i + 22]
        b = buckets.get(ph)
        if b is not None:
            b.append(unpack(raw, i + 8)[0])
            cu[ph].append(struct.unpack_from("<i", raw, i + 16)[0])
    out = {}
    for ph, vals in buckets.items():
        if not vals: continue
        s = sorted(vals)
        m = len(s)
        # nearest-rank, matching how HdrHistogram reports a percentile
        res = {}
        for p in pcts:
            idx = min(m - 1, max(0, int(-(-(p / 100.0 * m) // 1)) - 1))
            res[p] = s[idx] / 1000.0          # ns -> us
        res["count"] = m
        c = cu[ph]
        res["cu"] = sum(c) / len(c) / 100.0
        out[WINDOW[ph]] = res
    return out

def res_cu(got,name):
    return f"{got[name]['cu']:.2f}"

if __name__ == "__main__":
    d = sys.argv[1]
    pcts = [50, 90, 95, 99, 99.9, 99.99]
    got = percentiles(os.path.join(d, "requests.bin.gz"), pcts)
    ref = {p["name"]: p["raw"] for p in json.load(open(os.path.join(d, "summary.json")))["phases"]}
    print(f"{os.path.basename(d)}")
    print(f"  {'phase':<14}{'n':>10}{'P50':>9}{'P90':>9}{'P95':>9}{'P99':>9}{'P99.9':>10}{'P99.99':>10}")
    for name, r in got.items():
        print(f"  {name:<14}{r['count']:>10,}" + "".join(f"{r[p]:>9,.0f}" if p < 99.9 else f"{r[p]:>10,.0f}" for p in pcts))
        print(f"  {'  mean CU/req':<14}{res_cu(got,name):>10}")
        match = next((n for n in ref if n == name or n.startswith(name + "-")), None)
        if match:
            k = ref[match]
            print(f"  {'  summary.json':<14}{k['count']:>10,}{k['p50']:>9,.0f}{k['p90']:>9,.0f}{'--':>9}"
                  f"{k['p99']:>9,.0f}{k['p999']:>10,.0f}{k['p9999']:>10,.0f}")
            worst = max(abs(got[name][p] - k[j]) / k[j] * 100
                        for p, j in [(50,'p50'),(90,'p90'),(99,'p99'),(99.9,'p999')])
            print(f"  {'  max deviation':<14}{worst:>9.3f}%  (HdrHistogram quantises to 3 significant digits)")
        print()
