package com.dashwood.ftphandler.listener

import com.dashwood.ftphandler.ftp_service.FTPService.Action
import com.dashwood.ftphandler.ftp_service.FTPService.FtpError
import com.dashwood.ftphandler.model.FileModel

interface OnFTPJobListener {
    fun onConnected() {}
    fun onDisconnected() {}
    fun onSuccess(action: Action, message: Any) {}
    fun onProgress(fileModels : List<FileModel>) {}
    fun onError(action: Action, error: FtpError) {}
}