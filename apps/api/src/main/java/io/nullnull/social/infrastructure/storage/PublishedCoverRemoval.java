package io.nullnull.social.infrastructure.storage;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/** Removes every version of one published user cover after its post has been hidden. */
@Component
@ConditionalOnProperty("nullnull.upload.s3.bucket")
public final class PublishedCoverRemoval {
    private static final Pattern USER_COVER = Pattern.compile(
            "^/covers/user/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)$");
    private final S3Client s3;
    private final S3StorageProperties properties;

    public PublishedCoverRemoval(S3Client s3, S3StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    public CoverCleanup remove(String coverUrl) {
        String key = exactUserKey(coverUrl);
        if (key == null) {
            return new CoverCleanup(Status.NOT_USER_UPLOAD, 0);
        }
        try {
            List<ObjectIdentifier> versions = listExactVersions(key);
            if (versions.isEmpty()) {
                return new CoverCleanup(Status.ALREADY_ABSENT, 0);
            }
            for (int first = 0; first < versions.size(); first += 1000) {
                List<ObjectIdentifier> batch = versions.subList(first,
                        Math.min(first + 1000, versions.size()));
                var response = s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(properties.bucket())
                        .delete(Delete.builder().objects(batch).quiet(true).build())
                        .build());
                if (!response.errors().isEmpty()) {
                    throw new CleanupFailed();
                }
            }
            if (!listExactVersions(key).isEmpty()) {
                throw new CleanupFailed();
            }
            return new CoverCleanup(Status.DELETED, versions.size());
        } catch (SdkException e) {
            throw new CleanupFailed();
        }
    }

    private String exactUserKey(String coverUrl) {
        URI url;
        try {
            url = URI.create(coverUrl);
        } catch (RuntimeException e) {
            throw new InvalidCoverUrl();
        }
        String rawPath = url.getRawPath();
        String decodedPath = url.getPath();
        if (rawPath == null || decodedPath == null) {
            throw new InvalidCoverUrl();
        }
        boolean userCover = rawPath.startsWith("/covers/user/")
                || decodedPath.startsWith("/covers/user/");
        if (!userCover) {
            return null;
        }
        URI origin = URI.create(properties.publicBaseUrl());
        if (!Objects.equals(url.getScheme(), origin.getScheme())
                || !Objects.equals(url.getHost(), origin.getHost())
                || url.getPort() != origin.getPort()
                || url.getRawUserInfo() != null
                || url.getRawQuery() != null || url.getRawFragment() != null
                || !USER_COVER.matcher(rawPath).matches()) {
            throw new InvalidCoverUrl();
        }
        return rawPath.substring(1);
    }

    private List<ObjectIdentifier> listExactVersions(String key) {
        List<ObjectIdentifier> versions = new ArrayList<>();
        String keyMarker = null;
        String versionMarker = null;
        while (true) {
            var response = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(properties.bucket()).prefix(key)
                    .keyMarker(keyMarker).versionIdMarker(versionMarker).build());
            response.versions().stream().filter(version -> key.equals(version.key()))
                    .map(version -> ObjectIdentifier.builder()
                            .key(key).versionId(version.versionId()).build())
                    .forEach(versions::add);
            response.deleteMarkers().stream().filter(marker -> key.equals(marker.key()))
                    .map(marker -> ObjectIdentifier.builder()
                            .key(key).versionId(marker.versionId()).build())
                    .forEach(versions::add);
            if (!Boolean.TRUE.equals(response.isTruncated())) {
                return versions;
            }
            if (response.nextKeyMarker() == null || response.nextVersionIdMarker() == null
                    || (Objects.equals(keyMarker, response.nextKeyMarker())
                            && Objects.equals(versionMarker, response.nextVersionIdMarker()))) {
                throw new CleanupFailed();
            }
            keyMarker = response.nextKeyMarker();
            versionMarker = response.nextVersionIdMarker();
        }
    }

    public enum Status { DELETED, ALREADY_ABSENT, NOT_USER_UPLOAD }

    public record CoverCleanup(Status status, int deletedVersionCount) {}

    public static final class InvalidCoverUrl extends RuntimeException {
        public InvalidCoverUrl() { super("invalid user cover URL"); }
    }

    public static final class CleanupFailed extends RuntimeException {
        public CleanupFailed() { super("cover cleanup failed"); }
    }
}
