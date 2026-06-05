package io.ondevicellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import io.ondevicellm.ui.InferenceScreen
import io.ondevicellm.ui.InferenceViewModel
import io.ondevicellm.ui.OnDeviceLlmTheme

public class MainActivity : ComponentActivity() {

    private val viewModel: InferenceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OnDeviceLlmTheme {
                InferenceScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                )
            }
        }
    }
}
