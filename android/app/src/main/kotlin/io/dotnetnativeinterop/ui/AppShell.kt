package io.dotnetnativeinterop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.feature.ComparisonViewModel
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.ui.tabs.AboutScreen
import io.dotnetnativeinterop.ui.tabs.CompareScreen
import io.dotnetnativeinterop.ui.tabs.DashboardScreen
import io.dotnetnativeinterop.ui.tabs.FeatureDetailScreen
import io.dotnetnativeinterop.ui.tabs.FeaturesScreen
import io.dotnetnativeinterop.ui.tabs.AiScreen
import io.dotnetnativeinterop.ui.tabs.EdgeSearchScreen
import io.dotnetnativeinterop.ui.tabs.LabScreen
import io.dotnetnativeinterop.ui.tabs.LatencyScreen
import kotlinx.coroutines.launch

internal enum class Tab(val title: String) {
    Dashboard("Dashboard"), Features("Features"), Compare("Compare"), Latency("Latency"),
    About("About"), Ai("AI"), Manuals("Manuals"), Lab("Lab"), Stream("Stream"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppShell(
    inference: io.dotnetnativeinterop.ui.InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Dashboard) }
    var detailId by remember { mutableStateOf<String?>(null) }

    val features: FeaturesViewModel = viewModel()
    val comparison: ComparisonViewModel = viewModel()
    val latency: LatencyViewModel = viewModel()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        label = { Text(t.title) },
                        selected = t == tab,
                        onClick = { tab = t; detailId = null; scope.launch { drawer.close() } },
                    )
                }
            }
        },
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(if (tab == Tab.Features && detailId != null) "Feature" else tab.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                )
            },
        ) { pad ->
            val content = Modifier.padding(pad).fillMaxSize()
            when (tab) {
                Tab.Dashboard -> DashboardScreen(features, content)
                Tab.Features -> {
                    val id = detailId
                    if (id == null) FeaturesScreen(features, onOpenDetail = { detailId = it }, modifier = content)
                    else FeatureDetailScreen(features, id, onBack = { detailId = null }, modifier = content)
                }
                Tab.Compare -> CompareScreen(comparison, content)
                Tab.Latency -> LatencyScreen(latency, content)
                Tab.About -> AboutScreen(content)
                Tab.Ai -> AiScreen(content)
                Tab.Manuals -> {
                    val vm: io.dotnetnativeinterop.evs.EvsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    EdgeSearchScreen(vm, content)
                }
                Tab.Lab -> {
                    val lab: io.dotnetnativeinterop.lab.LabViewModel = viewModel()
                    LabScreen(lab, content)
                }
                Tab.Stream -> InferenceScreen(viewModel = inference, modifier = content)
            }
        }
    }
}
