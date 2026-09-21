package io.nullnull.social.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The object store an uploaded cover passes through, as the application needs it.
 *
 * <p>This is a port for a reason that is not ceremony. The bucket this runs against is an open
 * deployment decision, and the gate that has to prove the flow runs with egress denied - so the
 * only way the application layer can be written and verified today is behind an interface whose
 * real implementation lands later. Nothing above this interface knows a bucket name, a region or
 * a credential.
 *
 * <p>TWO LOCATIONS, ONE STORE. Bytes land in a quarantine location that no reader can fetch from,
 * and only a sanitised copy is written to the published location. A-058 removed the human approval
 * step, NOT the quarantine: stripping EXIF rewrites the file, so there is necessarily a moment when
 * the original exists somewhere, and the invariant is that the somewhere is not public.
 */
public interface ObjectStorage {

    /**
     * A URL the browser can PUT one object to, and nothing else.
     *
     * <p>The signature binds the content type and the exact length, so a caller who was signed for
     * 100 KB of JPEG cannot upload 40 MB of anything - that refusal happens at the store, before any
     * byte reaches us. It is the first of two layers; the second is that the server
     * validates the bytes it later reads, because a signature proves what was promised, not what
     * arrived.
     *
     * @param key the quarantine key the intent owns
     * @param contentType the media type the signature binds
     * @param contentLength the exact length the signature binds
     * @param ttl how long the URL stays usable
     */
    PresignedUpload presignQuarantinePut(String key, String contentType, long contentLength,
            Duration ttl);

    /**
     * The bytes at a quarantine key.
     *
     * @throws ObjectNotFoundException when nothing was ever uploaded there - a caller who asked to
     *         publish an upload that never happened, which is a refusal and not a server fault
     */
    byte[] readQuarantined(String key);

    /**
     * Writes the sanitised bytes to the published location and answers where readers will find them.
     *
     * @return the absolute https URL the CDN serves, which becomes the asset's served url
     */
    String publish(String key, byte[] bytes, String contentType);

    /**
     * Deletes a quarantined object.
     *
     * <p>Called on both endings, and the ending that matters is the failure: a rejected upload is
     * deleted where it is rejected rather than left for the lifecycle rule, because the rule's
     * granularity is a day and a rejected object is one we have already decided not to keep.
     *
     * <p>Deleting the ACCEPTED original is not housekeeping either - the original is the copy that
     * still carries the camera's coordinates (invariant 10), so keeping it would store precise
     * location by another name.
     */
    void deleteQuarantined(String key);

    /** A signed URL and everything the browser must send with it for the signature to hold. */
    record PresignedUpload(String url, String method, Map<String, String> headers, Instant expiresAt) {
        public PresignedUpload {
            headers = Map.copyOf(headers);
        }
    }

    /** Thrown when a quarantine key holds nothing. */
    class ObjectNotFoundException extends RuntimeException {
        public ObjectNotFoundException(String message) {
            super(message);
        }
    }
}
