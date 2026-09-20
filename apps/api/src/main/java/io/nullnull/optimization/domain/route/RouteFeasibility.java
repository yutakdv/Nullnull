package io.nullnull.optimization.domain.route;

import io.nullnull.optimization.domain.route.DirectedRouteMatrix.Leg;
import io.nullnull.optimization.domain.route.RouteInfeasibility.Reason;
import io.nullnull.trip.domain.ItemLock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Whether one ordered day of stops survives what is actually known about it (BA-083 step 2).
 *
 * <p>This is the layer the card's safety boundary asks for: <i>a travel time derived from distance
 * and speed is never treated as a successful route.</i> There is no such derivation in this file and
 * no place to put one - the only source of a duration is {@link DirectedRouteMatrix}, and every state
 * it can be in other than {@link Leg.Available} produces a named reason.
 *
 * <p><b>It is pure.</b> No clock, no randomness, no I/O, no database. The day, the departure time,
 * the stops, the matrix and the hours all arrive as arguments, so the same inputs always give the
 * same verdict and a test does not have to arrange the world to ask a question. Persisting any of it
 * is forbidden anyway (A-055), which is the other half of why nothing here reaches outward.
 *
 * <h2>Why a missing leg stops the walk</h2>
 *
 * <p>Arrival times are cumulative. Once one leg's duration is unknown, every later arrival is
 * unknown too, and a verdict about those stops would be a statement this layer cannot support - in
 * either direction. Reporting them as infeasible would be as invented as reporting them as fine. So
 * the walk stops at the first route gap and the verdict carries the one reason it can stand behind.
 *
 * <p>Hours and locks are different: they are checked against an arrival this layer did compute, so a
 * violation is recorded and the walk continues. A day with three closed places reports three reasons.
 */
public final class RouteFeasibility {

    /**
     * The outcome of one verification.
     *
     * <p>{@code completedAt} is non-null exactly when the day is feasible. A day with any reason has
     * no meaningful finish time - either the walk was cut short at a route gap, or the schedule it
     * would describe is one the rules just rejected - and returning a time for it would invite a
     * caller to show a number that nothing stands behind.
     */
    public record Verdict(List<RouteInfeasibility> reasons, LocalTime completedAt) {

        public Verdict {
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            if (reasons.isEmpty() != (completedAt != null)) {
                throw new IllegalArgumentException("completedAt is present exactly when there are no reasons");
            }
        }

        public boolean feasible() {
            return reasons.isEmpty();
        }
    }

    private RouteFeasibility() {
    }

    /**
     * Verify {@code stops} in the order given, leaving {@code departAt} on {@code date}.
     *
     * @param windows the curated reading for each place <em>on this date</em>, keyed by place id. A
     *     place with no entry is unverified, never open - see {@link OpeningWindow}.
     */
    public static Verdict verify(LocalDate date, LocalTime departAt, List<PlannedStop> stops,
            DirectedRouteMatrix matrix, Map<UUID, OpeningWindow> windows) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(departAt, "departAt");
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(windows, "windows");
        List<PlannedStop> itinerary = List.copyOf(Objects.requireNonNull(stops, "stops"));
        requireDistinctKeys(itinerary);

