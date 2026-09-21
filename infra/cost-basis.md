# Nullnull staging cost basis

Input to the operator cost gate (`staging_operator.py plan --cost-basis`). It is an approved estimate,
not a billing reading: this account is an AWS Organizations member whose SCP denies `budgets:*` and
`ce:GetCostAndUsage`, so no Budget or Cost Explorer check exists. The owner reads actual spend in the
organization's billing view.

## Unit prices (AWS Price List, ap-northeast-2)

| Item | Price (USD) | Publication |
| --- | --- | --- |
| Fargate vCPU / GB (Linux x86) | 0.04656 per vCPU-hour, 0.00511 per GB-hour | 2026-09-11 |
| RDS PostgreSQL `db.t4g.micro` Multi-AZ | 0.051 per hour | 2026-09-17 |
| RDS gp3 Multi-AZ storage | 0.262 per GB-month | 2026-09-17 |
| RDS T4g CPU credits (surplus) | 0.075 per vCPU-hour | 2026-09-17 |
| Application Load Balancer | 0.0225 per hour, 0.008 per LCU-hour | 2026-09-11 |
| Secrets Manager | 0.40 per secret-month | 2026-09-11 |
| CloudWatch Logs ingestion / alarms | 0.76 per GB, 0.10 per alarm-month | 2026-09-15 |
| ECR storage | 0.10 per GB-month | 2026-09-11 |
| Public IPv4 | 0.005 per address-hour | AWS VPC pricing |
| CloudFront (Korea) / WAF | 0.120 per GB, 0.0120 per 10k HTTPS requests / 5 per ACL-month + 1 per rule-month | published list |

## Assumptions (not prices)

API 0.5 vCPU / 1 GB x1, AI 0.25 vCPU / 0.5 GB x1, two public IPv4, ALB 1 LCU average, 20 GB gp3,
6 secrets, 3 alarms, WAF 2 rules, logs 0.1 GB/day, CloudFront 1 GB and 50k requests/day, CPU-credit
reserve 5, miscellaneous reserve 3 (Route 53 private zone for Cloud Map, S3, DynamoDB, Lambda, deploy
overlap), tax 10%. Free tier counted as zero.

The Seoul Live pilot refreshes one area inside the existing API task every five minutes. Its
cross-replica claim adds no recurring Fargate task. At most three transport attempts per refresh
mean up to 864 Seoul proxy calls per day; this fits the project's configured 1,000/day source
budget, which is not a verified provider quota. The proxy Lambda calls, logs and two new alarms
still consume the miscellaneous reserve. Actual spend remains an operator observation, not a gate
result.

## Result

| Window | Pre-tax | With 10% tax |
| --- | ---: | ---: |
| 14 days (plan window) | 64.13 | 70.55 |
| 2026-09-18 to 2026-10-25 (37 days) | 156.03 | 171.63 |
| + second API task for 7 judging days | +5.61 | +6.17 |
| After shutdown, per month (retained secrets, images, snapshots, logs) | 3.14 | 3.45 |

The operator plan uses **80** for its 14-day window (70.55 rounded up for deploy overlap and drills).
Running to 2026-10-25 with the judging scale-up is about **178** with tax, which leaves little of the
200 envelope; a restore drill, a second environment or sustained traffic above the assumptions must be
re-estimated first. AWS Budgets are not available here, so the 200 is watched by hand.
