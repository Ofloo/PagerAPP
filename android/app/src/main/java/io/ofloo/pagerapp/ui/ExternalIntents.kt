package io.ofloo.pagerapp.ui

import android.net.Uri

/** A notification tap that should open one specific page/chat. */
data class PageTarget(val pageId: String, val from: String?)

/** Content handed to PagerAPP through Android's share sheet. */
data class SharePayload(val text: String?, val uri: Uri?, val mime: String?)
