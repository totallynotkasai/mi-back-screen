package com.backscreen.wallpaper.notifications

import android.content.Context
import com.backscreen.wallpaper.R

/** The made-up message that the Notifications tab's preview and Try on back screen show. */
object SampleNotification {
    /** Not a real app: it has an icon of its own ([R.drawable.ic_sample_chat]). */
    const val PACKAGE = "com.backscreen.wallpaper.sample"

    fun make(context: Context) = RearNotification(
        key = "sample",
        packageName = PACKAGE,
        appName = context.getString(R.string.sample_app),
        postedAt = System.currentTimeMillis(),
        content = NoteContent(context.getString(R.string.sample_title), context.getString(R.string.sample_text)),
    )
}
