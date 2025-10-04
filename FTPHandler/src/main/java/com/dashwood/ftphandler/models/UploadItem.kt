package com.dashwood.ftphandler.models

import java.io.File

data class UploadItem(val local: File, val remotePath: String)
