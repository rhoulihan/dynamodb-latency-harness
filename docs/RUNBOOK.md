# Operator runbook

How to run this harness yourself, on AWS or OCI, from a clean machine.

The [README](../README.md) explains what the harness measures and why. This document is the
sequence you follow. It assumes no prior context — if you can clone the repo and have an account
with capacity, you can produce a valid measurement.

**Read §1 and §7 before you start anything.** §1 is what the run costs, §7 is how to stop paying
for it. Everything between them is the happy path.

---

## 1. Before you begin — money, and the one rule

**Provisioned capacity dominates the cost. The client instance is a rounding error.**

| Resource | Approximate | Notes |
|---|---|---|
| DynamoDB 40,000 RCU | ~$5.20/hr | read phases |
| DynamoDB 40,000 WCU | ~$26/hr | load phase — the expensive one |
| `c6in.4xlarge` client | ~$0.91/hr | |
| Oracle ADB, 64 ECPU | ~$21/hr | plus storage |
| OCI `E5.Flex` 8 OCPU client | ~$0.32/hr | |

A full AWS production run is roughly **$41** and takes about 2h25m. A five-run read series is
about **$37** over five hours. An OCI production run is in the same range.

> **The rule: every path out of a run must lower capacity.** The harness wraps every failure
> path so a crash cannot strand a table at full capacity, and the client self-stops when
> configured to. Neither protects against forgetting to run teardown. Set a calendar reminder if
> you are running overnight.

**Start with the smoke config.** It costs about a dollar and exercises the entire pipeline —
provision, deploy, run, collect, teardown. If the smoke run does not produce a valid window, the
production run will not either, and it will cost forty times as much to discover that.

---

## 2. Prerequisites

### Both clouds

| | |
|---|---|
| Java | 21 or newer (`java -version`) |
| Maven | 3.9+ (`mvn -v`) |
| Python | 3.9+ — used by the scripts for credential handling and by `scripts/percentiles.py` |
| Bash | the scripts are bash, not sh. On macOS the system bash is fine |
| git | to clone |

```bash
git clone https://github.com/rhoulihan/dynamodb-latency-harness.git
cd dynamodb-latency-harness
mvn test          # ~5 minutes, should report BUILD SUCCESS
```

Run the test suite first. It needs no cloud account and it tells you the toolchain is sound
before you spend anything.

### AWS only

- **aws-cli v2.** v1 is rejected by the preflight gate.
- **An IAM credential CSV** downloaded from the console — the file with `Access key ID` and
  `Secret access key` columns. Default location `~/Downloads/admin_accessKeys.csv`, override with
  `DDBLAT_CSV`.
- **Quota for 40,000 capacity units.** This is the one that blocks people, see §3.

### OCI only

- **oci-cli.** `brew install oci-cli` or Oracle's installer.
- **An API signing key** configured as a profile in `~/.oci/config`. See §5.1 — the console gives
  you the config block to paste.
- **A tenancy with paid Autonomous Database entitlement.** Developer tier does not work; see §8.

---

## 3. AWS — the quota gate

**Do this first. Nothing else runs until it passes, and a quota increase can take a day.**

The harness needs **40,000** capacity units, which is exactly the default per-table *and*
per-account cap. The preflight checks four quotas:

| Code | Quota |
|---|---|
| `L-CF0CBE56` | Table-level read throughput (RCU per table) |
| `L-AB614373` | Table-level write throughput (WCU per table) |
| `L-34F6A552` | Account-level read throughput (provisioned) |
| `L-34F8CCC8` | Account-level write throughput (provisioned) |

```bash
export DDBLAT_CSV=~/Downloads/admin_accessKeys.csv
./scripts/00-preflight-quota.sh
```

It writes an `aws` profile named `ddblat` from the CSV — the secret goes through stdin, never
argv — prints every DynamoDB capacity quota as applied to your account, and creates
`scripts/.preflight-ok` only if all four admit 40,000.

**If it fails**, raise the quotas in the Service Quotas console and re-run. The account-level
ones are often already higher than the table-level ones; check what the output actually says
rather than assuming.

**Dry run.** Every AWS script honours `DRY_RUN=1`, which prints the exact `aws` commands without
executing them:

```bash
DRY_RUN=1 ./scripts/10-provision.sh
```

Worth doing once on provision, to see what will be created in your account before it is.

---

## 4. AWS — the run

### 4.1 Provision

