package io.github.yanganqi.qqspaceautolike.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP -> QqAutoLikeService.requestStop()
        }
    }

    companion object {
        const val ACTION_STOP = "io.github.yanganqi.qqspaceautolike.action.STOP"
    }
}

