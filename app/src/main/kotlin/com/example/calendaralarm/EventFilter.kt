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
            val criteria = mutableListOf<FilterCriterion>()
            val ca = json.optJSONArray("criteria") ?: JSONArray()
            for (i in 0 until ca.length()) FilterCriterion.fromJson(ca.getJSONObject(i))?.let { criteria.add(it) }

            val actions = mutableListOf<FilterAction>()
            val aa = json.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until aa.length()) FilterAction.fromJson(aa.getJSONObject(i))?.let { actions.add(it) }

            return EventFilter(
                id       = json.optString("id", UUID.randomUUID().toString()),
                name     = json.optString("name", ""),
                criteria = criteria,
                actions  = actions
            )
        }
    }
}

sealed class FilterCriterion {
    enum class StringField(val display: String) {
        EVENT_NAME("Event name"), SENDER("Sender"), ATTENDEES("Attendees")
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
        fun fromJson(json: JSONObject): FilterCriterion? = runCatching {
            when (val type = json.getString("type")) {
                "source" -> SourceCriterion(json.getString("sourceId"))
                else     -> StringCriterion(
                    StringField.valueOf(type),
                    StringOp.valueOf(json.getString("op")),
                    json.optString("value", "")
                )
            }
        }.getOrNull()
    }
}

sealed class FilterAction {
    data class VisibilityAction(val show: Boolean)    : FilterAction()
    data class AlarmAction(val enable: Boolean)       : FilterAction()
    data class AdvanceAction(val minutes: Int)        : FilterAction()

    fun toJson(): JSONObject = when (this) {
        is VisibilityAction -> JSONObject().put("type", "visibility").put("show", show)
        is AlarmAction      -> JSONObject().put("type", "alarm").put("enable", enable)
        is AdvanceAction    -> JSONObject().put("type", "advance").put("minutes", minutes)
    }

    companion object {
        fun fromJson(json: JSONObject): FilterAction? = runCatching {
            when (json.getString("type")) {
                "visibility" -> VisibilityAction(json.getBoolean("show"))
                "alarm"      -> AlarmAction(json.getBoolean("enable"))
                "advance"    -> AdvanceAction(json.getInt("minutes"))
                else         -> null
            }
        }.getOrNull()
    }
}
