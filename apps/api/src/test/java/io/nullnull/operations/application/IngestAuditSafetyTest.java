package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestAuditSafetyTest {

    /**
     * No acceptance ID leads this label, and that is the honest state rather than an omission.
     * The clause is structural - an audit record cannot even declare a field that would hold a
     * credential, URL, body or user - and no catalogue row says it. `REC-SEC-02` injects a canary
     * and looks for it; `REC-DATA-05` is drift quarantine. This is neither, so the borrowed
     * `REC-DATA-05` was removed instead of swapped for another ID: a label naming a clause it does
     * not prove is the shape #195 describes. Raise the gap as `apps/ai` catalogue work.
     */
    @Test
    @DisplayName("ingest audit has no credential URL query body or user field")
    void unsafeDataHasNoStorageField() {
        for (Class<?> command : java.util.List.of(IngestAudit.StartRun.class,
                IngestAudit.CallRecord.class, IngestAudit.FinishRun.class)) {
            String fields = Arrays.stream(command.getRecordComponents()).map(RecordComponent::getName)
                    .map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining(" "));
            assertThat(fields).doesNotContain("key", "secret", "credential", "url", "uri", "query",
                    "body", "user", "owner", "session", "coordinate", "latitude", "longitude");
        }
    }
}
