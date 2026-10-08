package com.example.activitymonitor.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.activitymonitor.R
import com.example.activitymonitor.VideoPlayerActivity
import java.io.File
import java.util.Locale

class RecordingAdapter(
    private val items: List<File>,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.recordingName)
        private val meta = view.findViewById<TextView>(R.id.recordingMeta)
        private val play = view.findViewById<Button>(R.id.recordingPlay)
        private val delete = view.findViewById<Button>(R.id.recordingDelete)

        fun bind(file: File) {
            val appPackage = packageNameFromFile(file)
            val appName = resolveAppName(appPackage) ?: "סרטון תיעוד מסך"
            name.text = appName

            val duration = videoDuration(file)
            val durationText = duration?.let { " • ${formatDuration(it)}" } ?: ""
            meta.text = Formatters.dateTime(file.lastModified()) +
                durationText +
                " • " + formatSize(file.length())

            fun openVideo() {
                val context = view.context
                if (!file.exists()) {
                    Toast.makeText(context, "הסרטון כבר לא קיים", Toast.LENGTH_SHORT).show()
                    onDelete(file)
                    return
                }

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".files",
                    file
                )
                runCatching {
                    context.startActivity(
                        Intent(context, VideoPlayerActivity::class.java).apply {
                            putExtra(VideoPlayerActivity.EXTRA_URI, uri.toString())
                            putExtra(VideoPlayerActivity.EXTRA_TITLE, appName)
                        }
                    )
                }.onFailure {
                    Toast.makeText(context, "לא ניתן לפתוח את הסרטון", Toast.LENGTH_SHORT).show()
                }
            }

            view.setOnClickListener { openVideo() }
            play.setOnClickListener { openVideo() }

            delete.setOnClickListener {
                android.app.AlertDialog.Builder(view.context)
                    .setTitle("מחיקת סרטון")
                    .setMessage("למחוק את הסרטון הזה?")
                    .setNegativeButton("ביטול", null)
                    .setPositiveButton("מחק") { _, _ ->
                        if (file.delete()) {
                            onDelete(file)
                        } else {
                            Toast.makeText(view.context, "לא ניתן למחוק את הסרטון", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }

            view.contentDescription = "${appName}. ${meta.text}. הפעל סרטון."
        }

        private fun packageNameFromFile(file: File): String? {
            val stem = file.nameWithoutExtension
            val marker = stem.indexOf("__")
            if (marker < 0) return null
            return stem.substring(marker + 2).takeIf { it.contains('.') }
        }

        private fun resolveAppName(packageName: String?): String? = packageName?.let {
            runCatching {
                val info = view.context.packageManager.getApplicationInfo(it, 0)
                view.context.packageManager.getApplicationLabel(info).toString().ifBlank { it }
            }.getOrNull()
        }

        private fun videoDuration(file: File): Long? = runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val value = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            retriever.release()
            value
        }.getOrNull()?.takeIf { it > 0L }

        private fun formatDuration(ms: Long): String {
            val total = ms / 1000L
            val seconds = total % 60L
            val minutes = (total / 60L) % 60L
            val hours = total / 3600L
            return if (hours > 0L) {
                "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
            } else {
                "%02d:%02d".format(Locale.US, minutes, seconds)
            }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        }
    }
}