package com.example.healthconnectviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.healthconnectviewer.data.HealthConnectAvailability
import com.example.healthconnectviewer.data.HealthConnectManager
import com.example.healthconnectviewer.ui.MainViewModel
import com.example.healthconnectviewer.ui.MainViewModelFactory
import com.example.healthconnectviewer.ui.components.DrawerContent
import com.example.healthconnectviewer.ui.components.HealthConnectUnavailable
import com.example.healthconnectviewer.ui.navigation.Route
import com.example.healthconnectviewer.ui.screens.AnalysisScreen
import com.example.healthconnectviewer.ui.screens.ComparisonScreen
import com.example.healthconnectviewer.ui.screens.DiagnosticsScreen
import com.example.healthconnectviewer.ui.screens.MixingScreen
import com.example.healthconnectviewer.ui.screens.ViewerScreen
import com.example.healthconnectviewer.ui.theme.HealthConnectViewerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val healthConnectManager by lazy { HealthConnectManager(applicationContext) }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(healthConnectManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HealthConnectViewerTheme {
                val availability by viewModel.availability.collectAsState()

                // Permissions can be revoked from the Health Connect app while we are
                // backgrounded, and Health Connect itself can be installed or updated,
                // so the state is re-read every time the app comes forward.
                OnResume { viewModel.onResume() }

                val requestPermissionsLauncher = rememberLauncherForActivityResult(
                    healthConnectManager.requestPermissionsActivityContract()
                ) {
                    viewModel.checkPermissions()
                }
                val requestPermissions = { requestPermissionsLauncher.launch(healthConnectManager.permissions) }

                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var currentRoute by remember { mutableStateOf(Route.VIEWER) }

                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = Color(0xFFF8FAFC)
                ) {
                    if (availability != HealthConnectAvailability.AVAILABLE) {
                        HealthConnectUnavailable(
                            updateRequired = availability == HealthConnectAvailability.UPDATE_REQUIRED,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                DrawerContent(
                                    currentRoute = currentRoute,
                                    onNavigate = {
                                        currentRoute = it
                                        scope.launch { drawerState.close() }
                                    },
                                    onSyncData = {
                                        viewModel.syncData()
                                        scope.launch { drawerState.close() }
                                    }
                                )
                            }
                        ) {
                            val openDrawer = { scope.launch { drawerState.open() }; Unit }
                            when (currentRoute) {
                                Route.VIEWER -> ViewerScreen(
                                    viewModel = viewModel,
                                    onOpenDrawer = openDrawer,
                                    onRequestPermissions = requestPermissions
                                )
                                Route.COMPARISON -> ComparisonScreen(
                                    viewModel = viewModel,
                                    onOpenDrawer = openDrawer,
                                    onRequestPermissions = requestPermissions
                                )
                                Route.MIXING -> MixingScreen(
                                    viewModel = viewModel,
                                    onOpenDrawer = openDrawer,
                                    onRequestPermissions = requestPermissions
                                )
                                Route.ANALYSIS -> AnalysisScreen(
                                    viewModel = viewModel,
                                    onOpenDrawer = openDrawer
                                )
                                Route.DIAGNOSTICS -> DiagnosticsScreen(
                                    viewModel = viewModel,
                                    onOpenDrawer = openDrawer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Runs [block] on every ON_RESUME of the hosting lifecycle. */
@Composable
private fun OnResume(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentBlock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
