# FTPHandler (Android/Kotlin)

A lightweight, pragmatic FTP client for Android with **progress callbacks**, **folder listing**, and **parallel / resumable downloads**. Built to be dropped into modern Jetpack Compose projects with minimal ceremony.

> **TL;DR**
> ```kotlin
> val ftp = FTPService(host = "your.host", port = 21, username = "user", password = "pass")
> ftp.setListener(listener)
> ftp.connect()            // -> onConnected()
> ftp.list("/")            // -> onSuccess(Action.LIST, List<FileModel>)
> ftp.downloadMany(
>     items = listOf(DownloadItem("/remote/file.zip", File(destDir, "file.zip"))),
>     parallelism = 2
> )
> ```

---

## Why this library?
Because you want a **straightforward** FTP API that gives you:
- ✅ **Connection & directory listing**
- ✅ **Parallel downloads** with back‑pressure
- ✅ **Resumable download** helper
- ✅ **Progress snapshots** for each in‑flight transfer
- ✅ A clean, **listener-based** API that is easy to bind to Compose state

No fluff. No ceremony. It just works.

---

## Features
- **Connect / Disconnect** with a single call
- **List** remote directories and render them in your UI
- **Download single or many files** (control `parallelism`)
- **Resumable download** helper for large files
- **Progress reporting** per file (`bytesTransferred`, `totalBytes`)
- **Error types** you can act on: `AuthFailed`, `PathNotFound`, `Timeout`, `Protocol`, `IO`, `NotConnected`, `Unknown`

Minimum requirements:
- **minSdk**: 21+
- **Language**: Kotlin (works fine from Java, too)
- **UI**: Jetpack Compose example included (optional)

---

## Installation

### Option A — As a module (recommended during development)
1. Copy or include the `ftphandler` module in your project.
2. Add the module to your app’s `settings.gradle` and `app/build.gradle` as usual:
   ```gradle
   dependencies {
       implementation project(":ftphandler")
   }
   ```

### Option B — From a Maven host (e.g., JitPack)
If you publish the library, depend on it as you normally do. Example (JitPack):
```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.your-org:ftphandler:<version>' }
```

---

## Permissions & storage

- **Internet** is required:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" />
  ```

- For saving to **Downloads** you can either:
  - Use the legacy public downloads dir (pre‑29):
    ```kotlin
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    ```
  - Or, for API 29+ prefer the **Storage Access Framework** / `MediaStore` if you want user‑visible files with scoped storage.
  
This sample shows a simple approach for clarity; adapt to your storage policy.

---

## Quick start (Compose)

```kotlin
val transfers = remember { mutableStateMapOf<String, FileModel>() }
val fileModelList = remember { mutableStateListOf<FileModel>() }
var message by remember { mutableStateOf("Connect") }
var folderSelected by remember { mutableStateOf("/") }

val ftpService = remember {
    FTPService(host = "<HOST>", port = 21, username = "<USER>", password = "<PASS>")
}.apply {
    setListener(object : OnFTPJobListener {
        override fun onConnected() {
            message = "Connected"
            list("/")                 // fetch root listing
        }
        override fun onSuccess(action: FTPService.Action, msg: Any) {
            if (action == FTPService.Action.LIST) {
                fileModelList.clear()
                fileModelList.addAll(msg as List<FileModel>)
            } else {
                message = "✅ $action -> $msg"
            }
        }
        override fun onProgress(fileModels: List<FileModel>) {
            val seen = HashSet<String>()
            for (fm in fileModels) {
                val key = fm.name       // or remote full path if you want uniqueness across folders
                seen += key
                transfers[key] = fm
            }
            transfers.keys.retainAll(seen)
        }
        override fun onError(action: FTPService.Action, error: FTPService.FtpError) {
            message = when (error) {
                is FTPService.FtpError.AuthFailed    -> "Authentication failed (username/password)"
                is FTPService.FtpError.PathNotFound  -> "Path not found"
                is FTPService.FtpError.Timeout       -> "Timeout"
                is FTPService.FtpError.Protocol      -> "Protocol error"
                is FTPService.FtpError.IO            -> "I/O error"
                is FTPService.FtpError.NotConnected  -> "Not connected"
                is FTPService.FtpError.Unknown       -> "Unknown error"
            }
        }
    })
}

