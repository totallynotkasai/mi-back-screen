package com.backscreen.wallpaper.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.R

/**
 * The tab of a section that isn't built yet: its main switch, greyed out, and what it will do.
 * Each is replaced by the section's own tab as it's built.
 */
class PlannedFeatureFragment : Fragment(R.layout.fragment_planned) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val feature = Feature.valueOf(requireArguments().getString(ARG_FEATURE)!!)
        view.findViewById<MainSwitchBar>(R.id.mainSwitch).apply {
            setTitle(feature.switchTitle)
            setSummary(R.string.coming_later)
            isEnabled = false
        }
        view.findViewById<ImageView>(R.id.plannedIcon).setImageResource(feature.icon)
        feature.planned?.let { view.findViewById<TextView>(R.id.plannedText).setText(it) }
    }

    companion object {
        private const val ARG_FEATURE = "feature"

        fun newInstance(feature: Feature) = PlannedFeatureFragment().apply {
            arguments = Bundle().apply { putString(ARG_FEATURE, feature.name) }
        }
    }
}
