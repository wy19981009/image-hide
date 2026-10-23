package com.whjk.imagehide.ui.albumlist

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whjk.imagehide.data.auth.AuthEntryPoint
import com.whjk.imagehide.domain.model.Album
import com.whjk.imagehide.ui.common.AuthGateDialog
import com.whjk.imagehide.ui.common.ManageAllFilesGuideDialog
import com.whjk.imagehide.ui.common.RestoreFolderDialog
import com.whjk.imagehide.ui.common.WorkProgressDialog
import dagger.hilt.android.EntryPointAccessors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    onAlbumClick: (bucketId: Long, name: String, hidden: Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: AlbumListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safRequest by viewModel.safRequest.observeAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val auth = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AuthEntryPoint::class.java
        )
    }
    var lockHiddenAlbums by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var pendingHiddenOpen by remember { mutableStateOf<Pair<Long, String>?>(null) }

    var resumeTick by remember { mutableIntStateOf(0) }

    fun openAlbum(bucketId: Long, name: String, hidden: Boolean) {
        if (hidden && lockHiddenAlbums) {
            pendingHiddenOpen = bucketId to name
        } else {
            onAlbumClick(bucketId, name, hidden)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                viewModel.onReturnFromAllFilesSettings()
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick) {
        lockHiddenAlbums = auth.userPrefs().isLockHiddenAlbums()
        biometricEnabled = auth.userPrefs().isBiometricEnabled()
    }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onSafConfirmed()
        } else {
            viewModel.onSafCancelled()
        }
        viewModel.onSafConsumed()
    }

    LaunchedEffect(safRequest) {
        val req = safRequest ?: return@LaunchedEffect
        safLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
    }

    LaunchedEffect(state.error, state.message) {
        val msg = state.error ?: state.message
        if (msg != null) {
            snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.consumeMessage()
        }
    }

    if (pendingHiddenOpen != null) {
        AuthGateDialog(
            title = "打开隐藏相册",
            message = "验证后查看「${pendingHiddenOpen!!.second}」",
            biometricEnabled = biometricEnabled,
            biometricHelper = auth.biometricHelper(),
            onVerifyPin = { pin -> auth.userPrefs().verifyPin(pin) },
            onSuccess = {
                val (id, name) = pendingHiddenOpen!!
                pendingHiddenOpen = null
                onAlbumClick(id, name, true)
            },
            onDismiss = { pendingHiddenOpen = null }
        )
    }

    if (state.showRestoreFolderPicker) {
        RestoreFolderDialog(
            onDismiss = { viewModel.dismissRestoreFolderPicker() },
            onConfirm = { viewModel.confirmRestoreFolder(it) }
        )
    }

    if (state.showManageAllFilesGuide) {
        ManageAllFilesGuideDialog(
            itemCount = state.pendingHideCount,
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            },
            onContinueWithBatch = { viewModel.continueHideWithoutAllFiles() },
            onDismiss = { viewModel.dismissManageAllFilesGuide() }
        )
    }

    if (state.working) {
        val title = when (state.workKind) {
            WorkKind.HIDE -> "正在隐藏"
            WorkKind.RESTORE -> "正在还原"
            else -> "请稍候"
        }
        WorkProgressDialog(
            title = title,
            statusText = state.statusText,
            progress = state.progress,
            onCancel = { viewModel.cancelWorking() }
        )
    }

    Scaffold(
        topBar = {
            if (selectedTab == 1 && state.multiSelect) {
                TopAppBar(
                    title = { Text("已选 ${state.selectedAlbums.size}") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitMultiSelect() }) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllAlbums() }) {
                            Icon(Icons.Default.DoneAll, contentDescription = "全选")
                        }
                        IconButton(
                            onClick = { viewModel.restoreSelectedAlbums() },
                            enabled = state.selectedAlbums.isNotEmpty() && !state.working
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = "还原选中")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("相册") },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }, enabled = !state.working) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = onSettingsClick, enabled = !state.working) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { if (!state.working) selectedTab = 0 },
                    text = { Text("系统 (${state.systemAlbums.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { if (!state.working) selectedTab = 1 },
                    text = { Text("已隐藏 (${state.hiddenAlbums.size})") }
                )
            }

            when (selectedTab) {
                0 -> AlbumTabContent(
                    loading = state.loading && state.systemAlbums.isEmpty(),
                    error = state.error,
                    albums = state.systemAlbums,
                    hidden = false,
                    multiSelect = false,
                    selected = emptySet(),
                    onAlbumClick = { a -> if (!state.working) openAlbum(a.bucketId, a.name, false) },
                    onToggleHide = { if (!state.working) viewModel.toggleHide(it) },
                    onLongPress = {},
                    onRetry = { viewModel.refresh() }
                )
                1 -> AlbumTabContent(
                    loading = state.loading && state.hiddenAlbums.isEmpty(),
                    error = state.error,
                    albums = state.hiddenAlbums,
                    hidden = true,
                    multiSelect = state.multiSelect,
                    selected = state.selectedAlbums,
                    onAlbumClick = { a ->
                        if (state.working) return@AlbumTabContent
                        if (state.multiSelect) {
                            viewModel.toggleAlbumSelected(a.bucketId)
                        } else {
                            openAlbum(a.bucketId, a.name, true)
                        }
                    },
                    onToggleHide = {
                        if (state.working) return@AlbumTabContent
                        if (state.multiSelect) {
                            viewModel.toggleAlbumSelected(it.bucketId)
                        } else {
                            viewModel.toggleRestore(it)
                        }
                    },
                    onLongPress = { if (!state.working) viewModel.onAlbumLongPress(it) },
                    onRetry = { viewModel.refresh() }
                )
            }
        }
    }
}

@Composable
private fun AlbumTabContent(
    loading: Boolean,
    error: String?,
    albums: List<Album>,
    hidden: Boolean,
    multiSelect: Boolean,
    selected: Set<Long>,
    onAlbumClick: (Album) -> Unit,
    onToggleHide: (Album) -> Unit,
    onLongPress: (Album) -> Unit,
    onRetry: () -> Unit
) {
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (error != null && albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败：$error", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (albums.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (hidden) "暂无隐藏相册"
                        else "暂无系统相册"
                    )
                }
            }
        } else {
            items(albums, key = { it.bucketId }) { album ->
                AlbumCard(
                    album = album,
                    isHidden = hidden,
                    multiSelect = multiSelect,
                    selected = selected.contains(album.bucketId),
                    onClick = { onAlbumClick(album) },
                    onToggleHidden = { onToggleHide(album) },
                    onLongClick = { onLongPress(album) }
                )
            }
        }
    }
}

@Composable
private fun <T> androidx.lifecycle.LiveData<T>.observeAsState(): androidx.compose.runtime.State<T?> {
    val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<T?>(value) }
    androidx.compose.runtime.DisposableEffect(this) {
        val observer = androidx.lifecycle.Observer<T> { state.value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}
