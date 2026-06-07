package io.dotnetnativeinterop

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        setContent {
            DotnetNativeInteropTheme {
                AppShell(inference = inference)
            }
        }
    }
}
