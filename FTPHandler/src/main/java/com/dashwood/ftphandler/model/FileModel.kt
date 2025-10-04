package com.dashwood.ftphandler.model

import com.dashwood.ftphandler.ftp_service.FTPService
import com.dashwood.ftphandler.ftp_service.FTPService.Action

data class FileModel(
    val type: FTPService.Type,
    val name: String,
    val size: Long,
    val action: Action? = null,
    val bytesTransferred: Long=0,
    val totalBytes: Long=0
)