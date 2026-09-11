# DynamoDB GetItem / PutItem Latency Harness — Design

**Date:** 2026-09-08
**Status:** Awaiting review
**Region:** us-east-1

## 1. Purpose

Measure client-observed P50 / P90 / P99 / P99.9 latency for DynamoDB `PutItem` and
`GetItem` against a 100 GiB table of ~50 KiB items, from a client in the same region,
at 90% of provisioned capacity and with zero throttling.

The deliverable is a latency distribution plus enough supporting evidence to defend it:
throttle counts, retry counts, JVM pause attribution, and a cross-check of client-side
capacity accounting against CloudWatch.

### Non-goals

- Not a throughput benchmark. Capacity targets exist to fix the load level at which
  latency is measured, not to find a maximum.
- Not a mixed read/write test. During read phases WCU is 10 and nothing is writing.
  Contention between the write path and strongly-consistent reads is a different
  experiment (see §13).
- No DAX, no caching layer, no batch APIs. Single-item `PutItem` and `GetItem` only.

## 2. Test parameters

Derived numbers, not inputs. Everything below follows from "100 GiB of 48–52 KiB items"
plus DynamoDB's capacity arithmetic.

| Quantity | Value | Derivation |
| --- | --- | --- |
| Item count | 2,097,152 | 100 GiB ÷ 50 KiB, exact |
| Mean item size | 51,200 B (50 KiB) | midpoint of [48 KiB, 52 KiB] |
| Total dataset | 107,374,182,400 B | = 100 GiB exactly |
| WCU per PutItem | 48–52, **mean 50.496** | `ceil(bytes / 1024)`; rounding pushes the mean above 50 |
| RCU per strong GetItem | 12–13, mean 12.996 | `ceil(bytes / 4096)`; only the smallest bucket is 12 |
| RCU per eventual GetItem | 6–6.5, mean 6.498 | half of strong |

### Phase targets

| Phase | Capacity | Target 90% | Request rate | Duration |
| --- | --- | --- | --- | --- |
| LOAD | 40,000 WCU | 36,000 WCU/s | ~713 PutItem/s | ~49 min (fixed work) |
| R-A | 40,000 RCU | 36,000 RCU/s | 2,770 GetItem/s strong | 20 min window |
| R-B | 40,000 RCU | 36,000 RCU/s | 5,540 GetItem/s eventual | 20 min window |

Total write work: 2,097,152 × 50.496 WCU = 105,897,984 WCU ÷ 36,000/s = 2,942 s = 49.0 min.

Peak client ingress is R-B: 5,540 × 51,200 B = 284 MB/s ≈ 2.27 Gbps.

### On comparing R-A and R-B

Both windows run at 90% of provisioned capacity, which is the condition a customer
actually buys. Whether DynamoDB serves an eventually-consistent read from the leader or a
secondary is not guaranteed and not observable from the client. This spec therefore makes
no claim about internal routing or per-node utilization; it reports what the client
measures at a stated capacity utilization in each consistency mode, and lets the
measurement characterize the difference.

Expectation, recorded so the result can be checked against it: P50 should be close in both
windows, since the per-request work is identical. The tails may diverge in either
direction and no prediction is made.

## 3. Data model

Single table `latency-test-100g`. Partition key only. No sort key, no GSI, no LSI, no
streams, no TTL.

### Key space

Dense integers `0 … 2,097,151` rendered `K#%08d`. Dense means `GetItem` can sample the
whole table uniformly from an integer alone — no key list to store, ship, or keep in sync
between phases.

All 2,097,152 key strings are materialized into a `String[]` at startup (~130 MB
retained). This is a deliberate trade: it costs ~2 s of startup and a fixed slice of a
24 GiB heap, and in exchange key construction on the hot path becomes an array index with
zero allocation and no `String.format`.

### Item schema — 20 attributes

15 scalars carrying 222 bytes of fixed overhead, plus 5 String blobs carrying the bulk.

| # | Name | Type | Notes |
| --- | --- | --- | --- |
| 1 | `pk` | S | `K#%08d`, partition key |
| 2 | `ver` | N | schema version, constant 1 |
| 3 | `created` | N | epoch millis |
| 4 | `updated` | N | epoch millis |
| 5 | `active` | BOOL | |
| 6 | `region` | S | `us-east-1` |
| 7 | `tenant` | S | 16 chars |
| 8 | `category` | S | 12 chars |
| 9 | `status` | S | 8 chars |
| 10 | `seq` | N | |
| 11 | `score` | N | |
| 12 | `weight` | N | |
| 13 | `flags` | N | |
| 14 | `shard` | N | |
| 15 | `label` | S | 24 chars |
| 16–20 | `blob0`–`blob4` | S | ~9.8–10.6 KiB each, carrying `size − 222` bytes |

