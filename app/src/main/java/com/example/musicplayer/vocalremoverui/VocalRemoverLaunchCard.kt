package com.example.musicplayer.vocalremoverui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Sound-hub card matching the existing processor launch-card pattern. */
object VocalRemoverLaunchCard {
    fun build(parent: ViewGroup, onClick: () -> Unit): View {
        val accent = Color.rgb(44, 190, 214)

        val card = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(parent, 16),
                dp(parent, 14),
                dp(parent, 16),
                dp(parent, 14)
            )
            setBackgroundColor(Color.rgb(23, 26, 31))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        card.addView(
            TextView(parent.context).apply {
                text = "AI Vocal Remover"
                textSize = 17f
                setTextColor(Color.rgb(242, 245, 247))
            }
        )

        card.addView(
            TextView(parent.context).apply {
                text = "MDX-Net vocal separation · instrumental copy"
                textSize = 13f
                setTextColor(Color.rgb(157, 167, 177))
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(parent, 4)
            }
        )

        card.addView(
            TextView(parent.context).apply {
                text = "AI"
                textSize = 12f
                setTextColor(accent)
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(parent, 8)
            }
        )

        return card
    }

    private fun dp(v: Int): Int =
        (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    private fun dp(parent: ViewGroup, v: Int): Int =
        (v * parent.resources.displayMetrics.density).toInt()
}
