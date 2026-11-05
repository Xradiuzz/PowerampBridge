package com.example.powerampbridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val powerampPackage = "com.maxmpz.audioplayer"

    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var statusText: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.maxmpz.audioplayer.STATUS_CHANGED") return

            val title = intent.getStringExtra("title") ?: "Неизвестный трек"
            val artist = intent.getStringExtra("artist") ?: "Неизвестный исполнитель"
            val album = intent.getStringExtra("album") ?: "Неизвестный альбом"
            val paused = intent.getBooleanExtra("paused", true)

            titleText.text = title
            artistText.text = artist
            albumText.text = album
            statusText.text = if (paused) "На паузе" else "Воспроизводится"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        fun addText(text: String, size: Float): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
                layout.addView(this)
            }
        }

        titleText = addText("Poweramp Bridge", 24f)
        artistText = addText("Исполнитель", 18f)
        albumText = addText("Альбом", 16f)
        statusText = addText("Ожидание Poweramp...", 16f)

        fun addButton(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label
                setOnClickListener { action() }
            }

            layout.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addButton("Открыть Poweramp") {
            val launchIntent = packageManager
                .getLaunchIntentForPackage(powerampPackage)

            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                statusText.text = "Poweramp не найден"
            }
        }

        addButton("Play / Pause") {
            sendPowerampCommand("TOGGLE_PLAY_PAUSE")
        }

        addButton("Предыдущий трек") {
            sendPowerampCommand("PREVIOUS")
        }

        addButton("Следующий трек") {
            sendPowerampCommand("NEXT")
        }

        setContentView(layout)
    }

    private fun sendPowerampCommand(command: String) {
        val intent = Intent("com.maxmpz.audioplayer.API_COMMAND").apply {
            setPackage(powerampPackage)
            putExtra("cmd", command)
            putExtra("pak", packageName)
        }

        sendBroadcast(intent)
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(
            "com.maxmpz.audioplayer.STATUS_CHANGED"
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                statusReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }
}
