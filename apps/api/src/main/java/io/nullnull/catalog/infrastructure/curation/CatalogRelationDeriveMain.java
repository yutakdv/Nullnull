package io.nullnull.catalog.infrastructure.curation;

import io.nullnull.OperationsContext;
import io.nullnull.catalog.application.CatalogRelationDeriver;
import io.nullnull.catalog.application.CatalogRelationDeriver.DerivationReport;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * BA-026's operations script: re-derives the internal rule's SIMILAR evidence.
 *
 * <pre>
 * ./gradlew deriveRelations
 * </pre>
 *
 * <p>No external request and no input file. The rule reads the catalog we already hold, so unlike
 * {@code curateHours} there is nothing for an operator to review beforehand - what there is to review
 * is the report afterwards, and in particular the places it skipped.
 *
 * <p>Run it after a catalog ingest. The registry expects re-evaluation when the catalog changes and
 * gives a derived row seven days, so a catalog that moved without a run here leaves relations that
 * describe the catalog as it was until they lapse.
 */
public final class CatalogRelationDeriveMain {

    private CatalogRelationDeriveMain() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
            System.out.println(summary(context.getBean(CatalogRelationDeriver.class).derive()));
        }
    }

    /**
     * Counts, and the ids of the places that produced none.
     *
     * <p>The skipped ids are printed because they are the only place that fact exists: those places
     * answer with no related places and nothing else says why. Place ids are catalog identifiers, not
     * anybody's data.
     */
    static String summary(DerivationReport report) {
        StringBuilder out = new StringBuilder();
        out.append("relations_recorded=").append(report.recorded()).append('\n');
        out.append("relations_expired=").append(report.expired()).append('\n');
        out.append("relations_skipped=").append(report.skipped().size());
        report.skipped().forEach(place -> out.append("\nskipped_over_cap ").append(place));
        return out.toString();
    }
}
