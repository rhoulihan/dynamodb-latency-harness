#!/usr/bin/env bash
# 10-provision.sh — VPC gateway endpoint, security group, IAM role + instance
# profile, S3 results bucket, EC2 instance. Roughly six resources (spec §8), all
# recorded in scripts/.provisioned for teardown.
#
# No table is created here. Spec §6 has the Java orchestrator create it, because
# the orchestrator is what knows when the load actually finished and a shell
# script does not.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"
require_preflight

INSTANCE_TYPE="c6in.4xlarge"
VOLUME_GIB=40
ROLE_NAME="ddblat-instance-role"
PROFILE_NAME="ddblat-instance-profile"
POLICY_NAME="ddblat-scoped"
SELFSTOP_POLICY="ddblat-selfstop"
SG_NAME="ddblat-sg"

ACCOUNT_ID=$(query "111122223333" aws sts get-caller-identity --query Account --output text)
BUCKET="ddblat-results-${ACCOUNT_ID}-${AWS_REGION}"
TABLE_ARN_MAIN="arn:aws:dynamodb:${AWS_REGION}:${ACCOUNT_ID}:table/latency-test-100g"
TABLE_ARN_SMOKE="arn:aws:dynamodb:${AWS_REGION}:${ACCOUNT_ID}:table/latency-test-smoke"

state_put REGION      "$AWS_REGION"
state_put ACCOUNT_ID  "$ACCOUNT_ID"
state_put BUCKET      "$BUCKET"
state_put ROLE_NAME   "$ROLE_NAME"
state_put PROFILE_NAME "$PROFILE_NAME"

# ------------------------------------------------------------- VPC / subnet --
VPC_ID=$(query "vpc-0dryrun0000000001" aws ec2 describe-vpcs \
           --filters Name=isDefault,Values=true \
           --query 'Vpcs[0].VpcId' --output text)
[[ "$VPC_ID" != "None" && -n "$VPC_ID" ]] || die "no default VPC in $AWS_REGION"

SUBNET_ID=$(query "subnet-0dryrun000000001" aws ec2 describe-subnets \
              --filters Name=vpc-id,Values="$VPC_ID" Name=default-for-az,Values=true \
              --query 'sort_by(Subnets,&AvailabilityZone)[0].SubnetId' --output text)
[[ "$SUBNET_ID" != "None" && -n "$SUBNET_ID" ]] || die "no default subnet in $VPC_ID"

AZ=$(query "us-east-1a" aws ec2 describe-subnets --subnet-ids "$SUBNET_ID" \
       --query 'Subnets[0].AvailabilityZone' --output text)

# The route table the endpoint must be attached to is the one associated with
# this subnet; a default subnet usually has no explicit association, in which
# case it inherits the VPC main route table.
RTB_ID=$(query "rtb-0dryrun0000000001" aws ec2 describe-route-tables \
           --filters Name=association.subnet-id,Values="$SUBNET_ID" \
           --query 'RouteTables[0].RouteTableId' --output text)
if [[ "$RTB_ID" == "None" || -z "$RTB_ID" ]]; then
  RTB_ID=$(query "rtb-0dryrun0000000001" aws ec2 describe-route-tables \
             --filters Name=vpc-id,Values="$VPC_ID" Name=association.main,Values=true \
             --query 'RouteTables[0].RouteTableId' --output text)
fi
[[ "$RTB_ID" != "None" && -n "$RTB_ID" ]] || die "could not resolve a route table for $SUBNET_ID"

log "vpc=$VPC_ID subnet=$SUBNET_ID az=$AZ rtb=$RTB_ID"
state_put VPC_ID "$VPC_ID"; state_put SUBNET_ID "$SUBNET_ID"
state_put AZ "$AZ";         state_put RTB_ID "$RTB_ID"

# ------------------------------------------------- DynamoDB gateway endpoint --
# Gateway, not interface: free, no ENI, no per-GB charge, and it keeps the
# DynamoDB traffic off the IGW entirely (spec §8). SSM and S3 still egress via
# the default VPC's internet gateway, which is what the public IP is for.
EP_ID=$(query "vpce-0dryrun000000001" aws ec2 describe-vpc-endpoints \
          --filters Name=vpc-id,Values="$VPC_ID" \
                    Name=service-name,Values="com.amazonaws.${AWS_REGION}.dynamodb" \
          --query 'VpcEndpoints[0].VpcEndpointId' --output text)
if [[ "$EP_ID" == "None" || -z "$EP_ID" ]]; then
  EP_ID=$(query "vpce-0dryrun000000001" aws ec2 create-vpc-endpoint \
            --vpc-id "$VPC_ID" \
            --service-name "com.amazonaws.${AWS_REGION}.dynamodb" \
            --vpc-endpoint-type Gateway \
            --route-table-ids "$RTB_ID" \
            --tag-specifications 'ResourceType=vpc-endpoint,Tags=[{Key=project,Value=ddblat}]' \
            --query 'VpcEndpoint.VpcEndpointId' --output text)
  log "created DynamoDB gateway endpoint $EP_ID on $RTB_ID"