```bash
./scripts/10-provision.sh
```

Creates: a VPC gateway endpoint for DynamoDB, a security group, an IAM role and instance profile,
an S3 bucket for results, and a `c6in.4xlarge` instance with a 40 GiB gp3 root volume, in the
same AZ the table will live in. State lands in `scripts/.provisioned`.

Two deliberate properties worth knowing:

- **There is no SSH.** No key pair is created and the security group has no ingress rule. The only
  channel to the instance is SSM RunCommand. If you need a shell:
  `aws ssm start-session --target <instance-id>`.
- **The admin credential never leaves your machine.** It is used locally to provision; the
  instance authenticates with its instance profile, scoped to specific table ARNs with
  `ec2:StopInstances` restricted to its own ARN.

### 4.2 Deploy, run, collect

```bash
./scripts/20-deploy.sh conf/smoke.properties   # build the shaded jar, stage via S3 + SSM
./scripts/30-run.sh                            # start detached on the instance
./scripts/40-collect.sh                        # wait for DONE, pull artifacts down
```

`20-deploy.sh` verifies the jar's SHA-256 on the instance matches the local one before
proceeding. `30-run.sh` prints the commands to tail the log; the useful one is:

```bash
aws ssm start-session --target <instance-id>
# then: tail -f /var/log/ddblat.log
```

`40-collect.sh` polls S3 for the `DONE` marker — not the instance — because the instance
self-stops after writing it. Results land in `results/<run-id>/`.

### 4.3 What a healthy run looks like

Watch the one-second `TICK` lines. In the measurement window you want:

```
TICK phase=R-A-strong rampState=HOLDING target=36000.00 achievedCUs=35993.00
     threads=32 completed=2881213 retried=0 misses=0 cpu=0.09 windowOpen=true
```

- `rampState=HOLDING` and `achievedCUs` within 2% of `target`
- `misses=0` — if this climbs, the table is not loaded and your latencies are meaningless
- `cpu` well under 0.70 — above it the window fails and you are measuring your client
- `retried` near zero

Phase transitions print plainly: `LOAD: valid=true p50=… p99=…`, then `SWITCH`, then each read
phase. A window that fails prints its violations.

### 4.4 Repeated runs

A single run's tail is not a result. Strong-read P99 varied **12.3%** across five identical runs
on one machine against one dataset.

```bash
./scripts/50-repeat.sh conf/focused.properties 5
```

This raises capacity once for the whole series and lowers it once at the end. **That matters:**
DynamoDB permits only **4 provisioned-capacity decreases per table per UTC day**, then one per
hour. A per-run teardown exhausts them and strands the table at full capacity mid-series. The
script forces `selfStop=false`, aborts after two consecutive failures, and traps EXIT/INT/TERM to
park capacity and stop the instance on any exit path.

Each run gets its own directory under `results/series-*/`.

---

## 5. OCI — the run

The same harness, the same workload, pointed at Oracle Autonomous AI Database's
DynamoDB-compatible API. Read §8 first: several behaviours differ and two of them will silently
waste your afternoon.

### 5.1 Credentials — two different ones

This trips people up. You need **two** credentials that do different jobs:

| Credential | Used by | For |
|---|---|---|
| **OCI API signing key** (RSA) | `oci` CLI | provisioning — create the database, instance, network |
| **ADB access key/secret** | the AWS SDK | the benchmark traffic itself |

The signing key you create yourself: **Profile → My profile → API keys → Add API key**. Generate
the pair locally, paste the *public* half, and copy the config block the console prints into
`~/.oci/config` under a profile named `ddblat`.

```bash
openssl genrsa -out ~/.oci/ddblat_api_key.pem 2048
chmod 600 ~/.oci/ddblat_api_key.pem
openssl rsa -pubout -in ~/.oci/ddblat_api_key.pem   # paste this into the console
```

**The ADB access key is minted for you** by `10-provision.sh`. You never handle it.

### 5.2 Provision and run

```bash
./scripts/oci/00-preflight.sh    # auth, ATP ECPU headroom, compute headroom
./scripts/oci/10-provision.sh    # VCN, subnet, instance, ADB, DynamoDB API, access key
./scripts/oci/20-deploy.sh conf/oci-smoke.properties
./scripts/oci/30-run.sh
./scripts/oci/40-collect.sh
```

