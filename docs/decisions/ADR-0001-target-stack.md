# ADR-0001: 목표 기술 스택과 과거 프로토타입 지위

- 상태: Accepted
- 날짜: 2026-09-04

## Context

과거 작업공간에는 FastAPI + SQLite + React JavaScript prototype과 Next.js/FastAPI 현대화 문서가 있었지만 최신 Figma와 목표 계약은 React PWA + Spring Boot + PostgreSQL을 전제로 한다. 팀은 Frontend 1명, Backend/AI 1명이고 AWS 배포가 목표다. 혼재로 인한 오구현을 막기 위해 원격 이력을 보존하는 목표 저장소에는 현재 서비스만 둔다.

## Decision

- 목표 frontend: React + TypeScript + Vite PWA.
- 목표 backend: Java 21 + Spring Boot 모듈형 모놀리스.
- database: PostgreSQL, schema migration은 Flyway.
- contract: OpenAPI 3.1을 정본으로 TypeScript client 생성.
- infra: AWS CDK(TypeScript), frontend는 S3/CloudFront, API는 ECS Fargate/ALB, DB는 RDS PostgreSQL.
- 과거 FastAPI/React JS 구현은 목표 저장소에 포함하지 않는다. 필요한 실험 지식은 Git 이력/별도 작업공간에서 검토한 뒤 behavior test와 새 계약으로만 이식한다.
- Redis, microservice, 별도 ML service는 P0에 넣지 않는다.
- Node/npm/Java patch, Gradle/Spring/PostgreSQL/generator는 M0 compatibility test가 통과한 exact version을 lock한다. Node는 지원 LTS, Java는 21을 사용하고 floating `latest`에 의존하지 않는다.

## Why

- Vite의 React/TypeScript 생태계는 정적 PWA 배포와 빠른 FE 작업에 적합하다. Vite는 TypeScript를 transpile하지만 type check를 수행하지 않으므로 별도 `tsc --noEmit` CI를 둔다. [Vite 공식 가이드](https://vite.dev/guide/), [Vite 기능 안내](https://vite.dev/guide/features.html)
- Spring Boot는 Java 17 이상을 지원하며 Java 21은 장기 지원 runtime으로 팀 표준화에 적합하다. patch version은 dependency automation과 CI 검증을 통해 갱신한다. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- 2인 팀은 분산 transaction·service discovery·여러 deploy pipeline보다 명확한 module boundary가 있는 단일 API가 관리 가능하다.
- 정적 web과 container API를 분리하면 FE 배포가 빠르고 API의 보안/network 경계가 단순하다.

## Consequences

- 초기 scaffold와 일부 검증 코드는 새로 작성해야 한다.
- 과거 endpoint/data를 자동 호환한다고 가정하지 않는다. 필요한 실험 로직은 test로 옮겨 검증한다.
- FE/BE가 함께 영향을 받는 변경은 OpenAPI를 먼저 합의해야 한다.
- Spring Boot major/third-party compatibility는 scaffold PR에서 BOM과 dependency lock 결과로 확정한다.

## Rejected alternatives

- **기존 FastAPI를 그대로 운영화**: 빠르지만 최신 원격 기준과 backend 담당 기술 선택에 어긋나며, 현재 SQLite/model/API 계약이 Figma 도메인과 다르다.
- **Next.js full-stack**: SSR이 P0 핵심 요구가 아니고 FE/BE 역할 경계와 Spring backend 계획을 흐린다.
- **처음부터 microservice**: 현재 규모에 비해 운영·관측·배포 비용이 크다.
- **Lambda 중심 API**: 가능하지만 초기 cold start, persistence/job 구성, local parity보다 팀이 익숙한 container 단일 service가 적합하다.

## Review trigger

- 팀 구성 또는 backend 역량이 바뀜
- SSR/검색 노출이 핵심 KPI가 됨
- module별 독립 scaling/failure isolation이 수치로 입증됨
- ECS 운영비가 대안 대비 지속적으로 불리함
- Spring/Vite/Java/Node의 지원 종료 또는 critical security advisory로 lock된 조합을 유지할 수 없음
- P0 API 또는 optimizer가 단일 service에서 SLO를 지속적으로 위반함

검토 DRI는 Backend/AI 담당이며 Frontend 담당 승인이 필요하다. M0 dependency lock, M6 production go/no-go와 위 trigger 발생 시 다시 검토한다.
