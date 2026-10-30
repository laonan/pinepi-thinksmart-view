package com.pinepi.thinksmart.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

/** Public Android intents only; never changes the default Home or Bluetooth state. */
class SystemNavigation(private val activity: Activity) {
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun isDefaultHome(): Boolean {
        val resolved = activity.packageManager.resolveActivity(
            homeIntent(), PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolved?.activityInfo?.packageName == activity.packageName
    }

    fun openSettings(action: String) {
        if (launch(Intent(action))) return
        if (action != Settings.ACTION_SETTINGS && launch(Intent(Settings.ACTION_SETTINGS))) return
        showUnavailable()
    }

    fun openOtherLauncher() {
        val manager = activity.packageManager
        val candidates = manager.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != activity.packageName && it.activityInfo.exported }
            .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.loadLabel(manager).toString() }

        if (candidates.isEmpty()) {
            Toast.makeText(activity, R.string.no_other_launcher, Toast.LENGTH_LONG).show()
            openSettings(Settings.ACTION_HOME_SETTINGS)
            return
        }

        val labels = candidates.map {
            "${it.loadLabel(manager)} (${it.activityInfo.packageName})"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.maintenance_launcher)
            .setItems(labels) { _, index ->
                val info = candidates[index].activityInfo
                val intent = homeIntent()
                    .setComponent(ComponentName(info.packageName, info.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (!launch(intent)) showUnavailable()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launch(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun showUnavailable() {
        Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
