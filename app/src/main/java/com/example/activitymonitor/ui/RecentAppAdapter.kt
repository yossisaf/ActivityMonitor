package com.example.activitymonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.activitymonitor.R
import com.example.activitymonitor.db.ApplicationSessionEntity
import com.example.activitymonitor.repository.AppIconCache

class RecentAppAdapter(
    private val icons: AppIconCache,
    private val onClick: (ApplicationSessionEntity) -> Unit
) : RecyclerView.Adapter<RecentAppAdapter.Holder>() {
    private var items: List<ApplicationSessionEntity> = emptyList()

    fun submitList(value: List<ApplicationSessionEntity>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recent_app, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.recentAppIcon)
        private val name = view.findViewById<TextView>(R.id.recentAppName)
        private val time = view.findViewById<TextView>(R.id.recentAppTime)
        private val duration = view.findViewById<TextView>(R.id.recentAppDuration)

        fun bind(item: ApplicationSessionEntity) {
            name.text = item.appName
            time.text = "נפתחה \${Formatters.dateTime(item.startTime)}"
            duration.text = if (item.endTime == null) "פעילה כעת" else "משך שימוש: \${Formatters.duration(item.duration)}"
            icons.get(item.packageName)?.let { icon.setImageDrawable(it) }
                ?: icon.setImageResource(R.drawable.ic_launcher_foreground)
            itemView.setOnClickListener { onClick(item) }
            itemView.contentDescription = "\${item.appName}, \${time.text}"
        }
    }
}
