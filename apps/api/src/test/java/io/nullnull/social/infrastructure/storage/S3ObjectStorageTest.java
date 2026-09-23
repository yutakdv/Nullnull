package io.nullnull.social.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3ObjectStorageTest {
    @Test
    void newUploadedCoversDoNotPromisePermanentBrowserCopies() throws Exception {
        S3Client s3 = mock(S3Client.class);
        var storage = new S3ObjectStorage(s3, null,
                new S3StorageProperties("synthetic-bucket", "ap-northeast-2", "https://example.test"));
        byte[] bytes = {1, 2, 3};
        assertThat(storage.publish("covers/user/synthetic.jpg", bytes, "image/jpeg"))
                .isEqualTo("https://example.test/covers/user/synthetic.jpg");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertThat(request.getValue().cacheControl()).isEqualTo("no-store");
        assertThat(request.getValue().key()).isEqualTo("covers/user/synthetic.jpg");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        try (var input = body.getValue().contentStreamProvider().newStream()) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
    }
}
