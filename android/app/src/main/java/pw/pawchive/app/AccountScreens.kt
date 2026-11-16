package pw.pawchive.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.*
import pw.pawchive.data.*
import pw.pawchive.download.DownloadCoordinator

@Composable
fun MineScreen(app: AppViewModel, dark: Boolean, toggleTheme: () -> Unit, open: (NavKey) -> Unit) {
    val account by app.account.collectAsStateWithLifecycle()
    val follows by app.follows.collectAsStateWithLifecycle()
    val favorites by app.favorites.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = toggleTheme) { Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (dark) "切换亮色" else "切换暗色") }
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = !account.restoring) { open(if (account.username == null) LoginKey else SettingsKey) }.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(null, Modifier.size(64.dp))
                Text(account.username ?: if (account.restoring) "正在恢复账号" else "点击登录", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                listOf("关注" to follows.size, "收藏" to favorites.size).forEachIndexed { i, pair ->
                    Column(Modifier.weight(1f).clickable { open(CollectionKey(if (i == 0) "author" else "favorite")) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${pair.second}", fontSize = 22.sp); Text(pair.first, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        item { MenuRow("我的关注", { open(CollectionKey("author")) }) { Icon(Icons.Outlined.PersonOutline, null) } }
        item { MenuRow("我的收藏", { open(CollectionKey("favorite")) }) { Icon(Icons.Outlined.StarOutline, null) } }
        item { MenuRow("浏览历史", { open(CollectionKey("history")) }) { Icon(Icons.Outlined.History, null) } }
        item { MenuRow("稍后再看", { open(CollectionKey("later")) }) { Icon(Icons.Outlined.WatchLater, null) } }
        item { MenuRow("屏蔽作者", { open(CollectionKey("blocked")) }) { Icon(Icons.Outlined.Block, null) } }
        item { MenuRow("下载管理", { open(TransfersKey) }) { Icon(Icons.Outlined.Download, null) } }
        item { MenuRow("设置", { open(SettingsKey) }) { Icon(Icons.Outlined.Settings, null) } }
    }
}
@Composable
private fun MenuRow(label: String, click: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClick = click).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        icon(); Text(label, modifier = Modifier.weight(1f).padding(start = 16.dp), fontSize = 15.sp); Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun CollectionScreen(kind: String, app: AppViewModel, back: () -> Unit, author: (Creator) -> Unit, post: (Post) -> Unit) {
    val follows by app.follows.collectAsStateWithLifecycle()
    val favorites by app.favorites.collectAsStateWithLifecycle()
    val history by app.history.collectAsStateWithLifecycle()
    val later by app.later.collectAsStateWithLifecycle()
    val blocked by app.blocked.collectAsStateWithLifecycle()
    Page(when(kind) { "author" -> "我的关注"; "history" -> "浏览历史"; "later" -> "稍后再看"; "blocked" -> "屏蔽作者"; else -> "我的收藏" }, back) {
        if (kind == "blocked") LazyColumn {
            items(blocked, key = { it.key }) { creator -> Row(Modifier.fillMaxWidth().clickable { author(creator) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(creator)
                Column(Modifier.weight(1f).padding(12.dp)) { Text(creator.name.ifBlank { creator.id }); Text(creator.service, fontSize = 12.sp) }
                TextButton(onClick = { app.unblock(creator) }) { Text("取消屏蔽") }
            } }
        } else if (kind == "author") LazyColumn {
            if (follows.isEmpty()) item { StatusContent(false) }
            items(follows, key = { it.key }) { creator -> Row(Modifier.fillMaxWidth().clickable { author(creator) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(creator)
                Text(creator.name, modifier = Modifier.weight(1f).padding(12.dp), fontSize = 15.sp)
                FollowButton(true, { app.follow(creator) })
            } }
        } else {
            val posts = when(kind) { "history" -> history; "later" -> later; else -> favorites }
            LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (posts.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { StatusContent(false) }
                items(posts, key = { it.key }) { item -> WorkCard(item, app) { post(item) } }
            }
        }
    }
}
@Composable
fun LoginScreen(app: AppViewModel, back: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    // Password never enters SavedStateHandle, saved-instance state, preferences, or logs.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope(); val focus = LocalFocusManager.current
    fun submit() {
        if (busy || username.isBlank() || password.isEmpty()) return
        focus.clearFocus(); busy = true; error = null
        scope.launch {
            try { app.repo.login(username, password); password = ""; back() }
            catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "登录未成功" }
            finally { busy = false }
        }
    }
    Page("账号登录", back) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
            OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Button(onClick = { submit() }, enabled = !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("登录")
            }
        }
    }
}
@Composable
fun SettingsScreen(app: AppViewModel, dark: Boolean, toggle: () -> Unit, back: () -> Unit) {
    val account by app.account.collectAsStateWithLifecycle()
    val hideMediaLess by app.hideMediaLessWorks.collectAsStateWithLifecycle()
    var logoutDialog by remember { mutableStateOf(false) }
    Page("设置", back) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("深色主题", modifier = Modifier.weight(1f)); Switch(dark, { toggle() }, Modifier.testTag("settings_dark_switch").semantics { contentDescription = "深色主题" })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("过滤无图片和视频的作品")
                Text("作品搜索不显示纯文字或仅有附件的结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(hideMediaLess, { enabled -> app.action { app.repo.setHideMediaLessWorks(enabled) } }, Modifier.testTag("settings_media_switch").semantics { contentDescription = "过滤无图片和视频的作品" })
        }
        Row(Modifier.fillMaxWidth().padding(20.dp)) { Text("版本", modifier = Modifier.weight(1f)); Text(BuildConfig.VERSION_NAME) }
        if (account.username != null) TextButton(onClick = { logoutDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("退出登录") }
    }
    if (logoutDialog) AlertDialog(onDismissRequest = { logoutDialog = false }, title = { Text("退出登录？") }, text = { Text("此设备上的登录会话将被清除。") }, confirmButton = { TextButton(onClick = { logoutDialog = false; app.action { app.repo.logout() } }) { Text("退出") } }, dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("取消") } })
}
@Composable
fun TransferScreen(app: AppViewModel, coordinator: DownloadCoordinator, back: () -> Unit) {
    val transfers by app.transfers.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除下载记录？") },
        text = { Text("保留已下载文件和正在进行的任务。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; app.action { app.repo.dao.clearDownloadRecords() } }) { Text("清除记录") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    Page("下载管理", back) {
        if (transfers.any { it.status !in setOf("queued", "running") }) TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) { Text("清除已结束记录") }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
            if (transfers.isEmpty()) item { StatusContent(false, "暂无下载任务") }
            items(transfers, key = { it.id }) { task -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(task.name, maxLines = 2, fontSize = 14.sp)
                Text(when(task.status) { "complete" -> "已完成"; "running" -> "正在下载"; "queued" -> "等待下载"; "cancelled" -> "已取消"; else -> task.error ?: "已暂停" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                if (task.total > 0) LinearProgressIndicator(progress = { (task.bytes.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (task.status in listOf("running", "queued")) TextButton(onClick = { app.action { coordinator.cancel(task.id) } }) { Text("取消") }
                else if (task.status !in listOf("complete", "cancelled")) TextButton(onClick = { app.action { coordinator.schedule(task.id) } }) { Text("重试") }
                Row {
                    if (task.status == "complete") TextButton(onClick = { app.action {
                        val uri = android.net.Uri.parse(task.destination)
                        val providerMime = withContext(Dispatchers.IO) {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } ?: error("文件已移动、删除或无法读取")
                            context.contentResolver.getType(uri)
                        }
                        val mime = providerMime?.takeUnless { it == "application/octet-stream" || it == "*/*" }
                            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(task.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(android.content.Intent.createChooser(intent, "打开文件"))
                    } }) { Text("打开") }
                    if (task.status !in setOf("queued", "running")) TextButton(onClick = { app.action { app.repo.dao.removeDownloadRecord(task.id) } }) { Text("清除记录") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            } }
        }
    }
}
