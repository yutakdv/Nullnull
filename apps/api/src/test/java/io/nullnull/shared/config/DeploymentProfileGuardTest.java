package io.nullnull.shared.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Both directions. A guard that only ever refuses is indistinguishable from one that always refuses,
 * so the cases that must START are as load-bearing here as the ones that must fail.
 */
class DeploymentProfileGuardTest {

    private DeploymentProfileGuard guard(String deploymentEnvironment, String... activeProfiles) {
        var environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setActiveProfiles(activeProfiles);
        }
        // With none set, MockEnvironment reports the framework default, which is what a jar started
        // without SPRING_PROFILES_ACTIVE gets. application.yaml then names "local" instead.
        return new DeploymentProfileGuard(deploymentEnvironment, environment);
    }

    @Test
    void cloudEnvironmentRefusesTheDeveloperProfile() {
        for (String environment : new String[] {"staging", "production"}) {
            assertThatThrownBy(() -> guard(environment, "local"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NULLNULL_ENV=" + environment)
                    .hasMessageContaining("SPRING_PROFILES_ACTIVE")
                    .hasMessageContaining("application-local.yaml");
        }
    }

    @Test
    void theMessageSeparatesAnUnsetVariableFromAWrongOne() {
        var unset = new MockEnvironment();
        unset.setDefaultProfiles("local");
        assertThatThrownBy(() -> new DeploymentProfileGuard("staging", unset))
                .hasMessageContaining("is not set");

        assertThatThrownBy(() -> guard("staging", "local"))
                .hasMessageContaining("selects it");
    }

    @Test
    void theDeveloperProfileIsStillAllowedOffTheCloud() {
        // KtoActualSmokeIT and KtoForecastActualSmokeIT run exactly this shape.
        assertThatCode(() -> guard("local", "local")).doesNotThrowAnyException();
        // KtoPlaceDetailGatewayIT and KtoCrowdForecastGatewayIT set nullnull.env=test and no active
        // profile at all, which is the default-profile branch this must not refuse.
        var defaulted = new MockEnvironment();
        defaulted.setDefaultProfiles("local");
        assertThatCode(() -> new DeploymentProfileGuard("test", defaulted)).doesNotThrowAnyException();
    }

    @Test
    void aCloudEnvironmentOnItsOwnProfileStarts() {
        assertThatCode(() -> guard("staging", "staging")).doesNotThrowAnyException();
        assertThatCode(() -> guard("production", "production")).doesNotThrowAnyException();
        // compose.integration.yml's API service: NULLNULL_ENV=test with the integration profile.
        assertThatCode(() -> guard("test", "integration")).doesNotThrowAnyException();
    }

    @Test
    void anUnrecognisedEnvironmentIsNotClassifiedAsCloudHere() {
        // AccessLogFilter owns the NULLNULL_ENV vocabulary and refuses "prod"; this guard must not
        // silently accept it as safe either, so it is checked for what this class actually claims:
        // it does not throw, and the startup still fails - in the class that owns that rule.
        assertThatCode(() -> guard("prod", "local")).doesNotThrowAnyException();
    }
}
