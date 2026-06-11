package io.dotnetnativeinterop

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.dotnetnativeinterop.ui.AppShell
import io.dotnetnativeinterop.ui.DotnetNativeInteropTheme
import io.dotnetnativeinterop.ui.InferenceViewModel

public class MainActivity : ComponentActivity() {

    private val inference: InferenceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: force light system-bar icons instead of following the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            DotnetNativeInteropTheme {
                AppShell(inference = inference)
            }
        }
    }
}
