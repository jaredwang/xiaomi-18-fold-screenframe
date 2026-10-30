package com.fold18.frame

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.fold18.frame.core.DeviceProfile
import com.fold18.frame.core.Profiles
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The editor: every entry point (picker, share menu, tile, screenshot prompt)
 * lands here. One image is previewed whole at a time; options re-render it
 * live; nothing is written to the gallery until 保存到相册.
 *
 * Folding, unfolding and rotating swap the layout (options below the preview,
 * or beside it) without recreating the activity, so loaded images survive.
 */
class MainActivity : Activity() {
    private class Item(
        val source: Uri,
        val name: String,
        /** Downscaled copy: previews render from this, saving re-decodes the original. */
        val small: Bitmap,
        val thumb: Bitmap,
        val originalSize: String,
        val detectedProfile: DeviceProfile?,
        val detectedFlipped: Boolean,
    ) {
        var profile = detectedProfile
        var flipped = detectedFlipped

        /** Framed preview and the inputs it was rendered with. */
        var preview: Bitmap? = null
        var previewKey: Any? = null

        /** Bumped on every render request so stale results are dropped. */
        var generation = 0
    }

    private val items = mutableListOf<Item>()
    private var current = -1
    /** Bumped when an incoming share/open replaces the list; stale decodes are dropped. */
    private var loadBatch = 0
    private val currentItem get() = items.getOrNull(current)

    private val previewExec = Executors.newSingleThreadExecutor()
    private val workExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val renderCurrent = Runnable { currentItem?.let(::render) }

    private lateinit var settings: Settings
    private var busy = false
    private var optionTab = R.id.tab_finish
    private var watchSwitch: Switch? = null

