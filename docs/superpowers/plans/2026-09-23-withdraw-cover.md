---
aliases:
  - "게시물 표지 회수 구현 계획"
doc_type: plan
status: draft
area: operations
tags:
  - nullnull/operations
  - nullnull/security
---

# Versioned User Cover Withdrawal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An approved withdraw-post operation hides one post and removes every S3 version of only its uploaded cover, with truthful partial-failure reporting and safe retries.

**Architecture:** The existing DB transaction returns the hidden post's cover reference. After commit, the ops entry point validates that reference against the configured public origin and exact user-cover key pattern, then lists and deletes every version and delete marker for that key. Only the ops task receives version-deletion authority; the online API retains its existing PutObject-only user-cover scope.

**Tech Stack:** Java 21/Spring Boot, PostgreSQL, AWS SDK v2 S3, AWS CDK TypeScript, Python operator CLI, React Query/service worker.

**Spec:** docs/superpowers/specs/2026-09-23-withdraw-cover-design.md

## Global Constraints

- Keep the current owner approval, exact post ID, target DB, and release-binding gates before any DB or S3 mutation.
- Only a canonical URL at the deployed public origin and a `covers/user/{upload UUID}.(jpg|png)` key can lead to deletion; these are ImageFormat's current output extensions.
- A failed S3 step must leave the post HIDDEN and make the task fail visibly; rerunning the same approved post ID must converge.
- Never print cover URL, S3 key, image bytes, token, cookie, or raw user text in operator logs.
- Do not grant DeleteObjectVersion to the API service role or broad S3 deletion to the operator role.
- Do not close CloudFront traffic for deployment. Reconcile the current release record/image digest mismatch before running release-bound curation or withdrawal tasks.
- Existing browser and device caches cannot be recalled; document this accurately.

## Review Focus

- A cover URL with a different host or escaped path must fail closed before any S3 request (Task 4 test).
- A key that is a prefix of another key must not delete the neighbor (Task 4 test).
- Partial S3 deletion must return failure and leave remaining versions for the next attempt (Task 4/5 tests).
- A second withdrawal after HIDDEN must still clean a cover missed by the first attempt (Task 3/5 tests).
- A draft or unknown post must never trigger S3 deletion (Task 3/5 tests).

---

### Task 1: Block withdrawn posts as new candidate sources

**Files:**

- Modify: apps/api/src/main/java/io/nullnull/social/application/FeedStore.java
- Create: apps/api/src/main/java/io/nullnull/social/application/PostSourceService.java
- Modify: apps/api/src/main/java/io/nullnull/social/infrastructure/persistence/JdbcFeedStore.java
- Modify: apps/api/src/main/java/io/nullnull/trip/application/CandidateService.java
- Test: apps/api/src/integrationTest/java/io/nullnull/trip/CandidateIT.java
- Modify: apps/api/src/main/java/io/nullnull/social/infrastructure/storage/S3ObjectStorage.java
- Test: apps/api/src/test/java/io/nullnull/social/infrastructure/storage/S3ObjectStorageTest.java
- Modify: docs/api/openapi.yaml, packages/api-client/src/generated/openapi.ts, docs/roles/BACKEND_AI_PLAYBOOK.md, docs/project/DECISIONS_AND_RISKS.md

**Interfaces:** PostSourceService.requirePublished(UUID) uses FeedStore.lockPublishedPost(UUID) inside the candidate write transaction. S3ObjectStorage.publish sets no-store.

- [ ] **Step 1: Write failing tests.** CandidateIT must show a HIDDEN or DRAFT source is refused for a new candidate while a preexisting idempotency replay cannot create a new source. S3ObjectStorageTest must assert the published object has Cache-Control no-store.
- [ ] **Step 2: Run the focused tests and confirm the new assertions fail.** Run the CandidateIT class and S3ObjectStorageTest with the existing Gradle runner.
- [ ] **Step 3: Apply the reviewed minimal changes.** Use isolated patch commit 7fa382d3 (backend) as reference, rebase its hunks onto current main, and retain the essential decisions:

~~~java
if (sourceType == CandidateSourceType.POST) {
    postSources.requirePublished(postId);
}
// S3 PutObjectRequest for published user cover:
.cacheControl("no-store")
~~~

- [ ] **Step 4: Rerun focused tests and the generated-client/document contract checks.** Verify the candidate response code and OpenAPI example match; do not silently change FE-facing shape.
- [ ] **Step 5: Commit the backend safety patch on the backend role branch.** Keep the test and source changes in the same commit.

### Task 2: Keep withdrawable user covers out of the service-worker cache

**Files:**

- Modify: apps/web/public/sw.js
- Test: `apps/web/src/shared/testing/__tests__/offline-shell.test.ts`

**Interfaces:** Requests whose URL path starts with /covers/user/ bypass Cache Storage entirely.

