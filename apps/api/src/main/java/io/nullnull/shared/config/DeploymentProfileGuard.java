package io.nullnull.shared.config;

import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * A cloud deployment may not run the developer profile. Measured, not assumed.
 *
 * <p>{@code application.yaml} declares {@code spring.profiles.default: local} and no
 * {@code spring.datasource.url/username/password} of its own, there is no {@code application-staging.yaml},
 * and nothing outside {@code compose.integration.yml} sets {@code SPRING_PROFILES_ACTIVE}. Starting the
 * built jar with that variable unset was measured on 2026-09-17:
 *
 * <pre>
 * No active profile set, falling back to 1 default profile: "local"
 * Migrating schema "public" to version "001 - background jobs"   &lt;- against 127.0.0.1:5434
 * </pre>
 *
 * <p>So the developer datasource in {@code application-local.yaml} does not merely load: it
 * authenticates and runs Flyway. A task started in the cloud without the variable would quietly dial
 * loopback, and the only symptom is "why is there no data" - a silent fallback, which the repository
 * forbids (AGENTS.md, "missing config or data fails loudly").
 *
 * <p>An environment variable does override the profile file - measured the same way, a set
 * {@code SPRING_DATASOURCE_URL} won - so a fully specified task definition is safe. This guard is for
 * the case where one is missing, because that case is the quiet one. The contrast is
 * {@code nullnull.ai.base-url}, whose placeholder in {@code application.yaml} has no default: without
 * the local profile it fails closed by itself. The datasource has no such placeholder, and that
 * asymmetry is the whole hole.
 *
 * <p>The condition is an ALLOW-list of the two cloud environments rather than "not local", so an
 * unrecognised value cannot be classified as safe by accident. It deliberately does not re-check the
 * {@code NULLNULL_ENV} vocabulary: {@code AccessLogFilter} owns that list and refuses anything outside
 * it, and a rule kept in two places rots in one of them. A typo such as {@code prod} therefore fails
 * there rather than passing here.
 *
 * <p>This guard does not fire for any current test or gate. Every Spring-context test that sets
 * {@code nullnull.env} sets {@code local} or {@code test}, no test sets an active profile, and
 * {@code compose.integration.yml} runs the API with {@code NULLNULL_ENV=test} and
 * {@code SPRING_PROFILES_ACTIVE=integration}. The {@code NULLNULL_ENV=staging} in that file belongs to
 * the Python {@code ai} service, which is not this application.
 */
@Component
public class DeploymentProfileGuard {

    /** Environments whose datasource and secrets come from the deployment, never from a profile file. */
    static final Set<String> CLOUD_ENVIRONMENTS = Set.of("staging", "production");

    /** The profile that carries developer defaults (apps/api/src/main/resources/application-local.yaml). */
    static final String DEVELOPER_PROFILE = "local";

    public DeploymentProfileGuard(@Value("${nullnull.env}") String deploymentEnvironment,
            Environment environment) {
        String[] active = environment.getActiveProfiles();
        String[] effective = active.length == 0 ? environment.getDefaultProfiles() : active;
        if (!CLOUD_ENVIRONMENTS.contains(deploymentEnvironment)
                || !Arrays.asList(effective).contains(DEVELOPER_PROFILE)) {
            return;
        }
        // Which of the two mistakes it is decides what the operator does next: add the variable, or
        // correct it. Saying only "the profile is local" leaves them to guess.
        String cause = active.length == 0
                ? "SPRING_PROFILES_ACTIVE is not set, so Spring fell back to the default profile"
                : "SPRING_PROFILES_ACTIVE selects it";
        throw new IllegalStateException("NULLNULL_ENV=" + deploymentEnvironment + " must not run the '"
                + DEVELOPER_PROFILE + "' profile, but " + cause + " " + Arrays.toString(effective)
                + ". application-local.yaml supplies a developer datasource and AI base URL that a"
                + " deployment silently falls back to whenever the matching variable is unset. Set"
                + " SPRING_PROFILES_ACTIVE to this deployment's profile"
                + " (docs/operations/ENVIRONMENT.md §3).");
    }
}
