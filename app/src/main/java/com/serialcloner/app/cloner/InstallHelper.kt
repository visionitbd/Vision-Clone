package com.serialcloner.app.cloner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        var onInstallResult: ((Boolean, String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Clone installed on Home Screen!", Toast.LENGTH_SHORT).show()
                onInstallResult?.invoke(true, "Installed successfully")
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                onInstallResult?.invoke(false, "Installation cancelled")
            }

            else -> {
                val detail = if (message.isNotBlank()) message else "Install failed (code $status)"
                Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                onInstallResult?.invoke(false, detail)
            }
        }
    }
}

object InstallHelper {

    fun canInstallPackages(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Installs one or more APKs (base.apk + any split APKs) atomically using Android PackageInstaller.
     */
    fun installApks(context: Context, apkFiles: List<File>) {
        val installer = context.packageManager.packageInstaller
        val totalSize = apkFiles.sumOf { it.length() }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(totalSize)
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            for (apk in apkFiles) {
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    FileInputStream(apk).use { input ->
                        input.copyTo(out)
                    }
                    session.fsync(out)
                }
            }

            val callbackIntent = Intent(context, InstallResultReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                flags
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    fun launchApp(context: Context, packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }

    fun uninstallApp(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
