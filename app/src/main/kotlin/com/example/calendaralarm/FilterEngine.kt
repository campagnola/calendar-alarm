package com.example.calendaralarm

object FilterEngine {

    data class EventFlags(
        val alarmEnabled: Boolean = true,
        val visible: Boolean = true,
        val advanceMinutes: Int? = null,         // null = use global default
        val advanceRelativeToEnd: Boolean = false,
        val quietHoursMode: FilterAction.QuietHoursMode? = null
    ) {
        fun advanceMinutesOr(default: Int): Int = advanceMinutes ?: default
    }

    /** Evaluate all filters against an event; later filters override earlier ones. */
    fun evaluate(event: CalendarEvent, filters: List<EventFilter>): EventFlags {
        var alarmEnabled        = true
        var visible             = true
        var advanceMin: Int?    = null
        var relativeToEnd       = false
        var quietHoursMode: FilterAction.QuietHoursMode? = null
        for (filter in filters) {
            if (matches(event, filter.criteria)) {
                for (action in filter.actions) when (action) {
                    is FilterAction.VisibilityAction -> visible       = action.show
                    is FilterAction.AlarmAction      -> alarmEnabled  = action.enable
                    is FilterAction.AdvanceAction    -> {
                        advanceMin    = action.minutes
                        relativeToEnd = action.relativeToEnd
                    }
                    is FilterAction.QuietHoursAction -> quietHoursMode = action.mode
                }
            }
        }
        return EventFlags(alarmEnabled, visible, advanceMin, relativeToEnd, quietHoursMode)
    }

    /** Filter flags per event, overridden by series then per-event preferences. */
    fun effectiveFlags(
        events: List<CalendarEvent>,
        filters: List<EventFilter>,
        preferences: List<EventPreference>
    ): Map<CalendarEvent, EventFlags> {
        val prefByKey = preferences.associateBy { it.key }
        return events.associateWith { event ->
            var flags = evaluate(event, filters)
            alarmOverride(event, prefByKey)?.let { (_, enabled) -> flags = flags.copy(alarmEnabled = enabled) }
            advanceOverride(event, prefByKey)?.let {
                flags = flags.copy(advanceMinutes = it, advanceRelativeToEnd = false)
            }
            flags
        }
    }

    /** The per-event advance warning (minutes before start) overriding filters and the default. */
    fun advanceOverride(event: CalendarEvent, prefByKey: Map<String, EventPreference>): Int? =
        prefByKey[EventPreference.eventKey(event)]?.advanceMinutes

    /** The preference overriding [event]'s filter alarm setting, as (scope, enabled): per-event wins over series. */
    fun alarmOverride(event: CalendarEvent, prefByKey: Map<String, EventPreference>): Pair<EventPreference.Scope, Boolean>? {
        prefByKey[EventPreference.eventKey(event)]?.alarmEnabled?.let { return EventPreference.Scope.EVENT to it }
        if (event.isSeries) {
            prefByKey[EventPreference.seriesKey(event)]?.alarmEnabled?.let { return EventPreference.Scope.SERIES to it }
        }
        return null
    }

    /** True if the event satisfies all criteria (empty list = matches everything). */
    fun matches(event: CalendarEvent, criteria: List<FilterCriterion>): Boolean =
        criteria.isEmpty() || criteria.all { matchOne(event, it) }

    private fun matchOne(event: CalendarEvent, criterion: FilterCriterion): Boolean = when (criterion) {
        is FilterCriterion.SourceCriterion ->
            event.sourceId == criterion.sourceId
        is FilterCriterion.StringCriterion -> when (criterion.field) {
            FilterCriterion.StringField.EVENT_NAME ->
                matchesOp(event.summary, criterion.op, criterion.value)
            FilterCriterion.StringField.LOCATION ->
                matchesOp(event.location, criterion.op, criterion.value)
            FilterCriterion.StringField.SENDER ->
                matchesOp(event.organizer, criterion.op, criterion.value)
            FilterCriterion.StringField.ATTENDEES ->
                matchesAttendees(event.attendees, criterion.op, criterion.value)
        }
    }

    private fun matchesOp(field: String, op: FilterCriterion.StringOp, value: String): Boolean =
        when (op) {
            FilterCriterion.StringOp.CONTAINS     ->  field.contains(value, ignoreCase = true)
            FilterCriterion.StringOp.NOT_CONTAINS -> !field.contains(value, ignoreCase = true)
            FilterCriterion.StringOp.EQUALS       ->  field.equals(value, ignoreCase = true)
            FilterCriterion.StringOp.NOT_EQUALS   -> !field.equals(value, ignoreCase = true)
        }

    // For attendees each entry is checked individually; NOT variants require no entry to match.
    private fun matchesAttendees(attendees: List<String>, op: FilterCriterion.StringOp, value: String): Boolean =
        when (op) {
            FilterCriterion.StringOp.CONTAINS     ->  attendees.any { it.contains(value, ignoreCase = true) }
            FilterCriterion.StringOp.NOT_CONTAINS ->  attendees.none { it.contains(value, ignoreCase = true) }
            FilterCriterion.StringOp.EQUALS       ->  attendees.any { it.equals(value, ignoreCase = true) }
            FilterCriterion.StringOp.NOT_EQUALS   ->  attendees.none { it.equals(value, ignoreCase = true) }
        }
}
