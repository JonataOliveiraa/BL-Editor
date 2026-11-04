package dev.bunnyloader.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private sealed interface Screen {
    data object Projects : Screen
    data class New(val kind: Workspace.Kind) : Screen
    data class Project(val project: Workspace.Project) : Screen
    /** A pack's own screen: it opens with the textures or fonts, and its files are a step away. */
    data class Pack(val project: Workspace.Project) : Screen
}

@Composable
fun EditorApp() {
    val context = LocalContext.current
    var tree by remember { mutableStateOf(Workspace.saved(context)) }
    val workspace = remember(tree) { tree?.let { Workspace(context, it) } }
    var screen by remember { mutableStateOf<Screen>(Screen.Projects) }
    if (workspace == null) return ConnectScreen { tree = it }
    when (val current = screen) {
        Screen.Projects -> ProjectsScreen(
            workspace,
            onOpen = { screen = if (it.kind == Workspace.Kind.MOD) Screen.Project(it) else Screen.Pack(it) },
            onNew = { screen = Screen.New(it) },
            onDisconnect = {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(workspace.tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                tree = null
            },
        )
        is Screen.New -> {
            BackHandler { screen = Screen.Projects }
            NewModScreen(workspace, current.kind, onBack = { screen = Screen.Projects }, onCreated = {
                EditorPrefs.markOpened(context, it.folder.name)
                screen = if (it.kind == Workspace.Kind.MOD) Screen.Project(it) else Screen.Pack(it)
            })
        }
        is Screen.Project -> ProjectScreen(workspace, current.project) {
            screen = if (current.project.kind == Workspace.Kind.MOD) Screen.Projects else Screen.Pack(current.project)
        }
        is Screen.Pack -> {
            BackHandler { screen = Screen.Projects }
            val back = { screen = Screen.Projects }
            val files = { screen = Screen.Project(current.project) }
            if (current.project.kind == Workspace.Kind.FONT) FontPackScreen(workspace, current.project, back, files)
            else TexturePackScreen(workspace, current.project, back, files)
        }
    }
}

/** Calls [block] on each return to the screen (Bunny Loader may have been installed meanwhile). */
@Composable
fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) block() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun ConnectScreen(onConnected: (Uri) -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(BunnyLoader.status(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    OnResume { status = BunnyLoader.status(context) }
    val wrongFolder = stringResource(R.string.WrongFolder)

    fun accept(uri: Uri?) {
        if (uri == null) return
        if (uri.authority != "com.bunnyloader.documents" || DocumentsContract.getTreeDocumentId(uri) != "mod-sources") {
            error = wrongFolder
            return
        }
        try {
            Workspace.keep(context, uri)
            onConnected(uri)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val connect = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) accept(it.data?.data)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { accept(it) }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PixelIcon(R.drawable.ic_bunny_menu, 72.dp)
            PixelText(stringResource(R.string.ConnectTitle), Ts.Big, align = TextAlign.Center)
            PixelText(stringResource(R.string.ConnectDescription), Ts.Body, Bl.TextDim, align = TextAlign.Center)
            val problem = when (status) {
                BunnyLoader.Status.READY -> null
                BunnyLoader.Status.NOT_INSTALLED -> stringResource(R.string.NotInstalled)
                BunnyLoader.Status.OUTDATED -> stringResource(R.string.Outdated)
                BunnyLoader.Status.WRONG_SIGNATURE -> stringResource(R.string.WrongSignature)
            }
            (error ?: problem)?.let { PixelText(it, Ts.Body, Bl.Bad, align = TextAlign.Center) }
            Spacer(Modifier.height(2.dp))
            PixelButton(
                stringResource(R.string.Connect),
                { error = null; runCatching { connect.launch(BunnyLoader.connectIntent()) }.onFailure { error = it.message } },
                Modifier.fillMaxWidth(), fill = Bl.TitleFill, icon = R.drawable.ic_bunny_menu,
                enabled = status == BunnyLoader.Status.READY,
            )
            if (status != BunnyLoader.Status.NOT_INSTALLED) {
                PixelButton(stringResource(R.string.ChooseFolder), { error = null; picker.launch(BunnyLoader.SOURCES_ROOT) },
                    Modifier.fillMaxWidth(), icon = R.drawable.ic_folder, fontSize = Ts.Body)
            }
        }
    }
}
