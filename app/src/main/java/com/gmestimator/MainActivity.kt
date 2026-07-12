package com.gmestimator

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import com.gmestimator.ui.GmApp
import com.gmestimator.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android throttles or stops sensor delivery to a backgrounded app. A roll record is
        // 3-20 minutes long, so the screen must stay on for the whole run. The alternative
        // (a foreground service + wake lock) is more fragile on HyperOS, which aggressively
        // kills background work unless the user exempts the app from battery optimisation.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(ctx)
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface { GmApp(vm) }
            }
        }
    }
}
