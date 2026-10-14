package com.backscreen.wallpaper.ui

import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.MainActivity

/** Shizuku isn't ready, and has had its moment to connect: a tab that needs it says it's waiting. */
val Fragment.shizukuMissing: Boolean
    get() = (activity as? MainActivity)?.shizukuMissing == true

/** Something this tab does needs Shizuku, which isn't ready: says so, with Setup help. */
fun Fragment.needShizuku() = (requireActivity() as MainActivity).needShizuku()
