package io.dotnetnativeinterop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Stream
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.feature.ComparisonViewModel
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.ui.tabs.AboutScreen
import io.dotnetnativeinterop.ui.tabs.AiScreen
import io.dotnetnativeinterop.ui.tabs.CompareScreen
import io.dotnetnativeinterop.ui.tabs.DashboardScreen
import io.dotnetnativeinterop.ui.tabs.EdgeSearchScreen
import io.dotnetnativeinterop.ui.tabs.FeatureDetailScreen
import io.dotnetnativeinterop.ui.tabs.FeaturesScreen
import io.dotnetnativeinterop.ui.tabs.LabScreen
import io.dotnetnativeinterop.ui.tabs.LatencyScreen

/** Tab order mirrors the iOS RootTabView; Stream is the Android-only extra and stays last. */
internal enum class Tab(val title: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Outlined.GridView),
    Features("Features", Icons.Outlined.Verified),
    Lab("Lab", Icons.Outlined.Memory),
    Ai("AI", Icons.Outlined.AutoAwesome),
    Compare("Compare", Icons.Outlined.BarChart),
    Latency("Latency", Icons.Outlined.Timer),
    About("About", Icons.Outlined.Info),
    Manuals("Manuals", Icons.Outlined.Construction),
    Stream("Stream", Icons.Outlined.Stream),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppShell(
    inference: io.dotnetnativeinterop.ui.InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(Tab.Dashboard) }
    var detailId by remember { mutableStateOf<String?>(null) }

    val features: FeaturesViewModel = viewModel()
    val comparison: ComparisonViewModel = viewModel()
    val latency: LatencyViewModel = viewModel()

    Row(modifier = modifier.fillMaxSize().background(Instrument.bg0)) {
        NavigationRail(
            containerColor = Instrument.bg0,
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            Tab.entries.forEach { t ->
                NavigationRailItem(
                    selected = t == tab,
                    onClick = { tab = t; detailId = null },
                    icon = { Icon(t.icon, contentDescription = t.title) },
                    label = { Text(t.title) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = Instrument.accent,
                        selectedTextColor = Instrument.accent,
                        indicatorColor = Instrument.accent.copy(alpha = 0.14f),
                        unselectedIconColor = Instrument.textSecondary,
                        unselectedTextColor = Instrument.textSecondary,
                    ),
                )
            }
        }
        VerticalDivider(thickness = 1.dp, color = Instrument.hairline)

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Instrument.bg0,
            topBar = {
                TopAppBar(
                    title = { Text(if (tab == Tab.Features && detailId != null) "Feature" else tab.title) },
                    navigationIcon = {
                        if (tab == Tab.Features && detailId != null) {
                            IconButton(onClick = { detailId = null }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Instrument.bg0,
                        titleContentColor = Instrument.textPrimary,
                        navigationIconContentColor = Instrument.textSecondary,
                    ),
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                HorizontalDivider(thickness = 1.dp, color = Instrument.hairline)
                val content = Modifier.fillMaxWidth().weight(1f)
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
}
