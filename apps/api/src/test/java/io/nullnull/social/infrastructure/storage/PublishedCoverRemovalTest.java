package io.nullnull.social.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Error;

class PublishedCoverRemovalTest {
    private static final String KEY =
            "covers/user/0192f3a4-5b6c-7d8e-9f01-23456789abcd.jpg";
    private static final String URL = "https://nullnull.test/" + KEY;

    private static PublishedCoverRemoval removal(S3Client s3) {
        return new PublishedCoverRemoval(s3,
                new S3StorageProperties("test-bucket", "ap-northeast-2", "https://nullnull.test"));
    }

    private static ObjectVersion version(String key, String versionId) {
        return ObjectVersion.builder().key(key).versionId(versionId).build();
    }

    @Test
    void deletesEveryVersionAndMarkerOfOnlyTheExactKeyAcrossPages() {
        S3Client s3 = mock(S3Client.class);
        List<ObjectVersion> firstVersions = IntStream.range(0, 999)
                .mapToObj(i -> version(KEY, "v-" + i)).toList();
        var first = ListObjectVersionsResponse.builder()
                .versions(firstVersions)
                .deleteMarkers(DeleteMarkerEntry.builder().key(KEY).versionId("marker").build())
                .isTruncated(true).nextKeyMarker(KEY).nextVersionIdMarker("marker").build();
        var second = ListObjectVersionsResponse.builder()
                .versions(version(KEY, "v-999"), version(KEY, "v-1000"),
                        version(KEY + "-neighbor", "keep"))
                .isTruncated(false).build();
        var empty = ListObjectVersionsResponse.builder().isTruncated(false).build();
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(first, second, empty);
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        var result = removal(s3).remove(URL);

        assertThat(result.status()).isEqualTo(PublishedCoverRemoval.Status.DELETED);
        assertThat(result.deletedVersionCount()).isEqualTo(1002);
        var deletes = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).deleteObjects(deletes.capture());
        assertThat(deletes.getAllValues()).allSatisfy(request -> {
            assertThat(request.bucket()).isEqualTo("test-bucket");
            assertThat(request.delete().objects()).allSatisfy(object ->
                    assertThat(object.key()).isEqualTo(KEY));
            assertThat(request.delete().objects().size()).isLessThanOrEqualTo(1000);
        });
        assertThat(deletes.getAllValues().stream()
                .flatMap(request -> request.delete().objects().stream())
                .map(object -> object.versionId()).toList())
                .contains("marker", "v-0", "v-1000").doesNotContain("keep");
        assertThat(deletes.getAllValues().getFirst().delete().objects())
                .extracting(object -> object.versionId()).doesNotContain("marker");
        var listings = ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3, org.mockito.Mockito.times(3)).listObjectVersions(listings.capture());
        assertThat(listings.getAllValues()).allSatisfy(request ->
                assertThat(request.prefix()).isEqualTo(KEY));
        assertThat(listings.getAllValues().get(1).versionIdMarker()).isEqualTo("marker");
    }

    @Test
    void anAlreadyAbsentCoverIsAnIdempotentSuccess() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().isTruncated(false).build());

        var result = removal(s3).remove(URL);

        assertThat(result.status()).isEqualTo(PublishedCoverRemoval.Status.ALREADY_ABSENT);
        assertThat(result.deletedVersionCount()).isZero();
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void malformedOrForeignUserCoverUrlsNeverReachS3() {
        S3Client s3 = mock(S3Client.class);
        var removal = removal(s3);
        for (String candidate : List.of(
                "https://evil.test/" + KEY,
                URL + "?download=1",
                URL + "#copy",
                URL.replace("/covers/user/", "/covers/%75ser/"),
                URL.replace(".jpg", ".gif"),
                URL.replace("0192f3a4", "0192F3A4"),
                "https://nullnull.test:444/" + KEY)) {
            assertThatThrownBy(() -> removal.remove(candidate))
                    .as(candidate).isInstanceOf(PublishedCoverRemoval.InvalidCoverUrl.class);
        }
        assertThat(removal.remove("https://nullnull.test/covers/first-party.jpg").status())
                .isEqualTo(PublishedCoverRemoval.Status.NOT_USER_UPLOAD);
        verifyNoInteractions(s3);
    }

    @Test
    void aPartialDeleteFailsAndTheNextRunRemovesOnlyRemainingVersions() {
        S3Client s3 = mock(S3Client.class);
        var both = ListObjectVersionsResponse.builder()
                .versions(version(KEY, "v-1"), version(KEY, "v-2")).isTruncated(false).build();
        var remaining = ListObjectVersionsResponse.builder()
                .versions(version(KEY, "v-2")).isTruncated(false).build();
        var empty = ListObjectVersionsResponse.builder().isTruncated(false).build();
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(both, remaining, empty);
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().errors(
                        S3Error.builder().key(KEY).versionId("v-2")
                                .code("AccessDenied").message("private detail").build()).build())
                .thenReturn(DeleteObjectsResponse.builder().build());
        var removal = removal(s3);

        assertThatThrownBy(() -> removal.remove(URL))
                .isInstanceOf(PublishedCoverRemoval.CleanupFailed.class)
                .hasMessage("cover cleanup failed");
        var retry = removal.remove(URL);
        assertThat(retry.status()).isEqualTo(PublishedCoverRemoval.Status.DELETED);
        assertThat(retry.deletedVersionCount()).isEqualTo(1);
        var deletes = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).deleteObjects(deletes.capture());
        assertThat(deletes.getAllValues().get(1).delete().objects())
                .extracting(object -> object.versionId()).containsExactly("v-2");
    }
}
