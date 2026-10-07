package com.example.activitymonitor.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.activitymonitor.R
import java.io.File
import java.util.Locale

class RecordingAdapter(
    private val items: List<File>,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.recordingName)
        private val meta = view.findViewById<TextView>(R.id.recordingMeta)
        private val delete = view.findViewById<Button>(R.id.recordingDelete)

        fun bind(file: File) {
            name.text = file.name
            meta.text = Formatters.dateTime(file.lastModified()) + " • " + formatSize(file.length())
            view.setOnClickListener {
                val context = view.context
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".files",
                    file
                )
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                }
            }
            delete.setOnClickListener {
                android.app.AlertDialog.Builder(view.context)
                    .setTitle("מחיקת הקלטה")
                    .setMessage("למחוק את ההקלטה הזו?")
                    .setNegativeButton("ביטול", null)
                    .setPositiveButton("מחק") { _, _ ->
                        if (file.delete()) onDelete(file)
                    }
                    .show()
            }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        }
    }
}