else
  log "reusing existing DynamoDB gateway endpoint $EP_ID"
  run aws ec2 modify-vpc-endpoint --vpc-endpoint-id "$EP_ID" --add-route-table-ids "$RTB_ID" \
    || log "route table $RTB_ID already attached to $EP_ID"
fi
state_put VPCE_ID "$EP_ID"

# ---------------------------------------------------------- security group ---
# Egress-all, ingress-none. There is no SSH and no key pair: the only inbound
# path to this host is SSM, which the agent establishes outbound (spec §9).
SG_ID=$(query "sg-0dryrun00000000001" aws ec2 describe-security-groups \
          --filters Name=vpc-id,Values="$VPC_ID" Name=group-name,Values="$SG_NAME" \
          --query 'SecurityGroups[0].GroupId' --output text)
if [[ "$SG_ID" == "None" || -z "$SG_ID" ]]; then
  SG_ID=$(query "sg-0dryrun00000000001" aws ec2 create-security-group \
            --group-name "$SG_NAME" \
            --description "ddblat harness: egress only, access via SSM" \
            --vpc-id "$VPC_ID" \
            --tag-specifications 'ResourceType=security-group,Tags=[{Key=project,Value=ddblat}]' \
            --query 'GroupId' --output text)
  log "created security group $SG_ID (default egress-all, no ingress rules added)"
else
  log "reusing security group $SG_ID"
fi
state_put SG_ID "$SG_ID"

# ------------------------------------------------------------- S3 bucket -----
if query "false" aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  log "reusing bucket s3://$BUCKET"
else
  # us-east-1 is the one region where create-bucket must NOT get a location constraint.
  run aws s3api create-bucket --bucket "$BUCKET"
  run aws s3api put-public-access-block --bucket "$BUCKET" \
      --public-access-block-configuration \
      BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  run aws s3api put-bucket-encryption --bucket "$BUCKET" \
      --server-side-encryption-configuration \
      '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
  log "created bucket s3://$BUCKET"
fi

# -------------------------------------------------- IAM role + instance profile
TRUST=$(mktemp -t ddblat-trust); POLICY=$(mktemp -t ddblat-policy)
trap 'rm -f "$TRUST" "$POLICY"' EXIT

cat > "$TRUST" <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "ec2.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
EOF

# CreateTable is present because spec §6 has the orchestrator create the table.
# GetMetricData is Resource "*" because CloudWatch GetMetricData does not support
# resource-level permissions -- it is scoped by action only.
# GetObject/ListBucket are present because deployment stages through this bucket.
# Scan is present for SegmentKeySelector only: a parallel Scan's Segment/TotalSegments is
# the one way to learn which keys live in which slice of the hash key space, which is what
# the focused-partition read run needs. Nothing in the measured phases scans -- the selector
# runs once, before any measurement window opens.
cat > "$POLICY" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "TableOps",
      "Effect": "Allow",
      "Action": [
        "dynamodb:CreateTable",
        "dynamodb:DescribeTable",
        "dynamodb:UpdateTable",
        "dynamodb:PutItem",
        "dynamodb:GetItem",
        "dynamodb:Scan"
      ],
      "Resource": ["$TABLE_ARN_MAIN", "$TABLE_ARN_SMOKE"]
    },
    {
      "Sid": "CloudWatchCrossCheck",
      "Effect": "Allow",
      "Action": ["cloudwatch:GetMetricData"],
      "Resource": "*"
    },
    {
      "Sid": "ResultsAndDeployStaging",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:AbortMultipartUpload"],
      "Resource": [
        "arn:aws:s3:::$BUCKET/results/*",
        "arn:aws:s3:::$BUCKET/deploy/*"
      ]
    },
    {
      "Sid": "ListForSync",
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::$BUCKET",
      "Condition": { "StringLike": { "s3:prefix": ["results/*", "deploy/*"] } }
    }
  ]
}
EOF

if query "false" aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  log "reusing IAM role $ROLE_NAME"
else
  run aws iam create-role --role-name "$ROLE_NAME" \
      --assume-role-policy-document "file://$TRUST" \
      --description "ddblat latency harness instance role" \
      --tags Key=project,Value=ddblat
fi
run aws iam put-role-policy --role-name "$ROLE_NAME" \
    --policy-name "$POLICY_NAME" --policy-document "file://$POLICY"
run aws iam attach-role-policy --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore

if query "false" aws iam get-instance-profile --instance-profile-name "$PROFILE_NAME" >/dev/null 2>&1; then
  log "reusing instance profile $PROFILE_NAME"
else
  run aws iam create-instance-profile --instance-profile-name "$PROFILE_NAME"
  run aws iam add-role-to-instance-profile \
      --instance-profile-name "$PROFILE_NAME" --role-name "$ROLE_NAME"
  log "waiting 15s for IAM instance profile propagation"
  run sleep 15
