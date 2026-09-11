package io.nullnull.operations.application;

import io.nullnull.operations.domain.JobRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Every {@link JobHandler} bean, indexed by type.
 *
 * <p>Two handlers claiming one type is a startup failure: whichever one an index happened to keep
 * would silently take over the other's work. An enqueue for a type nothing handles is refused at the
 * call site for the mirror-image reason - the row would sit claimable-but-never-claimed until someone
 * noticed the queue depth.
 *
 * <p>The registry exists whether or not the worker is enabled, so the enqueue check behaves the same
 * in a test context as in production.
 */
@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> byType;
    private final SortedSet<String> types;

    public JobHandlerRegistry(List<JobHandler> handlers) {
        Map<String, JobHandler> index = new TreeMap<>();
        Map<String, String> owners = new HashMap<>();
        for (JobHandler handler : handlers) {
            String type = handler.type();
            if (type == null || !JobRequest.TYPE.matcher(type).matches()) {
                throw new IllegalStateException(handler.getClass().getName()
                        + " declares a job type that does not match " + JobRequest.TYPE.pattern() + ": " + type);
            }
            String previous = owners.put(type, handler.getClass().getName());
            if (previous != null) {
                throw new IllegalStateException("two job handlers declare the type " + type + ": "
                        + previous + " and " + handler.getClass().getName());
            }
            index.put(type, handler);
        }
        this.byType = Map.copyOf(index);
        this.types = Collections.unmodifiableSortedSet(new TreeSet<>(index.keySet()));
    }

    /** The types a worker polls, sorted so poll scheduling and logs are stable across restarts. */
    public Set<String> types() {
        return types;
    }

    public boolean isRegistered(String type) {
        return byType.containsKey(type);
    }

    /**
     * @throws IllegalStateException when nothing handles the type; a claimed job with no handler
     *         means the worker and the registry disagree, which must never be a silent skip
     */
    public JobHandler require(String type) {
        JobHandler handler = byType.get(type);
        if (handler == null) {
            throw new IllegalStateException("no job handler is registered for the type " + type);
        }
        return handler;
    }
}
