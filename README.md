# DynamoDB Latency Harness

A measurement harness for **GetItem and PutItem tail latency** against a large, provisioned
DynamoDB-compatible table, driven from an in-region client at a controlled fraction of
provisioned capacity.

It runs against two services with the same code path and the same workload:

| Provider | Service |
|---|---|
| `aws` | Amazon DynamoDB |
| `oci` | Oracle Autonomous AI Database — DynamoDB-compatible API |

Oracle exposes a wire-compatible endpoint, so the same AWS SDK v2 client measures both; only
the endpoint and credentials differ. That is what makes a like-for-like comparison possible at
all.

It exists because most published latency numbers are either synthetic micro-benchmarks or
unqualified anecdotes. This measures a real table under controlled load, and **refuses to report
a number that was taken under conditions that would invalidate it**.

---

## What makes a measurement valid

A window is discarded unless **all seven** hold. This is most of the point of the harness.

| Criterion | Threshold |
|---|---|
| Throttles inside the window | zero |
| Requests served on first attempt | ≥ 99.99% |
| Client CPU | < 70% |
| Client-side consumed capacity vs CloudWatch | within 2% *(AWS only)* |
| Achieved rate vs target | within 2% for ≥ 95% of one-second ticks |
| JIT compilation quiesced before the window opens | 5-minute minimum ramp |
| Miss rate (reads that found no item) | ≤ 0.01% |

That last one exists because of a real incident: a stale checkpoint caused a run to load 7% of
the dataset, after which reads mostly missed, cost ~1 RCU instead of 15, and returned fast,
plausible, completely meaningless latencies. Nothing flagged it. **The most dangerous
measurement failure is the one that produces a believable number.**

A criterion a provider cannot support is recorded as **not applicable**, never as a pass. A
validity gate that quietly weakens itself on a platform it cannot inspect would be worse than no
gate — it would certify numbers it never checked.

---

## How it works

Four phases, orchestrated by `Main`:

```
LOAD      write the dataset at 90% of provisioned WCU, measure PutItem
SWITCH    drop WCU to minimum, raise RCU, settle 5 minutes
R-A       strongly consistent GetItem at 90% of provisioned RCU
R-B       eventually consistent GetItem      (skipped where the service has no such path)
BATCH     optional (batchOps=true): BatchGetItem strong + eventual, BatchWriteItem x25,
          TransactWriteItems up to x100 -- latency recorded per call
TEARDOWN  capacity back to minimum, upload artifacts, stop the client
```

### Nothing allocates on the request path

Item payloads are pre-built templates reused across requests, worker threads are created once,
and each completed request is written as a **fixed-width 24-byte binary record** rather than a
formatted line. Formatting text would have meant millions of short-lived Strings inside the
measurement window, and that garbage lands in the tail being measured.

Latencies go to an HdrHistogram, raw and coordinated-omission corrected. It is a
`ConcurrentHistogram` deliberately: a plain `Histogram` is not thread-safe and silently drops
counts under contention — an earlier build lost 9.3% of its samples that way with no error.

### Rate control is denominated in capacity units, not requests

A 59 KiB write costs 59 WCU; a strong read of the same item costs 15 RCU. Pacing on requests per
second would be one arithmetic step removed from what the table is provisioned in, so the limiter
meters capacity directly.

It is lock-free — tokens are a single `AtomicLong` refilled every millisecond, waiters park 50 µs
at a time. A synchronized limiter was rejected on purpose: at thousands of requests/s across
dozens of threads, monitor contention would surface as stalls at exactly the percentile being
measured. **The instrument must not manufacture the tail it is reading.**

On AWS, achieved throughput comes from `ReturnConsumedCapacity=TOTAL` on every response — what
the service actually billed. Oracle never populates it, so there the same figure is derived from
the size model instead, which is exact rather than approximate because every item is a fixed
size.

### The ramp

| State | Behaviour | Exit |
|---|---|---|
| `WARM` | hold 10% of ceiling, let JIT quiesce | 60 s |
| `RAMPING` | advance target 10% at a time, add threads only below 70% CPU | target reaches 90%, minimum 5 min |
| `HOLDING` | steady at 90% of provisioned; the window opens here | window completes |

A throttle anywhere freezes advancement for 60 s and backs the target off. The controller is a
pure function — it reads no clock and owns no threads, taking the current time as an argument —
so a five-minute ramp is verifiable in microseconds in a test.

### Finding partition boundaries with a parallel Scan

