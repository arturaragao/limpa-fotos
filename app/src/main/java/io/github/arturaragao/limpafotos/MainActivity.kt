package io.github.arturaragao.limpafotos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import kotlinx.coroutines.yield

class MainActivity : ComponentActivity() {
    private val model: CleanViewModel by viewModels()
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var accessNotice: TextView
    private lateinit var rescan: Button
    private lateinit var quantity: EditText
    private lateinit var reminder: Button
    private lateinit var trash: Button
    private lateinit var localTrash: Button
    private lateinit var googleActions: LinearLayout
    private lateinit var googleSkip: Button
    private lateinit var googleRetry: Button
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var adapter: PhotoAdapter
    private val chips = linkedMapOf<Category?, TextView>()
    private var lastPhotos: List<Photo>? = null
    private var lastCategory: Category? = null
    private var accessMode = -1
    private var trashPending = false
    private var checkingGoogle = false
    private var pendingAnalysis = false
    private val notificationPreferences by lazy { getSharedPreferences("notification_permission", MODE_PRIVATE) }

    private val reviewRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshReviewSelection()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        accessMode = MediaAccess.mode(this)
        // Ao abrir só mostra o que já está no cache; analisar fotos novas é sempre um toque do Artur.
        if (accessMode > 0) model.scan(analyzeMore = false) else {
            model.clearAccess()
            model.message("Autorize o acesso às fotos. Se o Android não perguntar novamente, use Configurações do app.")
        }
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Lembrete mensal ativado." else "Notificações desativadas pelo Android.", Toast.LENGTH_SHORT).show()
        updateReminderButton()
    }
    private val scanNotificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingAnalysis && MediaAccess.mode(this) > 0) model.scan()
        pendingAnalysis = false
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
        pendingAnalysis = savedInstanceState?.getBoolean("pendingAnalysis") ?: false
        buildScreen()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("trashPending", trashPending)
        outState.putBoolean("pendingAnalysis", pendingAnalysis)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val mode = MediaAccess.mode(this)
        if (mode == 0) {
            if (accessMode != mode) model.clearAccess()
        } else if ((accessMode >= 0 && accessMode != mode) || !model.state.value.scanned) {
            if (!trashPending) model.scan(analyzeMore = false)
        }
        accessMode = mode
        render(model.state.value)
        updateReminderButton()
        Updater.checkOnOpen(this, intent.getBooleanExtra(Updater.EXTRA, false))
        intent.removeExtra(Updater.EXTRA)
        if (model.state.value.googleFlow != null && !checkingGoogle) {
            checkingGoogle = true
            lifecycleScope.launch {
                try {
                    yield() // Let onResume finish before opening another activity.
                    model.checkGoogleReturn()
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) driveGooglePhotos()
                } finally { checkingGoogle = false; render(model.state.value) }
            }
        }
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
        header.addView(text("Limpar Fotos", 28f).apply { setTypeface(typeface, Typeface.BOLD) })
        header.addView(text("v${BuildConfig.VERSION_NAME}", 13f, R.color.secondary).apply { setPadding(dp(8), dp(8), 0, 0) },
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

        quantity = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(getColor(R.color.text))
            background = rounded(this@MainActivity, R.color.surface, 16)
            contentDescription = getString(R.string.analysis_limit)
            setText(model.state.value.batchLimit.toString())
        }
        rescan = action("") {
            if (model.state.value.scanning) model.stop() else {
                val value = quantity.text.toString().toIntOrNull()
                if (value == null || value !in 1..100_000) quantity.error = getString(R.string.invalid_limit)
                else { model.setBatchLimit(value); startAnalysis() }
            }
        }.apply {
            setTextColor(getColor(R.color.on_accent))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setTypeface(typeface, Typeface.BOLD)
        }
        val analyzeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        analyzeRow.addView(quantity, LinearLayout.LayoutParams(dp(88), dp(52)).apply { marginEnd = dp(8) })
        analyzeRow.addView(rescan, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(analyzeRow, LinearLayout.LayoutParams(-1, -2))
        reminder = action("Ativar lembrete mensal") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPreferences.edit().putBoolean("asked", true).apply()
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

        root.addView(text("Toque para ampliar • toque no ✓ para manter", 12f, R.color.secondary).apply { setPadding(0, 0, 0, dp(8)) })
        adapter = PhotoAdapter(this, lifecycleScope, model::toggle) { photo ->
            val state = model.state.value
            val visible = if (state.category == null) state.photos else state.photos.filter { state.category in it.categories }
            try {
                ReviewSessionStore(this).write(ReviewSession(visible.map { ReviewPhoto(it.meta.id, it.meta.name) },
                    state.selected, visible.indexOfFirst { it.meta.id == photo.meta.id }))
                reviewRequest.launch(Intent(this, PhotoActivity::class.java))
            } catch (_: Exception) { model.message("Não foi possível abrir a foto. Tente novamente.") }
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
        trash = action("Apagar no Google Fotos") { confirmGooglePhotos() }.apply {
            minHeight = dp(56)
            setTextColor(getColor(R.color.on_accent))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
        }
        root.addView(trash, LinearLayout.LayoutParams(-1, -2))
        localTrash = action("Lixeira só do celular") { moveToTrash() }
        root.addView(localTrash, LinearLayout.LayoutParams(-1, -2))
        googleActions = LinearLayout(this).apply { visibility = View.GONE }
        googleSkip = action("Pular") { model.skipGooglePhoto(); driveGooglePhotos() }
        googleRetry = action("Tentar de novo") { model.retryGooglePhoto(); driveGooglePhotos() }
        googleActions.addView(googleSkip, LinearLayout.LayoutParams(0, -2, 1f))
        googleActions.addView(googleRetry, LinearLayout.LayoutParams(0, -2, 1f))
        googleActions.addView(action("Encerrar") { model.finishGooglePhotos() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(googleActions, LinearLayout.LayoutParams(-1, -2))
        root.addView(text("Google Fotos: toque na lixeira lá e volte aqui para continuar.\nLixeira só do celular: a cópia na nuvem pode continuar lá.", 11f, R.color.secondary)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    }

    private fun startAnalysis() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPreferences.getBoolean("asked", false)) {
            notificationPreferences.edit().putBoolean("asked", true).apply()
            pendingAnalysis = true
            scanNotificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else model.scan()
    }

    private fun render(state: CleanState) {
        val mode = MediaAccess.mode(this)
        val flow = state.googleFlow
        val busy = trashPending || flow != null
        val counts = state.progress ?: ScanProgress(0, 0)
        val reclaimable = state.photos.filter(PhotoRules::initiallySelected).sumOf { it.meta.bytes }
        summary.text = getString(R.string.analysis_summary, counts.done, counts.total, PhotoRules.formatMb(reclaimable))
        status.text = if (state.scanning) state.progress?.takeIf { it.batchSize > 0 }
            ?.let { "Analisando ${it.batchDone} de ${it.batchSize} deste lote…" } ?: "Lendo a galeria…" else state.message
        if (flow != null) status.text = "Google Fotos • ${flow.progressText} • ${flow.deleted} apagadas\n${state.message}"
        accessNotice.visibility = if (mode < 2) View.VISIBLE else View.GONE
        accessNotice.text = getString(if (mode == 1) R.string.partial_access else R.string.no_access)
        progress.visibility = if (state.scanning || flow != null) View.VISIBLE else View.GONE
        progress.isIndeterminate = (state.progress?.batchSize ?: 0) == 0
        state.progress?.let { progress.max = it.batchSize.coerceAtLeast(1); progress.progress = it.batchDone }
        if (flow != null) { progress.isIndeterminate = false; progress.max = flow.ids.size; progress.progress = flow.index }
        val complete = state.scanned && counts.done == counts.total
        rescan.text = when {
            state.scanning -> getString(R.string.stop_analysis)
            complete -> getString(R.string.check_new_photos)
            counts.done == 0 -> getString(R.string.analyze_first)
            else -> getString(R.string.analyze_more)
        }
        rescan.isEnabled = mode > 0 && !busy
        quantity.visibility = if (state.scanning || complete) View.GONE else View.VISIBLE
        if (!quantity.hasFocus() && quantity.text.toString() != state.batchLimit.toString())
            quantity.setText(state.batchLimit.toString())
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
        adapter.enabled = !state.scanning && !busy
        val selected = state.photos.filter { it.meta.id in state.selected }
        trash.text = "Apagar ${selected.size} no Google Fotos (${PhotoRules.formatMb(selected.sumOf { it.meta.bytes })})"
        trash.isEnabled = selected.isNotEmpty() && !state.scanning && !busy && mode > 0
        trash.alpha = if (trash.isEnabled) 1f else 0.5f
        localTrash.isEnabled = trash.isEnabled
        localTrash.alpha = trash.alpha
        googleActions.visibility = if (flow != null) View.VISIBLE else View.GONE
        googleSkip.isEnabled = flow?.needsDecision == true && !checkingGoogle
        googleRetry.isEnabled = googleSkip.isEnabled
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
        permissions.isEnabled = !model.state.value.scanning && !trashPending && model.state.value.googleFlow == null
        content.addView(permissions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        content.addView(action("Mostrar de novo as que mantive") { model.showKeptAgain() }.apply {
            isEnabled = !model.state.value.scanning && !trashPending && model.state.value.googleFlow == null
        }, LinearLayout.LayoutParams(-1, -2))
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

    private fun confirmGooglePhotos() {
        val state = model.state.value
        if (state.scanning || trashPending || state.googleFlow != null || state.selected.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Apagar no Google Fotos")
            .setMessage("Abra cada foto, toque na lixeira do Google Fotos e confirme lá. Ao voltar, a próxima abre automaticamente. Para apagar também da nuvem, use a lixeira do Google Fotos, não ‘Excluir do dispositivo’.")
            .setPositiveButton("Começar") { _, _ -> model.beginGooglePhotos(); driveGooglePhotos() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun driveGooglePhotos() {
        val flow = model.state.value.googleFlow ?: return
        if (flow.finished) { model.finishGooglePhotos(); return }
        if (flow.awaitingReturn || flow.needsDecision) { render(model.state.value); return }
        val uri = photoUri(flow.currentId ?: return)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
            .setPackage("com.google.android.apps.photos")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Foto selecionada", uri) }
        model.googleOpened()
        try { startActivity(view) }
        catch (_: ActivityNotFoundException) {
            val remaining = model.cancelGoogleForLocal()
            model.message("Google Fotos indisponível. A confirmação abaixo move apenas para a lixeira do celular.")
            moveToTrash(remaining)
        } catch (_: Exception) { model.googleFailed() }
        render(model.state.value)
    }

    private fun moveToTrash(ids: List<Long>? = null) {
        val state = model.state.value
        if (state.scanning || trashPending || state.googleFlow != null) return
        val uris = (ids ?: state.photos.filter { it.meta.id in state.selected }.map { it.meta.id }).map(::photoUri).distinct()
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
