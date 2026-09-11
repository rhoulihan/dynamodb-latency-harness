# DynamoDB Latency Harness

A measurement harness for **GetItem and PutItem tail latency** against a large, provisioned
DynamoDB table, driven from an in-region EC2 client at a controlled fraction of provisioned
capacity.

It exists because most published DynamoDB latency numbers are either synthetic micro-benchmarks
or unqualified anecdotes. This measures a real table — 118 GiB, fixed 59 KiB items — under
controlled load, and **refuses to report a number that was taken under conditions that would
invalidate it**.

---

## Measured results

118 GiB table, 2,097,152 items of exactly 59 KiB, `c6in.4xlarge` client in the same AZ over a
VPC gateway endpoint, at 90% of provisioned capacity. Milliseconds, full SDK round trip
including retries.

| Operation | P50 | P90 | P95 | **P99** | P99.9 |
|---|---|---|---|---|---|
| **PutItem** | 5.46 | 6.51 | 7.06 | **10.77** | 27.00 |
| **GetItem** strongly consistent | 2.84 | 4.01 | 4.61 | **7.61** | 22.31 |
| **GetItem** eventually consistent | 2.54 | 3.74 | 4.26 | **6.99** | 22.48 |

Read figures are pooled over **five repetitions, 43.2 M requests**, with load deliberately
concentrated onto 15 of the table's ~40 partitions so each carried 2,400 RCU/s — 80% of
DynamoDB's 3,000 RCU/s per-partition ceiling. Zero throttles, zero misses. PutItem is a single
measurement, because the load phase writes the table once by construction.

Two findings worth more than the headline numbers:

**Run-to-run variance is large at the tail.** Strong-read P99 ranged **7.33–8.26 ms across five
identical runs** — a 12.3% spread on one machine, one dataset, one afternoon. Eventual reads
were steadier at 5.5%. A single run's tail figure should not be quoted to more than two
significant figures.

**Eventual consistency is not reliably faster.** Across seven runs the eventual-vs-strong P99
gap ranged from −15.3% to −0.4%, and at P99.9 the sign flipped in three of seven. The billing
difference reproduces every time; the latency difference does not. Treat eventual consistency
as a cost and throughput lever, not a latency one.

---

## What makes a measurement valid

A window is discarded unless **all seven** hold. This is most of the point of the harness.

| Criterion | Threshold |
|---|---|
| Throttles inside the window | zero |
| Requests served on first attempt | ≥ 99.99% |
| Client CPU | < 70% |
| Client-side consumed capacity vs CloudWatch | within 2% |
| Achieved rate vs target | within 2% for ≥ 95% of one-second ticks |
| JIT compilation quiesced before the window opens | 5-minute minimum ramp |
| Miss rate (reads that found no item) | ≤ 0.01% |

That last one exists because of a real incident: a stale checkpoint file caused a run to load
7% of the dataset, after which reads mostly missed, cost ~1 RCU instead of 15, and returned
fast, plausible, completely meaningless latencies. Nothing flagged it. **The most dangerous
measurement failure is the one that produces a believable number**, so the harness now checks
that reads actually found items.

---

## How it works

Four phases, orchestrated by `Main`:

```
LOAD      write the dataset at 90% of provisioned WCU, measure PutItem
SWITCH    drop WCU to 10, raise RCU, settle 5 minutes
R-A       strongly consistent GetItem at 90% of provisioned RCU
R-B       eventually consistent GetItem at 90% of provisioned RCU
TEARDOWN  capacity back to 10/10, upload artifacts, self-stop the instance
```

### Nothing allocates on the request path

Item payloads are pre-built templates reused across requests, worker threads are created once,
and each completed request is written as a **fixed-width 24-byte binary record** — start offset,
latency, consumed capacity, thread id, phase, attempt count — rather than a formatted line.
Formatting text would have meant ~12 M short-lived Strings inside the measurement window, and
that garbage lands in the tail being measured.

Latencies go to an HdrHistogram, recorded both raw and coordinated-omission corrected. It is a
`ConcurrentHistogram` deliberately: a plain `Histogram` is not thread-safe and silently drops
counts under contention — an earlier build lost 9.3% of its samples that way with no error.

### Rate control is denominated in capacity units, not requests

