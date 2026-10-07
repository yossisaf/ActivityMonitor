package com.example.activitymonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.activitymonitor.R
import com.example.activitymonitor.repository.AppIconCache

class AppAdapter(
    private val icons: AppIconCache,
    private val onToggle: (String, Boolean) -> Unit,
    private val onRecordingToggle: (String, Boolean) -> Unit,
    private val onClick: (AppRow) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {
    private var items: List<AppRow> = emptyList()

    fun submitList(value: List<AppRow>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.appIcon)
        private val name = view.findViewById<TextView>(R.id.appName)
        private val metrics = view.findViewById<TextView>(R.id.appMetrics)
        private val last = view.findViewById<TextView>(R.id.appLast)
        private val toggle = view.findViewById<Switch>(R.id.appToggle)
        private val recordToggle = view.findViewById<Switch>(R.id.appRecordToggle)

        fun bind(item: AppRow) {
            name.text = item.name
            metrics.text = "היום: ${Formatters.duration(item.todayMs)} • השבוע: ${Formatters.duration(item.weekMs)}\nהפעלות: ${item.openings} • פעולות: ${item.events}"
            last.text = "פעולה אחרונה: ${item.lastAction ?: "אין נתונים"}"
            icons.get(item.packageName)?.let { icon.setImageDrawable(it) }
                ?: icon.setImageResource(R.drawable.ic_launcher_foreground)

            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = item.enabled
            toggle.setOnCheckedChangeListener { _, checked -> onToggle(item.packageName, checked) }

            recordToggle.setOnCheckedChangeListener(null)
            recordToggle.isChecked = item.screenRecord
            recordToggle.setOnCheckedChangeListener { _, checked -> onRecordingToggle(item.packageName, checked) }

            itemView.setOnClickListener { onClick(item) }
            itemView.contentDescription =
                "${item.name}. לחיצה מציגה את הפעילות. ניתן בנפרד להפעיל מעקב ותיעוד מסך."
        }
    }
}

data class AppRow(
    val packageName: String,
    val name: String,
    val todayMs: Long,
    val weekMs: Long,
    val openings: Int,
    val events: Int,
    val lastAction: String?,
    val enabled: Boolean,
    val screenRecord: Boolean
)
