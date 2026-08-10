package com.iamxpp.isaver

import android.content.Intent

enum class ShareTarget(val id: String) {
    SAVE("save"),
    OPEN_LOCATION("open_location"),
    ;

    companion object {
        const val EXTRA_NAME = "com.iamxpp.isaver.extra.SHARE_TARGET"

        fun fromIntent(intent: Intent?): ShareTarget? {
            if (intent?.isShareAction() != true) return null
            return when (intent.getStringExtra(EXTRA_NAME)) {
                OPEN_LOCATION.id -> OPEN_LOCATION
                SAVE.id, null -> SAVE
                else -> SAVE
            }
        }
    }
}

private fun Intent.isShareAction(): Boolean =
    action == Intent.ACTION_SEND || action == Intent.ACTION_VIEW
