package com.iamxpp.isaver

import android.content.Intent

internal object ShareIntentDispatchPolicy {
    fun shouldHandleInitial(hasSavedState: Boolean, flags: Int): Boolean =
        !hasSavedState && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    fun shouldHandleNewIntent(flags: Int): Boolean =
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
}
