package com.dashwood.ftphandler.models

import java.io.File

data class DownloadItem(val remotePath: String, val dest: File)