A 59 KiB write costs 59 WCU; a strong read of the same item costs 15 RCU; an eventual read, 7.5.
Pacing on requests per second would be one arithmetic step removed from what the table is
actually provisioned in, so the limiter meters capacity directly.

It is lock-free — tokens are a single `AtomicLong` refilled every millisecond, waiters park
50 µs at a time. A synchronized limiter was rejected on purpose: at 4,800 requests/s across 32
threads, monitor contention would surface as stalls at exactly the percentile being measured.
**The instrument must not manufacture the tail it is reading.**

Achieved throughput is never inferred from a request count. Every response carries
`ReturnConsumedCapacity=TOTAL`, and those real billed figures feed a sliding 10-second window of
100 ms buckets — what DynamoDB charged, not a model of it.

### The ramp

| State | Behaviour | Exit |
|---|---|---|
| `WARM` | hold 10% of ceiling, let JIT quiesce | 60 s |
| `RAMPING` | advance target 10% at a time, add threads (+16, cap 256) only below 70% CPU | target reaches 90%, minimum 5 min |
| `HOLDING` | steady at 90% of provisioned; the window opens here | 20-minute window completes |

A throttle anywhere freezes advancement for 60 s and backs the target off. The controller is a
pure function — it reads no clock and owns no threads, taking the current time as an argument —
so a five-minute ramp is verifiable in microseconds in a test.

### Finding partition boundaries with a parallel Scan

To load one slice of partitions hard, you must know which keys live on them. Restricting the
*index* range does not work: DynamoDB assigns partitions by hashing the key, so any subset of
key indices still spreads almost perfectly evenly across every partition — measured at **+1.03%
on the hottest partition** for every index subset tried.

A parallel `Scan` is different. `Segment`/`TotalSegments` divides the table into equal,
contiguous slices of the **actual hash key space**, so segment *i* of *N* is a real, known slice
rather than a guess at the hash function. Setting `TotalSegments=40` to match the table's 40
partitions and scanning segments 0–14 yields exactly the keys on those partitions.

The confirmation that the mapping is real: the selector collected **786,155 keys against 786,432
expected — 99.96%**. If segment boundaries did not line up with partition boundaries that count
would be arbitrary.

The scan reads only the partition key (`ProjectionExpression=pk`), which cuts transfer from tens
of GiB to a few MB — capacity is still billed on full item size, only the bytes on the wire
shrink — and it runs **once, before any measurement window opens**, so its own consumed capacity
never counts toward a window.

