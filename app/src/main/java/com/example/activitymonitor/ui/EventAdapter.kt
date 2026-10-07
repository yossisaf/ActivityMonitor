package com.example.activitymonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.activitymonitor.R
import com.example.activitymonitor.db.ActivityEventEntity
import com.example.activitymonitor.repository.AppIconCache

class EventAdapter(private val icons: AppIconCache, private val onClick: (ActivityEventEntity) -> Unit) : RecyclerView.Adapter<EventAdapter.Holder>() {
    private var items: List<ActivityEventEntity> = emptyList()
    fun submitList(value: List<ActivityEventEntity>) { items = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.eventIcon)
        private val app = view.findViewById<TextView>(R.id.eventApp)
        private val time = view.findViewById<TextView>(R.id.eventTime)
        private val desc = view.findViewById<TextView>(R.id.eventDescription)
        private val text = view.findViewById<TextView>(R.id.eventText)
        fun bind(item: ActivityEventEntity) {
            app.text = item.appName; time.text = "${Formatters.time(item.timestamp)} • ${Formatters.shortDate(item.timestamp)}"; desc.text = item.eventDescription
            text.text = listOfNotNull(item.text?.takeIf { it.isNotBlank() }, item.contentDescription?.takeIf { it.isNotBlank() }).joinToString(" • ").ifBlank { "המידע אינו זמין מאפליקציית המקור" }
            icons.get(item.packageName)?.let { icon.setImageDrawable(it) } ?: icon.setImageResource(R.drawable.ic_launcher_foreground)
            itemView.setOnClickListener { onClick(item) }
            itemView.contentDescription = "${item.appName}, ${item.eventDescription}, ${Formatters.dateTime(item.timestamp)}"
        }
    }
}
