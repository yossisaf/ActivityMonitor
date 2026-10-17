package com.example.activitymonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.activitymonitor.R
import com.example.activitymonitor.repository.AppIconCache

data class RecordingAppRow(
    val packageName: String,
    val name: String
)

/** Focused list for choosing which installed apps trigger screen recording. */
class RecordingAppAdapter(
    private val icons: AppIconCache,
    private val onSelectionChanged: (String, Boolean) -> Unit
) : RecyclerView.Adapter<RecordingAppAdapter.Holder>() {
    private var items: List<RecordingAppRow> = emptyList()
    private val selectedPackages = mutableSetOf<String>()

    fun submitList(value: List<RecordingAppRow>, selected: Set<String>) {
        items = value
        selectedPackages.clear()
        selectedPackages.addAll(selected)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording_app, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.recordAppIcon)
        private val name = view.findViewById<TextView>(R.id.recordAppName)
        private val packageName = view.findViewById<TextView>(R.id.recordAppPackage)
        private val check = view.findViewById<CheckBox>(R.id.recordAppCheck)

        fun bind(item: RecordingAppRow) {
            name.text = item.name
            packageName.text = item.packageName
            val drawable = icons.get(item.packageName)
            if (drawable != null) icon.setImageDrawable(drawable)
            else icon.setImageResource(R.drawable.ic_launcher_foreground)

            check.setOnCheckedChangeListener(null)
            check.isChecked = selectedPackages.contains(item.packageName)
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedPackages.add(item.packageName)
                else selectedPackages.remove(item.packageName)
                onSelectionChanged(item.packageName, checked)
            }
            itemView.setOnClickListener { check.toggle() }
            itemView.contentDescription =
                "${item.name}. ${if (check.isChecked) "נבחרה להסרטה" else "לא נבחרה להסרטה"}"
        }
    }
}
