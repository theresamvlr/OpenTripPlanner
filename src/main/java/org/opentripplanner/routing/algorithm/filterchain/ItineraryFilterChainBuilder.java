package org.opentripplanner.routing.algorithm.filterchain;

import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.routing.algorithm.filterchain.filters.DebugFilterWrapper;
import org.opentripplanner.routing.algorithm.filterchain.filters.FilterChain;
import org.opentripplanner.routing.algorithm.filterchain.filters.GroupByLegDistanceFilter;
import org.opentripplanner.routing.algorithm.filterchain.filters.LatestDepartureTimeFilter;
import org.opentripplanner.routing.algorithm.filterchain.filters.MaxLimitFilter;
import org.opentripplanner.routing.algorithm.filterchain.filters.OtpDefaultSortOrder;
import org.opentripplanner.routing.algorithm.filterchain.filters.ReduceTimeTableVariationFilter;
import org.opentripplanner.routing.algorithm.filterchain.filters.RemoveTransitIfStreetOnlyIsBetterFilter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;


/**
 * Create a filter chain based on the given config.
 */
public class ItineraryFilterChainBuilder {
    /**
     * Use a BIG negative number as unset value to prevent collisions with real values and
     * accidental overflow.
      */
    private static final int NOT_SET = -999_999;

    private final boolean arriveBy;
    private double groupByP = 0.68;
    private int minLimit = 3;
    private int maxLimit = 20;
    private Instant latestDepartureTimeLimit = null;
    private boolean removeTransitWithHigherCostThenWalkOnly = true;
    private int shortTransitSlackInSeconds = NOT_SET;
    private boolean debug;
    private Consumer<Itinerary> maxLimitReachedSubscriber;


    /** @param arriveBy Used to set the correct sort order.  */
    public ItineraryFilterChainBuilder(boolean arriveBy) {
        this.arriveBy = arriveBy;
    }

    /**
     * Max departure time. This is a absolute filter on the itinerary departure time from the
     * origin. This do not respect the {@link #setApproximateMinLimit(int)}.
     */
    public void setLatestDepartureTimeLimit(Instant latestDepartureTimeLimit) {
        this.latestDepartureTimeLimit = latestDepartureTimeLimit;
    }

    /**
     * Set a guideline for the minimum number of itineraries to return. Some filters may respect a
     * minimum number of elements to keep when filtering and stop reducing the number when this
     * limit is reached. This depend on the filter and the intended use case.
     * <p>
     * For example the group-by filter will keep 2 samples in each group if there is 2 groups and
     * the min-limit is 3 ~ keeping up to 4 itineraries (approximately 3).
     */
    public void setApproximateMinLimit(int minLimit) {
        this.minLimit = minLimit;
    }

    /**
     * The maximum number of itineraries returned. This will remove all itineraries at the
     * end of the list, just before the filter chain returns - this is the last step.
     */
    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    /**
     * Group by legs that account for more then 'p' % for the total distance.
     * Must be a number between 0.0 (0%) and 1.0 (100%).
     */
    public void setGroupByP(double groupByP) {
        this.groupByP = groupByP;
    }

    /**
     * If the maximum number of itineraries is exceeded, then the excess itineraries are removed.
     * To get notified about this a subscriber can be added. The first itinerary removed by the
     * {@code maxLimit} is retuned. The 'maxLimit' check is last thing happening in the
     * filter-chain after the final sort. So, if another filter remove an itinerary, the
     * itinerary is not considered with the respect to this feature.
     *
     * @param maxLimitReachedSubscriber the subscriber to notify in case any elements are removed.
     *                                  Only the first element removed is passed to the subscriber.
     */
    public void setMaxLimitReachedSubscriber(Consumer<Itinerary> maxLimitReachedSubscriber) {
        this.maxLimitReachedSubscriber = maxLimitReachedSubscriber;
    }
    /**
     * The direct street search(walk, bicycle, car) is not pruning the transit search, so in some
     * cases we get "silly" transit itineraries that is marginally better on travel-duration
     * compared with a on-street-all-the-way itinerary. Use this method to turn this filter
     * on/off.
     * <p>
     * The filter remove all itineraries with a generalized-cost that is higher than the best
     * on-street-all-the-way itinerary.
     * <p>
     * This filter is enabled by default.
     * <p>
     * This filter only have an effect, if an on-street-all-the-way(WALK, BICYCLE, CAR) itinerary
     * exist.
     */
    public void removeTransitWithHigherCostThanBestOnStreetOnly(boolean value) {
        this.removeTransitWithHigherCostThenWalkOnly = value;
    }

    /**
     * If the time-table-view is enabled, the result may contain similar itineraries where only the
     * first and/or last legs are different. This can happen by walking to/from another stop,
     * saving some time, but getting a higher generalized-cost; Or, by taking a short ride.
     * Setting the {@code shortTransitSlackInSeconds} will remove these itineraries an keep only
     * the itineraries with the lowest generalized-cost.
     * <p>
     * When the {@code shortTransitSlackInSeconds} is set, itineraries are grouped by the "main"
     * transit legs. The first and/or last leg is skipped if the duration is less than the given
     * value. Than for each group of itineraries the itinerary with the lowest generalized-cost
     * is kept. All other itineraries are dropped.
     * <p>
     * The default is NOT_SET(any negative number), witch will disable the filter.
     * <p>
     * Normally, we want some variation, so a good value to use for this parameter is the combined
     * cost of board- and alight-cost including indirect cost from board- and alight-slack.
     */
    public void setShortTransitSlackInSeconds(int shortTransitSlackInSeconds) {
        this.shortTransitSlackInSeconds = shortTransitSlackInSeconds;
    }

    /**
     * This will NOT delete itineraries, but tag them as deleted using the
     * {@link Itinerary#systemNotices}.
     */
    public void debug() {
        this.debug = true;
    }

    public ItineraryFilter build() {
        List<ItineraryFilter> filters = new ArrayList<>();

        if(removeTransitWithHigherCostThenWalkOnly) {
            filters.add(new RemoveTransitIfStreetOnlyIsBetterFilter());
        }

        if(shortTransitSlackInSeconds > 0) {
            filters.add(new ReduceTimeTableVariationFilter(shortTransitSlackInSeconds));
        }

        filters.add(new GroupByLegDistanceFilter(groupByP, minLimit, arriveBy));

        if (latestDepartureTimeLimit != null) {
            filters.add(new LatestDepartureTimeFilter(latestDepartureTimeLimit));
        }

        // Sort itineraries
        filters.add(new OtpDefaultSortOrder(arriveBy));

        // Remove itineraries if max limit is exceeded
        if (maxLimit >= minLimit) {
            filters.add(new MaxLimitFilter("number-of-itineraries-filter", maxLimit, maxLimitReachedSubscriber));
        }

        if(debug) {
            filters = addDebugWrappers(filters);
        }

        return new FilterChain(filters);
    }


    /* private methods */

    private List<ItineraryFilter> addDebugWrappers(List<ItineraryFilter> filters) {
        final DebugFilterWrapper.Factory factory = new DebugFilterWrapper.Factory();
        return filters.stream().map(factory::wrap).collect(Collectors.toList());
    }
}