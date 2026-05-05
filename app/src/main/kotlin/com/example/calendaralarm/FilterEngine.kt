package com.example.calendaralarm

object FilterEngine {

    data class EventFlags(
        val alarmEnabled: Boolean = true,
        val visible: Boolean = true,
        val advanceMinutes: Int? = null  // null = use global default
    )

    /** Evaluate all filters against an event; later filters override earlier ones. */
    fun evaluate(event: CalendarEvent, filters: List<EventFilter>): EventFlags {
        var alarmEnabled = true
        var visible      = true
        var advanceMin: Int? = null
        for (filter in filters) {
            if (matches(event, filter.criteria)) {
                for (action in filter.actions) when (action) {
                    is FilterAction.VisibilityAction -> visible      = action.show
                    is FilterAction.AlarmAction      -> alarmEnabled = action.enable
                    is FilterAction.AdvanceAction    -> advanceMin   = action.minutes
                }
            }
        }
        return EventFlags(alarmEnabled, visible, advanceMin)
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
