package com.backscreen.wallpaper.wallpaper

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric: Add images adds to the app's copies, and takes a folder's place. */
@RunWith(AndroidJUnit4::class)
class GalleryAddTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Picked "images": files holding their own [names], to tell them apart. */
    private fun picked(vararg names: String): List<Uri> = names.map { name ->
        Uri.fromFile(File(context.cacheDir, name).apply { writeText(name) })
    }

    private fun contents() = Gallery.images(context, fresh = true).map { File(it.path!!).readText() }

    @Test
    fun addedImagesGoAfterTheOthersAndTheOneShowingStays() {
        Gallery.addImages(context, picked("a", "b"))
        // The second, not the first: starting again would show that one too.
        Gallery.current(context)
        val showing = Gallery.skip(context)
        assertEquals("b", showing?.let { File(it.path!!).readText() })

        Gallery.addImages(context, picked("c"))

        assertEquals(listOf("a", "b", "c"), contents())
        assertEquals(3, Gallery.copyCount(context))
        assertEquals(showing, Gallery.current(context).image)
    }

    @Test
    fun setImagesReplacesTheCopies() {
        Gallery.addImages(context, picked("a", "b"))
        Gallery.setImages(context, picked("c"))
        assertEquals(listOf("c"), contents())
    }

    @Test
    fun withAFolderAddedImagesTakeItsPlace() {
        Gallery.addImages(context, picked("a"))
        val folder = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putString("folder", folder).commit()
        // While a folder is chosen, the copies don't count.
        assertEquals(0, Gallery.copyCount(context))

        Gallery.addImages(context, picked("b", "c"))

        assertNull(Gallery.folder(context))
        assertEquals(listOf("b", "c"), contents())
    }
}
