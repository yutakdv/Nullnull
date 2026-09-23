package io.nullnull.social.infrastructure.storage;

import io.nullnull.social.application.ObjectStorage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * {@link ObjectStorage} over the bucket the web edge already serves (owner decision (A),
 * 2026-09-20).
 *
 * <p>ONE BUCKET, TWO PREFIXES, AND THE SEPARATION IS THE IAM POLICY'S. Quarantine keys are not
 * served by the distribution and the published prefix is. Nothing in this class enforces that -
 * the grant on the API task role does, scoped to the published prefix so a bug here cannot
 * overwrite the application bundle in the same bucket.
 */
@Component
// Conditional for the same reason the configuration is, and it has to be here TOO: a @Component is
// a bean DEFINITION as soon as it is scanned, whether or not its dependencies could be satisfied.
// Guarding only the configuration left two ObjectStorage definitions in every unconfigured context,
// which is a NoUniqueBeanDefinitionException at the injection point rather than a missing client.
@ConditionalOnProperty("nullnull.upload.s3.bucket")
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3StorageProperties properties;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, S3StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public PresignedUpload presignQuarantinePut(String key, String contentType, long contentLength,
            Duration ttl) {
        // Both are part of what is signed, so the store refuses a PUT that does not match them -
        // before a byte reaches us. A caller signed for 100 KB of JPEG cannot upload 40 MB of
        // anything, which is one of two layers; the other is that the bytes that do
        // arrive are validated anyway, because a signature says what was promised.
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(put)
                        .build());

        // The browser has to send exactly these, or the signature does not verify.
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", Long.toString(contentLength));
        return new PresignedUpload(presigned.url().toString(), "PUT", headers,
                presigned.expiration());
    }

    @Override
    public byte[] readQuarantined(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            // A ticket that was signed and never used. It is a refusal, not a fault of ours, and
            // the caller is told so rather than being handed a 500.
            throw new ObjectNotFoundException("no object at the quarantine key");
        }
    }

    @Override
    public String publish(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        // Upload ids prevent overwrites, not rights withdrawal. Do not promise
                        // permanent HTTP-cache copies of content that may need to be taken down.
                        // This does not purge previously cached bytes or service-worker storage.
                        .cacheControl("no-store")
                        .build(),
                RequestBody.fromBytes(bytes));
        return properties.publicBaseUrl() + "/" + key;
    }

    @Override
    public void deleteQuarantined(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
    }
}