This technique comes from [`rhoulihan/dynamodb-office-hours`](https://github.com/rhoulihan/dynamodb-office-hours)
(2020), ported from SDK v1's `ScanSpec` to SDK v2's scan request fields.

### Partition count is a consequence of provisioned capacity

Partitions are allocated at table-creation time from provisioned capacity and **never merge**.
40,000 WCU buys 40 partitions at 1,000 WCU/s each — so running at 90% of provisioned *globally*
means running at 90% of **every partition's own ceiling**.

That is what invalidated the first write measurement: 900 WCU/s driven against a 1,000 WCU/s
hard limit is **0.47σ** of Poisson arrival margin, and 33 throttles got through. The fix is to
create the table at 40,000 WCU to fix 40 partitions, then drop to 30,000 before loading — the
partition count persists, per-partition rate falls to 675 WCU/s, and margin rises to **1.76σ**.
That run threw zero throttles.

---

## Quick start

Requires Java 21+, Maven, the AWS CLI, and an AWS account with a 40,000 WCU/RCU table-level
quota in the target region.

```bash
# 0. quota gate — refuses to let anything else run until the account can support the test
export DDBLAT_CSV=~/Downloads/admin_accessKeys.csv   # IAM console credential CSV
./scripts/00-preflight-quota.sh

# 1. provision VPC endpoint, security group, IAM role, S3 bucket, EC2 client
./scripts/10-provision.sh

# 2. build the shaded jar and stage it to the instance via S3 + SSM
./scripts/20-deploy.sh conf/prod.properties

# 3. start the run (detached on the instance)
./scripts/30-run.sh

# 4. wait for the DONE marker and pull artifacts down
./scripts/40-collect.sh

# 5. tear down. --destroy also deletes the table, bucket, endpoint, SG and IAM role
./scripts/99-teardown.sh --destroy
```

To repeat a measurement N times and collect each run separately:

```bash
./scripts/50-repeat.sh conf/focused.properties 5
```

`50-repeat.sh` raises capacity once for the whole series and drops it once at the end —
DynamoDB allows only **4 provisioned-capacity decreases per table per UTC day**, then one per
hour, so a per-run teardown will strand the table at full capacity partway through a series.

There is **no SSH**: the security group has no ingress rule and no key pair is created. The only
channel to the instance is SSM RunCommand.

---

## Configuration

Three profiles are included. All keys are documented inline in the files.

| File | Purpose |
|---|---|
| `conf/smoke.properties` | ~5 minute, ~$1 rehearsal at 1,000 WCU |
| `conf/prod.properties` | the full 118 GiB measurement |
| `conf/focused.properties` | read-only re-run concentrating load onto 15 partitions |

Selected keys:

| Key | Meaning |
|---|---|
| `itemCount` | must be a power of two — the full-period read cycle requires it |
| `presplitWcu` | create the table at this WCU to fix partition count, then drop to `loadWcu` |
| `targetFraction` | fraction of provisioned capacity to drive (0.90) |
| `readSegmentsTotal` / `readSegmentsUsed` | segment-scoped reads; 0 disables |
| `skipLoad` | read-only re-run against an already-loaded table |
| `manageCapacity` | false hands capacity control to the caller for a multi-run series |
| `windowMinutes` / `minRampMinutes` / `warmSeconds` | measurement timing |

---

## Artifacts

Each run produces, locally and in S3:

| File | Contents |
|---|---|
| `summary.json` | per-phase percentiles, validity verdict, violations, throttles, retries |
| `requests.bin.gz` | every request as a 24-byte record — 11.7 M for a production run |
| `*.hlog` | HdrHistogram interval logs for post-hoc re-slicing |
| `run.jfr` | JFR flight recording for the whole run |
| `size-model-probe.json` | predicted vs real billed capacity for 128 probe items |
| `ddblat.log` | phase transitions, one-second TICK lines, violations |

`scripts/percentiles.py` computes exact percentiles straight from `requests.bin.gz`, including
ones the summary does not record. It cross-checks itself two ways — against `summary.json`'s
HdrHistogram values, which it reproduces to within 0.085%, and against mean consumed capacity,
which comes back as exactly 59.00 WCU per write and 15.00 / 7.50 RCU per read.

**The binary records are little-endian**, not the `ByteBuffer` default. Decoding them as
big-endian yields plausible record counts with garbage latencies.

---

## Cost

Approximate, us-east-1, provisioned capacity:

| Run | Duration | Cost |
|---|---|---|
| Smoke | ~5 min | ~$1 |
| Full production (load + both read phases) | ~2h25m | ~$41 |
| Five-run focused read series | ~5h | ~$37 |

40,000 RCU is ~$5.20/hr and 40,000 WCU ~$26/hr, so **capacity dominates** — the `c6in.4xlarge`
is ~$0.91/hr. Teardown drops capacity before stopping the instance for that reason, and every
failure path is wrapped so a crash cannot strand the table at full capacity.

---

## Security notes

- The admin credential is used **only from the operator's machine** to provision. It is never
  copied to the instance; the instance uses its instance profile.
- The instance role is scoped to specific table ARNs, with `ec2:StopInstances` restricted to the
  instance's own ARN.
- `_common.sh` never enables `xtrace` around credential handling.
- No key pair is created and the security group has no ingress rule.

---

## Development

```bash
mvn test          # 238 unit and integration tests
mvn package       # shaded jar at target/ddblat.jar
```

Built test-first. Integration tests use Testcontainers against DynamoDB Local; if you run Podman
rather than Docker, note that `TESTCONTAINERS_RYUK_DISABLED` must be set as an **environment
variable** — the properties-file entry is inert in Testcontainers 1.20.4.

Six real defects in this harness were found only by running it against real AWS, none caught by
the test suite or by review: a token-bucket deadlock when burst capacity fell below one
request's cost, results never reaching S3, a false quota gate, a 0-byte JFR recording, abandoned
in-flight writes on shutdown, and the stale checkpoint described above. The design spec is in
[`docs/design-spec.md`](docs/design-spec.md).

---

## License

MIT — see [LICENSE](LICENSE).