// Connect
LaunchedEffect(Unit) {
    if (!ftpService.isConnected()) ftpService.connect()
}
```

### Listing and downloading

```kotlin
// Navigate folders
fun open(file: FileModel) {
    when (file.type) {
        FTPService.Type.DIR -> {
            folderSelected += "${'$'}{file.name}/"
            fileModelList.clear()
            ftpService.list(folderSelected)
        }
        FTPService.Type.FILE -> {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dashwood = File(downloads, "DashWood").apply { if (!exists()) mkdirs() }
            val remote = "${'$'}folderSelected${'$'}{file.name}"
            val dest   = File(dashwood, file.name)
            // Parallel/queued downloads
            ftpService.downloadMany(
                items = listOf(DownloadItem(remote, dest)),
                parallelism = 1
            )
            // Or resumable single download:
            // ftpService.downloadResumable(remote, dest)
        }
        FTPService.Type.LINK -> { /* handle symlink if you need */ }
    }
}
```

### Showing progress

```kotlin
val live = transfers[file.name]
if (live != null) {
    if (live.totalBytes > 0) {
        val p = (live.bytesTransferred.toFloat() / live.totalBytes.toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = p)
        Text("${'$'}{formatBytes(live.bytesTransferred)} / ${'$'}{formatBytes(live.totalBytes)}")
    } else {
        LinearProgressIndicator() // unknown size
        Text("${'$'}{formatBytes(live.bytesTransferred)} / ?")
    }
}
```

---

## API surface (essentials)

```kotlin
class FTPService(
    host: String,
    port: Int = 21,
    username: String,
    password: String
) {
    enum class Action { CONNECT, LIST, DOWNLOAD, DOWNLOAD_MANY }
    enum class Type { FILE, DIR, LINK }

    fun setListener(listener: OnFTPJobListener)
    fun connect()
    fun disconnect()
    fun isConnected(): Boolean

    fun list(path: String) // triggers onSuccess(Action.LIST, List<FileModel>)

    fun downloadResumable(remotePath: String, dest: File)

    fun downloadMany(items: List<DownloadItem>, parallelism: Int = 2)
}

interface OnFTPJobListener {
    fun onConnected()
    fun onSuccess(action: FTPService.Action, msg: Any)
    fun onProgress(fileModels: List<FileModel>)
    fun onError(action: FTPService.Action, error: FTPService.FtpError)
}
```

**Models (abridged):**
- `FileModel`: `name`, `type`, `bytesTransferred`, `totalBytes`, etc.
- `DownloadItem(remotePath: String, destFile: File)`

---

## Error handling

You’ll get structured errors to help you recover or inform the user:

- `AuthFailed` — wrong credentials
- `PathNotFound` — listing or downloading a missing path
- `Timeout` — flaky connections
- `Protocol` — low‑level FTP protocol issues
- `IO` — local file problems (disk full, permissions, etc.)
- `NotConnected` — you forgot to `connect()`
- `Unknown` — catch‑all

Use these to show **clear, actionable** messages (as done in the sample).

---

## R8 / ProGuard

If you shrink/obfuscate, keep the library’s public API (usually safe by default). If you run into issues, you can add:

```pro
-keep class com.dashwood.ftphandler.** { *; }
```

---

## Notes & tips

- Use **unique keys** (e.g., remote full path) if you can download files with the same name from different folders.
- For very large files, prefer `downloadResumable()`; it plays nicely with flaky links.
- If you target API 29+, consider SAF/MediaStore for saving **user‑visible** files.
- FTP is stateful; **one connection per logical task** is often cleaner than reusing a stale one.

---

## Sample app

A minimal Compose sample is included in `ftphandlersample`. It shows:
- Connecting and listing `/`
- Navigating into directories
- Downloading a file into `Downloads/DashWood`
- Per‑file progress bars bound to Compose state

Clone, run, and replace placeholders for host/port/username/password.

---

## Contributing

PRs that improve stability, add unit tests around edge cases, or extend progress reporting are welcome. Keep the API small and predictable.

---

## Support

If you run into a real‑world edge case (firewalls, NATs, partial transfers), open an issue with **logs and steps**. I will be opinionated about scope creep—features should earn their place.
