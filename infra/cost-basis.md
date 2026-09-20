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

The Seoul Live pilot adds 480 Fargate ops launches per day (one area every three minutes). At the
existing 0.5 vCPU / 1 GB rate, an assumed two billable minutes per launch cost
`480 * 2 / 60 * (0.5 * 0.04656 + 0.00511) = 0.45424` USD/day before tax. This is an estimate, not
measured task duration. It starts with the new release, so the 2026-09-21 to 2026-10-25 remainder is
34 days; manual retries, longer runs, log growth and alarm charges are additional. A three-minute
average would add 50% more task cost and exceed the 200 USD envelope below.

## Result

| Window | Pre-tax | With 10% tax |
| --- | ---: | ---: |
| 14 days (plan window) | 64.13 | 70.55 |
| 2026-09-18 to 2026-10-25 (37 days) | 156.03 | 171.63 |
| + second API task for 7 judging days | +5.61 | +6.17 |
| + Seoul Live from 2026-09-21 to 2026-10-25 (34 days, 2 min/run) | +15.44 | +16.99 |
| After shutdown, per month (retained secrets, images, snapshots, logs) | 3.14 | 3.45 |

The operator plan uses **80** for its 14-day window (70.55 rounded up for deploy overlap and drills).
The Seoul schedule would add **7.00** with tax to a 14-day window, leaving approximately 2.45 of that
80 limit. Running to 2026-10-25 with the judging scale-up and Seoul pilot is approximately **195**
with tax under the two-minute assumption. It exceeds the existing **180** operating-plan ceiling and
leaves little of the 200 envelope; the owner must approve a revised budget gate before release, or the
schedule must stay disabled. AWS Budgets are not available here, so the 200 is watched by hand.
