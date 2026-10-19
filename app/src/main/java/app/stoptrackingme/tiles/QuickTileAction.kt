package app.stoptrackingme.tiles

internal enum class QuickTileAction(val intentAction: String) {
    OPEN_APP("app.stoptrackingme.action.TILE_OPEN_APP"),
    CLEAN_CLIPBOARD("app.stoptrackingme.action.TILE_CLEAN_CLIPBOARD"),
    PICK_QR_IMAGE("app.stoptrackingme.action.TILE_PICK_QR_IMAGE"),
    ;

    companion object {
        fun fromIntentAction(action: String?): QuickTileAction? =
            entries.firstOrNull { it.intentAction == action }
    }
}