- [ ] **Step 1: Add a failing offline-shell test.** Fetch one user cover while online, then inspect Cache Storage and assert the URL is absent. Repeat after service-worker activation to catch old-cache behavior.
- [ ] **Step 2: Run the focused Vitest test and confirm the new assertion fails.**
- [ ] **Step 3: Apply the minimal bypass.** Use isolated commit 5d9bdb84 (web) as reference and preserve the existing offline shell for app assets:

~~~javascript
if (url.pathname.startsWith("/covers/user/")) {
  return fetch(request);
}
~~~

- [ ] **Step 4: Rerun the offline-shell test and commit on the frontend role branch.**

### Task 3: Return the hidden post's safe cover reference

**Files:**

- Modify: apps/api/src/main/java/io/nullnull/social/application/FeedStore.java
- Modify: apps/api/src/main/java/io/nullnull/social/infrastructure/persistence/JdbcFeedStore.java
- Modify: apps/api/src/main/java/io/nullnull/social/application/PostWithdrawalService.java
- Test: apps/api/src/integrationTest/java/io/nullnull/social/PostWithdrawalIT.java
- Test: apps/api/src/integrationTest/java/io/nullnull/social/PostWithdrawalConcurrencyIT.java
- Modify/Test: apps/api/src/main/java/io/nullnull/social/infrastructure/moderation/PostWithdrawMain.java and apps/api/src/test/java/io/nullnull/social/infrastructure/moderation/PostWithdrawMainTest.java

**Interfaces:** PostWithdrawalService.withdraw(UUID) produces WithdrawalResult(Withdrawal outcome, Optional of String coverUrl). FeedStore.hiddenCoverUrl(UUID) reads only a HIDDEN row. No URL is logged.

- [ ] **Step 1: Add failing integration tests.** For PUBLISHED, assert HIDDEN plus the stored cover URL in WithdrawalResult. For ALREADY_HIDDEN, assert the same URL is available for retry. For DRAFT and unknown ID, assert no cover reference. Keep existing concurrent withdrawal assertions; Task 5 verifies that these cases never call S3.
- [ ] **Step 2: Run PostWithdrawalIT and PostWithdrawalConcurrencyIT; confirm the new tests fail.**
- [ ] **Step 3: Add the minimal DB result path.** The service keeps its transaction and reads cover_url after the conditional update or confirmed HIDDEN status:

~~~java
public record WithdrawalResult(Withdrawal outcome, Optional<String> coverUrl) {}
// On WITHDRAWN or ALREADY_HIDDEN:
return new WithdrawalResult(outcome, feed.hiddenCoverUrl(postId));
~~~

The JDBC query must constrain both id and status = 'HIDDEN'. Do not fetch title, body, author, or arbitrary media metadata for the cleanup task.

- [ ] **Step 4: Update PostWithdrawMain's injected function/test seam to receive WithdrawalResult, preserving exact UUID and approval checks.** Do not call S3 yet; existing successful report stays valid until Task 4 changes it.
- [ ] **Step 5: Run the focused integration and main tests, then commit.**

### Task 4: Delete only the exact user-cover object's versions

**Files:**

- Create: apps/api/src/main/java/io/nullnull/social/infrastructure/storage/PublishedCoverRemoval.java
- Create: apps/api/src/test/java/io/nullnull/social/infrastructure/storage/PublishedCoverRemovalTest.java
- Modify: apps/api/src/main/java/io/nullnull/social/infrastructure/storage/S3StorageProperties.java or its existing configuration constructor only if needed for public-origin validation

**Interfaces:** PublishedCoverRemoval.remove(String coverUrl) returns CoverCleanup(status, deletedVersionCount), where status is DELETED, ALREADY_ABSENT, or NOT_USER_UPLOAD. Curated covers outside the user prefix return NOT_USER_UPLOAD; a URL claiming the user prefix with a wrong origin or malformed key throws a fixed refusal code. No key is exposed to logs.

- [ ] **Step 1: Write failing tests with a fake S3Client.** Cover exact origin and UUID/extension validation; encoded traversal, query, fragment, wrong host; 1,001 versions across pages; one adjacent prefix key; delete markers; partial delete error; second invocation after a partial failure. Assert no S3 call for invalid URLs and no neighboring key in DeleteObjects requests.
- [ ] **Step 2: Run PublishedCoverRemovalTest and confirm the new assertions fail.**
- [ ] **Step 3: Implement exact key parsing and version pagination.** Use URI parsing plus a strict anchored path expression, compare URI scheme/host/port with configured public base URL, and demand the S3 returned key equals the parsed key before collecting its version ID:

~~~java
if (!returnedKey.equals(exactKey)) {
    continue;
}
versions.add(ObjectIdentifier.builder().key(exactKey).versionId(versionId).build());
~~~

Page through both keyMarker and versionIdMarker until not truncated. Delete collected identifiers in batches of at most 1,000, inspect per-object errors, then list again and require zero matching versions and markers. An empty first list is ALREADY_ABSENT. On any AWS exception or partial error, throw a fixed cleanup failure so a rerun can continue.

