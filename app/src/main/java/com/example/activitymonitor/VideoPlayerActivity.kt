package com.example.activitymonitor

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class VideoPlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "סרטון"
        val uriText = intent.getStringExtra(EXTRA_URI)
        val titleView = findViewById<TextView>(R.id.videoPlayerTitle)
        val statusView = findViewById<TextView>(R.id.videoPlayerStatus)
        val video = findViewById<VideoView>(R.id.videoView)
        titleView.text = title

        if (uriText.isNullOrBlank()) {
            statusView.text = "לא נמצא קובץ סרטון"
            Toast.makeText(this, "הסרטון אינו זמין", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val uri = Uri.parse(uriText)
        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)

        video.setOnPreparedListener { player ->
            if (player.videoWidth <= 0 || player.videoHeight <= 0) {
                statusView.visibility = View.VISIBLE
                statusView.text = "הקובץ אינו מכיל מסלול תמונה תקין"
                return@setOnPreparedListener
            }
            statusView.visibility = View.VISIBLE
            statusView.text = "מפעיל את הסרטון…"
            player.isLooping = false
            video.start()
        }

        video.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    statusView.visibility = View.GONE
                    true
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    statusView.visibility = View.VISIBLE
                    statusView.text = "טוען את הסרטון…"
                    true
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    statusView.visibility = View.GONE
                    true
                }
                else -> false
            }
        }

        video.setOnErrorListener { _, what, extra ->
            statusView.visibility = View.VISIBLE
            statusView.text = "שגיאה בהפעלת הסרטון ($what/$extra). ייתכן שהקובץ נפגם בזמן ההסרטה."
            Toast.makeText(this, "לא ניתן להפעיל את הסרטון. בדוק שההסרטה הסתיימה ונשמרה.", Toast.LENGTH_LONG).show()
            true
        }

        video.setOnCompletionListener {
            statusView.visibility = View.VISIBLE
            statusView.text = "ההפעלה הסתיימה"
        }

        video.setVideoURI(uri)
    }

    companion object {
        const val EXTRA_URI = "video_uri"
        const val EXTRA_TITLE = "video_title"
    }
}
