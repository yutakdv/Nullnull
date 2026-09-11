package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-005: two handlers for one type, or a type nothing handles, must be loud.
 *
 * <p>Both are silent by default. A duplicate would let whichever handler an index happened to keep
 * take over the other's work; an unhandled type would leave rows claimable but never claimed, which
 * looks exactly like an idle queue.
 */
@DisplayName("BA-005 job handler registry")
class JobHandlerRegistryTest {

    @Test
    void handlersAreIndexedByTypeInSortedOrder() {
        JobHandlerRegistry registry = new JobHandlerRegistry(
                List.of(handler("optimization"), handler("collector"), handler("deletion")));
        assertThat(registry.types()).containsExactly("collector", "deletion", "optimization");
        assertThat(registry.isRegistered("deletion")).isTrue();
        assertThat(registry.isRegistered("unknown")).isFalse();
        assertThat(registry.require("deletion").type()).isEqualTo("deletion");
    }

    @Test
    void twoHandlersForOneTypeFailAtStartup() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler("deletion"), handler("deletion"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deletion");
    }

    @Test
    void aHandlerWithAnInvalidTypeFailsAtStartup() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler("Deletion"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler(null))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiringAnUnknownTypeFailsInsteadOfSkipping() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler("deletion")));
        assertThatThrownBy(() -> registry.require("collector"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collector");
    }

    @Test
    void anEmptyRegistryIsValidBeforeTheFirstHandlerSliceLands() {
        assertThat(new JobHandlerRegistry(List.of()).types()).isEmpty();
    }

    private static JobHandler handler(String type) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(JobContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
