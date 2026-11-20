package lytso.code.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lytso.code.data.receiver.TermuxOpenReceiver as CoreOpenReceiver

/**
 * Direct lytso.code.app.TermuxOpenReceiver implementation for shell scripts
 * (e.g., xdg-open, termux-open) targeting explicit component lytso.code.app.TermuxOpenReceiver.
 */
class TermuxOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        CoreOpenReceiver.handleOpen(context, intent)
    }
}
