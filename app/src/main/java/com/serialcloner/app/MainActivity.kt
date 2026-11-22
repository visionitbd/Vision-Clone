package com.serialcloner.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.serialcloner.app.cloner.ApkClonerEngine
import com.serialcloner.app.cloner.AppRepository
import com.serialcloner.app.cloner.InstallHelper
import com.serialcloner.app.cloner.InstalledAppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0A1931),
                    secondary = Color(0xFFFF5714),
                    surface = Color(0xFFF8FAFC),
                    background = Color(0xFFF1F5F9)
                )
            ) {
                SerialClonerApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialClonerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var originalApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var clonedApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var appToClone by remember { mutableStateOf<InstalledAppItem?>(null) }
    var isCloning by remember { mutableStateOf(false) }
    var cloneProgressText by remember { mutableStateOf("") }

    fun refreshApps() {
        scope.launch {
            isLoading = true
            val (orig, clones) = withContext(Dispatchers.IO) { AppRepository.loadApps(context) }
            originalApps = orig
            clonedApps = clones
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refreshApps() }

    val filteredOriginals = remember(originalApps, searchQuery) {
        if (searchQuery.isBlank()) originalApps
        else originalApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredClones = remember(clonedApps, searchQuery) {
        if (searchQuery.isBlank()) clonedApps
        else clonedApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vision Clone", fontWeight = FontWeight.Bold)
                        Text(
                            "Fast Serial Cloner • 1, 2, 3...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshApps() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Apps (${originalApps.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("My Clones (${clonedApps.size})") }
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search app name...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (selectedTab == 0) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredOriginals, key = { it.packageName }) { app ->
                        OriginalAppCard(
                            app = app,
                            onCloneClick = { appToClone = app }
                        )
                    }
                }
            } else {
                if (filteredClones.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No cloned apps yet. Pick an app from the Apps tab!",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredClones, key = { it.packageName }) { clone ->
                            ClonedAppCard(
                                app = clone,
                                onOpen = { InstallHelper.launchApp(context, clone.packageName) },
                                onUninstall = { InstallHelper.uninstallApp(context, clone.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }

    val target = appToClone
    if (target != null) {
        var serialNum by remember(target.packageName) { mutableIntStateOf(target.nextSerialNumber) }
        var customName by remember(target.packageName, serialNum) {
            mutableStateOf("${target.label} $serialNum")
        }

        AlertDialog(
            onDismissRequest = { if (!isCloning) appToClone = null },
            title = { Text("Clone ${target.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Serial #:", fontWeight = FontWeight.Medium)
                        FilledTonalIconButton(
                            onClick = { if (serialNum > 1) serialNum-- },
                            enabled = !isCloning && serialNum > 1
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        Text(
                            "$serialNum",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        FilledTonalIconButton(
                            onClick = { if (serialNum < 99) serialNum++ },
                            enabled = !isCloning && serialNum < 99
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }

                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Home screen name") },
                        singleLine = true,
                        enabled = !isCloning
                    )

                    Text(
                        "Package: ${target.packageName}.clone$serialNum",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isCloning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(cloneProgressText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isCloning,
                    onClick = {
                        if (!InstallHelper.canInstallPackages(context)) {
                            Toast.makeText(
                                context,
                                "Please allow 'Install unknown apps' first, then tap Clone again.",
                                Toast.LENGTH_LONG
                            ).show()
                            InstallHelper.openUnknownSourcesSettings(context)
                            return@Button
                        }

                        isCloning = true
                        cloneProgressText = "Starting..."
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    ApkClonerEngine.cloneApk(
                                        context = context,
                                        sourceApkPath = target.sourceApkPath,
                                        originalPackage = target.packageName,
                                        originalLabel = target.label,
                                        serialNumber = serialNum,
                                        customLabel = customName,
                                        onProgress = { msg ->
                                            scope.launch(Dispatchers.Main) {
                                                cloneProgressText = msg
                                            }
                                        }
                                    )
                                }
                            }
                            isCloning = false
                            result.onSuccess { cloned ->
                                appToClone = null
                                InstallHelper.installApk(context, cloned.signedApk)
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    "Clone failed: ${err.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(if (isCloning) "Cloning..." else "Clone & Install")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isCloning,
                    onClick = { appToClone = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun OriginalAppCard(
    app: InstalledAppItem,
    onCloneClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconImage(drawable = app.icon)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.existingCloneSerials.isNotEmpty()) {
                    Text(
                        "Clones installed: ${app.existingCloneSerials.joinToString(", ") { "#$it" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onCloneClick,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Clone #${app.nextSerialNumber}")
            }
        }
    }
}

@Composable
private fun ClonedAppCard(
    app: InstalledAppItem,
    onOpen: () -> Unit,
    onUninstall: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AppIconImage(drawable = app.icon)
                if (app.cloneSerial != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${app.cloneSerial}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Open")
            }
            IconButton(onClick = onUninstall) {
                Icon(Icons.Default.Delete, contentDescription = "Uninstall")
            }
        }
    }
}

@Composable
private fun AppIconImage(drawable: Drawable?) {
    val bitmap = remember(drawable) {
        drawable?.let { d ->
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            bmp.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE2E8F0)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Android, contentDescription = null)
        }
    }
}