`10-provision.sh` generates the database ADMIN password itself, stores it `0600` at
`~/.oci/ddblat-adb-admin.txt`, and never prints it. It enables the DynamoDB API via a tag
**update** (not at create time — see §8) and waits out the ten-minute activation.

Tunable with environment variables, all with working defaults:

| Variable | Default | |
|---|---|---|
| `ECPU` | 64 | database compute |
| `STORAGE_GB` | 256 | |
| `OCPUS` / `MEM_GB` | 8 / 32 | client instance |
| `OCI_REGION` | `us-ashburn-1` | |
| `OCI_PROFILE` | `ddblat` | |

Unlike the AWS side, the OCI client is reached over **SSH** on a public subnet, with a key the
pipeline generates at `~/.ssh/ddblat_oci`. Ingress is port 22 only.

```bash
ssh -i ~/.ssh/ddblat_oci opc@<instance-ip> 'tail -f ~/ddblat.log'
```

**The OCI scripts do not support `DRY_RUN`.** Read them before running if that matters to you.

---

## 6. Reading the results

Each run directory holds:

| File | |
|---|---|
| `summary.json` | per-phase percentiles, validity verdict, violations, throttles, retries, misses |
| `requests.bin.gz` | every request as a 24-byte record |
| `*.hlog` | HdrHistogram interval logs, for re-slicing by time |
| `run.jfr` | JFR flight recording |
| `size-model-probe.json` | predicted vs billed capacity (AWS only) |
| `ddblat.log` | phase transitions, TICK lines, violations, error summaries |

**Check validity before you quote anything:**

```bash
python3 -c "
import json; d=json.load(open('results/<run-id>/summary.json'))
for p in d['phases']:
    r=p['raw']
    print(f\"{p['name']:<14} valid={p['valid']} n={r['count']:>9,} \"
          f\"p50={r['p50']/1000:>7.2f} p99={r['p99']/1000:>7.2f} ms\")
    if p['violations']: print('   ', p['violations'])
"
```

`valid=false` means the window broke one of the seven criteria. The numbers are real
measurements, but they were taken under conditions that make them unquotable — the violation
names which one.

For percentiles the summary does not record, or to verify it:

```bash
python3 scripts/percentiles.py results/<run-id>
```

It reproduces every summary percentile within 0.085% (HdrHistogram quantises to three
significant digits) and cross-checks mean consumed capacity per request. **The binary records are
little-endian** — decoding them as big-endian yields plausible record counts with garbage
latencies.

---

## 7. Teardown — do not skip this

```bash
./scripts/99-teardown.sh              # AWS: RCU to 10, stop the instance
./scripts/99-teardown.sh --destroy    # AWS: also delete table, bucket, endpoint, SG, IAM role

./scripts/oci/99-teardown.sh          # OCI: scale ADB to 2 ECPU, stop the instance
./scripts/oci/99-teardown.sh --destroy
```

The default form parks the expensive resources and **keeps the loaded table**, which is what you
want between runs of a series — reloading 118 GiB costs about 76 minutes. `--destroy` leaves
nothing.

Capacity is lowered **before** the instance is stopped, deliberately: 40,000 RCU is ~$5.20/hr
against the instance's ~$0.91/hr, so a later step failing must not leave the pricier resource
running.

**Then verify, rather than trusting the exit code.** A teardown that reports success while
leaving resources behind is a real failure mode — it happened on this project's first OCI
teardown, where a route rule referencing the gateway blocked its deletion and the failure was
swallowed by `>/dev/null 2>&1`.

```bash
# AWS
aws dynamodb list-tables --profile ddblat --region us-east-1
aws ec2 describe-instances --profile ddblat --region us-east-1 \
  --filters "Name=instance-state-name,Values=running,stopped" \
  --query 'Reservations[].Instances[].InstanceId'
aws s3api list-buckets --profile ddblat --query 'Buckets[].Name'

# OCI
oci db autonomous-database list --compartment-id <tenancy> --profile ddblat --all \
  --query 'data[]."lifecycle-state"'
oci compute instance list --compartment-id <tenancy> --profile ddblat --all \
  --query 'data[?"lifecycle-state"!=`TERMINATED`]."display-name"'
```

Empty is what you want. Also check **EBS volumes, snapshots and AMIs** on AWS — those survive an
instance termination and are the usual thing left billing.

---

## 8. Troubleshooting

Every entry here is something that actually happened, with the diagnosis that resolved it.

### The run "hangs" with `achievedCUs=0.00` and `hits=0`

