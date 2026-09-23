package io.nullnull.social.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.social.application.ObjectStorage.PresignedUpload;
import io.nullnull.social.application.PostAuthoringService;
import io.nullnull.social.application.PostAuthoringService.AuthoringRejectedException;
import io.nullnull.social.application.UploadIntentService;
import io.nullnull.social.application.UploadIntentService.IssuedUpload;
import io.nullnull.social.application.UploadIntentService.UploadRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * createPostImageUpload and createPost (BA-082).
 *
 * <p>The author is {@link OwnerContext}'s owner and nothing here takes one. A-058 lets an anonymous
 * session author a post, which does not weaken invariant 11: anonymous still means a cookie session
 * we resolved, not an identifier the caller sent.
 *
 * <p>Neither operation takes or returns a storage path. The first hands back a URL the caller could
 * not have constructed; the second takes the id of that hand-back. A request has nothing in it that
 * a key is built from.
 */
@RestController
public class PostAuthoringController {

    private final UploadIntentService uploads;
    private final PostAuthoringService posts;

    public PostAuthoringController(UploadIntentService uploads, PostAuthoringService posts) {
        this.uploads = uploads;
        this.posts = posts;
    }

    @PostMapping(value = "/posts/images/uploads", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "createPostImageUpload", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<UploadTicketResponse> createUpload(OwnerContext owner,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateUploadRequest request) {
        IssuedUpload issued;
        try {
            issued = uploads.issue(owner.ownerId(), request.contentType(), request.contentLength(),
                    request.checksumSha256());
        } catch (UploadRejectedException e) {
            throw switch (e.rejection()) {
                case UNSUPPORTED_CONTENT_TYPE -> new ApiException(ProblemCode.VALIDATION_FAILED,
                        "That image format is not accepted.");
                case TOO_LARGE -> new ApiException(ProblemCode.VALIDATION_FAILED,
                        "That image is larger than the limit.");
                case MALFORMED_CHECKSUM -> new ApiException(ProblemCode.INVALID_REQUEST,
                        "checksumSha256 must be 64 lowercase hexadecimal characters.");
            };
        }
        return ResponseEntity.created(URI.create("/posts/images/uploads/" + issued.uploadId()))
                // The body carries a signed URL: a shared cache holding one would hand another
                // visitor a URL that writes into this owner's prefix.
                .header("Cache-Control", "private, no-store")
                .body(UploadTicketResponse.from(issued));
    }

    @PostMapping(value = "/posts", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "createPost", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<CreatedPostResponse> create(OwnerContext owner,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePostRequest request) {
        UUID postId;
        try {
            postId = posts.publish(owner.ownerId(), request.uploadId(), request.title(),
                    request.body(), request.altText(), request.placeIds());
        } catch (AuthoringRejectedException e) {
            throw switch (e.rejection()) {
                // One answer for four situations. A different refusal for any of them would
                // confirm that somebody else's id is real, which BA-070-T1 forbids - and the
                // contract could not carry the distinction anyway (see AuthoringRejection).
                case UPLOAD_UNUSABLE -> new ApiException(ProblemCode.NOT_FOUND,
                        "That upload is not available. Choose the photo again.");
                case UPLOAD_MISSING -> new ApiException(ProblemCode.VALIDATION_FAILED,
                        "No image was uploaded for that ticket.");
                case IMAGE_REJECTED -> new ApiException(ProblemCode.VALIDATION_FAILED,
                        "That image could not be accepted.");
            };
        }
        return ResponseEntity.created(URI.create("/posts/" + postId))
                .header("Cache-Control", "private, no-store")
                .body(new CreatedPostResponse(postId));
    }

    /** The upload the caller wants permission for. It names no path and no owner. */
    public record CreateUploadRequest(
            @NotBlank String contentType,
            long contentLength,
            @NotBlank String checksumSha256) {}

    /** What the caller PUTs the file with, and the id it hands back to createPost. */
    public record UploadTicketResponse(UUID uploadId, String url, String method,
            Map<String, String> headers, Instant expiresAt) {

        static UploadTicketResponse from(IssuedUpload issued) {
            PresignedUpload upload = issued.upload();
            return new UploadTicketResponse(issued.uploadId(), upload.url(), upload.method(),
                    upload.headers(), upload.expiresAt());
        }
    }

    /**
     * The post being written.
     *
     * <p>There is no licence flag. The owner chose (2026-09-20) that consent is carried by a notice
     * beside the publish control rather than by a checkbox, and a field a client always sends the
     * same value for records nothing: it would look like a user action in the contract and be a
     * constant in every request. The notice is the artefact; see issue #312.
     */
    public record CreatePostRequest(
            @NotNull UUID uploadId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20_000) String body,
            @Size(max = 500) String altText,
            @NotNull @Size(min = 1, max = 50) List<UUID> placeIds) {}

    /** Where the finished post lives. */
    public record CreatedPostResponse(UUID postId) {}
}
