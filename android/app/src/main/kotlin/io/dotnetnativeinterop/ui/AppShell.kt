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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Engineering
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
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
import io.dotnetnativeinterop.agent.ForemanScreen
import io.dotnetnativeinterop.feature.ComparisonViewModel
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.ui.tabs.AboutScreen
import io.dotnetnativeinterop.ui.tabs.AnalysisScreen
import io.dotnetnativeinterop.ui.tabs.BoundaryHubScreen
import io.dotnetnativeinterop.ui.tabs.FeatureDetailScreen
import io.dotnetnativeinterop.ui.tabs.FeaturesScreen
import io.dotnetnativeinterop.ui.tabs.LabScreen
import io.dotnetnativeinterop.ui.tabs.SearchScreen

/**
 * IA collapse (spec 2 of 3, 2026-06-21): 5 sections, identical order/set to iOS. Dashboard,
 * Compare, Latency + telemetry -> Analysis; AI, Manuals -> Search; the Android-only legacy Stream
 * tab folds into Boundary (its "streaming callback" preset). About demotes from a top-level tab to
 * a toolbar info-icon action (see the `showAbout` state in [AppShell]). This is a re-grouping, not
 * a removal: every prior screen is still reachable, just relocated.
 *
 * Foreman (2026-07-06) adds a 6th, top-level section for the on-device tool-calling agent — per the
 * Foreman design doc it gets its own nav entry (not folded into Search), first in the rail as the
 * app's new flagship demo and the default landing tab. iOS must mirror this exact order when it adds
 * the same section (tracked with the broader iOS Plan D parity debt).
 */
internal enum class Tab(val title: String, val icon: ImageVector) {
    Foreman("Foreman", Icons.Outlined.Engineering),
    Boundary("Boundary", Icons.Outlined.SwapHoriz),
    Catalog("Catalog", Icons.Outlined.Verified),
    Lab("Lab", Icons.Outlined.Memory),
    Search("Search", Icons.Outlined.Search),
    Analysis("Analysis", Icons.Outlined.BarChart),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppShell(
    inference: io.dotnetnativeinterop.ui.InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(Tab.Foreman) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }

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
                    selected = t == tab && !showAbout,
                    onClick = { tab = t; detailId = null; showAbout = false },
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
                    title = {
                        Text(
                            when {
                                showAbout -> "About"
                                tab == Tab.Catalog && detailId != null -> "Feature"
                                else -> tab.title
                            },
                        )
                    },
                    navigationIcon = {
                        if (showAbout) {
                            IconButton(onClick = { showAbout = false }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                        } else if (tab == Tab.Catalog && detailId != null) {
                            IconButton(onClick = { detailId = null }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (!showAbout) {
                            IconButton(onClick = { showAbout = true }) {
                                Icon(Icons.Outlined.Info, contentDescription = "About")
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
                if (showAbout) {
                    AboutScreen(content)
                } else {
                    when (tab) {
                        Tab.Foreman -> ForemanScreen(modifier = content)
                        Tab.Boundary -> BoundaryHubScreen(inference, content)
                        Tab.Catalog -> {
                            val id = detailId
                            if (id == null) FeaturesScreen(features, onOpenDetail = { detailId = it }, modifier = content)
                            else FeatureDetailScreen(features, id, onBack = { detailId = null }, modifier = content)
                        }
                        Tab.Lab -> {
                            val lab: io.dotnetnativeinterop.lab.LabViewModel = viewModel()
                            LabScreen(lab, content)
                        }
                        Tab.Search -> SearchScreen(content)
                        Tab.Analysis -> AnalysisScreen(features, comparison, latency, content)
                    }
                }
            }
        }
    }
}
