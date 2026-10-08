package com.example.calendaralarm

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class EventFilter(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    val criteria: MutableList<FilterCriterion> = mutableListOf(),
    val actions: MutableList<FilterAction> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("criteria", JSONArray(criteria.map { it.toJson() }))
        put("actions", JSONArray(actions.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): EventFilter {
            val ca = json.getJSONArray("criteria")
            val criteria = (0 until ca.length()).mapTo(mutableListOf()) { FilterCriterion.fromJson(ca.getJSONObject(it)) }

            val aa = json.getJSONArray("actions")
            val actions = (0 until aa.length()).mapTo(mutableListOf()) { FilterAction.fromJson(aa.getJSONObject(it)) }

            return EventFilter(
                id       = json.getString("id"),
                name     = json.optString("name", ""),
                criteria = criteria,
                actions  = actions
            )
        }
    }
}

sealed class FilterCriterion {
    enum class StringField(val display: String) {
        EVENT_NAME("Event name"), LOCATION("Location"), SENDER("Sender"), ATTENDEES("Attendees")
    }
    enum class StringOp(val display: String) {
        CONTAINS("contains"), NOT_CONTAINS("does not contain"),
        EQUALS("equals"), NOT_EQUALS("not equals")
    }

    data class SourceCriterion(val sourceId: String) : FilterCriterion()
    data class StringCriterion(val field: StringField, val op: StringOp, val value: String) : FilterCriterion()

    fun toJson(): JSONObject = when (this) {
        is SourceCriterion -> JSONObject().put("type", "source").put("sourceId", sourceId)
        is StringCriterion -> JSONObject()
            .put("type", field.name).put("op", op.name).put("value", value)
    }

    companion object {
        fun fromJson(json: JSONObject): FilterCriterion =
            when (val type = json.getString("type")) {
                "source" -> SourceCriterion(json.getString("sourceId"))
                else     -> StringCriterion(
                    StringField.valueOf(type),
                    StringOp.valueOf(json.getString("op")),
                    json.optString("value", "")
                )
            }
    }
}

sealed class FilterAction {
    enum class QuietHoursMode(val display: String) {
        SILENT("Silent - notify without sound or vibration"),
        EARLY("Early - alarm at start of quiet hours"),
        ALARM("Alarm - ignore quiet hours")
    }

    data class VisibilityAction(val show: Boolean)    : FilterAction()
    data class AlarmAction(val enable: Boolean)       : FilterAction()
    data class AdvanceAction(val minutes: Int, val relativeToEnd: Boolean = false) : FilterAction()
    data class QuietHoursAction(val mode: QuietHoursMode) : FilterAction()

    fun toJson(): JSONObject = when (this) {
        is VisibilityAction -> JSONObject().put("type", "visibility").put("show", show)
        is AlarmAction      -> JSONObject().put("type", "alarm").put("enable", enable)
        is AdvanceAction    -> JSONObject().put("type", "advance").put("minutes", minutes)
                                          .put("relativeToEnd", relativeToEnd)
        is QuietHoursAction -> JSONObject().put("type", "quiet_hours").put("mode", mode.name)
    }

    companion object {
        fun fromJson(json: JSONObject): FilterAction =
            when (val type = json.getString("type")) {
                "visibility" -> VisibilityAction(json.getBoolean("show"))
                "alarm"      -> AlarmAction(json.getBoolean("enable"))
                "advance"    -> AdvanceAction(json.getInt("minutes"),
                                              json.optBoolean("relativeToEnd", false))
                "quiet_hours" -> QuietHoursAction(
                    QuietHoursMode.valueOf(json.getString("mode"))
                )
                else         -> throw IllegalArgumentException("unknown filter action type '$type'")
            }
    }
}