### Size distribution

Item size is a deterministic function of the key, so it is reproducible without state:

```
template  = mix64(key) mod 256                    // SplitMix64 finalizer
size(j)   = 49152 + round(j * 4096 / 255)         // j = 0..255
```

256 discrete sizes evenly spanning [49,152 B, 53,248 B] = [48 KiB, 52 KiB]. Mean 51,200 B.
Uniform template selection over 2,097,152 keys puts the dataset total at 100 GiB.

### Blob alphabet

Blobs draw from `[A-Za-z0-9]` only. This is a correctness requirement, not a style
choice: `"` and `\` are JSON-escaped on the wire, which would inflate transferred bytes
above billed item size and silently distort the latency being measured.

For the same reason blobs are String (`S`) and not Binary (`B`). DynamoDB bills `B` at raw
size but base64-encodes it in the JSON protocol — 33% more bytes on the wire per billed
KiB. `S` with an ASCII alphabet keeps wire bytes equal to item bytes.

### Size model validation

The computed size model is verified against DynamoDB's own accounting before the load
begins: 100 probe items spanning the template range are written with
`ReturnConsumedCapacity=TOTAL`, and `ceil(model_bytes / 1024)` must equal the billed WCU
for every one. A mismatch aborts the run.

Validation granularity is ±1 KiB, the finest DynamoDB exposes. That is 2% of a 50 KiB
item and well inside the 48–52 KiB band, so it is sufficient for both the band constraint
and the 100 GiB total.

`DescribeTable`'s `ItemCount` and `TableSizeBytes` are **not** used for verification —
they update roughly every six hours. The harness's own committed-chunk count is
authoritative.

### Read access pattern

Full-cycle LCG over the key space: `key_i = (i · P) mod 2^21` with P odd. Every key is
read exactly once per cycle — 1.6 cycles in R-A, 3.2 in R-B. This removes repeated-key
locality as a possible confound. `--access-pattern=random` remains available for
comparison runs.

## 4. Measurement architecture

**Synchronous `DynamoDbClient` (Apache HTTP client), fixed thread pool, token-bucket rate
limiter.**

One thread holds exactly one in-flight call, so a recorded latency spans a single
request with no event-loop scheduling and no internal queue between `nanoTime()` and the
socket. Required concurrency peaks around 28 in-flight requests, so nothing is gained by
an async client, and an async client would introduce scheduling jitter precisely where
the P99.9 is being read.

Rejected alternatives:

- **Netty async client.** Higher ceiling than needed; event-loop scheduling delay lands
  in the measured interval.
- **AWS CRT client.** Off-heap HTTP buffers would cut read-path allocation meaningfully,
  but adds a native dependency and less transparent connection management to solve a
  problem not yet confirmed to exist. Held as the escalation path (§5).

### Throughput accounting

Every request sets `ReturnConsumedCapacity=TOTAL`. Achieved capacity is the sum of real
`CapacityUnits` over a 10 s sliding window — measured from what DynamoDB charged, not
estimated from request counts.

### Client configuration

| Setting | Value | Rationale |
| --- | --- | --- |
| `maxConnections` | `threads × 2` | never block on pool acquisition |
| `connectionTimeToLive` | 0 (no recycle) | a mid-window TLS handshake is a fabricated tail sample |
| `connectionMaxIdleTime` | 30 min | exceeds any window; no idle reaping mid-run |
| `apiCallTimeout` | 10 s | bounds a hung worker |
| `apiCallAttemptTimeout` | 5 s | far above any plausible P99.99 |
| `numRetries` | 2, full-jitter backoff | known policy, not SDK default |

Connections are pre-warmed before each ramp with `threads × 2` concurrent `GetItem` calls
against a nonexistent key. Those timings are discarded.

### What gets recorded

Headline percentiles are **total API call latency** (client-observed, retries included).
A secondary histogram covers single-attempt requests only. `attempts` is recorded per
request; if more than 0.01% of a window has `attempts > 1`, the window is invalid and
re-runs.

### Coordinated omission

Open-loop pacing under-records when the service stalls. Both the raw histogram and the
`recordValueWithExpectedInterval`-corrected histogram are emitted, and the report shows
both.

## 5. Allocation and tail control

Allocation budget, measured per phase:

| Path | Rate | Per request | Allocation | Poolable |
| --- | --- | --- | --- | --- |
| Write — payload generation | 713/s | ~50 KB | 36 MB/s | **yes** |
| Write — SDK marshal | 713/s | ~55 KB | 39 MB/s | no |
| Read — SDK parse + socket buffers | 5,540/s | ~100 KB | **554 MB/s** | no |

The read path dominates by 7x and is entirely inside the SDK — `GetItem` materializes a
fresh `AttributeValue` with a fresh 50 KB String on every call, with no hook to pool it.
What makes that survivable is that every one of those objects dies within microseconds.
Generational ZGC collects young garbage concurrently and its pauses are O(root set), not
O(allocation rate); 554 MB/s of die-young allocation is comfortable in a 24 GiB heap.

Pooling is therefore applied where it is both possible and useful:

### 5.1 Pre-built payload templates

256 immutable `Map<String, AttributeValue>` templates (one per size bucket, ~13 MB total)
are built once at startup. Per request the harness constructs a 20-entry map holding 19
shared value references plus the key — ~1 KB of allocation instead of ~50 KB, a 98%
reduction on the write path, and random-string generation leaves the hot path entirely.

Item size, wire bytes, and stored bytes are bit-identical to generating fresh content.
DynamoDB neither compresses nor deduplicates, so reuse is not observable server-side.

### 5.2 Allocation-free measurement path

This is the change that most directly protects the P99.9, because it runs on the worker
thread.

- `HdrHistogram.recordValue` is an array increment — already allocation-free.
- The per-request log is **not** formatted text. 12M `String`s would be a self-inflicted
  tail. Records are fixed-width 24-byte binary, written into a per-thread direct
  `ByteBuffer`, `long`s only, no boxing.

```
offset  0  int64   startNanos      (offset from run start)
offset  8  int64   latencyNanos
offset 16  int32   consumedCU × 100
offset 20  int16   threadId
offset 22  int8    phaseId
offset 23  int8    attempts << 4 | statusClass
```

Full buffers are handed to a single writer thread through a bounded queue; workers take a
clean buffer from a pre-sized free list (8 × 64 KB per thread). Steady state allocates
nothing. Buffer handoff happens after the closing `nanoTime()`, so even a writer stall
cannot corrupt a recorded latency — it would only reduce throughput, which the controller
observes.

Volume: 24 B × ~12.1M records ≈ 290 MB raw, gzipped after the run rather than inline, so
compression never touches the hot path.

### 5.3 JIT is the larger tail risk

The first few thousand requests run interpreted, then C1, before C2 settles. Including
them in a P99.9 would corrupt it far more than any allocation rate.

- Ramp runs a **minimum of 5 minutes** before any measurement window opens, regardless of
  how quickly capacity targets are met — at 713+ req/s that is ≥200,000 requests.
- Compilation quiescence is verified post-hoc from JFR `jdk.Compilation` events.
- `-XX:+AlwaysPreTouch` with a fixed 24 GiB heap (`-Xms` = `-Xmx`) so no heap expansion
  occurs mid-window either.

### 5.4 Pause attribution

JFR runs for the whole test (`settings=profile`, `maxsize=2G`). Post-run analysis
correlates every sample above P99.9 against `jdk.GCPhasePause`, `jdk.ZAllocationStall`,
and safepoint events, and the report states what fraction of the tail coincided with a JVM
pause. The number is measured and published rather than assumed.

**Escalation:** if JFR shows GC materially in the tail, switch to the CRT client, which
moves HTTP buffering off-heap and cuts roughly half the read-path allocation. Not taken
up front — it trades measurement transparency for an unconfirmed problem.

## 6. Phase machine

The Java orchestrator drives all four phases including the `UpdateTable` calls; it knows
when the load actually finished, and a shell script does not.

```
LOAD     create table (WCU 40,000 / RCU 10)
         validate size model on 100 probe items
         warm 60 s @ 10%  →  ramp (min 5 min)  →  hold @ 90%
         until all 2,097,152 items committed                        ~55 min

