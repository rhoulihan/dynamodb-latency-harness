# Operator runbook

How to run this harness yourself, on AWS or OCI, from a clean machine.

The [README](../README.md) explains what the harness measures and why. This document is the
sequence you follow. It assumes no prior context — if you can clone the repo and have an account
with capacity, you can produce a valid measurement.

**Read §1 and §8 before you start anything.** §1 is what the run costs, §8 is how to stop paying
for it. Everything between them is the happy path.

**If you cannot run the provisioning scripts** — a locked-down account, no IAM write, an existing
instance you must use, or infrastructure-as-code that owns your estate — skip to
**[§6, Bring your own infrastructure](#6-bring-your-own-infrastructure)**. It states the contract
the harness actually needs, on both clouds, rather than what the scripts happen to build.

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
- **A tenancy with paid Autonomous Database entitlement.** Developer tier does not work — see
  §6.5, which explains what it does instead of failing.

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
DynamoDB-compatible API. **Read §6.5 first** — several behaviours differ from DynamoDB, and two
of them fail silently rather than erroring.

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
**update** (not at create time — see §6.5) and waits out the ten-minute activation.

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

## 6. Bring your own infrastructure

The scripts in §4 and §5 create everything. If you cannot — a locked-down account, no IAM
write, an existing bastion you must use, or infrastructure-as-code that owns your estate — the
harness does not need them. **It needs a table and a machine in the same region.** Everything
else the scripts build is convenience.

This section is the contract: what must exist, what the harness will do to it, and what it costs
you in measurement validity if you deviate.

### 6.1 The minimum

The harness reads a properties file and needs exactly **six** keys. Everything else has a
working default:

```properties
table=my-latency-table
region=us-east-1
itemCount=2097152        # must be a power of two
loadWcu=30000
readRcu=40000
resultsDir=/var/tmp/ddblat-results
```

Run it directly:

```bash
mvn package
java -XX:+UseZGC -Xms24g -Xmx24g -XX:+AlwaysPreTouch      -XX:StartFlightRecording=settings=profile,filename=$PWD/results/run.jfr,maxsize=2G      -jar target/ddblat.jar --config my.properties
```

Heap size should be comfortably above `maxThreads × 8 × 2,730 × 24 bytes` of record buffers plus
the item templates; 24 GiB is what the reference runs used and is generous. `AlwaysPreTouch`
costs about 15 s at startup and keeps page faults out of the measurement window.

**Neither S3 nor EC2 is required.** `s3Bucket` defaults to blank, and a blank bucket skips the
upload silently; `selfStop` defaults to `false`. Results are written to `resultsDir` regardless
— collect them however you like.

### 6.2 The credential contract

At runtime the harness makes exactly seven DynamoDB calls: `CreateTable`, `DescribeTable`,
`UpdateTable`, `PutItem`, `GetItem`, `Scan`, `DeleteTable`. That is the whole API surface. The
minimum policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "dynamodb:CreateTable",
      "dynamodb:DescribeTable",
      "dynamodb:UpdateTable",
      "dynamodb:PutItem",
      "dynamodb:GetItem",
      "dynamodb:Scan"
    ],
    "Resource": "arn:aws:dynamodb:REGION:ACCOUNT:table/YOUR-TABLE"
  }]
}
```

Three optional additions, each buying one feature:

| Add | Enables |
|---|---|
| `s3:PutObject`, `s3:GetObject`, `s3:AbortMultipartUpload` on `bucket/prefix/*` | `s3Bucket` upload of results |
| `ec2:StopInstances` on the instance's **own** ARN | `selfStop=true` |
| `cloudwatch:GetMetricData` (Resource `*` — the API has no resource-level scoping) | the post-hoc `CloudWatchCrossCheck` tool |

`dynamodb:Scan` is needed **only** if you set `readSegmentsTotal` to concentrate reads on a
subset of partitions. `dynamodb:DeleteTable` is used by the test suite, not by a run.

**Credentials resolve through the default AWS provider chain.** An instance profile, a task role,
`AWS_PROFILE`, or environment variables all work. The harness never reads a credential file
itself on the AWS path.

### 6.3 If the table already exists

The harness handles both cases, and the difference matters:

| | |
|---|---|
| **Table absent** | It creates the table at `presplitWcu` (default: `loadWcu`), waits for ACTIVE, then drops to `loadWcu` if they differ |
| **Table exists** | It **does not** re-create or re-shape it. It calls `ensureCapacity` to raise it to the configured level and waits |

**Pre-splitting only works at creation time.** Partitions are allocated from provisioned capacity
when a table is created and never merge, so a table you created at 100 WCU has few partitions
forever — and running a 30,000 WCU load against it will throttle no matter what the config says.
If you are bringing your own table and want the partition behaviour the reference runs had,
create it at 40,000 WCU and let the harness drop it.

Your table must be:

- **Partition key only**, named `pk`, type `S`. No sort key.
- **Provisioned** billing mode — the harness sets RCU/WCU directly, and on-demand has no
  capacity to set.
- **No GSI, LSI, streams or TTL.** None are used; all of them consume capacity and would
  contaminate the measurement.

Set `manageCapacity=false` if you do not want the harness changing capacity at all. It will then
**verify** the table is already at the configured level and fail loudly if it is not — not
managing capacity must not become not checking it, since driving 36,000 RCU/s at a 10-RCU table
throttles from the first request and fills the window with retry latencies.

### 6.4 Your client machine

This is where deviating actually costs you.

| Requirement | Why |
|---|---|
| **Same region as the table. Same AZ if you can** | The reference runs measured TCP connect at 0.9 ms and TLS at 9 ms in-region, against 45 ms and 104 ms from a workstation. A cross-region client measures your network, not the service |
| **Enough cores to not be the bottleneck** | The validity gate fails any window where client CPU exceeds 70%. The reference client was 16 vCPU and peaked near 10% |
| **A VPC endpoint for DynamoDB, ideally** | Keeps traffic off NAT and the internet gateway. Not required, but a NAT gateway in the path is a latency and cost term you did not intend to measure |
| **Java 21+** | |

Check the region assumption before a long run:

```bash
# from the client, against your regional endpoint
for i in 1 2 3; do
  curl -so /dev/null -w "connect=%{time_connect}s tls=%{time_appconnect}s
" \
    https://dynamodb.us-east-1.amazonaws.com/
done
```

Single-digit milliseconds means you are in-region. Tens of milliseconds means you are about to
measure the internet.

### 6.5 Bringing your own OCI infrastructure

Oracle's endpoint is wire-compatible, so the harness talks to it with the same AWS SDK client.
What differs is everything around it — and four of those differences are undocumented and will
cost you an afternoon each if you meet them cold.

#### The database

| Requirement | Consequence if you get it wrong |
|---|---|
| **Transaction Processing** workload | Other workload types do not offer the DynamoDB API |
| **Not** Autonomous Database for Developers | `CreateTable` returns HTTP 200 with `TableStatus=CREATING` and **silently never creates the table.** No error in the response, in work requests, or in `lifecycle-details`. Verified by building an otherwise identical non-dev database, where the same call succeeds in five seconds |
| **Not** in an elastic pool | The API is unavailable |
| Sized to your target capacity | See the ceiling below — this is not a free parameter |

Provision one however your organisation does it. The reference used 64 ECPU with 256 GB of
storage; the CLI form is:

```bash
oci db autonomous-database create \
  --compartment-id "$COMPARTMENT" --db-name ddblatprod --display-name ddblat-adb \
  --db-workload OLTP --compute-model ECPU --compute-count 64 \
  --data-storage-size-in-gbs 256 --license-model LICENSE_INCLUDED \
  --admin-password "$PW" --wait-for-state AVAILABLE
```

Note the absence of `--is-dev-tier`. That is deliberate and it is the single most important flag
on this page.

#### Enabling the DynamoDB API

Apply a free-form tag — **as an update to an existing database, never in the create call:**

```bash
oci db autonomous-database update --autonomous-database-id "$ADB_ID" --force \
  --freeform-tags '{"adb$feature":"{\"name\":\"DynamoDB_API\",\"enable\":true}"}'
```

Lifecycle goes to `UPDATING`, a work request appears, and activation takes **up to ten minutes**.

Passed at create time the tag persists on the resource — `DescribeAutonomousDatabase` shows it —
and fires **no enablement work request at all**. The feature is never provisioned while
everything looks correct. Check for the work request rather than for the tag:

```bash
oci work-requests work-request list --compartment-id "$COMPARTMENT" \
  --query 'data[?contains("operation-type", `Tag`)].{op:"operation-type",status:status}' \
  --output table
```

#### The access key

There is no console UI for this. POST with database Basic auth from a user holding `PDB_DBA`
(normally `ADMIN`):

```bash
curl -sS -X POST \
  "https://dataaccess.adb.${REGION}.oraclecloudapps.com/adb/auth/v1/databases/${ADB_ID}/accesskeys" \
  --user "ADMIN:${DB_PASSWORD}" --header 'Content-Type: application/json' \
  --data-raw '{"name":"ddblat","permissions":[{"actions":["ADMIN_ANY"]}],"expiration_minutes":1440}' \
  > ~/.oci/ddblat-keys.json && chmod 600 ~/.oci/ddblat-keys.json
```

**Run that in your own shell**, not through an agent, a CI job with logging, or a shared
terminal: the request carries the database password and the response body carries the secret.
Redirect it straight to a `0600` file and never echo it.

`expiration_minutes` is optional; omit it for a non-expiring key. `ADMIN_ANY` is the simplest
grant — narrower options are `CREATE_TABLE`, `READ_ANY`, `READ_WRITE_ANY`, and per-table forms.

The response is AWS-shaped:

```json
{"access_key_id": "ak_…", "secret_access_key": "…", "expiration_time": "…"}
```

#### The capacity ceiling — plan around it

**Provisioned capacity caps near 9,000 units, and the cap is cumulative across every table in
the database.** Above it, `CreateTable` returns
`ValidationException: Provisioned throughput exceeds maximum capacity`.

Measured on an **empty** 64-ECPU database: 9,000 accepted; 10,000, 11,000, 12,000, 16,000,
20,000 and 30,000 all rejected. Scaling the database from 48 to 64 ECPUs barely moved the
ceiling, so this is a service cap and not a sizing problem — **you cannot buy past it.**

Two practical consequences:

1. **Size your config to the ceiling.** `loadWcu=9000` and `readRcu=9000` is the reference. A
   config carrying DynamoDB's 40,000 will fail at `CreateTable`.
2. **Delete leftover tables before sizing a new one.** They consume the same budget. A
   half-finished experiment holding 6,000 units leaves you 3,000.

```bash
# what is currently committed
aws dynamodb list-tables --endpoint-url "$ENDPOINT" --region us-west-2   # signing region, see below
```

#### Client configuration

Four keys instead of two:

```properties
provider=oci
region=us-ashburn-1
ociDatabaseOcid=ocid1.autonomousdatabase.oc1.iad.YOUR-OCID
ociKeyFile=/home/opc/ddblat-keys.json
```

The endpoint is derived: `https://dataaccess.adb.{region}.oraclecloudapps.com/adb/keyvaluestore/v1/{ocid}`

**The SigV4 signing region must be `us-west-2`**, whatever region the database lives in. The
harness pins this internally and you should not override it — but if you write your own client
against the same endpoint, this is the first thing to get right. Every other region returns
`401 Invalid credential`, including the database's own, and the error blames the credential, so
the natural fix of re-minting the key never works. Verified against six regions.

#### The client machine

Same requirement as AWS: **in the same region as the database.** The reference used a
`VM.Standard.E5.Flex` with 8 OCPU and 32 GB — 16 vCPU-equivalent, matching the AWS client.

The measured difference is stark. From a workstation over the internet: TCP connect **45 ms**,
TLS **104 ms**. From an instance in `us-ashburn-1` alongside the database: **0.9 ms** and
**9 ms**. A 60 ms P50 measured from a laptop is almost entirely WAN.

```bash
# run this on your client before trusting any number it produces
for i in 1 2 3; do
  curl -so /dev/null -w "connect=%{time_connect}s tls=%{time_appconnect}s\n" \
    "https://dataaccess.adb.${REGION}.oraclecloudapps.com/"
done
```

Unlike the AWS path there is no SSM equivalent in use here — the scripted path reaches the client
over plain SSH on a public subnet with ingress restricted to port 22. If your organisation
requires a bastion or private subnet, nothing in the harness cares; it only needs outbound HTTPS
to the endpoint.

#### Expect a wider thread pool

Worth knowing before you size the client: at Oracle's higher per-request latency the saturation
detector grows the pool. In the reference run the write phase ramped **32 → 48 → 64 → 80**
threads to hold its target, where the AWS run held 458 writes/s on 32.

That is arithmetic, not a defect — a synchronous thread completes `1000 / mean_ms` requests per
second, so 13.5 ms per request yields ~74/s against ~176/s at 5.7 ms. Holding any given offered
rate takes a proportionally wider pool. Leave `maxThreads` at its default of 256 and let the
detector do its work; CPU stayed near 11% throughout.

#### What the harness will not do on OCI

Three validity checks cannot run, and each is recorded as **not applicable** rather than passed:

| | |
|---|---|
| Size-model probe | Oracle reports no consumed capacity, so predicted-vs-billed has nothing to compare against |
| CloudWatch cross-check | No equivalent metric is published |
| Eventually-consistent read phase | **Skipped entirely.** `ConsistentRead=false` returns the same strongly consistent result, so running it would re-measure the strong phase under a misleading label *and* charge it at half cost, inflating achieved throughput 2× |

Achieved throughput is derived from the size model instead of from reported capacity. That is
exact rather than approximate here, because every item is a fixed 59 KiB.

### 6.6 What you give up

Running without the scripts costs you four things. None are fatal; all are worth knowing.

1. **The quota gate does not run.** Nothing checks that your account admits 40,000 capacity units
   before you start a two-hour load. Run `00-preflight-quota.sh` on its own even if you provision
   everything else yourself — it only needs the CLI and reads quotas.
2. **No automatic teardown.** Capacity stays where the run left it. The harness drops the table
   to 10/10 in its own `finally` block, but nothing stops your instance or deletes anything.
3. **No S3 upload unless you configure it.** If the instance is ephemeral, results die with it.
   Set `s3Bucket`, or collect `resultsDir` before terminating.
4. **The CloudWatch cross-check is manual.** It is a post-hoc tool either way, but the scripted
   path leaves the artifacts where it expects them.

### 6.7 Verify your setup before spending two hours

Run the smoke config against your own infrastructure first. It is about a dollar and exercises
every code path a production run uses:

```bash
java -XX:+UseZGC -Xms4g -Xmx4g -jar target/ddblat.jar --config conf/smoke.properties
```

You are looking for three things in the log: `PROVIDER:` naming the right service and endpoint,
`rampState=HOLDING` with `achievedCUs` tracking target, and a phase line reading `valid=true`. If
you get all three, your infrastructure is sound and the production config will behave.

---

## 7. Reading the results

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

## 8. Teardown — do not skip this

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

## 9. Troubleshooting

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

## 10. Changing the test

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

## 11. Getting help from the artifacts

If a run behaves strangely and the log does not explain it, the raw record log usually does. It
holds every request with its start offset, latency, consumed capacity, thread id, phase and
attempt count — so you can reconstruct exactly what happened second by second, per thread, long
after the run ended.

`scripts/percentiles.py` is the worked example of reading it. It is about 60 lines and the
format is documented in `LatencyRecord`.