fi

# ------------------------------------------------------------- EC2 instance --
IID=$(query "i-0dryrun00000000001" aws ec2 describe-instances \
        --filters Name=tag:project,Values=ddblat \
                  Name=instance-state-name,Values=pending,running,stopping,stopped \
        --query 'Reservations[0].Instances[0].InstanceId' --output text)

if [[ "$IID" == "None" || -z "$IID" ]]; then
  AMI_ID=$(query "ami-0dryrun00000000001" aws ssm get-parameter \
             --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
             --query 'Parameter.Value' --output text)
  log "AL2023 AMI: $AMI_ID"

  UD=$(mktemp -t ddblat-userdata)
  cat > "$UD" <<'EOF'
#!/bin/bash
set -euo pipefail
exec > >(tee -a /var/log/ddblat-bootstrap.log) 2>&1

# Corretto 25 is not in the AL2023 repos; add the Corretto repo for it. The
# -headless package is the JDK without GUI libraries -- it carries `java`,
# `jcmd` and `jfr`, which is everything the run needs. The harness jar is built
# on the Mac and shipped here, so no Maven and no compiler are installed.
rpm --import https://yum.corretto.aws/corretto.key
curl -fsSL -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
dnf install -y java-25-amazon-corretto-headless

mkdir -p /opt/ddblat/results /var/lib/ddblat
chmod 0755 /opt/ddblat

systemctl enable --now amazon-ssm-agent

java -version
touch /opt/ddblat/.bootstrap-complete
echo "bootstrap complete"
EOF

  IID=$(query "i-0dryrun00000000001" aws ec2 run-instances \
          --image-id "$AMI_ID" \
          --instance-type "$INSTANCE_TYPE" \
          --subnet-id "$SUBNET_ID" \
          --security-group-ids "$SG_ID" \
          --associate-public-ip-address \
          --iam-instance-profile "Name=$PROFILE_NAME" \
          --metadata-options 'HttpEndpoint=enabled,HttpTokens=required,HttpPutResponseHopLimit=1' \
          --block-device-mappings \
            "[{\"DeviceName\":\"/dev/xvda\",\"Ebs\":{\"VolumeSize\":$VOLUME_GIB,\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
          --user-data "file://$UD" \
          --tag-specifications \
            'ResourceType=instance,Tags=[{Key=Name,Value=ddblat},{Key=project,Value=ddblat}]' \
            'ResourceType=volume,Tags=[{Key=project,Value=ddblat}]' \
          --query 'Instances[0].InstanceId' --output text)
  # Record the id THE INSTANT it exists. run-instances has already created a
  # real, billing c6in.4xlarge; nothing that can fail -- including the
  # instance-running wait a few lines down -- may sit between this line and
  # the state file, or a wait timeout leaves an orphan teardown can't find.
  state_put INSTANCE_ID "$IID"
  state_put INSTANCE_ARN "arn:aws:ec2:${AWS_REGION}:${ACCOUNT_ID}:instance/${IID}"
  rm -f "$UD"
  log "launched $IID ($INSTANCE_TYPE, ${VOLUME_GIB} GiB gp3)"
  run aws ec2 wait instance-running --instance-ids "$IID"
else
  log "reusing instance $IID"
  state_put INSTANCE_ID "$IID"
  state_put INSTANCE_ARN "arn:aws:ec2:${AWS_REGION}:${ACCOUNT_ID}:instance/${IID}"
fi

# ---------------------------------------------- self-stop, scoped to this box --
# Written as a second inline policy only now, because the instance ARN does not
# exist until the instance does. Spec §9: exactly one resource ARN, its own.
SELFSTOP=$(mktemp -t ddblat-selfstop)
cat > "$SELFSTOP" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "StopOnlyMyself",
    "Effect": "Allow",
    "Action": "ec2:StopInstances",
    "Resource": "arn:aws:ec2:${AWS_REGION}:${ACCOUNT_ID}:instance/${IID}"
  }]
}
EOF
run aws iam put-role-policy --role-name "$ROLE_NAME" \
    --policy-name "$SELFSTOP_POLICY" --policy-document "file://$SELFSTOP"
rm -f "$SELFSTOP"
log "self-stop policy scoped to $IID"

# --------------------------------------------------------------- readiness ----
log "waiting for the SSM agent to register (up to 5 min)"
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  for _ in $(seq 1 60); do
    ping=$(aws ssm describe-instance-information \
             --filters "Key=InstanceIds,Values=$IID" \
             --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || echo None)
    if [[ "$ping" == "Online" ]]; then break; fi
    sleep 5
  done
  [[ "$ping" == "Online" ]] || die "instance $IID never came Online in SSM"
fi

echo
echo "Provisioned. scripts/.provisioned:"
if [[ -f "$STATE_FILE" ]]; then cat "$STATE_FILE"; fi
