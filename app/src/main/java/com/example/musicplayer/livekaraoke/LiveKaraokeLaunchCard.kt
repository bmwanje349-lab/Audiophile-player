package com.example.musicplayer.livekaraoke

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** Launch card for the separate progressive/live karaoke engine. */
object LiveKaraokeLaunchCard {

    fun build(
        parent: ViewGroup,
        onClick: () -> Unit,
    ): View {
        val accent =
            Color.rgb(84, 184, 255)

        val card =
            MaterialCardView(parent.context).apply {
                radius = 22f
                setCardBackgroundColor(
                    Color.rgb(20, 23, 28)
                )
                strokeWidth = 1
                strokeColor =
                    Color.rgb(39, 44, 51)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onClick()
                }
            }

        val row =
            LinearLayout(parent.context).apply {
                gravity =
                    Gravity.CENTER_VERTICAL
                setPadding(
                    16,
                    16,
                    12,
                    16,
                )
            }

        row.addView(
            TextView(parent.context).apply {
                text = "LIVE"
                gravity = Gravity.CENTER
                setTextColor(accent)
                textSize = 11f
                setTypeface(
                    null,
                    Typeface.BOLD,
                )
                background =
                    GradientDrawable().apply {
                        setColor(
                            Color.rgb(
                                27,
                                31,
                                37,
                            )
                        )
                        cornerRadius = 17f
                    }
            },
            LinearLayout.LayoutParams(
                76,
                76,
            ),
        )

        val copy =
            LinearLayout(parent.context).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    14,
                    0,
                    8,
                    0,
                )
            }

        copy.addView(
            TextView(parent.context).apply {
                text = "Live Karaoke"
                setTextColor(
                    Color.rgb(
                        245,
                        247,
                        250,
                    )
                )
                textSize = 17f
                setTypeface(
                    null,
                    Typeface.BOLD,
                )
            }
        )

        copy.addView(
            TextView(parent.context).apply {
                text =
                    "Play the instrumental while MDX-Net keeps processing ahead"
                setTextColor(
                    Color.rgb(
                        167,
                        175,
                        186,
                    )
                )
                textSize = 13f
                setPadding(
                    0,
                    4,
                    0,
                    0,
                )
            }
        )

        copy.addView(
            TextView(parent.context).apply {
                text =
                    "Progressive • seekable • bounded RAM"
                setTextColor(
                    Color.rgb(
                        115,
                        124,
                        136,
                    )
                )
                textSize = 12f
                setPadding(
                    0,
                    6,
                    0,
                    0,
                )
            }
        )

        row.addView(
            copy,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f,
            ),
        )

        row.addView(
            TextView(parent.context).apply {
                text = "›"
                setTextColor(
                    Color.rgb(
                        167,
                        175,
                        186,
                    )
                )
                textSize = 30f
                gravity = Gravity.CENTER
            }
        )

        card.addView(row)

        return card.apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    -2,
                ).apply {
                    bottomMargin = 12
                }
        }
    }
}