    // Views of the inflated layout; rebound after every configuration change.
    private lateinit var preview: ImageView
    private lateinit var empty: View
    private lateinit var message: TextView
    private lateinit var toolbar: View
    private lateinit var counter: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var thumbsScroll: HorizontalScrollView
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        Notifications.createChannels(this)
        if (settings.watchScreenshots) ScreenshotJobService.schedule(this)
        // Leftovers from a share in an earlier session (the delayed cleanup died with the process).
        thread { Framer.clearShareCache(applicationContext) }
        bindLayout()
        handleIntent(intent)
    }

    /** Fold/unfold or rotate: pick up layout / layout-land without losing the images. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindLayout()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        previewExec.shutdownNow()
        workExec.shutdown()
        super.onDestroy()
    }

    private fun bindLayout() {
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        preview = findViewById(R.id.preview)
        empty = findViewById(R.id.empty)
        message = findViewById(R.id.message)
        toolbar = findViewById(R.id.toolbar)
        counter = findViewById(R.id.counter)
        thumbs = findViewById(R.id.thumbs)
        thumbsScroll = findViewById(R.id.thumbs_scroll)
        saveButton = findViewById(R.id.save)
        shareButton = findViewById(R.id.share)

        findViewById<View>(R.id.stage).background = checkerboard()
        findViewById<Button>(R.id.pick).setOnClickListener { pickImages() }
        findViewById<Button>(R.id.empty_pick).setOnClickListener { pickImages() }
        findViewById<Button>(R.id.settings).setOnClickListener { showEntries() }
        findViewById<Button>(R.id.rotate).setOnClickListener {
            currentItem?.let { it.flipped = !it.flipped; render(it) }
        }
        findViewById<Button>(R.id.screen).setOnClickListener {
            // Cycle inner ⇄ cover; also how an unrecognised size gets a frame.
            currentItem?.let {
                it.profile = if (it.profile === Profiles.INNER) Profiles.OUTER else Profiles.INNER
                it.flipped = false
                render(it)
            }
        }
        findViewById<Button>(R.id.remove).setOnClickListener { removeCurrent() }
        saveButton.setOnClickListener { export(toGallery = true) }
        shareButton.setOnClickListener { export(toGallery = false) }
        bindOptions()
        bindOptionTabs()
        rebuildThumbs()
        showCurrent()
    }

    /** Short screens only: show one option row at a time; the tab survives layout swaps. */
    private fun bindOptionTabs() {
        val tabs: RadioGroup = findViewById(R.id.option_tabs) ?: return
        val pages = mapOf(
            R.id.tab_finish to R.id.page_finish,
            R.id.tab_background to R.id.page_background,
            R.id.tab_padding to R.id.page_padding,
            R.id.tab_radius to R.id.page_radius,
        )
        fun show(tab: Int) {
            optionTab = tab
            for ((t, page) in pages) findViewById<View>(page).visibility = if (t == tab) View.VISIBLE else View.GONE
        }
        tabs.setOnCheckedChangeListener { _, id -> show(id) }
        tabs.check(optionTab)
        show(optionTab)
    }

    // --- loading ----------------------------------------------------------------

    /** Share menu, tile and screenshot prompt replace what is on screen. */
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            ACTION_OPEN -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        items.forEach { it.generation++ }
        items.clear()
        current = -1
        loadBatch++
        rebuildThumbs()
        showCurrent()
        load(uris)
    }

    private fun pickImages() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            .setType("image/*")
            .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> if (resultCode == RESULT_OK && data != null) {
                load(data.clipData?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data))
            }
            REQ_TRASH -> onTrashResult(resultCode == RESULT_OK)
        }
    }

    private fun load(sources: List<Uri>) {
        val batch = loadBatch
        workExec.execute {
            for (source in sources) {
                try {
                    val full = Framer.decode(this, source)
                    val (profile, flipped) = Framer.detect(full)
                    val size = "${full.width}×${full.height}"
                    val small = Framer.downscale(full, PREVIEW_LONG_SIDE)
                    if (small !== full) full.recycle()
                    val thumb = Framer.downscale(small, THUMB_LONG_SIDE)
                    val item = Item(source, Framer.baseName(this, source), small, thumb, size, profile, flipped)
                    // Drop images of a batch that a newer share/open has replaced meanwhile.
                    main.post { if (batch == loadBatch) add(item) }
                } catch (e: Exception) {
                    main.post { toast(getString(R.string.open_failed, e.message)) }
                }
            }
        }
    }

    private fun add(item: Item) {
        items += item
        rebuildThumbs()
        // The first image of a batch takes the stage; later ones queue in the strip.
        if (current < 0) select(items.lastIndex) else refreshUi()
    }

    private fun select(index: Int) {
        if (index !in items.indices) return
        // Only the shown image keeps a full preview in memory.
        currentItem?.takeIf { items.indexOf(it) != index }?.apply { preview = null; previewKey = null }
        current = index
        showCurrent()
        thumbs.getChildAt(index)?.let { thumb -> thumbsScroll.post { thumbsScroll.smoothScrollTo(thumb.left - thumb.width, 0) } }
    }

    private fun removeCurrent() {
        val item = currentItem ?: return
        item.generation++
        items.removeAt(current)
        current = if (items.isEmpty()) -1 else current.coerceAtMost(items.lastIndex)
        rebuildThumbs()
        showCurrent()
    }

    // --- stage & preview ----------------------------------------------------------

    private fun showCurrent() {
        val item = currentItem
        empty.visibility = if (item == null) View.VISIBLE else View.GONE
        toolbar.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
        for (i in 0 until thumbs.childCount) thumbs.getChildAt(i).isSelected = i == current
        if (item == null) {
            preview.setImageDrawable(null)
            message.visibility = View.GONE
        } else {
            render(item)
        }
        refreshUi()
    }

    private fun render(item: Item) {
        val generation = ++item.generation
        val profile = item.profile
        findViewById<Button>(R.id.screen).text = profile?.let(::screenName) ?: getString(R.string.choose_screen)
        findViewById<Button>(R.id.rotate).isEnabled = profile != null
        if (profile == null) {
            preview.setImageDrawable(null)
            message.text = getString(R.string.unrecognized, item.originalSize)
            message.visibility = View.VISIBLE
            refreshUi()
            return
        }
        message.visibility = View.GONE
        val options = settings.frameOptions()
        val flipped = item.flipped
        val key = Triple(options, profile, flipped)
        item.preview?.takeIf { item.previewKey == key }?.let {
            preview.setImageBitmap(it)
            refreshUi()
            return
        }
        previewExec.execute {
            if (generation != item.generation) return@execute
            val bmp = Framer.render(item.small, profile, flipped, options)
            main.post {
                if (generation != item.generation) return@post
                item.preview = bmp
                item.previewKey = key
                if (item === currentItem) preview.setImageBitmap(bmp)
            }
        }
        refreshUi()
    }

    /** Coalesce rapid option changes (slider drags) into one re-render. */
    private fun optionsChanged() {
        main.removeCallbacks(renderCurrent)
        main.postDelayed(renderCurrent, 40)
    }

    private fun rebuildThumbs() {
        thumbs.removeAllViews()
        items.forEachIndexed { index, item ->
            thumbs.addView(
                ImageView(this).apply {
                    setImageBitmap(item.thumb)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val pad = (3 * dp).roundToInt()
                    setPadding(pad, pad, pad, pad)
                    background = selectableOutline()
                    isSelected = index == current
                    contentDescription = getString(R.string.thumb_description, index + 1)
                    setOnClickListener { select(index) }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
                        .apply { marginEnd = (6 * dp).roundToInt() }
                },
            )
        }
        thumbsScroll.visibility = if (items.size > 1) View.VISIBLE else View.GONE
    }

    // --- export -----------------------------------------------------------------

    private fun export(toGallery: Boolean) {
        val ready = items.filter { it.profile != null }
        if (ready.isEmpty() || busy) return
        busy = true
        refreshUi()
        val options = settings.frameOptions()
        val jobs = ready.map { Triple(it, it.profile!!, it.flipped) }
        workExec.execute {
            val outputs = mutableListOf<Uri>()
            val done = mutableListOf<Item>()
            var error: String? = null
            if (!toGallery) Framer.clearShareCache(this)
            jobs.forEachIndexed { i, (item, profile, flipped) ->
                main.post { (if (toGallery) saveButton else shareButton).text = getString(R.string.processing, i + 1, jobs.size) }
                try {
                    val full = Framer.decode(this, item.source)
                    val framed = Framer.render(full, profile, flipped, options)
                    full.recycle()
                    outputs += if (toGallery) Framer.saveToGallery(this, framed, item.name) else Framer.saveForShare(this, framed, item.name)
                    framed.recycle()
                    done += item
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
            }
            main.post {
                busy = false
                refreshUi()
                error?.let { toast(getString(R.string.error, it)) }
                if (outputs.isEmpty()) return@post
                if (toGallery) {
                    toast(resources.getQuantityString(R.plurals.saved_n, outputs.size, outputs.size, getString(R.string.album_dir)))
                    if (settings.trashAfterSave) trashOriginals(done)
                } else {
                    share(outputs, done)
                }
            }
        }
    }

    // --- after a share: clean up -------------------------------------------------

    /** Images of the share sheet currently open; null when no share is in flight. */
    private var sharing: List<Item>? = null
    private var leftDuringShare = false
    private var trashing: List<Item> = emptyList()

    override fun onStop() {
        super.onStop()
        // The share sheet only covers us; a picked target app sends us to the background.
        if (sharing != null) leftDuringShare = true
    }

    override fun onResume() {
        super.onResume()
        val shared = sharing ?: return
        sharing = null
        if (ShareChosenReceiver.chosen || leftDuringShare) afterShared(shared)
    }

    private fun afterShared(shared: List<Item>) {
        // Give the receiving app time to read the file before the cache goes.
        ShareCacheJobService.schedule(this, SHARE_CACHE_TTL_MS)
        if (settings.trashAfterShare) trashOriginals(shared)
    }

    /** Ask the system to move the original screenshots behind [done] to the trash. */
    private fun trashOriginals(done: List<Item>) {
        if (!hasMediaAccess()) {
            toast(getString(R.string.need_photos_to_trash))
            return
        }
        thread(name = "find-originals") {
            val found = done.mapNotNull { item -> runCatching { Framer.galleryUriFor(this, item.source) }.getOrNull()?.let { item to it } }
            main.post {
                if (found.isEmpty()) {
                    toast(getString(R.string.originals_not_found))
                    return@post
                }
                trashing = found.map { it.first }
                val request = MediaStore.createTrashRequest(contentResolver, found.map { it.second }, true)
                startIntentSenderForResult(request.intentSender, REQ_TRASH, null, 0, 0, 0)
            }
        }
    }

    private fun onTrashResult(ok: Boolean) {
        val gone = trashing
        trashing = emptyList()
        if (!ok) {
            toast(getString(R.string.trash_cancelled))
            return
        }
        toast(resources.getQuantityString(R.plurals.trashed_n, gone.size, gone.size))
        // Their sources no longer open, so take them off the editor too.
        val shown = currentItem
        gone.forEach { it.generation++ }
        items.removeAll(gone.toSet())
        current = shown?.let { items.indexOf(it) }?.takeIf { it >= 0 } ?: if (items.isEmpty()) -1 else 0
        rebuildThumbs()
        showCurrent()
    }

    private fun share(uris: List<Uri>, shared: List<Item>) {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = "image/png"
        // Grants for SEND_MULTIPLE travel via ClipData.
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ShareChosenReceiver.chosen = false
        leftDuringShare = false
        sharing = shared
        startActivity(Intent.createChooser(send, getString(R.string.share_title), ShareChosenReceiver.callback(this).intentSender))
    }

    private fun refreshUi() {
        val ready = items.count { it.profile != null }
        counter.text = if (items.size > 1) "${current + 1} / ${items.size}" else ""
        saveButton.isEnabled = ready > 0 && !busy
        shareButton.isEnabled = ready > 0 && !busy
        if (!busy) {
            saveButton.text = if (ready > 1) resources.getQuantityString(R.plurals.save_n, ready, ready) else getString(R.string.save)
            shareButton.text = getString(R.string.share)
        }
    }

    // --- options ----------------------------------------------------------------

    private val backgrounds = mapOf(R.id.bg_light to "light", R.id.bg_dark to "dark", R.id.bg_transparent to "transparent")
    private val paddings = mapOf(R.id.pad_none to 0f, R.id.pad_tight to 0.06f, R.id.pad_normal to 0.12f, R.id.pad_loose to 0.18f)

    /** Wire the option controls of the current layout; [syncOptions] puts the stored values in them. */
    private fun bindOptions() {
        syncOptions()
        findViewById<RadioGroup>(R.id.finish).setOnCheckedChangeListener { _, id ->
            settings.finish = Finish.entries[id - FINISH_ID_BASE]
            optionsChanged()
        }
        findViewById<RadioGroup>(R.id.background).setOnCheckedChangeListener { _, id ->
            settings.background = backgrounds.getValue(id)
            optionsChanged()
        }
        findViewById<RadioGroup>(R.id.padding).setOnCheckedChangeListener { _, id ->
            settings.padding = paddings.getValue(id)
            optionsChanged()
        }
        findViewById<Switch>(R.id.shadow).setOnCheckedChangeListener { _, on ->
            settings.shadow = on
            optionsChanged()
        }
        // 0..16 → 50%..130% in 5% steps.
        findViewById<SeekBar>(R.id.radius).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.radiusScale = 0.5f + progress * 0.05f
                showRadius()
                optionsChanged()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        findViewById<Button>(R.id.reset).setOnClickListener { resetAll() }
    }

    private fun syncOptions() {
        val finishes = findViewById<RadioGroup>(R.id.finish)
        if (finishes.childCount == 0) for (finish in Finish.entries) finishes.addView(finishChip(finish))
        finishes.check(FINISH_ID_BASE + settings.finish.ordinal)
        findViewById<RadioGroup>(R.id.background).check(backgrounds.entries.first { it.value == settings.background }.key)
        findViewById<RadioGroup>(R.id.padding).check(paddings.minBy { abs(it.value - settings.padding) }.key)
        findViewById<Switch>(R.id.shadow).isChecked = settings.shadow
        findViewById<SeekBar>(R.id.radius).progress = ((settings.radiusScale - 0.5f) / 0.05f).roundToInt()
        showRadius()
    }

    private fun showRadius() {
        findViewById<TextView>(R.id.radius_value).text = "${(settings.radiusScale * 100).roundToInt()}%"
    }

    /** 重置: default look, and every image back to the screen/pose detected on load. */
    private fun resetAll() {
        settings.resetAppearance()
        syncOptions()
        for (item in items) {
            item.profile = item.detectedProfile
            item.flipped = item.detectedFlipped
        }
        optionsChanged()
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
    }

    // --- entry points (settings dialog) -----------------------------------------

    private fun showEntries() {
        val view = layoutInflater.inflate(R.layout.dialog_entries, null)
        val watch = view.findViewById<Switch>(R.id.watch)
        watchSwitch = watch
        watch.isChecked = settings.watchScreenshots && hasMediaAccess()
        watch.setOnCheckedChangeListener { _, on ->
            if (!on) {
                ScreenshotJobService.disable(this)
            } else if (hasMediaAccess() && hasNotifications()) {
                thread { ScreenshotJobService.enable(this) }
            } else {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS), REQ_WATCH)
            }
        }
        view.findViewById<Button>(R.id.add_tile).setOnClickListener {
            if (hasMediaAccess()) requestTile() else requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), REQ_TILE)
        }
        view.findViewById<Switch>(R.id.trash_after_share).apply {
            isChecked = settings.trashAfterShare
            setOnCheckedChangeListener { _, on -> settings.trashAfterShare = on }
        }
        view.findViewById<Switch>(R.id.trash_after_save).apply {
            isChecked = settings.trashAfterSave
            setOnCheckedChangeListener { _, on -> settings.trashAfterSave = on }
        }
        view.findViewById<Button>(R.id.manage_media).apply {
            // With "媒体管理" access the system moves files to the trash without asking each time.
            setText(if (MediaStore.canManageMedia(this@MainActivity)) R.string.manage_media_on else R.string.manage_media_off)
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.entries_title)
            .setView(view)
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener { watchSwitch = null }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasMediaAccess()) {
            toast(getString(R.string.need_photos_to_read))
            if (requestCode == REQ_WATCH) watchSwitch?.isChecked = false
            return
        }
        when (requestCode) {
            REQ_WATCH -> thread { ScreenshotJobService.enable(this) }
            REQ_TILE -> requestTile()
        }
    }

    private fun requestTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, FrameTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { code ->
            toast(
                when (code) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> getString(R.string.tile_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> getString(R.string.tile_exists)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> getString(R.string.tile_not_added)
                    else -> getString(R.string.tile_unsupported)
                },
            )
        }
    }

    private fun screenName(profile: DeviceProfile) =
        getString(if (profile === Profiles.OUTER) R.string.screen_outer else R.string.screen_inner)

    private fun hasMediaAccess() =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications() =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- drawables ---------------------------------------------------------------

    private val dp get() = resources.displayMetrics.density

    /** Rounded outline in the accent colour when selected/checked, invisible otherwise. */
    private fun selectableOutline(radiusDp: Float = 8f): StateListDrawable {
        fun outline(color: Int) = GradientDrawable().apply {
            cornerRadius = radiusDp * dp
            setStroke((1.5f * dp).roundToInt(), color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), outline(getColor(R.color.accent)))
            addState(intArrayOf(android.R.attr.state_checked), outline(getColor(R.color.accent)))
            addState(intArrayOf(), outline(0x00000000))
        }
    }

    /** Colour chip: swatch dot + name, outlined in the accent colour when chosen. */
    private fun finishChip(finish: Finish): RadioButton {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(finish.swatch)
            setStroke((1 * dp).roundToInt(), 0x33000000)
            setSize((14 * dp).roundToInt(), (14 * dp).roundToInt())
        }
        return RadioButton(this).apply {
            id = FINISH_ID_BASE + finish.ordinal
            setText(finish.label)
            textSize = 14f
            setTextColor(getColor(R.color.text))
            buttonDrawable = null
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            compoundDrawablePadding = (6 * dp).roundToInt()
            setPadding((10 * dp).roundToInt(), (4 * dp).roundToInt(), (12 * dp).roundToInt(), (4 * dp).roundToInt())
            background = selectableOutline(100f)
            layoutParams = RadioGroup.LayoutParams(RadioGroup.LayoutParams.WRAP_CONTENT, RadioGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (2 * dp).roundToInt()
            }
        }
    }

    /** Checkerboard behind the preview so a transparent background reads as transparent. */
    private fun checkerboard(): BitmapDrawable {
        val cell = (8 * dp).roundToInt()
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            drawColor(getColor(R.color.checker_a))
            val p = Paint().apply { color = getColor(R.color.checker_b) }
            drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
            drawRect(cell.toFloat(), cell.toFloat(), 2f * cell, 2f * cell, p)
        }
        return BitmapDrawable(resources, tile).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_WATCH = 2
        private const val REQ_TILE = 3
        private const val REQ_TRASH = 4
        private const val SHARE_CACHE_TTL_MS = 60_000L
        /** Big enough to stay sharp on the unfolded inner screen. */
        private const val PREVIEW_LONG_SIDE = 1600
        private const val THUMB_LONG_SIDE = 240
        private const val ACTION_OPEN = "com.fold18.frame.OPEN"
        private const val FINISH_ID_BASE = 0x1800

        /** Open the editor on one image (tile, screenshot prompt). */
        fun openIntent(context: Context, source: Uri): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).setData(source)
    }
}