        List<RouteInfeasibility> reasons = new ArrayList<>();
        LocalDateTime cursor = LocalDateTime.of(date, departAt);
        for (int index = 0; index < itinerary.size(); index++) {
            PlannedStop stop = itinerary.get(index);
            if (index > 0) {
                String from = itinerary.get(index - 1).key();
                Leg leg = matrix.leg(from, stop.key());
                switch (leg) {
                    case Leg.Available available -> cursor = cursor.plus(available.travelTime());
                    case Leg.Unavailable ignored -> {
                        reasons.add(RouteInfeasibility.between(Reason.ROUTE_UNAVAILABLE, from, stop.key()));
                        return cutShort(reasons);
                    }
                    case Leg.Absent ignored -> {
                        reasons.add(RouteInfeasibility.between(Reason.ROUTE_ABSENT, from, stop.key()));
                        return cutShort(reasons);
                    }
                }
            }
            if (!cursor.toLocalDate().equals(date)) {
                reasons.add(RouteInfeasibility.at(Reason.DAY_OVERFLOW, stop.key()));
                return cutShort(reasons);
            }
            LocalDateTime arrival = cursor;
            LocalDateTime start = waitsForReservation(stop, date, arrival);
            LocalDateTime departure = start.plus(stop.dwell());
            checkHours(stop, date, start, departure, windows, reasons);
            checkLock(stop, date, arrival, departure, reasons);
            cursor = departure;
        }
        if (!reasons.isEmpty()) {
            return new Verdict(reasons, null);
        }
        return new Verdict(List.of(), cursor.toLocalTime());
    }

    /**
     * A booking you reach early is waited out, not started early. The wait only applies when the
     * reservation is for the day being verified; a reservation on another date is a mismatch that
     * {@link #checkLock} reports, and honouring its clock here would shift the whole day around a
     * lock that does not belong to it.
     */
    private static LocalDateTime waitsForReservation(PlannedStop stop, LocalDate date, LocalDateTime arrival) {
        for (ItemLock lock : stop.locks()) {
            if (lock instanceof ItemLock.Reservation reservation
                    && reservation.date().equals(date)
                    && arrival.toLocalTime().isBefore(reservation.startTime())) {
                return LocalDateTime.of(date, reservation.startTime());
            }
        }
        return arrival;
    }

    /** The whole visit has to fit inside a window somebody verified. */
    private static void checkHours(PlannedStop stop, LocalDate date, LocalDateTime start,
            LocalDateTime departure, Map<UUID, OpeningWindow> windows, List<RouteInfeasibility> reasons) {
        OpeningWindow window = windows.get(stop.placeId());
        if (window == null) {
            reasons.add(RouteInfeasibility.at(Reason.HOURS_UNVERIFIED, stop.key()));
            return;
        }
        if (window.state() == OpeningWindow.State.CLOSED) {
            reasons.add(RouteInfeasibility.at(Reason.PLACE_CLOSED, stop.key()));
            return;
        }
        LocalDateTime opens = LocalDateTime.of(date, window.opensAt());
        LocalDateTime closes = LocalDateTime.of(date, window.closesAt());
        if (start.isBefore(opens) || departure.isAfter(closes)) {
            reasons.add(RouteInfeasibility.at(Reason.OUTSIDE_OPENING_WINDOW, stop.key()));
        }
    }

    /**
     * The locks that say something about <em>when</em>. Each type is checked on its own fields only,
     * which is what keeps the four independent (invariant 7); MUST_VISIT says nothing about time and
     * is deliberately inert here - see {@link Reason}.
     *
     * <p><b>Every lock the stop carries is checked, not the first one.</b> A stop may hold one of
     * each type at once, and independence means a DATE that matches does not excuse a TIME that is
     * missed. Each failing lock adds its own reason, in the order the stop lists them, so a caller
     * sees all of them rather than whichever came first.
     */
    private static void checkLock(PlannedStop stop, LocalDate date, LocalDateTime arrival,
            LocalDateTime departure, List<RouteInfeasibility> reasons) {
        for (ItemLock lock : stop.locks()) {
            switch (lock) {
                case ItemLock.MustVisit ignored -> {
                    // Says the place stays in the trip, not when it is visited. Inert here.
                }
                case ItemLock.Date held -> {
                    if (!held.date().equals(date)) {
                        reasons.add(RouteInfeasibility.at(Reason.DATE_LOCK_MISMATCH, stop.key()));
                    }
                }
                case ItemLock.Time held -> {
                    LocalDateTime pinned = LocalDateTime.of(date, held.startTime());
                    if (Duration.between(pinned, arrival).abs().toMinutes() > held.toleranceMinutes()) {
                        reasons.add(RouteInfeasibility.at(Reason.TIME_LOCK_MISSED, stop.key()));
                    }
                }
                case ItemLock.Reservation held ->
                        checkReservation(held, stop, date, arrival, departure, reasons);
            }
        }
    }

    private static void checkReservation(ItemLock.Reservation held, PlannedStop stop, LocalDate date,
            LocalDateTime arrival, LocalDateTime departure, List<RouteInfeasibility> reasons) {
        if (!held.date().equals(date)) {
            reasons.add(RouteInfeasibility.at(Reason.RESERVATION_DATE_MISMATCH, stop.key()));
            return;
        }
        if (arrival.isAfter(LocalDateTime.of(date, held.startTime()))) {
            reasons.add(RouteInfeasibility.at(Reason.RESERVATION_ARRIVAL_LATE, stop.key()));
        }
        if (held.endTime() != null && departure.isAfter(LocalDateTime.of(date, held.endTime()))) {
            reasons.add(RouteInfeasibility.at(Reason.RESERVATION_DEPARTURE_OVERRUNS, stop.key()));
        }
    }

    private static Verdict cutShort(List<RouteInfeasibility> reasons) {
        return new Verdict(reasons, null);
    }

    private static void requireDistinctKeys(List<PlannedStop> stops) {
        Set<String> seen = new HashSet<>();
        for (PlannedStop stop : stops) {
            if (!seen.add(stop.key())) {
                throw new IllegalArgumentException("a stop key appears twice in one day");
            }
        }
    }
}
