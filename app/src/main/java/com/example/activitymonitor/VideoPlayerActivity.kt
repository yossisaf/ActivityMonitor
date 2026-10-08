package com.example.activitymonitor

import android.net.Uri
import android.os.Bundle
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
        val video = findViewById<VideoView>(R.id.videoView)
        titleView.text = title

        if (uriText.isNullOrBlank()) {
            Toast.makeText(this, "הסרטון אינו זמין", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val uri = Uri.parse(uriText)
        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setVideoURI(uri)
        video.setOnPreparedListener { player ->
            player.isLooping = false
            video.start()
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "לא ניתן להפעיל את הסרטון", Toast.LENGTH_LONG).show()
            false
        }
    }

    companion object {
        const val EXTRA_URI = "video_uri"
        const val EXTRA_TITLE = "video_title"
    }
}