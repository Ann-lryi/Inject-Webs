package com.aho.streambrowser.ui

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import com.aho.streambrowser.databinding.ActivityPlayerBinding
import com.aho.streambrowser.model.StreamItem
import com.aho.streambrowser.model.StreamType
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Use BundleCompat for API 33+ compatibility
        val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_STREAM, StreamItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_STREAM)
        }
        if (stream == null) { finish(); return }

        initPlayer(stream)
    }

    private fun initPlayer(stream: StreamItem) {
        val origin = runCatching {
            val u = java.net.URL(stream.referer)
            "${u.protocol}://${u.host}"
        }.getOrElse { "" }

        val httpFactory = DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Referer"    to stream.referer,
                "Origin"     to origin
            ))
        }
        val dataFactory = DefaultDataSource.Factory(this, httpFactory)

        val mediaItem = MediaItem.fromUri(stream.url)
        val mediaSource = when (stream.type) {
            StreamType.HLS  -> HlsMediaSource.Factory(dataFactory).createMediaSource(mediaItem)
            else            -> ProgressiveMediaSource.Factory(dataFactory).createMediaSource(mediaItem)
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(DefaultTrackSelector(this))
            .build()
            .also { exo ->
                b.playerView.player = exo
                exo.setMediaSource(mediaSource)
                exo.prepare()
                exo.playWhenReady = true
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        runOnUiThread {
                            Toast.makeText(this@PlayerActivity,
                                "Lỗi phát: ${error.message}", Toast.LENGTH_LONG).show()
                            // Show retry option
                            AlertDialog.Builder(this@PlayerActivity)
                                .setTitle("Lỗi phát video")
                                .setMessage("Không thể phát luồng này.\n${error.message}")
                                .setPositiveButton("Thử lại") { _, _ ->
                                    exo.seekToDefaultPosition()
                                    exo.prepare()
                                }
                                .setNegativeButton("Đóng") { _, _ -> finish() }
                                .show()
                        }
                    }
                })
            }
    }

    override fun onStop()    { super.onStop();    player?.pause()   }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION  or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    companion object {
        const val EXTRA_STREAM = "extra_stream"
    }
}
