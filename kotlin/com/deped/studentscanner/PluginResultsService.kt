package com.deped.studentscanner

import android.app.IntentService
import android.content.Intent
import android.util.Log

class PluginResultsService : IntentService("PluginResultsService") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) return
        val taskId = intent.getIntExtra("task_id", -1)
        val resultBundle = intent.getBundleExtra("com.termux.service.extra.plugin_result_bundle")
        if (taskId == -1 || resultBundle == null) return

        val stdout = resultBundle.getString("com.termux.service.extra.plugin_result_bundle_stdout", "")
        val stderr = resultBundle.getString("com.termux.service.extra.plugin_result_bundle_stderr", "")
        val exitCode = resultBundle.getInt("com.termux.service.extra.plugin_result_bundle_exit_code", -1)

        Log.d("PluginResultsService", "Task $taskId result: exitCode=$exitCode, stdout=$stdout, stderr=$stderr")

        if (exitCode == 0) {
            TaskManager.updateTask(taskId, TaskStatus.SUCCESS, result = stdout)
        } else {
            TaskManager.updateTask(taskId, TaskStatus.ERROR, result = stdout, error = stderr)
        }
    }
} 