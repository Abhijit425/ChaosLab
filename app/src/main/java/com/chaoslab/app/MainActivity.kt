package com.chaoslab.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.chaoslab.app.data.SettingsStore
import com.chaoslab.app.engine.ChaosEngine
import com.chaoslab.app.ui.ChaosLabApp
import com.chaoslab.app.ui.ChaosTheme

class MainActivity : ComponentActivity() {

    private val engine = ChaosEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val store = SettingsStore(this)
        setContent {
            ChaosTheme { ChaosLabApp(engine, store) }
        }
    }
}
