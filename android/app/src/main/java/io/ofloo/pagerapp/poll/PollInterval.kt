package io.ofloo.pagerapp.poll

import android.content.Context
import io.ofloo.pagerapp.data.Store

/** Reads the user's polling interval without needing a Store instance in the service loop. */
object PollInterval {
    fun seconds(context: Context): Int = Store(context).pollSeconds
}