To load one slice of partitions hard, you must know which keys live on them. Restricting the
*index* range does not work: DynamoDB assigns partitions by hashing the key, so any subset of key
indices still spreads almost perfectly evenly across every partition.

A parallel `Scan` is different. `Segment`/`TotalSegments` divides the table into equal, contiguous
slices of the **actual hash key space**, so segment *i* of *N* is a real, known slice rather than
a guess at the hash function. The selector reads only the partition key
(`ProjectionExpression=pk`) and runs **once, before any measurement window opens**, so its own
consumed capacity never counts toward a window. A guard aborts the run if it returns far fewer
keys than expected, because silently reading a smaller key set would drive partitions past their
limit and produce throttling that looked like a service problem rather than a harness bug.

The technique comes from [`rhoulihan/dynamodb-office-hours`](https://github.com/rhoulihan/dynamodb-office-hours)
(2020), ported from SDK v1's `ScanSpec` to SDK v2's scan request fields.

### Partition count is a consequence of provisioned capacity

On DynamoDB, partitions are allocated at table-creation time from provisioned capacity and
**never merge**. 40,000 WCU buys 40 partitions at 1,000 WCU/s each — so running at 90% of
provisioned *globally* means running at 90% of **every partition's own ceiling**, which is only
0.47σ of Poisson arrival margin and throttles. Creating the table high to fix the partition
count and then dropping to the test level raises that margin to 1.76σ. `presplitWcu` exists for
exactly this.

---

## Quick start

**For a step-by-step operator guide — prerequisites, quota gates, cost control, reading the
results, teardown verification and troubleshooting — see [`docs/RUNBOOK.md`](docs/RUNBOOK.md).**
This section is the short form.

Requires Java 21+ and Maven.

### AWS

Needs the AWS CLI and an account with a 40,000 WCU/RCU table-level quota in the target region.

```bash
export DDBLAT_CSV=~/Downloads/admin_accessKeys.csv   # IAM console credential CSV
./scripts/00-preflight-quota.sh    # quota gate — blocks everything else until it passes
./scripts/10-provision.sh          # VPC endpoint, security group, IAM role, S3 bucket, EC2 client
./scripts/20-deploy.sh conf/prod.properties
./scripts/30-run.sh
./scripts/40-collect.sh
./scripts/99-teardown.sh --destroy
```

There is **no SSH**: the security group has no ingress rule and no key pair is created. The only
channel to the instance is SSM RunCommand.

### OCI

Needs the `oci` CLI with an API signing key configured as a profile (default `ddblat`). Create
one under **Profile → My profile → API keys** and add the printed block to `~/.oci/config`.

```bash
./scripts/oci/00-preflight.sh      # checks auth, ATP ECPU headroom, compute headroom
./scripts/oci/10-provision.sh      # VCN, subnet, client instance, ADB, DynamoDB API access key
./scripts/oci/20-deploy.sh conf/oci-prod.properties
./scripts/oci/30-run.sh
./scripts/oci/40-collect.sh
./scripts/oci/99-teardown.sh --destroy
```

`10-provision.sh` does everything the API needs, including the parts that are easy to get wrong:
it generates the database admin password itself (stored `0600`, never printed), enables the
DynamoDB API, and mints the access key. No credential is ever echoed.

### Repeated runs

To run the same measurement N times and collect each separately:

```bash
./scripts/50-repeat.sh conf/focused.properties 5
```

It raises capacity once for the whole series and drops it once at the end — DynamoDB allows only
**4 provisioned-capacity decreases per table per UTC day**, then one per hour, so a per-run
teardown strands the table at full capacity partway through a series.

---

## Configuration

| File | Purpose |
|---|---|
| `conf/smoke.properties` | short AWS rehearsal |
| `conf/prod.properties` | the full AWS measurement |
| `conf/focused.properties` | AWS read-only re-run concentrating load onto a subset of partitions |
| `conf/oci-smoke.properties` | short OCI rehearsal |
| `conf/oci-prod.properties` | the full OCI measurement |
| `conf/meli-{370,523,2000,10000,50000}.properties` | MELI PoC: same request rates at five item sizes, all operation types |

Selected keys:

| Key | Meaning |
|---|---|
| `provider` | `aws` (default) or `oci` |
| `ociDatabaseOcid` / `ociKeyFile` | required when `provider=oci` |
| `itemCount` | must be a power of two — the full-period read cycle requires it |
| `presplitWcu` | create the table at this WCU to fix partition count, then drop to `loadWcu` |
| `targetFraction` | fraction of provisioned capacity to drive |
| `readSegmentsTotal` / `readSegmentsUsed` | segment-scoped reads; 0 disables |
| `skipLoad` | read-only re-run against an already-loaded table |
| `manageCapacity` | false hands capacity control to the caller for a multi-run series |
| `itemSize` | bytes per item, exact; defaults to 60,416 (59 KiB) |
| `batchOps` | adds the BatchGetItem / BatchWriteItem / TransactWriteItems phases |
| `batchGetSize` / `batchWriteSize` / `txnItems` | keys per call: ≤100 / ≤25 / ≤100 (transactions also clamp to 4 MB, so 83 at 50 KB) |
| `batchWriteWcu` / `txnWcu` | pacing ceilings for the two batch-write phases; the table holds the larger from SWITCH on |

---

## What differs on Oracle, and why

All of these were measured, not assumed. Each is a capability flag on `Provider`, so a check
that cannot run is skipped explicitly rather than passing vacuously.

| Behaviour | Effect on the harness |
|---|---|
| `ConsumedCapacity` is never populated — the object is returned but its `capacityUnits` field is null | Achieved throughput derived from the size model. Note the shape: checking the object for null is not enough, and missing that throws `NullPointerException` on unboxing, which gets classified as a request failure |
| SigV4 credential scope must be `us-west-2`, whatever region the database is in | Signing region is pinned per provider; every other region returns `401 Invalid credential` |
| No eventually consistent read path — `ConsistentRead=false` returns the same result | The R-B phase is skipped. Running it would re-measure R-A under a misleading label *and* charge it at half cost, inflating achieved throughput |
| Provisioned capacity is capped well below DynamoDB's | Configure to the service's ceiling; the harness's ramp will otherwise never satisfy its 95%-of-target gate and the window will never open |
| `CreateTable` on **Autonomous Database for Developers** returns `CREATING` and silently never creates the table | Provision a non-dev-tier database. `10-provision.sh` does |
| No `ItemCount` / `TableSizeBytes` from `DescribeTable`; larger item ceiling than 400 KB | Size-model probe skipped — nothing to verify predicted capacity against |

---

## Artifacts

Each run produces, locally and in object storage:

| File | Contents |
|---|---|
| `summary.json` | per-phase percentiles, validity verdict, violations, throttles, retries |
| `requests.bin.gz` | every request as a 24-byte record |
| `*.hlog` | HdrHistogram interval logs for post-hoc re-slicing |
| `run.jfr` | JFR flight recording for the whole run |
| `size-model-probe.json` | predicted vs real billed capacity (AWS only) |
| `ddblat.log` | phase transitions, one-second TICK lines, violations, error summaries |

`scripts/percentiles.py` computes exact percentiles straight from `requests.bin.gz`, including
ones the summary does not record. It cross-checks itself against `summary.json`'s HdrHistogram
values and against mean consumed capacity per request.

**The binary records are little-endian**, not the `ByteBuffer` default. Decoding them as
big-endian yields plausible record counts with garbage latencies.

---

## Cost

Capacity dominates on both clouds; the client instance is a rounding error. Tear down with
`--destroy` when finished — every failure path is wrapped so a crash cannot strand a table at
full capacity, but nothing protects against simply forgetting.

---

## Development

```bash
mvn test          # unit and integration tests
mvn package       # shaded jar at target/ddblat.jar
```

Built test-first. Integration tests use Testcontainers against DynamoDB Local; if you run Podman
rather than Docker, `TESTCONTAINERS_RYUK_DISABLED` must be set as an **environment variable** —
the properties-file entry is inert in Testcontainers 1.20.4.

Several real defects in this harness were found only by running it against a live service, none
caught by the test suite or by review: a token-bucket deadlock when burst capacity fell below one
request's cost, results never reaching object storage, a false quota gate, a 0-byte JFR
recording, abandoned in-flight writes on shutdown, a stale checkpoint that silently loaded 7% of
the dataset, and an `ensureCapacity` path that waited ten minutes for a capacity nothing had set.
That last one was latent on AWS for the whole project and only surfaced on the first re-run
against an existing table.

Design spec: [`docs/design-spec.md`](docs/design-spec.md). Operator runbook:
[`docs/RUNBOOK.md`](docs/RUNBOOK.md).

---

## License

MIT — see [LICENSE](LICENSE).
