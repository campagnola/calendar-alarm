package com.example.calendaralarm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.calendaralarm.databinding.ActivityFilterSettingsBinding
import com.example.calendaralarm.databinding.ItemFilterActionBinding
import com.example.calendaralarm.databinding.ItemFilterCriterionBinding
import java.util.UUID

class FilterSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILTER_ID = "filter_id"

        private val CRITERION_TYPES = listOf("Source calendar", "Event name", "Sender", "Attendees")
        private val STRING_OPS      = FilterCriterion.StringOp.values().map { it.display }
        private val ACTION_TYPES    = listOf("Visibility", "Alarm", "Advance warning")
        private val VISIBILITY_OPTS = listOf("Show", "Hide")
        private val ALARM_OPTS      = listOf("Enable", "Disable")
    }

    private lateinit var binding: ActivityFilterSettingsBinding
    private lateinit var prefs: Prefs
    private var filterId: String? = null
    private var isNew = false

    private var sourceIds:   List<String> = emptyList()
    private var sourceNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilterSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs    = Prefs(this)
        filterId = intent.getStringExtra(EXTRA_FILTER_ID)
        isNew    = filterId == null

        supportActionBar?.title = if (isNew) "New filter" else "Edit filter"

        val urls = prefs.icsSources
        sourceIds   = urls.map { prefs.urlId(it) }
        sourceNames = urls.map { url -> prefs.icsSourceName(prefs.urlId(url)).ifEmpty { url } }

        val filter = filterId?.let { prefs.getFilter(it) }
        if (filter != null) {
            binding.nameInput.setText(filter.name)
            filter.criteria.forEach { addCriterionView(it) }
            filter.actions.forEach  { addActionView(it) }
        }

        binding.deleteButton.visibility = if (isNew) View.GONE else View.VISIBLE

        binding.addCriterionButton.setOnClickListener { addCriterionView(null) }
        binding.addActionButton.setOnClickListener    { addActionView(null) }
        binding.saveButton.setOnClickListener         { save() }
        binding.deleteButton.setOnClickListener       { confirmDelete() }
    }

    // ── Criterion rows ────────────────────────────────────────────────────────

    private fun addCriterionView(criterion: FilterCriterion?) {
        val b = ItemFilterCriterionBinding.inflate(LayoutInflater.from(this),
                                                   binding.criteriaContainer, false)

        b.typeDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, CRITERION_TYPES))
        b.sourceDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sourceNames))
        b.opDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, STRING_OPS))

        fun showForType(idx: Int) {
            b.sourceLayout.visibility = if (idx == 0) View.VISIBLE else View.GONE
            b.stringLayout.visibility = if (idx != 0) View.VISIBLE else View.GONE
        }

        b.typeDropdown.setOnItemClickListener { _, _, pos, _ -> showForType(pos) }

        when (criterion) {
            is FilterCriterion.SourceCriterion -> {
                b.typeDropdown.setText(CRITERION_TYPES[0], false)
                showForType(0)
                val idx = sourceIds.indexOf(criterion.sourceId)
                if (idx >= 0) b.sourceDropdown.setText(sourceNames[idx], false)
            }
            is FilterCriterion.StringCriterion -> {
                val typeIdx = when (criterion.field) {
                    FilterCriterion.StringField.EVENT_NAME -> 1
                    FilterCriterion.StringField.SENDER     -> 2
                    FilterCriterion.StringField.ATTENDEES  -> 3
                }
                b.typeDropdown.setText(CRITERION_TYPES[typeIdx], false)
                showForType(typeIdx)
                b.opDropdown.setText(criterion.op.display, false)
                b.valueInput.setText(criterion.value)
            }
            null -> {
                b.typeDropdown.setText(CRITERION_TYPES[0], false)
                showForType(0)
            }
        }

        b.removeButton.setOnClickListener { binding.criteriaContainer.removeView(b.root) }
        binding.criteriaContainer.addView(b.root)
    }

    private fun readCriteria(): List<FilterCriterion> {
        val result = mutableListOf<FilterCriterion>()
        repeat(binding.criteriaContainer.childCount) { i ->
            val b = ItemFilterCriterionBinding.bind(binding.criteriaContainer.getChildAt(i))
            val typeIdx = CRITERION_TYPES.indexOf(b.typeDropdown.text.toString())
            when (typeIdx) {
                0 -> {
                    val nameText = b.sourceDropdown.text.toString()
                    val srcIdx = sourceNames.indexOf(nameText)
                    if (srcIdx >= 0) result.add(FilterCriterion.SourceCriterion(sourceIds[srcIdx]))
                }
                in 1..3 -> {
                    val field = when (typeIdx) {
                        1    -> FilterCriterion.StringField.EVENT_NAME
                        2    -> FilterCriterion.StringField.SENDER
                        else -> FilterCriterion.StringField.ATTENDEES
                    }
                    val opIdx = STRING_OPS.indexOf(b.opDropdown.text.toString())
                    val op    = FilterCriterion.StringOp.values().getOrElse(opIdx) { FilterCriterion.StringOp.CONTAINS }
                    result.add(FilterCriterion.StringCriterion(field, op, b.valueInput.text.toString()))
                }
            }
        }
        return result
    }

    // ── Action rows ───────────────────────────────────────────────────────────

    private fun addActionView(action: FilterAction?) {
        val b = ItemFilterActionBinding.inflate(LayoutInflater.from(this),
                                                binding.actionsContainer, false)

        b.actionTypeDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ACTION_TYPES))

        fun showForActionType(idx: Int) {
            b.toggleLayout.visibility  = View.GONE
            b.advanceLayout.visibility = View.GONE
            when (idx) {
                0 -> {
                    b.toggleDropdown.setAdapter(
                        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, VISIBILITY_OPTS))
                    b.toggleLayout.visibility = View.VISIBLE
                }
                1 -> {
                    b.toggleDropdown.setAdapter(
                        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ALARM_OPTS))
                    b.toggleLayout.visibility = View.VISIBLE
                }
                2 -> b.advanceLayout.visibility = View.VISIBLE
            }
        }

        b.actionTypeDropdown.setOnItemClickListener { _, _, pos, _ ->
            b.toggleDropdown.setText("", false)
            showForActionType(pos)
        }

        when (action) {
            is FilterAction.VisibilityAction -> {
                b.actionTypeDropdown.setText(ACTION_TYPES[0], false)
                showForActionType(0)
                b.toggleDropdown.setText(if (action.show) VISIBILITY_OPTS[0] else VISIBILITY_OPTS[1], false)
            }
            is FilterAction.AlarmAction -> {
                b.actionTypeDropdown.setText(ACTION_TYPES[1], false)
                showForActionType(1)
                b.toggleDropdown.setText(if (action.enable) ALARM_OPTS[0] else ALARM_OPTS[1], false)
            }
            is FilterAction.AdvanceAction -> {
                b.actionTypeDropdown.setText(ACTION_TYPES[2], false)
                showForActionType(2)
                b.advanceInput.setText(action.minutes.toString())
            }
            null -> {
                b.actionTypeDropdown.setText(ACTION_TYPES[0], false)
                showForActionType(0)
                b.toggleDropdown.setText(VISIBILITY_OPTS[0], false)
            }
        }

        b.removeButton.setOnClickListener { binding.actionsContainer.removeView(b.root) }
        binding.actionsContainer.addView(b.root)
    }

    private fun readActions(): List<FilterAction> {
        val result = mutableListOf<FilterAction>()
        repeat(binding.actionsContainer.childCount) { i ->
            val b = ItemFilterActionBinding.bind(binding.actionsContainer.getChildAt(i))
            when (ACTION_TYPES.indexOf(b.actionTypeDropdown.text.toString())) {
                0 -> result.add(FilterAction.VisibilityAction(
                        b.toggleDropdown.text.toString() == VISIBILITY_OPTS[0]))
                1 -> result.add(FilterAction.AlarmAction(
                        b.toggleDropdown.text.toString() == ALARM_OPTS[0]))
                2 -> result.add(FilterAction.AdvanceAction(
                        b.advanceInput.text.toString().toIntOrNull() ?: 5))
            }
        }
        return result
    }

    // ── Save / Delete ─────────────────────────────────────────────────────────

    private fun save() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a filter name", Toast.LENGTH_SHORT).show()
            return
        }
        val filter = EventFilter(
            id       = filterId ?: UUID.randomUUID().toString(),
            name     = name,
            criteria = readCriteria().toMutableList(),
            actions  = readActions().toMutableList()
        )
        prefs.saveFilter(filter)
        Toast.makeText(this, if (isNew) "Filter added" else "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Remove filter")
            .setMessage("Remove this filter?")
            .setPositiveButton("Remove") { _, _ -> filterId?.let { prefs.deleteFilter(it) }; finish() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