SWITCH   UpdateTable: WCU 40,000 → 10, RCU 10 → 40,000
         poll DescribeTable until ACTIVE and values reflected
         settle 5 min                                               ~10 min

R-A      pre-warm connections → ramp (min 5 min) → hold @ 36,000 RCU/s
         strongly consistent, 2,770 req/s, 20 min window            ~25 min

R-B      pre-warm connections → ramp (min 5 min) → hold @ 36,000 RCU/s
         eventually consistent, 5,540 req/s, 20 min window          ~25 min

TEARDOWN UpdateTable: RCU → 10
         gzip logs, sync results + DONE marker to S3
         self-stop the instance                                     ~5 min
```

The table is created with RCU 10, not 40,000 — read capacity during a write-only phase
costs ~$5/hr for nothing. Partition count is driven by the 40,000 WCU at creation
(~40 partitions), and partitions do not merge, so raising RCU later finds the table
already split.

Capacity decreases used: two (WCU at SWITCH, RCU at TEARDOWN), against a verified limit of
27 per day.

### Resumability

The key space is chunked and completed chunks checkpoint to a local file. A loader crash
at 80% resumes at 80%, not zero. The same chunking provides multi-client support through
`--client-index` / `--client-count`, though one instance covers this load comfortably.

## 7. Rate control and ramp

### Token bucket

Denominated in **capacity units per second**, not requests per second, because per-request
cost varies with item size (48–52 WCU) and consistency (13 vs 6.5 RCU).

A refill thread adds `rate / 1000` micro-units every millisecond, capped at 100 ms of
burst. Workers CAS-decrement by the request's CU cost; on insufficient tokens they
`LockSupport.parkNanos(50_000)` and retry. Lock-free and allocation-free. At 5,540 req/s
across ~32 threads, CAS contention is negligible — a synchronized limiter was rejected
because block contention would show up at exactly the percentile being measured.

### Ramp controller

Start at 10% of ceiling. Every 30 s, raise the target by 10 percentage points if all three
hold:

1. achieved ≥ 95% of current target over the last 10 s
2. zero throttles in the last 30 s
3. client CPU < 70%

Any throttle drops the target 10 points, holds 60 s, then resumes. Ramp stops at 90% and,
after the 5-minute minimum has elapsed, the measurement window opens.

### Client saturation signal

If the bucket sits at its cap for more than 1 s while the target is unmet, tokens are
going unconsumed because no worker is free — add 16 threads, up to 256.

At 50 KiB items this condition is not expected to fire. The capacity ceiling arrives at
~28 in-flight requests, so the ramp should always terminate on the throughput condition.
The signal is implemented and logged anyway, so the claim is measured rather than assumed.

## 8. Infrastructure

| Component | Choice | Rationale |
| --- | --- | --- |
| Instance | c6in.4xlarge (16 vCPU, 32 GiB, up to 50 Gbps) | network-optimized; R-B needs 2.3 Gbps sustained and the client must never be the bottleneck |
| OS | Amazon Linux 2023 | |
| Storage | 40 GiB gp3 | ~400 MB results + 2 GB JFR, with room |
| JVM | Corretto 25, generational ZGC, `-Xms24g -Xmx24g -XX:+AlwaysPreTouch` | |
| Networking | VPC **gateway** endpoint for DynamoDB | free, no ENI, keeps traffic off IGW/NAT — better tail and no data-transfer charge |
| Build | on the instance (dnf: Corretto 25 + Maven) | one toolchain; no local Maven on the Mac |
| Provisioning | AWS CLI shell scripts | ~6 resources for a rig deliberately mutated mid-run; readable and steppable |
| Results | S3 bucket, synced at teardown | survive instance stop; report generation runs on the Mac without SSH |

Provisioning is imperative rather than CloudFormation because the rig changes its own
capacity mid-run and is discarded afterward; a stack adds lifecycle management that buys
nothing here.

## 9. IAM

The admin access key in `~/Downloads/admin_accessKeys.csv` is used **only from the Mac**,
for provisioning. It is never copied to the instance.

The instance profile carries a scoped policy:

- `dynamodb:PutItem`, `GetItem`, `DescribeTable`, `UpdateTable` on the single table ARN
- `cloudwatch:GetMetricData` on the table's metrics
- `s3:PutObject` on the results bucket prefix
- `ec2:StopInstances` on exactly one resource ARN — its own instance — so the harness can
  stop itself at teardown without the Mac having to stay awake polling for 2.5 hours

## 10. Outputs

- **`requests-<phase>.bin.gz`** — every request: start offset, latency, consumed CU,
  thread, phase, attempts, status class.
- **`<phase>.hlog`** — HdrHistogram interval logs at 10 s resolution, giving
  latency-over-time rather than a single terminal number.
- **`summary.json`** per phase — P50 / P90 / P99 / **P99.9** / P99.99 / max / mean /
  stddev / count, throttles, retries, achieved CU/s, and the GC pause histogram alongside.
- **`run.jfr`** — full flight recording for pause and compilation attribution.
- **`report.md`** with charts, following the project SVG standards.

### CloudWatch cross-check

After each phase the harness pulls `ConsumedReadCapacityUnits`,
`ConsumedWriteCapacityUnits`, `ThrottledRequests`, and `SuccessfulRequestLatency` at
1-minute resolution for the window.

Two uses: client-side CU accounting must agree with DynamoDB's within 2%, and
`SuccessfulRequestLatency` is DynamoDB's own server-side measurement — differencing it
against the client-observed number yields the network plus SDK overhead, which is a
result worth reporting in its own right.

## 11. Run validity criteria

A measurement window is valid only if all of the following hold. Any failure invalidates
the window and it re-runs.

1. Zero throttled requests inside the window.
2. `attempts == 1` for ≥ 99.99% of requests.
3. Client CPU < 70% for the whole window.
4. Client-side consumed CU within 2% of CloudWatch.
5. Achieved rate within 2% of the 90% target for ≥ 95% of the window.
6. JIT compilation quiesced before the window opened (verified from JFR).
7. For LOAD only: committed item count exactly 2,097,152.

## 12. Cost and runtime

| Item | Cost |
| --- | --- |
| 40,000 WCU × ~1.2 hr | $31 |
| 40,000 RCU × ~1.0 hr | $5 |
| c6in.4xlarge × ~4 hr | $4 |
| Storage, EBS, S3 during run | ~$1 |
| **Total for the run** | **~$41** |

Residual after teardown, with the instance stopped and the table retained: **~$30/month**
(table storage 107.4 GB × $0.25 = $26.84, plus 40 GiB gp3 at $3.20). A stopped instance
still bills its EBS volume.

Wall-clock: ~2 h 25 m end to end (20 m provision, 5 m build, 55 m load, 10 m switch,
25 m R-A, 25 m R-B, 5 m teardown).

## 13. Risks

1. ~~**Quota — the schedule risk.**~~ **CLEARED 2026-09-08.** Verified against account
   111122223333 in us-east-1: table-level read and write both 40,000 (exactly what the test
   needs), account-level read and write both 80,000 (double the default, so an increase was
   already applied), and provisioned capacity decreases 27/day. The account holds zero
   DynamoDB tables, so nothing else is drawing on the account-level budget. No support
   ticket required.
2. **Partition split during load.** If the table does not pre-split to ~40 partitions at
   creation, early writes throttle. The 60 s warm phase and gradual ramp absorb this; the
   throttle-triggered backoff catches it otherwise.
3. **Client saturation will not fire.** At 50 KiB items the ceiling arrives at ~28
   in-flight requests, so the ramp will always terminate on throughput, never on client
   busy-ness. Stated so the result is not a surprise.
4. **Leader contention is never exercised.** With WCU at 10 during read phases, nothing is
   writing anywhere. Testing strongly-consistent reads contending with an active write
   path needs a mixed window with real WCU provisioned — a different experiment, out of
   scope here.

## 14. Pre-flight checklist

Ordered; each gates the next.

1. ~~Install AWS CLI v2 on the Mac; configure the admin key as a named profile.~~
   **DONE** — aws-cli 2.36.41, profile `ddblat`, us-east-1.
2. ~~Confirm quotas admit 40,000.~~ **DONE** — see risk 1; all four quotas clear.
3. Provision: VPC gateway endpoint, security group, IAM role and instance profile, S3
   results bucket, EC2 instance.
4. Build the harness on the instance.
5. **Smoke run** — full four-phase cycle against a throwaway table at 1,000 WCU with
   10,000 items, ~5 minutes, ~$1. Exercises every phase transition, the size-model
   validator, the ramp controller, the binary log writer, and teardown before an hour is
   spent loading 100 GiB.
6. Real run.
7. Pull results from S3 to the Mac; generate the report.

## 15. Open items

None. All parameters are fixed above; the quota check in step 2 is the only external
dependency, and it gates rather than modifies the design.
