package com.tyndalehouse.step.core.service;

import java.util.List;

import org.joda.time.LocalDateTime;

import com.tyndalehouse.step.core.data.entities.HotSpot;
import com.tyndalehouse.step.core.data.entities.TimelineEvent;
import com.tyndalehouse.step.core.data.entities.aggregations.TimelineEventsAndDate;

/**
 * The timeline service gives access to all the data relating to the timeline the events, the configuration,
 * etc.
 * 
 * The timeline data is currently loaded from a CSV file and stored in the database
 * 
 * @author Chris
 * 
 */
public interface TimelineService {
    /**
     * Retrieves the whole configuration of the timeline. This defines a number of different bands, each with
     * their hotpots. Each timeband is given a suggested scale (or time unit - decade, century, month day),
     * etc. The hotspots also also given a unit.
     * 
     * @return a list of timebands with all the required details
     */
    List<HotSpot> getTimelineConfiguration();

    /**
     * Returns events that fall within a certain time period
     * 
     * @param from from date
     * @param to to date
     * @return a list of timeline events contained between the two dates
     */
    List<TimelineEvent> getTimelineEvents(LocalDateTime from, LocalDateTime to);

    /**
     * Given a reference to a passage, this looks up the relevant set of events
     * 
     * @param reference the reference to look up
     * @return a set of events and the date at which they occur
     */
    TimelineEventsAndDate getEventsFromScripture(String reference);
}
