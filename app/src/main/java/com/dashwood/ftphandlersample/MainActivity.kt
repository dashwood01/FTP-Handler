package com.dashwood.ftphandlersample

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dashwood.ftphandler.ftp_service.FTPService
import com.dashwood.ftphandler.listener.OnFTPJobListener
import com.dashwood.ftphandler.model.FileModel
import com.dashwood.ftphandler.models.DownloadItem
import com.dashwood.ftphandler.models.UploadItem
import com.dashwood.ftphandlersample.ui.theme.FTPHandlerSampleTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val externalCacheDir: File? = externalCacheDir

        setContent {
            FTPHandlerSampleTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        externalCacheDir = externalCacheDir,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(
    externalCacheDir: File?,
    modifier: Modifier = Modifier
) {

    // Live transfer snapshots keyed by FULL remote path or dest path (avoid same-name collisions)
    val transfers = remember { mutableStateMapOf<String, FileModel>() }

    var message by remember { mutableStateOf("Connect") }
    val fileModelList = remember { mutableStateListOf<FileModel>() }
    val listFile = remember { mutableStateListOf<UploadItem>() }
    var folderSelected by remember { mutableStateOf("/") }
    var isUploading by remember { mutableStateOf(false) }

    // ✅ Create the service ONCE; never recreate across recompositions
    val ftpService = remember {
        FTPService(
            host = "",
            port = 0,
            username = "",
            password = ""
        )
    }

    // ✅ Stable listener object
    val listener = remember {
        object : OnFTPJobListener {
            override fun onConnected() {
                message = "Connected"
                ftpService.list(folderSelected)
            }

            override fun onSuccess(action: FTPService.Action, msg: Any) {
                if (action == FTPService.Action.LIST) {
                    @Suppress("UNCHECKED_CAST")
                    val list = (msg as List<FileModel>)
                    fileModelList.clear()
                    fileModelList.addAll(list)
                    return
                }

                // Detect end-of-batch messages; release the UI guard
                if (msg is String && msg.startsWith("Batch upload finished")) {
                    isUploading = false
                    // listFile.clear() // uncomment if you want to flush the queue after a successful batch
                }

                message = "✅ $action -> $msg"
            }

            override fun onProgress(fileModels: List<FileModel>) {
                val seenKeys = HashSet<String>()
                for (fm in fileModels) {
                    // Use a strong key to avoid collisions: prefer full remote or dest path if present
                    val key = when (fm.action) {
                        FTPService.Action.UPLOAD -> "${folderSelected}${fm.name}"
                        FTPService.Action.DOWNLOAD -> fm.name // dest name is OK; adjust to a full path if you track it
                        else -> fm.name
                    }
                    seenKeys += key
                    transfers[key] = fm
                    println("NAME:${fm.name} SIZE:${fm.size} PROG:${fm.bytesTransferred}/${fm.totalBytes}")
                }
                // Remove finished/non-updating entries
                transfers.keys.retainAll(seenKeys)
            }

            override fun onError(action: FTPService.Action, error: FTPService.FtpError) {
                isUploading = false
                message = when (error) {
                    is FTPService.FtpError.AuthFailed -> "Authentication failed (username/password)."
                    is FTPService.FtpError.PathNotFound -> "Path not found."
                    is FTPService.FtpError.Timeout -> "Timeout."
                    is FTPService.FtpError.Protocol -> "Protocol problem."
                    is FTPService.FtpError.IO -> "I/O error."
                    is FTPService.FtpError.NotConnected -> "Not connected."
                    is FTPService.FtpError.Unknown -> "Unknown error."
                }
            }
        }
    }

    // ✅ Attach listener once; clean up on dispose
    DisposableEffect(Unit) {
        ftpService.setListener(listener)
        onDispose {
            ftpService.setListener(null)
            ftpService.disconnect()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Button(
                onClick = {
                    if (!ftpService.isConnected()) {
                        message = "Connecting..."
                        ftpService.connect()
                    }
                }
            ) { Text(message) }

            // Pick file(s) and enqueue for upload
            FilePickerSample { picked ->
                listFile.add(UploadItem(picked, "$REMOTE_BASE/${picked.name}"))
            }

            // ✅ Guard: prevent double-submit while a batch is in-flight
            Button(
                enabled = !isUploading && listFile.isNotEmpty(),
                onClick = {
                    isUploading = true
                    ftpService.uploadManySequential(listFile)
                }
            ) { Text(if (isUploading) "Uploading…" else "Upload") }
        }

        items(items = fileModelList, key = { it.name }) { fileModel ->
            val key = "${folderSelected}${fileModel.name}"
            val live = transfers[key]

            Button(
                modifier = Modifier.padding(5.dp),
                onClick = {
                    when (fileModel.type) {
                        FTPService.Type.DIR -> {
                            folderSelected = if (folderSelected.endsWith("/")) {
                                "$folderSelected${fileModel.name}/"
                            } else {
                                "$folderSelected/${fileModel.name}/"
                            }
                            fileModelList.clear()
                            ftpService.list(folderSelected)
                        }

                        FTPService.Type.FILE -> {
                            val downloads = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            )
                            val dashwoodFolder = File(downloads, "DashWood").apply { mkdirs() }

                            val remotePath = "$folderSelected${fileModel.name}"
                            val destFile = File(dashwoodFolder, fileModel.name)

                            ftpService.downloadMany(
                                items = listOf(DownloadItem(remotePath, destFile)),
                                parallelism = 1
                            )
                        }

                        FTPService.Type.LINK -> Unit
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp)
                ) {
                    Text(text = fileModel.name)

                    if (live != null) {
                        if (live.totalBytes > 0) {
                            val p = (live.bytesTransferred.toFloat() / live.totalBytes.toFloat())
                                .coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = p,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${formatBytes(live.bytesTransferred)} / ${formatBytes(live.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "${formatBytes(live.bytesTransferred)} / ?",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Human-readable formatter. */
@Composable
private fun formatBytes(b: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        b >= gb -> String.format("%.2f GB", b / gb)
        b >= mb -> String.format("%.2f MB", b / mb)
        b >= kb -> String.format("%.2f KB", b / kb)
        else -> "$b B"
    }
}