package com.geno1024.ai.netdis.ui

import androidx.activity.compose.BackHandler
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
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.TopologyNode
import com.geno1024.ai.netdis.ui.theme.NetDisTheme
import com.geno1024.ai.netdis.ui.topology.TopologyScreen

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
 *
 * Adding and editing a router is a full page rather than a dialog or a tab. It
 * is opened from the router list or from any node's credential action, which is
 * why [editingRouter] temporarily replaces the destination content and hides
 * the bottom bar.
 */
@Composable
fun NetDisApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(Destination.TOPOLOGY.name) }
    val tab = Destination.entries.firstOrNull { it.name == tabName } ?: Destination.TOPOLOGY
    var selectedNode by remember { mutableStateOf<TopologyNode?>(null) }
    var editingRouter by remember { mutableStateOf<RouterConfig?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // The editor replaces the current tab rather than pushing a destination, so
    // wire the system back gesture to the same action as the page's arrow.
    BackHandler(enabled = editingRouter != null) { editingRouter = null }

    fun openCredentials(target: RouterCredentialTarget) {
        selectedNode = null
        editingRouter = target.toConfig()
    }

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
                    if (editingRouter == null) {
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
                    }
                },
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner)) {
                    val editor = editingRouter
                    if (editor != null) {
                        RouterEditorScreen(
                            initial = editor,
                            onSave = { config ->
                                viewModel.saveRouter(config)
                                editingRouter = null
                            },
                            onBack = { editingRouter = null },
                        )
                    } else {
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
                                onEditCredentials = ::openCredentials,
                                onScan = {
                                    selectedNode = null
                                    viewModel.startScan()
                                },
                            )

                            Destination.ROUTERS -> RoutersScreen(
                                state = state,
                                onAdd = {
                                    editingRouter = RouterConfig(id = "", label = "", host = "")
                                },
                                onEdit = { editingRouter = it },
                                onDelete = viewModel::deleteRouter,
                                onToggle = viewModel::setRouterEnabled,
                                onLogin = viewModel::loginRouter,
                                onNodeClick = { selectedNode = it },
                            )

                            Destination.SETTINGS -> SettingsScreen(
                                state = state,
                                onSettingsChange = viewModel::updateSettings,
                                onCheckUpdate = viewModel::checkUpdate,
                                onDownloadUpdate = viewModel::downloadUpdate,
                                onUpdateMessage = viewModel::setUpdateMessage,
                            )
                        }
                    }
                }
            }
        }

        // Re-read the selected node from the latest graph so a note edit or a
        // router login updates the open sheet without closing it.
        val liveNode = selectedNode?.let { picked ->
            state.snapshot?.graph?.nodes?.firstOrNull { it.id == picked.id } ?: picked
        }
        if (editingRouter == null) {
            liveNode?.let { node ->
                val credentialTarget = when (node.kind) {
                    NodeKind.ROUTER -> routerCredentialTarget(node)
                    NodeKind.DEVICE -> deviceCredentialTarget(node, state.snapshot)
                    NodeKind.INTERNET -> null
                }
                NodeDetailSheet(
                    node = node,
                    snapshot = state.snapshot,
                    onDismiss = { selectedNode = null },
                    credentialTarget = credentialTarget,
                    onEditCredentials = ::openCredentials,
                    onRescan = {
                        selectedNode = null
                        tabName = Destination.TOPOLOGY.name
                        viewModel.startScan()
                    },
                    onSaveNote = viewModel::saveDeviceNote,
                    onDeleteNote = viewModel::deleteDeviceNote,
                    routerLogin = node.router?.id?.let(state.routerLogins::get),
                    onLoginRouter = { viewModel.loginRouter(it.id) },
                    portScan = state.portScan,
                    onStartPortScan = viewModel::startPortScan,
                    onCancelPortScan = viewModel::cancelPortScan,
                    onDismissPortScan = viewModel::dismissPortScan,
                )
            }
        }
    }
}
