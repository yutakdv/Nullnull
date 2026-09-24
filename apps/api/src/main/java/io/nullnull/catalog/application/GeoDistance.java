package io.nullnull.catalog.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Great-circle distance on a sphere. One implementation because two readers must agree on it: the
 * English match probe printed the distances the owner reviewed candidates by, and the link rule
 * enforces the owner's 100 m on the same measure (BA-086).
 */
public final class GeoDistance {

    /** IUGG mean Earth radius, the value the match probe has used since the owner reviewed its output. */
    public static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    public static double haversineMeters(BigDecimal latitude1, BigDecimal longitude1, BigDecimal latitude2,
            BigDecimal longitude2) {
        Objects.requireNonNull(latitude1, "latitude1");
        Objects.requireNonNull(longitude1, "longitude1");
        Objects.requireNonNull(latitude2, "latitude2");
        Objects.requireNonNull(longitude2, "longitude2");
        double phi1 = Math.toRadians(latitude1.doubleValue());
        double phi2 = Math.toRadians(latitude2.doubleValue());
        double deltaPhi = phi2 - phi1;
        double deltaLambda = Math.toRadians(longitude2.doubleValue() - longitude1.doubleValue());
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
