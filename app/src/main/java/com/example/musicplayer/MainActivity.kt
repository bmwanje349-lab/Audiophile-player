package com.example.musicplayer
import com.example.musicplayer.vocalremoverui.VocalRemoverActivity
import com.example.musicplayer.vocalremoverui.VocalRemoverLaunchCard
import com.example.musicplayer.livekaraoke.LiveKaraokeActivity
import com.example.musicplayer.livekaraoke.LiveKaraokeLaunchCard
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.appbar.MaterialToolbar
import java.util.Calendar
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private lateinit var content: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var miniWrap: LinearLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniProgress: LinearProgressIndicator
    private lateinit var libraryAdapter: TrackAdapter
    private lateinit var statusText: TextView
    private lateinit var libraryRecycler: androidx.recyclerview.widget.RecyclerView
    private var librarySearchQuery = ""
    // Preserve taps made while MediaController is still connecting.
    private var pendingTrackId: Long? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var allTracks: List<TrackItem> = emptyList()
    private var currentTab = TAB_HOME
    private var nowPlayingShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) scanLibrary()
        else statusTextSafe("Music permission is needed to show your local library.")
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestMusicPermissionIfNeeded()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshPlaybackUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        connectController()
        refreshPlaybackUi()
        scheduleProgress()
    }

    override fun onStop() {
        uiHandler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Android 15 enforces edge-to-edge for targetSdk 35+. Reserve the navigation-bar
        // inset so the bottom navigation labels and mini-player are not hidden behind gestures.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBarBottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom
            view.setPadding(0, 0, 0, navBarBottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)

        toolbar = MaterialToolbar(this).apply {
            setTitleTextColor(TEXT)
            setBackgroundColor(BG)
            elevation = 0f
            navigationIcon = null
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(8))
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        miniWrap = buildMiniPlayer()
        root.addView(miniWrap, LinearLayout.LayoutParams(-1, dp(78)))

        bottomNav = BottomNavigationView(this).apply {
            setBackgroundColor(SURFACE)
            setItemIconTintList(navColors())
            setItemTextColor(navColors())
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            itemIconSize = dp(22)
            menu.add(0, TAB_HOME, 0, "Home").setIcon(android.R.drawable.ic_menu_view)
            menu.add(0, TAB_LIBRARY, 1, "Library").setIcon(android.R.drawable.ic_menu_agenda)
            menu.add(0, TAB_SOUND, 2, "Sound").setIcon(android.R.drawable.ic_menu_manage)
            menu.add(0, TAB_SETTINGS, 3, "Settings").setIcon(android.R.drawable.ic_menu_preferences)
            setOnItemSelectedListener {
                selectTab(it.itemId)
                true
            }
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(72)))

        bottomNav.selectedItemId = TAB_HOME
        return root
    }

    private fun buildMiniPlayer(): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mini = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = 1
            strokeColor = BORDER
            setCardBackgroundColor(SURFACE)
            isClickable = true
            isFocusable = true
            setOnClickListener { showNowPlaying() }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(6), dp(7))
        }
        val art = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(TEXT)
            background = roundedDrawable(SURFACE_2, 12)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Album artwork placeholder"
        }
        row.addView(art, LinearLayout.LayoutParams(dp(44), dp(44)))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        miniTitle = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniArtist = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(miniTitle, LinearLayout.LayoutParams(-1, -2))
        textCol.addView(miniArtist, LinearLayout.LayoutParams(-1, -2))
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        miniPlay = ImageButton(this).apply {
            background = null
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(TEXT)
            contentDescription = "Play or pause"
            setOnClickListener { togglePlay() }
        }
        row.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.addView(row)

        miniProgress = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
            trackThickness = dp(2)
            setIndicatorColor(ACCENT)
            trackColor = BORDER
        }

        wrap.setPadding(dp(12), dp(2), dp(12), dp(3))
        wrap.addView(mini, LinearLayout.LayoutParams(-1, dp(67)))
        wrap.addView(miniProgress, LinearLayout.LayoutParams(-1, dp(2)))
        return wrap
    }

    private fun selectTab(id: Int) {
        nowPlayingShown = false
        currentTab = id
        miniWrap.isVisible = true
        bottomNav.isVisible = true
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        content.removeAllViews()

        when (id) {
            TAB_LIBRARY -> {
                toolbar.title = "Library"
                content.addView(buildLibrary())
            }
            TAB_SOUND -> {
                toolbar.title = "Sound"
                content.addView(buildSoundHub())
            }
            TAB_SETTINGS -> {
                toolbar.title = "Settings"
                content.addView(buildSettings())
            }
            else -> {
                toolbar.title = "Audiophile Player"
                content.addView(buildHome())
            }
        }
    }

    private fun showNowPlaying() {
        nowPlayingShown = true
        miniWrap.isVisible = false
        bottomNav.isVisible = false
        content.removeAllViews()
        toolbar.title = "Now Playing"
        toolbar.navigationIcon = getDrawable(R.drawable.ic_arrow_back_24)
        toolbar.setNavigationOnClickListener { selectTab(currentTab) }
        content.addView(buildNowPlaying())
    }

    private fun buildHome(): View {
        val scroll = androidx.core.widget.NestedScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }

        root.addView(TextView(this).apply {
            text = greeting()
            setTextColor(TEXT)
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Your music, with a professional audio engine underneath."
            setTextColor(TEXT_SECONDARY)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(18))
        })

        root.addView(sectionLabel("Continue listening"))
        root.addView(buildContinueCard())

        root.addView(sectionLabel("Recently added"))
        if (allTracks.isEmpty()) {
            root.addView(emptyStateCard("No local music yet", "Grant music access or scan your library to populate the player."))
        } else {
            allTracks.take(6).forEachIndexed { index, track ->
                root.addView(trackRow(track, index))
            }
        }

        root.addView(sectionLabel("Sound engine"))
        root.addView(infoCard(
            "Midnight Audiophile",
            "Graphic EQ, 16-band Parametric EQ and the professional v10 Stereo Widener stay separate in the Sound section."
        ))

        scroll.addView(root)
        return scroll
    }

    private fun buildContinueCard(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(16))
        }
        val art = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(TEXT)
            background = roundedDrawable(SURFACE_2, 18)
            scaleType = ImageView.ScaleType.CENTER
        }
        row.addView(art, LinearLayout.LayoutParams(dp(72), dp(72)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        val current = controller?.currentMediaItem
        col.addView(TextView(this).apply {
            text = current?.mediaMetadata?.title?.toString() ?: "Nothing playing"
            setTextColor(TEXT)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = current?.mediaMetadata?.artist?.toString() ?: "Choose a track from your library"
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(8))
        })
        val open = MaterialButton(this).apply {
            text = if (current != null) "Open player" else "Open library"
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                if (current != null) showNowPlaying() else bottomNav.selectedItemId = TAB_LIBRARY
            }
        }
        col.addView(open, LinearLayout.LayoutParams(-2, dp(42)))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        }
    }

    private fun buildLibrary(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "Your music"
            setTextColor(TEXT)
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(MaterialButton(this).apply {
            text = "Rescan"
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { scanLibrary() }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56)))

        val search = EditText(this).apply {
            hint = "Search songs, artists…"
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 15f
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedDrawable(SURFACE, 16)
            contentDescription = "Search songs and artists"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    librarySearchQuery = s?.toString()?.trim().orEmpty()
                    filterLibrary()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })

        statusText = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
            text = if (allTracks.isEmpty()) "Scanning your music collection…" else "${allTracks.size} tracks"
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, dp(30)))

        libraryRecycler = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            setPadding(0, dp(4), 0, dp(8))
            clipToPadding = false
        }
        libraryAdapter = TrackAdapter(this) { track -> playFromLibrary(track) }
        libraryRecycler.adapter = libraryAdapter
        root.addView(libraryRecycler, LinearLayout.LayoutParams(-1, 0, 1f))
        filterLibrary()
        return root
    }

    private fun buildNowPlaying(): View {
        val scroll = androidx.core.widget.NestedScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(26))
        }

        val art = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(TEXT)
            background = roundedDrawable(SURFACE_2, 28)
            scaleType = ImageView.ScaleType.CENTER
        }
        root.addView(art, LinearLayout.LayoutParams(dp(292), dp(292)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val title = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(18), 0, 0)
        }
        val artist = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(10))
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(artist, LinearLayout.LayoutParams(-1, -2))

        val seek = SeekBar(this).apply { progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT) }
        val times = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val elapsed = TextView(this).apply { setTextColor(TEXT_SECONDARY); textSize = 12f; text = "0:00" }
        val duration = TextView(this).apply { setTextColor(TEXT_SECONDARY); textSize = 12f; text = "0:00"; gravity = Gravity.END }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(duration, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(times, LinearLayout.LayoutParams(-1, dp(24)))

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        controls.addView(actionButton(android.R.drawable.ic_media_rew, "Previous") { controller?.seekToPreviousMediaItem() }, LinearLayout.LayoutParams(dp(60), dp(60)))
        val play = actionButton(android.R.drawable.ic_media_play, "Play or pause") { togglePlay() }.apply {
            background = roundedDrawable(ACCENT, 34)
            setColorFilter(BG)
        }
        controls.addView(play, LinearLayout.LayoutParams(dp(72), dp(72)).apply { leftMargin = dp(12); rightMargin = dp(12) })
        controls.addView(actionButton(android.R.drawable.ic_media_ff, "Next") { controller?.seekToNextMediaItem() }, LinearLayout.LayoutParams(dp(60), dp(60)))
        root.addView(controls)

        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(12)) }
        modes.addView(textAction(if (controller?.shuffleModeEnabled == true) "Shuffle on" else "Shuffle") {
            controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false)
        })
        modes.addView(textAction(repeatLabel()) { cycleRepeat() })
        modes.addView(textAction("Queue") { showQueueDialog() })
        root.addView(modes, LinearLayout.LayoutParams(-1, dp(52)))

        root.addView(MaterialButton(this).apply {
            text = "Open Sound"
            setOnClickListener { launchDspHub() }
        }, LinearLayout.LayoutParams(-1, dp(52)))

        root.addView(infoCard("Playback engine", "Media3 handles the queue and background session; custom PCM DSP remains in the audio path when active."))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) controller?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        uiHandler.postDelayed(object : Runnable {
            override fun run() {
                val c = controller
                if (c != null && nowPlayingShown) {
                    val item = c.currentMediaItem
                    title.text = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
                    artist.text = item?.mediaMetadata?.artist?.toString() ?: "—"
                    val durationMs = max(0L, c.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
                    seek.max = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    seek.progress = c.currentPosition.coerceIn(0L, seek.max.toLong()).toInt()
                    elapsed.text = formatTime(c.currentPosition)
                    duration.text = formatTime(durationMs)
                    play.setImageResource(if (c.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                }
                if (nowPlayingShown) uiHandler.postDelayed(this, 450)
            }
        }, 80)

        scroll.addView(root)
        return scroll
    }

    private fun buildSoundHub(): View {
    val scroll = androidx.core.widget.NestedScrollView(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(24))
    }

    root.addView(TextView(this).apply {
        text = "Shape your sound"
        setTextColor(TEXT)
        textSize = 28f
        setTypeface(null, Typeface.BOLD)
    })

    root.addView(TextView(this).apply {
        text = "Three separate processors. One clean signal chain."
        setTextColor(TEXT_SECONDARY)
        textSize = 14f
        setPadding(0, dp(4), 0, dp(18))
    })

    root.addView(
        soundCard(
            "Graphic Equalizer",
            "10-band quick tone shaping",
            "31 Hz  →  16 kHz",
            "graphic"
        )
    )

    root.addView(
        soundCard(
            "Parametric Equalizer",
            "16-band precision control",
            "Frequency  •  Gain  •  Q  •  Filter",
            "parametric"
        )
    )

    root.addView(
        soundCard(
            "Stereo Widener",
            "Professional spatial processing",
            "Width  •  bass protection  •  depth",
            "stereo"
        )
    )

    root.addView(
        VocalRemoverLaunchCard.build(root) {
            launchVocalRemover()
        }
    )

    root.addView(
        LiveKaraokeLaunchCard.build(root) {
            launchLiveKaraoke()
        }
    )

    root.addView(sectionLabel("Signal chain"))

    root.addView(
        infoCard(
            "Processing order",
            "Graphic EQ  →  Parametric EQ  →  makeup gain  →  v10 Stereo Widener  →  final output path"
        )
    )

    root.addView(statusCard())

    scroll.addView(root)
    return scroll
}

    private fun soundCard(title: String, subtitle: String, detail: String, type: String): View {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
            isClickable = true
            isFocusable = true
            setOnClickListener { launchDsp(type) }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(16))
        }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedDrawable(SURFACE_2, 17)
        }
        preview.addView(TextView(this).apply {
            text = when (type) { "graphic" -> "GE"; "parametric" -> "PEQ"; else -> "ST" }
            setTextColor(TEXT)
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(60), dp(25)))
        preview.addView(previewBars(type), LinearLayout.LayoutParams(dp(60), dp(22)))
        row.addView(preview, LinearLayout.LayoutParams(dp(76), dp(76)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        col.addView(TextView(this).apply { text = title; setTextColor(TEXT); textSize = 17f; setTypeface(null, Typeface.BOLD) })
        col.addView(TextView(this).apply { text = subtitle; setTextColor(TEXT_SECONDARY); textSize = 13f; setPadding(0, dp(4), 0, 0) })
        col.addView(TextView(this).apply { text = detail; setTextColor(MUTED); textSize = 12f; setPadding(0, dp(6), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "›"; setTextColor(TEXT_SECONDARY); textSize = 30f; gravity = Gravity.CENTER })
        card.addView(row)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) } }
    }

    private fun previewBars(type: String): View {
        val bars = when (type) {
            "graphic" -> intArrayOf(8, 13, 20, 15, 10, 18, 23, 16)
            "parametric" -> intArrayOf(10, 11, 11, 21, 22, 13, 10, 18)
            else -> intArrayOf(10, 16, 20, 22, 18, 22, 16, 10)
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bars.forEach { height ->
            val bar = View(this).apply { setBackgroundColor(ACCENT) }
            row.addView(bar, LinearLayout.LayoutParams(dp(5), dp(height)).apply { leftMargin = dp(1); rightMargin = dp(1) })
        }
        return row
    }

    private fun buildSettings(): View {
        val scroll = androidx.core.widget.NestedScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }

        root.addView(settingsGroup("Playback", listOf(
            settingRow("Playback", "Background audio, queue, shuffle and repeat", android.R.drawable.ic_media_play) { showInfoDialog("Playback", "Background playback is provided by the Media3 MediaSessionService. Queue, shuffle and repeat are controlled from the player.") },
            settingRow("Session behavior", "Keep the music service alive outside the UI", android.R.drawable.ic_menu_recent_history) { showInfoDialog("Session behavior", "The playback service is separate from the Activity, so playback can continue while the screen is closed.") }
        )))

        root.addView(settingsGroup("Library", listOf(
            settingRow("Rescan music", "Refresh your local MediaStore library", android.R.drawable.ic_popup_sync) { scanLibrary() },
            settingRow("Music storage", "Android MediaStore / shared audio library", android.R.drawable.ic_menu_manage) { showInfoDialog("Music storage", "The player reads local audio through Android MediaStore so it works with scoped storage on modern Android.") }
        )))

        root.addView(settingsGroup("Audio & Output", listOf(
            settingRow("Output path", "PCM processing path for custom DSP", android.R.drawable.ic_lock_silent_mode_off) { showAudioInfo() },
            settingRow("Audio information", "Current track and DSP latency", android.R.drawable.ic_menu_info_details) { showAudioInfo() }
        )))

        root.addView(settingsGroup("DSP", listOf(
            settingRow("Sound controls", "Graphic EQ, Parametric EQ and Stereo Widener", android.R.drawable.ic_menu_manage) { bottomNav.selectedItemId = TAB_SOUND },
            settingRow("Reset DSP settings", "Return EQ and widener controls to their stored defaults", android.R.drawable.ic_menu_revert) { confirmResetDsp() }
        )))

        root.addView(settingsGroup("Appearance", listOf(
            settingRow("Theme", "Midnight Audiophile • dark premium theme", android.R.drawable.ic_menu_gallery) { showInfoDialog("Theme", "Midnight Audiophile is the current interface theme: near-black background, graphite surfaces, soft white text and a cool cyan-blue accent.") }
        )))

        root.addView(settingsGroup("Notifications", listOf(
            settingRow("Playback notification", "Media controls supplied by the playback service", android.R.drawable.ic_menu_info_details) { showInfoDialog("Notifications", "Playback notification and media-session controls are provided by Media3. Android 13+ may require notification permission.") }
        )))

        root.addView(settingsGroup("About", listOf(
            settingRow("Audiophile Player", "Version ${appVersionName()} • custom C++ DSP engine", android.R.drawable.ic_menu_info_details) { showInfoDialog("About", "Audiophile Player ${appVersionName()}\n\nCustom audio chain:\nGraphic EQ → Parametric EQ → v10 Stereo Widener.\n\nThe visual theme is Midnight Audiophile.") }
        )))

        scroll.addView(root)
        return scroll
    }

    private fun settingsGroup(title: String, rows: List<View>): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionLabel(title))
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { index, row ->
            inner.addView(row)
            if (index != rows.lastIndex) inner.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        card.addView(inner)
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        return col
    }

    private fun settingRow(title: String, summary: String, iconRes: Int, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(ACCENT)
            background = roundedDrawable(SURFACE_2, 13)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        text.addView(TextView(this).apply { this.text = title; setTextColor(TEXT); textSize = 15f; setTypeface(null, Typeface.BOLD) })
        text.addView(TextView(this).apply { this.text = summary; setTextColor(TEXT_SECONDARY); textSize = 12f; setPadding(0, dp(2), 0, 0) })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { this.text = "›"; setTextColor(MUTED); textSize = 28f })
        return row
    }

    private fun statusCard(): View {
        val peq = DspRuntime.peqEngine
        val widener = DspRuntime.widenerEngine
        val peqLatency = peq?.latencyFrames ?: 0
        val widenerLatency = widener?.latencyFrames ?: 0
        val text = "PEQ latency: $peqLatency frames\nWidener latency: $widenerLatency frames\nCustom DSP active through the PCM processing path when enabled."
        return infoCard("DSP status", text)
    }

    private fun infoCard(title: String, body: String): View {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        col.addView(TextView(this).apply { text = title; setTextColor(TEXT); textSize = 15f; setTypeface(null, Typeface.BOLD) })
        col.addView(TextView(this).apply { text = body; setTextColor(TEXT_SECONDARY); textSize = 13f; setPadding(0, dp(5), 0, 0) })
        card.addView(col)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) } }
    }

    private fun emptyStateCard(title: String, subtitle: String): View = infoCard(title, subtitle)

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(MUTED)
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(dp(2), dp(10), dp(2), dp(8))
    }

    private fun trackRow(item: TrackItem, index: Int): View {
        val card = MaterialCardView(this).apply {
            radius = dp(15).toFloat()
            setCardBackgroundColor(ColorStateList.valueOf(SURFACE))
            strokeWidth = 1
            strokeColor = BORDER
            isClickable = true
            setOnClickListener { playFromLibrary(index) }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(8), dp(8), dp(8)) }
        row.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(TEXT)
            background = roundedDrawable(SURFACE_2, 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(8), 0) }
        col.addView(TextView(this).apply { text = item.title; setTextColor(TEXT); textSize = 14f; setTypeface(null, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(TextView(this).apply { text = item.artist; setTextColor(TEXT_SECONDARY); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = formatTime(item.durationMs); setTextColor(MUTED); textSize = 11f })
        card.addView(row)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
    }

    private fun connectController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching {
                val connected = future.get()
                if (controllerFuture !== future) {
                    connected.release()
                    return@runCatching
                }
                controller = connected
                controller?.addListener(playerListener)
                refreshPlaybackUi()
                playPendingTrackIfReady()
                if (content.childCount == 0) selectTab(currentTab)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestMusicPermissionIfNeeded()
    }

    private fun requestMusicPermissionIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
        } else if (Build.VERSION.SDK_INT >= 23) {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            scanLibrary()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun scanLibrary() {
        if (!hasMusicPermission()) return
        statusTextSafe("Scanning your music collection…")
        MediaStoreRepository.scan(this) { tracks ->
            runOnUiThread {
                allTracks = tracks
                if (::libraryAdapter.isInitialized) filterLibrary()
                else statusTextSafe(if (tracks.isEmpty()) "No local music was found." else "${tracks.size} tracks")
                playPendingTrackIfReady()
                if (currentTab == TAB_HOME && !nowPlayingShown) {
                    // Rebuild Home so the recent list is immediately visible after a scan.
                    selectTab(TAB_HOME)
                }
            }
        }
    }

    private fun filterLibrary() {
        if (!::libraryAdapter.isInitialized) return
        val q = librarySearchQuery.lowercase()
        val filtered = if (q.isBlank()) {
            allTracks
        } else {
            allTracks.filter { track ->
                track.title.lowercase().contains(q) ||
                    track.artist.lowercase().contains(q)
            }
        }
        libraryAdapter.submit(filtered)
        if (::statusText.isInitialized) {
            statusText.text = when {
                q.isBlank() -> if (allTracks.isEmpty()) "No local music was found." else "${allTracks.size} tracks"
                filtered.isEmpty() -> "No songs match “$librarySearchQuery”."
                else -> "${filtered.size} matching songs"
            }
        }
    }

    private fun hasMusicPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    } else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun statusTextSafe(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun playFromLibrary(index: Int) {
        allTracks.getOrNull(index)?.let(::playFromLibrary)
    }

    /**
     * Play by stable MediaStore ID rather than by the currently filtered list position.
     * This prevents search results from starting an unrelated track.
     */
    private fun playFromLibrary(track: TrackItem) {
        val c = controller
        if (c == null) {
            pendingTrackId = track.id
            Toast.makeText(this, "Connecting to the player…", Toast.LENGTH_SHORT).show()
            return
        }
        startTrackPlayback(track, c)
    }

    private fun playPendingTrackIfReady() {
        val id = pendingTrackId ?: return
        val c = controller ?: return
        val track = allTracks.firstOrNull { it.id == id } ?: return
        pendingTrackId = null
        startTrackPlayback(track, c)
    }

    private fun startTrackPlayback(track: TrackItem, c: MediaController) {
        val index = allTracks.indexOfFirst { it.id == track.id }
        if (index < 0) {
            Toast.makeText(this, "That song is no longer in your library. Rescan and try again.", Toast.LENGTH_LONG).show()
            return
        }

        val items = allTracks.map { it.toMediaItem() }
        if (index !in items.indices) {
            Toast.makeText(this, "No playable songs were found. Rescan your library.", Toast.LENGTH_LONG).show()
            return
        }

        runCatching {
            c.setMediaItems(items, index, 0L)
            c.prepare()
            c.play()
            showNowPlaying()
        }.onFailure { error ->
            Log.e("MainActivity", "Unable to start selected track id=${track.id}", error)
            Toast.makeText(
                this,
                "Couldn't play this song: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
        refreshPlaybackUi()
    }

    private fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refreshPlaybackUi()
    }

    private fun repeatLabel(): String = when (controller?.repeatMode) {
        Player.REPEAT_MODE_ONE -> "Repeat 1"
        Player.REPEAT_MODE_ALL -> "Repeat all"
        else -> "Repeat"
    }

    private fun showQueueDialog() {
        val c = controller ?: return
        val labels = Array(c.mediaItemCount) { i -> c.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Untitled" }
        if (labels.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Queue")
            .setItems(labels) { _, which -> c.seekToDefaultPosition(which); c.play() }
            .show()
    }

    private fun showAudioInfo() {
        val c = controller
        val item = c?.currentMediaItem
        val peqLatency = DspRuntime.peqEngine?.latencyFrames ?: 0
        val widenerLatency = DspRuntime.widenerEngine?.latencyFrames ?: 0
        val body = buildString {
            append("Track: ${item?.mediaMetadata?.title ?: "Nothing playing"}\n")
            append("Artist: ${item?.mediaMetadata?.artist ?: "—"}\n\n")
            append("PEQ latency: $peqLatency frames\n")
            append("Stereo widener latency: $widenerLatency frames\n")
            append("Processing mode: PCM path\n")
            append("True-peak protection: enabled in the DSP stages where configured")
        }
        showInfoDialog("Audio information", body)
    }

    private fun confirmResetDsp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset DSP settings?")
            .setMessage("Return Graphic EQ, Parametric EQ and Stereo Widener controls to their stored default state.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                val store = EqSettingsStore(this)
                DspRuntime.peqEngine?.let { p ->
                    com.example.peq.GraphicalEqController(p).reset()
                    p.graphicEqEnabled = false
                    p.peqEnabled = true
                    p.bypass = false
                    p.limiterEnabled = true
                    p.limiterCeilingDb = -1f
                    p.limiterSafetyDb = 0.10f
                    p.limiterLookaheadMs = 2.0f
                    p.limiterReleaseMs = 180f
                    p.filterSmoothingMs = 8f
                    p.gainSmoothingMs = 5f
                    p.autoGainEnabled = false
                    p.autoGainAmount = 1f
                    p.linearPhaseEnabled = false
                    p.linearPhaseTaps = 513
                    p.inputGainDb = 0f
                    p.outputGainDb = 0f
                    p.applyPreset(PeqResetPreset)
                }
                store.saveGraphic(FloatArray(10), false)
                DspRuntime.widenerEngine?.let {
                    it.enabled = true
                    it.width = 1f
                    it.dryWet = 1f
                    it.bassMonoFrequencyHz = 80f
                    it.lowCrossoverHz = 180f
                    it.highCrossoverHz = 3200f
                    it.haasDelayMs = 0f
                    it.haasMix = 0f
                    it.outputGainDb = 0f
                    it.outputCeilingDb = -1f
                    it.autoLevel = true
                }
                saveDsp()
                showInfoDialog("DSP reset", "The major DSP stages have been returned to their neutral/default state.")
            }
            .show()
    }

    private fun saveDsp() {
        val p = DspRuntime.peqEngine ?: return
        val w = DspRuntime.widenerEngine ?: return
        val store = EqSettingsStore(this)
        store.savePeq(p)
        store.saveWidener(w)
    }

    private fun showInfoDialog(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshPlaybackUi() {
        val c = controller ?: return
        val item = c.currentMediaItem
        miniTitle.text = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
        miniArtist.text = item?.mediaMetadata?.artist?.toString() ?: "Tap to choose music"
        miniPlay.setImageResource(if (c.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        val d = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        miniProgress.progress = if (d > 0) ((c.currentPosition * 1000L / d).toInt().coerceIn(0, 1000)) else 0
        if (::libraryAdapter.isInitialized) {
            val newPlayingId = c.currentMediaItem?.mediaId?.toLongOrNull()
            if (libraryAdapter.playingId != newPlayingId) {
                libraryAdapter.playingId = newPlayingId
                libraryAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun scheduleProgress() {
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.post(object : Runnable {
            override fun run() {
                refreshPlaybackUi()
                uiHandler.postDelayed(this, 450)
            }
        })
    }

    private fun launchDsp(type: String) {
        startActivity(Intent(this, DspActivity::class.java).putExtra(DspActivity.EXTRA_SECTION, type))
    }

    private fun launchDspHub() {
        startActivity(Intent(this, DspActivity::class.java).putExtra(DspActivity.EXTRA_SECTION, "hub"))
    }
    private fun launchVocalRemover() {
        val intent = Intent(this, VocalRemoverActivity::class.java)

        controller?.currentMediaItem?.let { item ->
            item.localConfiguration?.uri?.toString()?.let { uri ->
                intent.putExtra(VocalRemoverActivity.EXTRA_TRACK_URI, uri)
            }

            item.mediaMetadata.title
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { title ->
                    intent.putExtra(VocalRemoverActivity.EXTRA_TRACK_TITLE, title)
                }
        }

        runCatching {
            startActivity(intent)
        }.onFailure { throwable ->
            Log.e("MainActivity", "Unable to launch AI Vocal Remover", throwable)
            Toast.makeText(
                this,
                "AI Vocal Remover could not open: " +
                    (throwable.message ?: throwable.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun launchLiveKaraoke() {
        val intent =
            Intent(this, LiveKaraokeActivity::class.java)

        controller?.currentMediaItem?.let { item ->
            item.localConfiguration?.uri?.toString()?.let { uri ->
                intent.putExtra(
                    LiveKaraokeActivity.EXTRA_TRACK_URI,
                    uri,
                )
            }

            item.mediaMetadata.title
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { title ->
                    intent.putExtra(
                        LiveKaraokeActivity.EXTRA_TRACK_TITLE,
                        title,
                    )
                }

            intent.putExtra(
                LiveKaraokeActivity.EXTRA_TRACK_POSITION_MS,
                controller?.currentPosition ?: 0L,
            )
        }

        runCatching {
            startActivity(intent)
            // LiveKaraokeActivity starts the foreground neural engine immediately.
            // Pause only after the Activity launch succeeds so a launch failure
            // never leaves the normal player silently paused.
            controller?.pause()
        }.onFailure { throwable ->
            Log.e("MainActivity", "Unable to launch Live Karaoke", throwable)
            Toast.makeText(
                this,
                "Live Karaoke could not open: " +
                    (throwable.message ?: throwable.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    private fun actionButton(icon: Int, description: String, listener: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(TEXT)
        background = null
        contentDescription = description
        setOnClickListener { listener() }
    }

    private fun textAction(text: String, listener: () -> Unit): MaterialButton = MaterialButton(this).apply {
        this.text = text
        setTextSize(12f)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { listener() }
    }

    private fun greeting(): String {
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun navColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(ACCENT, TEXT_SECONDARY)
    )

    /** The installed versionName, so the About screen can never drift from build.gradle.kts. */
    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    private fun roundedDrawable(color: Int, radiusDp: Int): android.graphics.drawable.Drawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val BG = 0xFF0B0D10.toInt()
        private const val SURFACE = 0xFF14171C.toInt()
        private const val SURFACE_2 = 0xFF1B1F25.toInt()
        private const val TEXT = 0xFFF5F7FA.toInt()
        private const val TEXT_SECONDARY = 0xFFA7AFBA.toInt()
        private const val MUTED = 0xFF737C88.toInt()
        private const val BORDER = 0xFF272C33.toInt()
        private const val ACCENT = 0xFF54B8FF.toInt()

        private const val TAB_HOME = 1000
        private const val TAB_LIBRARY = 1001
        private const val TAB_SOUND = 1003
        private const val TAB_SETTINGS = 1004

        private val PeqResetPreset = com.example.peq.PeqPresets.all.keys.firstOrNull() ?: "Flat"
    }
}

private class TrackAdapter(
    private val context: Context,
    private val onClick: (TrackItem) -> Unit,
) : androidx.recyclerview.widget.RecyclerView.Adapter<TrackAdapter.Holder>() {
    private var items: List<TrackItem> = emptyList()
    var playingId: Long? = null

    fun submit(newItems: List<TrackItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = MaterialCardView(context).apply {
            radius = 15f * context.resources.displayMetrics.density
            setCardBackgroundColor(0xFF14171C.toInt())
            strokeWidth = 1
            strokeColor = 0xFF272C33.toInt()
            useCompatPadding = true
            isClickable = true
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(8), dp(8), dp(8))
        }
        val art = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(0xFFF5F7FA.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1B1F25.toInt())
                cornerRadius = 12f * context.resources.displayMetrics.density
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        row.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(8), 0) }
        val title = TextView(context).apply { setTextColor(0xFFF5F7FA.toInt()); textSize = 15f; setTypeface(null, Typeface.BOLD); maxLines = 1 }
        val artist = TextView(context).apply { setTextColor(0xFFA7AFBA.toInt()); textSize = 12f; maxLines = 1 }
        col.addView(title)
        col.addView(artist)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val duration = TextView(context).apply { setTextColor(0xFF737C88.toInt()); textSize = 11f }
        row.addView(duration)
        root.addView(row)
        return Holder(root, title, artist, duration)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.artist.text = item.artist
        holder.duration.text = format(item.durationMs)
        holder.root.setOnClickListener { onClick(item) }
        holder.title.setTextColor(if (item.id == playingId) 0xFF54B8FF.toInt() else 0xFFF5F7FA.toInt())
    }

    override fun getItemCount(): Int = items.size

    private fun format(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    class Holder(
        val root: View,
        val title: TextView,
        val artist: TextView,
        val duration: TextView,
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)
}