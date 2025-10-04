package com.dashwood.ftphandlersample

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

@Composable
fun FilePickerSample(getFilePathPicked: (filePath: File) -> Unit) {
    val context = LocalContext.current
    var pickedFilePath by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pickedFilePath = getRealPathFromURI(context, it)
        }
    }
    LaunchedEffect(pickedFilePath) {
        if (pickedFilePath.isNullOrEmpty()) return@LaunchedEffect
        getFilePathPicked(File(pickedFilePath!!))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(onClick = { filePickerLauncher.launch("*/*") }) {
            Text("Pick File")
        }

        Text(
            text = pickedFilePath ?: "No file selected",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Resolve Uri to a real filesystem path string.
 * For SAF Uris, copies the file to cache dir and returns that path.
 */
fun getRealPathFromURI(context: Context, uri: Uri): String {
    // Handle content:// URIs (modern storage)
    if (uri.scheme == "content") {
        // Some providers return file:// directly
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        var fileName: String? = null
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }

        fileName = fileName ?: "tempfile"
        val tempFile = File(context.cacheDir, fileName!!)

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            copyStreamToFile(inputStream, tempFile)
        }

        return tempFile.absolutePath
    }
    // Handle file:// URIs (legacy)
    else if (uri.scheme == "file") {
        return uri.path ?: ""
    }
    return ""
}

private fun copyStreamToFile(inputStream: InputStream, file: File) {
    FileOutputStream(file).use { output ->
        inputStream.copyTo(output)
    }
}
