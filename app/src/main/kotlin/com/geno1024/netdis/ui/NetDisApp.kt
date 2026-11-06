package com.geno1024.netdis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geno1024.netdis.model.TopologyNode
import com.geno1024.netdis.ui.theme.NetDisTheme
import com.geno1024.netdis.ui.topology.TopologyScreen

private enum class Destination(val label: String, val icon: ImageVector) {
    TOPOLOGY("拓扑", Icons.Outlined.AccountTree),
    DEVICES("设备", Icons.Outlined.DevicesOther),
    ROUTERS("路由器", Icons.Outlined.Router),
    SETTINGS("设置", Icons.Outlined.Settings),
}

/**
 * One activity, four destinations, no navigation library. The selected node is
 * transient UI state; everything durable (routers, settings, last scan) comes
 * from the view model.
 */
@Composable
fun NetDisApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(Destination.TOPOLOGY.name) }
    val tab = Destination.entries.firstOrNull { it.name == tabName } ?: Destination.TOPOLOGY
    var selectedNode by remember { mutableStateOf<TopologyNode?>(null) }
    var initialRouterHost by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    NetDisTheme(mode = state.settings.theme) {
        LaunchedEffect(state.error, tabName) {
            val error = state.error
            if (error != null && tab != Destination.TOPOLOGY) {
                snackbar.showSnackbar(error)
                viewModel.clearError()
            }
        }

        Backdrop {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        tonalElevation = 4.dp,
                    ) {
                        for (destination in Destination.entries) {
                            NavigationBarItem(
                                selected = tab == destination,
                                onClick = { tabName = destination.name },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner)) {
                    when (tab) {
                        Destination.TOPOLOGY -> TopologyScreen(
                            state = state,
                            onScan = {
                                selectedNode = null
                                viewModel.startScan()
                            },
                            onCancel = viewModel::cancelScan,
                            onDismissError = viewModel::clearError,
                            onNodeClick = { selectedNode = it },
                        )

                        Destination.DEVICES -> DevicesScreen(
                            state = state,
                            onNodeClick = { selectedNode = it },
                            onScan = {
                                selectedNode = null
                                viewModel.startScan()
                            },
                        )

                        Destination.ROUTERS -> RoutersScreen(
                            state = state,
                            onSave = viewModel::saveRouter,
                            onDelete = viewModel::deleteRouter,
                            onToggle = viewModel::setRouterEnabled,
                            onNodeClick = { selectedNode = it },
                            initialHost = initialRouterHost,
                            onInitialHostConsumed = { initialRouterHost = null },
                        )

                        Destination.SETTINGS -> SettingsScreen(
                            state = state,
                            onSettingsChange = viewModel::updateSettings,
                            onCheckUpdate = viewModel::checkUpdate,
                            onDownloadUpdate = viewModel::downloadUpdate,
                        )
                    }
                }
            }
        }

        selectedNode?.let { node ->
            NodeDetailSheet(
                node = node,
                snapshot = state.snapshot,
                onDismiss = { selectedNode = null },
                onAddRouter = { host ->
                    selectedNode = null
                    initialRouterHost = host
                    tabName = Destination.ROUTERS.name
                },
                onRescan = {
                    selectedNode = null
                    tabName = Destination.TOPOLOGY.name
                    viewModel.startScan()
                },
            )
        }
    }
}
