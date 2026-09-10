package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestAuditSafetyTest {

    @Test
    @DisplayName("REC-DATA-05 ingest audit has no credential URL query body or user field")
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
