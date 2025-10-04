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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dashwood.ftphandler.ftp_service.FTPService
import com.dashwood.ftphandler.listener.OnFTPJobListener
import com.dashwood.ftphandler.model.FileModel
import com.dashwood.ftphandler.models.DownloadItem
import com.dashwood.ftphandlersample.ui.theme.FTPHandlerSampleTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val externalCacheDir = externalCacheDir  // returns File?

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
    val transfers = remember { mutableStateMapOf<String, FileModel>() }

    var message: String by remember { mutableStateOf("Connect") }
    val fileModelList = remember { mutableStateListOf<FileModel>() }
    val ftpService = FTPService(
        host = "workspace.oscaret.com",
        port = 2100,
        username = "ftpsepehrandroid",
        password = "FTPSep@And!14"
    )

    ftpService.setListener(object : OnFTPJobListener {
        override fun onConnected() {
            message = "Connected"
            ftpService.list("/")
            //folderList.addAll(ftpService.list("/"))
            // مثلاً بعد از اتصال لیست بگیر
            //ftp.list("/public_html")
        }

        override fun onSuccess(action: FTPService.Action, msg: Any) {
            if (action == FTPService.Action.LIST) {
                fileModelList.addAll(msg as List<FileModel>)
                return
            }
            message = "✅ $action -> $msg"
        }

        override fun onProgress(fileModels: List<FileModel>) {
            // Merge snapshot into our state map
            // Key strategy: here we use the file name; if you can have duplicates, key with full path instead.
            val seenKeys = HashSet<String>()
            for (fm in fileModels) {
                val key = fm.name
                seenKeys += key
                transfers[key] = fm
            }
            // Optionally remove transfers that are not in the latest snapshot (finished)
            transfers.keys.retainAll(seenKeys)
        }


        override fun onError(action: FTPService.Action, error: FTPService.FtpError) {
            // هندل خطا به تفکیک
            message =
                when (error) {
                    is FTPService.FtpError.AuthFailed -> "Authentication failed user or pass wrong"
                    is FTPService.FtpError.PathNotFound -> "Path not found"
                    is FTPService.FtpError.Timeout -> "Timeout"
                    is FTPService.FtpError.Protocol -> "Having problem with protocol"
                    is FTPService.FtpError.IO -> "Io error"
                    is FTPService.FtpError.NotConnected -> "Not connected"
                    is FTPService.FtpError.Unknown -> "Unknown error"
                }

        }

    })
    var folderSelected by remember { mutableStateOf("/") }
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
        }

        // Your directory rows
        items(
            items = fileModelList,
            key = { it.name } // if names aren’t unique, use a stronger key
        ) { fileModel ->
            // Find live progress (if any) for this row
            val live = transfers[fileModel.name] // or use full path as key

            Button(
                modifier = Modifier.padding(5.dp),
                onClick = {
                    when (fileModel.type) {
                        FTPService.Type.DIR -> {
                            folderSelected += "${fileModel.name}/"
                            fileModelList.clear()
                            ftpService.list(folderSelected)
                        }

                        FTPService.Type.FILE -> {
                            val downloads = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            )
                            val dashwoodFolder = File(downloads, "DashWood")
                            if (!dashwoodFolder.exists()) dashwoodFolder.mkdirs()

                            val remotePath = "$folderSelected${fileModel.name}"
                            val destFile = File(dashwoodFolder, fileModel.name)

                            // Start RESUMABLE download; progress will flow into `onProgress`
                          //  ftpService.downloadResumable(remotePath, destFile)
                            ftpService.downloadMany(
                                items = listOf(DownloadItem(remotePath, destFile)),
                                parallelism = 1 // one file in this call; you can keep >1 too
                            )
                        }

                        FTPService.Type.LINK -> { /* handle symlink if you want */
                        }
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp)
                ) {
                    Text(text = fileModel.name)

                    // Progress bar: determinate if we know total, otherwise indeterminate.
                    if (live != null) {
                        if (live.totalBytes > 0) {
                            val p = (live.bytesTransferred.toFloat() / live.totalBytes.toFloat())
                                .coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = p,
                                modifier = Modifier.fillMaxWidth()
                            )
                            // Optional: textual percentage
                            Text(
                                text = "${formatBytes(live.bytesTransferred)} / ${formatBytes(live.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            // Unknown total size -> indeterminate
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

@Composable
private fun formatBytes(b: Long): String {
    // Simple human-readable formatter
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