- [ ] **Step 4: Run focused tests and commit the storage component.**

### Task 5: Wire the operator result and least-privilege IAM

**Files:**

- Modify: apps/api/src/main/java/io/nullnull/social/infrastructure/moderation/PostWithdrawMain.java
- Test: apps/api/src/test/java/io/nullnull/social/infrastructure/moderation/PostWithdrawMainTest.java
- Modify: scripts/aws/staging_operator.py
- Test: scripts/tests/test_aws_operator.py
- Modify: infra/src/staging.ts
- Test: infra/test/staging.test.ts

**Interfaces:** The ops main prints one exact success line containing post ID, DB outcome, cover outcome, and version count. Failure prints post_withdraw_failed with fixed reason plus cover_cleanup=pending, exits nonzero. Python operator accepts only that success shape and never infers success from task exit alone.

- [ ] **Step 1: Write failing main/operator tests.** Test DELETED, ALREADY_ABSENT, NOT_USER_UPLOAD, partial cleanup failure, wrong post ID, duplicate/conflicting success lines, and no success for DRAFT/unknown ID. Assert logs never include URL/key. Update the log allowlist with the exact new success/failure forms.
- [ ] **Step 2: Write failing IAM synth tests.** Locate the OpsTask task role statement and assert ListBucketVersions is limited to the web bucket with s3:prefix covers/user/*, DeleteObjectVersion is limited to covers/user/* objects, and the ApiTask has no DeleteObjectVersion. Assert no broad bucket DeleteObject statement appears.
- [ ] **Step 3: Run the focused Java, Python and infra tests to confirm failure.**
- [ ] **Step 4: Wire the sequence.** The main first calls PostWithdrawalService.withdraw, waits for the transaction to return, then calls PublishedCoverRemoval only for WITHDRAWN/ALREADY_HIDDEN with a validated user cover. Its success line has the exact fixed fields below; cleanup failure throws after printing a fixed pending result. The Python operator requires that line and removes its unconditional cover-object-not-deleted warning.

~~~text
post_withdrawn post=<canonical-uuid> outcome=WITHDRAWN cover=DELETED versions=2
post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending
~~~

- [ ] **Step 5: Add only the OpsTask environment and IAM grants.** Pass bucket, region and public base URL; grant ListBucketVersions on the bucket with an s3:prefix condition and DeleteObjectVersion on covers/user/* object ARN. Do not alter the online API task's grant. The policy shape must be equivalent to:

~~~typescript
new iam.PolicyStatement({
  actions: ["s3:ListBucketVersions"],
  resources: [webBucket.bucketArn],
  conditions: { StringLike: { "s3:prefix": ["covers/user/*"] } },
});
new iam.PolicyStatement({
  actions: ["s3:DeleteObjectVersion"],
  resources: [webBucket.arnForObjects("covers/user/*")],
});
~~~

- [ ] **Step 6: Rerun focused tests and commit.**

### Task 6: Document the residual risk and verify the complete change

**Files:**

- Modify: docs/operations/STAGING_DEPLOYMENT_RUNBOOK.md
- Modify: docs/project/DECISIONS_AND_RISKS.md
- Modify: docs/roles/BACKEND_AI_PLAYBOOK.md
- Modify: output/nullnull-work-checklist-2026-09-22.md (local evidence ledger, outside Git worktree)

**Interfaces:** Runbook specifies the existing post-specific approval command, its partial-failure retry, exact-key version verification, and inability to recall already delivered browser/device copies.

- [ ] **Step 1: Add a runbook example for one approved post ID.** The example must explain that HIDDEN plus cleanup=pending is an incomplete incident, that rerunning the same approved ID is safe, and that neither a CloudFront 404 nor a successful DB withdrawal proves all S3 versions are gone.
- [ ] **Step 2: Run repository checks.** Execute docs validation, Markdown lint, Redocly OpenAPI lint, AJV event validation, API unit/integration/OpenAPI contract tests, web verify:ci, infra check, and scripts/integration-test.sh. Record exact pass/fail counts; do not mark skipped checks as passing.
- [ ] **Step 3: Compare synthesized Migration and Services templates and IAM with the live stack before deployment.** Require the protected WebEdge template to remain byte-identical. Reconcile the existing hotfix image/release-record mismatch using an approved preserve-open release path; do not forge a current release record.
- [ ] **Step 4: Open role-branch PRs and require docs-contract and docker-integration.** Attach each created PR to the current Codex task. Merge only after required checks pass. Keep #338 open until an isolated versioned S3 canary, exact-key cleanup, retry behavior, and public URL response are verified with the edge continuously available.
- [ ] **Step 5: Update the checklist and GitHub issue with evidence, then close #338 only when the complete acceptance and operational checks pass.**
