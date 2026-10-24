package io.github.arturaragao.limpafotos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: CleanViewModel by viewModels()
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var accessNotice: TextView
    private lateinit var rescan: Button
    private lateinit var reminder: Button
    private lateinit var trash: Button
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var adapter: PhotoAdapter
    private val chips = linkedMapOf<Category?, TextView>()
    private var lastPhotos: List<Photo>? = null
    private var lastCategory: Category? = null
    private var accessMode = -1
    private var trashPending = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        accessMode = MediaAccess.mode(this)
        if (accessMode > 0) model.scan(analyzeMore = !model.state.value.scanned) else {
            model.clearAccess()
            model.message("Autorize o acesso às fotos. Se o Android não perguntar novamente, use Configurações do app.")
        }
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Lembrete mensal ativado." else "Notificações desativadas pelo Android.", Toast.LENGTH_SHORT).show()
        updateReminderButton()
    }
    private val trashRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        trashPending = false
        if (result.resultCode == Activity.RESULT_OK) {
            // A fresh MediaStore query, not an assumed deletion, is authoritative.
            model.scan(analyzeMore = false)
        } else model.message("Movimentação cancelada. Suas fotos continuam na galeria.")
        render(model.state.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trashPending = savedInstanceState?.getBoolean("trashPending") ?: false
        buildScreen()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("trashPending", trashPending)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val mode = MediaAccess.mode(this)
        if (mode == 0) {
            if (accessMode != mode) model.clearAccess()
        } else if ((accessMode >= 0 && accessMode != mode) || !model.state.value.scanned) {
            if (!trashPending) model.scan(analyzeMore = !model.state.value.scanned)
        }
        accessMode = mode
        render(model.state.value)
        updateReminderButton()
        Updater.checkOnOpen(this, intent.getBooleanExtra(Updater.EXTRA, false))
        intent.removeExtra(Updater.EXTRA)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        // Android 15 enforces edge-to-edge for target 35.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Limpa Fotos", 28f).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text))
            background = rounded(this@MainActivity, R.color.surface, 16)
            contentDescription = getString(R.string.settings)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        summary = text("", 17f).apply {
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(this@MainActivity, R.color.surface, 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        accessNotice = text("", 13f, R.color.accent).apply {
            setPadding(0, dp(8), 0, dp(8))
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setOnClickListener { showSettings() }
        }
        root.addView(accessNotice)
        status = text("", 13f, R.color.secondary).apply { setPadding(0, dp(12), 0, dp(8)) }
        root.addView(status)

        rescan = action("") { model.scan() }.apply {
            setTextColor(getColor(R.color.on_accent))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(rescan, LinearLayout.LayoutParams(-1, dp(52)))
        reminder = action("Ativar lembrete mensal") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        root.addView(reminder, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(8) })

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        for (category in listOf<Category?>(null) + Category.entries) {
            val chip = text("", 13f).apply {
                gravity = Gravity.CENTER
                minHeight = dp(56)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { model.category(category) }
            }
            chips[category] = chip
            categories.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        scroll.addView(categories)
        root.addView(scroll)

        root.addView(text("Toque para marcar • segure para ver a foto", 12f, R.color.secondary).apply { setPadding(0, 0, 0, dp(8)) })
        adapter = PhotoAdapter(this, lifecycleScope, model::toggle) { photo ->
            startActivity(Intent(this, PhotoActivity::class.java).putExtra("photoId", photo.meta.id).putExtra("name", photo.meta.name))
        }
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(108)
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            adapter = this@MainActivity.adapter
        }
        empty = text("", 16f).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        root.addView(empty, LinearLayout.LayoutParams(-1, -2))
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        trash = action("Mover 0 para a lixeira (0,0 MB)") { moveToTrash() }.apply {
            minHeight = dp(56)
            setTextColor(getColor(R.color.on_accent))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
        }
        root.addView(trash, LinearLayout.LayoutParams(-1, -2))
        root.addView(text("Lixeira do Android: restaure em até 30 dias.\nA seleção vale para todas as categorias, sem contar fotos duas vezes.", 11f, R.color.secondary)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    }

    private fun render(state: CleanState) {
        val mode = MediaAccess.mode(this)
        val counts = state.progress ?: ScanProgress(0, 0)
        val reclaimable = state.photos.filter(PhotoRules::initiallySelected).sumOf { it.meta.bytes }
        summary.text = getString(R.string.analysis_summary, counts.done, counts.total, PhotoRules.formatMb(reclaimable))
        status.text = if (state.scanning) "Analisando este lote…" else state.message
        accessNotice.visibility = if (mode < 2) View.VISIBLE else View.GONE
        accessNotice.text = getString(if (mode == 1) R.string.partial_access else R.string.no_access)
        progress.visibility = if (state.scanning) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.progress == null
        state.progress?.let { progress.max = it.total.coerceAtLeast(1); progress.progress = it.done }
        val complete = state.scanned && counts.done == counts.total
        rescan.text = if (complete) getString(R.string.check_new_photos) else getString(R.string.analyze_more, state.batchLimit)
        rescan.isEnabled = mode > 0 && !state.scanning && !trashPending
        rescan.alpha = if (rescan.isEnabled) 1f else 0.5f
        if (state.photos !== lastPhotos || state.category != lastCategory) {
            for ((category, chip) in chips) {
                val photos = if (category == null) state.photos else state.photos.filter { category in it.categories }
                chip.text = "${category?.title ?: "Todas"}\n${photos.size} • ${PhotoRules.formatMb(photos.sumOf { it.meta.bytes })}"
                chip.background = rounded(this, if (state.category == category) R.color.accent else R.color.surface, 28)
                chip.setTextColor(getColor(if (state.category == category) R.color.on_accent else R.color.text))
                chip.isSelected = state.category == category
            }
            val visible = if (state.category == null) state.photos else state.photos.filter { state.category in it.categories }
            adapter.submit(visible, state.selected)
            lastPhotos = state.photos
            lastCategory = state.category
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        } else adapter.selection(state.selected)
        empty.text = if (state.scanning) "Analisando este lote de fotos." else if (state.scanned)
            "Nenhuma sugestão nesta categoria entre as fotos analisadas." else "Sua galeria será analisada aqui."
        adapter.enabled = !state.scanning && !trashPending
        val selected = state.photos.filter { it.meta.id in state.selected }
        trash.text = "Mover ${selected.size} para a lixeira (${PhotoRules.formatMb(selected.sumOf { it.meta.bytes })})"
        trash.isEnabled = selected.isNotEmpty() && !state.scanning && !trashPending && mode > 0
        trash.alpha = if (trash.isEnabled) 1f else 0.5f
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(16))
        }
        val label = text(getString(R.string.analysis_limit), 16f)
        content.addView(label)
        val limit = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(model.state.value.batchLimit.toString())
            setTextColor(getColor(R.color.text))
            selectAll()
        }
        label.labelFor = limit.id
        content.addView(limit, LinearLayout.LayoutParams(-1, dp(56)))
        content.addView(text(getString(R.string.batch_explanation), 13f, R.color.secondary))
        val permissions = action(getString(R.string.access_permissions)) { }
        permissions.isEnabled = !model.state.value.scanning && !trashPending
        content.addView(permissions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        content.addView(text(getString(R.string.app_version, version), 13f, R.color.secondary).apply {
            setPadding(0, dp(16), 0, 0)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun saveLimit(): Boolean {
            val value = limit.text.toString().toIntOrNull()
            if (value == null || value <= 0) {
                limit.error = getString(R.string.invalid_limit)
                return false
            }
            model.setBatchLimit(value)
            return true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveLimit()) dialog.dismiss()
            }
        }
        permissions.setOnClickListener {
            if (saveLimit()) {
                dialog.dismiss()
                permissionRequest.launch(MediaAccess.permissions())
            }
        }
        dialog.show()
    }

    private fun moveToTrash() {
        val state = model.state.value
        if (state.scanning || trashPending) return
        val uris = state.photos.filter { it.meta.id in state.selected }.map { photoUri(it.meta.id) }.distinct()
        if (uris.isEmpty()) return
        try {
            // The only write to the gallery. No delete(), resolver.update() or automatic approval.
            val request = MediaStore.createTrashRequest(contentResolver, uris, true)
            trashPending = true
            trashRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
            render(state)
        } catch (_: Exception) {
            trashPending = false
            model.message("Não foi possível abrir a confirmação. Tente varrer novamente ou selecionar menos fotos.")
        }
    }

    private fun updateReminderButton() {
        if (!::reminder.isInitialized) return
        reminder.text = if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            "Ativar lembrete mensal" else "Configurar notificações"
    }

    private fun text(value: String, size: Float, color: Int = R.color.text) = TextView(this).apply {
        text = value; textSize = size; setTextColor(getColor(color))
    }
    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 13f; minHeight = dp(48)
        setTextColor(getColor(R.color.text)); background = rounded(this@MainActivity, R.color.surface, 18)
        setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun rounded(context: android.content.Context, color: Int, radius: Int) = GradientDrawable().apply {
    setColor(context.getColor(color))
    cornerRadius = radius * context.resources.displayMetrics.density
    setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), context.getColor(R.color.outline))
}
