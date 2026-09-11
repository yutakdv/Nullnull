package nullnull.testsupport.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;

/**
 * An ordinary {@code @Validated} application service. Bean Validation on a service - not on a
 * controller argument - is what actually raises {@code jakarta.validation.ConstraintViolationException};
 * a violated controller parameter raises {@code HandlerMethodValidationException} instead, which is a
 * different mapping.
 */
@Validated
public class ValidatedTestService {

    public int countPlaces(@Min(1) int limit) {
        return limit;
    }

    /**
     * A cascaded constraint on an INDEXED element, so the property path is nested
     * ({@code save.request.items[3].name}) rather than a single leaf. Two violations on different
     * rows have to stay two distinct fields in the response, otherwise the caller is told twice that
     * something called {@code name} is wrong and cannot tell which row to fix.
     */
    public int save(@Valid Places request) {
        return request.items().size();
    }

    public record Place(@NotBlank String name) {
    }

    public record Places(List<@Valid Place> items) {
    }
}