Requests are failing, not stalling. Check the error summary:

```bash
grep WORKLOAD-ERROR <run-dir>/ddblat.log
```

The harness logs the first occurrence of each distinct failure, de-duplicated, with a per-phase
count. If nothing appears there, the failures are upstream of the workload — check the phase
lines at the top of the log.

### Quota gate fails on a compliant account

Check the quota *codes* in the output against §3. An earlier version of this script used wrong
codes and produced a false failure against an account that was fine.

### The window never opens; the ramp keeps blocking

The service cannot reach the target rate, so the ramp never satisfies its 95%-of-target gate.
Look at the best `achievedCUs` reached and size `loadWcu`/`readRcu` to what the service actually
delivers. This is the normal experience on a small Oracle ADB.

### `LOAD: valid=false — work exhausted before the measurement window opened`

`itemCount` is too small for the configured rate: the dataset finished writing before the ramp
completed. Raise `itemCount` or lower the rate.

### A re-run blocks for ten minutes doing nothing

Fixed in `1b34f71`, but if you are on older code: `createIfAbsent` is a no-op on an existing
table, so nothing ever set the capacity the run was waiting for. Current code uses
`TableAdmin.ensureCapacity`.

### `LimitExceededException: Provisioned throughput decreases are limited`

You have used the four daily decreases. Wait for the hourly window, and use `50-repeat.sh` for
series rather than tearing down per run.

### Reads return misses and the latencies look implausibly good

The table is not loaded. A miss costs ~1 RCU against 15 for a hit, so the numbers are fast,
plausible and meaningless. The harness fails the window at a 0.01% miss rate — trust that gate.
If you are resuming, check `checkpointFile`; a stale checkpoint once caused a run to load 7% of
its dataset and report a full set of numbers.

### OCI — `401 Invalid credential` with a freshly minted key

**The SigV4 signing region must be `us-west-2`**, whatever region the database is in. Every other
region returns this error, including the database's own. The harness pins it; if you are writing
your own client, this is the first thing to check.

### OCI — `CreateTable` returns `CREATING` and the table never appears

You are on **Autonomous Database for Developers**. It returns success and creates nothing, with
no error in the response, work requests, or `lifecycle-details`. Provision a non-dev-tier
database — `10-provision.sh` does not pass `--is-dev-tier`.

### OCI — `ValidationException: Provisioned throughput exceeds maximum capacity`

The API caps provisioned capacity near **9,000 units**, cumulative across tables, and scaling the
database barely moves it. Size your config to the ceiling and delete leftover tables first —
they consume the same budget.

### OCI — the DynamoDB API never activates

The `adb$feature` tag must be applied as an **update** to an existing database. Passed in the
create call it persists on the resource and fires no enablement work request.

### Podman instead of Docker for the test suite

`TESTCONTAINERS_RYUK_DISABLED` must be set as an **environment variable**. The properties-file
entry is inert in Testcontainers 1.20.4.

---

## 9. Changing the test

| Want to | Change |
|---|---|
| Different item size | `ItemSizeModel.ITEM_SIZE` — and re-derive `DATASET_BYTES`. The size model is verified against billed capacity, so it must stay exact |
| Different dataset size | `itemCount` — **must be a power of two**, the full-period read cycle requires it |
| Different capacity | `loadWcu` / `readRcu`, and `presplitWcu` on AWS to fix partition count |
| Concentrate reads on fewer partitions | `readSegmentsTotal` / `readSegmentsUsed`; 0 disables |
| Re-read an already-loaded table | `skipLoad=true` |
| Drive a multi-run series | `manageCapacity=false` and let `50-repeat.sh` own capacity |
| Shorter windows for a rehearsal | `windowMinutes`, `minRampMinutes`, `warmSeconds` |

Every key is documented inline in the `conf/*.properties` files, usually with the reasoning for
its value.

**If you change the workload, re-run the test suite.** Several invariants are enforced there
rather than at runtime — the power-of-two item count, the size model's exactness, and the
capacity arithmetic among them.

---

## 10. Getting help from the artifacts

If a run behaves strangely and the log does not explain it, the raw record log usually does. It
holds every request with its start offset, latency, consumed capacity, thread id, phase and
attempt count — so you can reconstruct exactly what happened second by second, per thread, long
after the run ended.

`scripts/percentiles.py` is the worked example of reading it. It is about 60 lines and the
format is documented in `LatencyRecord`.
