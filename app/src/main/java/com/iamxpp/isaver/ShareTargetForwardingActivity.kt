package com.iamxpp.isaver

import android.app.Activity
import android.content.Intent
import android.os.Bundle

abstract class ShareTargetForwardingActivity : Activity() {
    protected abstract val target: ShareTarget

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(intent).apply {
                setClass(this@ShareTargetForwardingActivity, MainActivity::class.java)
                putExtra(ShareTarget.EXTRA_NAME, target.id)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        finish()
    }
}

class SaveSharedFileActivity : ShareTargetForwardingActivity() {
    override val target = ShareTarget.SAVE
}

class OpenSharedFileLocationActivity : ShareTargetForwardingActivity() {
    override val target = ShareTarget.OPEN_LOCATION
}
