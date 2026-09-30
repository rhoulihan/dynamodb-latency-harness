# MELI PoC — DynamoDB latency by item size

Measured 2026-09-30, AWS `us-east-1`, provisioned mode, same client (c6in.4xlarge) and harness for every size.
Request rates held constant across sizes so item size is the only variable: 2,400 strong reads/s, 458 puts/s,
24 BatchGetItem calls/s, 20 BatchWriteItem calls/s, 3 transactions/s. 40 partitions (pre-split), 2²¹ items per size.
10-minute measurement windows after a ≥5-minute JIT-quiesce ramp.

Cells are **mean / P95 in ms**, client-side, per call. ✅/❌ against MELI's SLA (single read AVG<4/P95<7,
single write AVG<7/P95<10, bulk/batch AVG<20/P95<30).

| Operation | 370 B | 523 B | 2,000 B | 10,000 B | 50,000 B | MELI SLA |
|---|---|---|---|---|---|---|
| PutItem (single) | 3.74 / 4.58 ✅ | 3.69 / 4.53 ✅ | 3.94 / 4.82 ✅ | 4.30 / 5.26 ✅ | 5.41 / 6.57 ✅ | <7 / <10 |
| GetItem strong | 2.27 / 3.08 ✅ | 2.30 / 3.10 ✅ | 2.29 / 2.94 ✅ | 2.44 / 3.16 ✅ | 3.01 / 4.26 ✅ | <4 / <7 |
| GetItem eventual | 1.91 / 2.48 ✅ | 2.06 / 2.80 ✅ | 2.11 / 2.86 ✅ | 2.16 / 2.83 ✅ | 2.75 / 3.88 ✅ | <4 / <7 |
| BatchGetItem x100 strong | 17.77 / 26.72 ✅ | 17.87 / 28.74 ✅ | 18.00 / 26.94 ✅ | 26.55 / 37.85 ❌ | 76.67 / 94.04 ❌ | <20 / <30 |
| BatchGetItem x100 eventual | 14.70 / 24.81 ✅ | 15.20 / 25.23 ✅ | 14.45 / 24.30 ✅ | 26.19 / 40.76 ❌ | 71.87 / 91.42 ❌ | <20 / <30 |
| BatchWriteItem x25 | 6.53 / 10.69 ✅ | 7.13 / 12.26 ✅ | 7.56 / 11.50 ✅ | 10.14 / 16.20 ✅ | 23.94 / 30.36 ❌ | <20 / <30 |
| TransactWriteItems x100 (x83 at 50 KB) | 90.18 / 110.03 ❌ | 94.08 / 114.43 ❌ | 101.44 / 121.11 ❌ | 123.77 / 146.54 ❌ | 192.25 / 223.22 ❌ | <20 / <30 |

## Findings

1. **Single-item reads and writes meet MELI's SLA at every size.** Strong GetItem mean moves 2.27 → 3.01 ms from 370 B to 50 KB; PutItem 3.74 → 5.41 ms.
2. **BatchGetItem x100 meets the bulk SLA only up to 2 KB.** Latency tracks bytes returned per call, not key count: ~26 ms mean at 10 KB (~1 MB/call), ~77 ms at 50 KB (~5 MB/call). Smaller batches are the remedy for large items.
3. **BatchWriteItem x25 meets the bulk SLA through 10 KB** and sits at the edge at 50 KB (23.9 mean, P95 30.4).
4. **TransactWriteItems at 100 items misses at every size** — 90 ms mean at 370 B, 124 ms at 10 KB, 192 ms at 50 KB (83 items: the 4 MB transaction cap). This is the operation MELI specified for TC1/TC2 ("Batch (Atomic), lotes de hasta 100 items"). Atomic 100-item batches are 4.5–10× over budget on DynamoDB; non-atomic 25-item batches are within it.

## Validity

Every window was checked against the harness's seven criteria (zero throttles, ≥99.99% first-attempt success, client CPU <70%, capacity cross-check, rate adherence, JIT quiesce, miss rate). Exceptions:

- **BatchGetItem x100 strong @ 523 B** — throttles inside window: 1
- **BatchWriteItem x25 @ 370 B** — throttles inside window: 1
- **TransactWriteItems x100 (x83 at 50 KB) @ 370 B** — achieved rate within 2% of target for only 496/591 ticks, need 95% (last achieved 600 CU/s vs target 600 CU/s)
- **TransactWriteItems x100 (x83 at 50 KB) @ 523 B** — achieved rate within 2% of target for only 480/591 ticks, need 95% (last achieved 580 CU/s vs target 600 CU/s)
- **TransactWriteItems x100 (x83 at 50 KB) @ 2,000 B** — achieved rate within 2% of target for only 457/591 ticks, need 95% (last achieved 1200 CU/s vs target 1201 CU/s)
- **TransactWriteItems x100 (x83 at 50 KB) @ 50,000 B** — achieved rate within 2% of target for only 537/591 ticks, need 95% (last achieved 24402 CU/s vs target 24403 CU/s)

Throttle flags were 1 event in ~12–14k calls (0.007%), inside MELI's 0.02% error budget but over the harness's zero-throttle gate. The TransactWriteItems rate-adherence flags are an artifact of 3 calls/s: one early or late call moves a one-second tick by ~33%, so a ±2% band cannot hold. At 10 KB, where per-call capacity is larger and pacing smoother, the transaction window passed every criterion. None of these flags change the latency figures.

## Runs

- 370 B — `meli-370-20260930T035542Z`
- 523 B — `meli-523-20260930T005506Z`
- 2,000 B — `meli-2000-20260930T065614Z`
- 10,000 B — `meli-10000-20260930T095745Z`
- 50,000 B — `meli-50000-20260930T130831Z`

Raw per-request records (`requests.bin.gz`), HdrHistogram logs and JFR recordings are in `results/<run>/` locally (not committed; ~3 GB). `scripts/percentiles.py results/<run>` recomputes every percentile from the raw records.
