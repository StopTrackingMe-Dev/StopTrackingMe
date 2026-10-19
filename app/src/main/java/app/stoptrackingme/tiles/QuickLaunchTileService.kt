package app.stoptrackingme.tiles

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.stoptrackingme.MainActivity

abstract class QuickLaunchTileService : TileService() {
    internal abstract val launchAction: QuickTileAction

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launchApp() } else launchApp()
    }

    private fun launchApp() {
        val intent = createLaunchIntent(this, launchAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    launchAction.ordinal,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        internal fun createLaunchIntent(context: Context, action: QuickTileAction): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(action.intentAction)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

class OpenAppTileService : QuickLaunchTileService() {
    internal override val launchAction = QuickTileAction.OPEN_APP
}

class ClipboardTileService : QuickLaunchTileService() {
    internal override val launchAction = QuickTileAction.CLEAN_CLIPBOARD
}
