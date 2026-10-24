package io.github.arturaragao.limpafotos

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class PhotoActivity : ComponentActivity() {
    private lateinit var session: ReviewSession
    private lateinit var store: ReviewSessionStore
    private lateinit var image: ZoomPhotoView
    private lateinit var caption: TextView
    private lateinit var mark: Button
    private lateinit var previous: Button
    private lateinit var next: Button
    private var animation: AnimatedImageDrawable? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ReviewSessionStore(this)
        session = try { store.read() } catch (_: Exception) { finish(); return }
        if (session.index !in session.photos.indices) { finish(); return }
        try {
            val currentId = session.photos[session.index].id
            val kept = KeptPhotoStore(this).read()
            val photos = session.photos.filter { it.id !in kept }
            if (photos.isEmpty()) { finish(); return }
            session = session.copy(photos = photos, selected = session.selected - kept,
                index = photos.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
        } catch (_: Exception) { finish(); return }
        window.setDecorFitsSystemWindows(false)
        hideBars()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ZoomPhotoView(this) { direction -> navigate(direction) }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0x99000000.toInt())
        }
        top.addView(button("Voltar") { finish() })
        caption = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 3 }
        top.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setBackgroundColor(0x99000000.toInt())
        }
        previous = button("‹") { navigate(-1) }.apply { contentDescription = "Foto anterior" }
        next = button("›") { navigate(1) }.apply { contentDescription = "Próxima foto" }
        mark = button("") { toggle() }
        controls.addView(previous, LinearLayout.LayoutParams(-2, -2))
        controls.addView(mark, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(next, LinearLayout.LayoutParams(-2, -2))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top.setPadding(safe.left, safe.top, safe.right, 0)
            controls.setPadding(safe.left, 0, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        showPhoto()
    }

    private fun hideBars() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }

    private fun navigate(direction: Int) {
        val index = adjacent(direction) ?: return
        session = session.copy(index = index)
        try { store.write(session) } catch (_: Exception) { /* Current session still works. */ }
        showPhoto()
    }

    private fun toggle() {
        val id = session.photos[session.index].id
        val keep = id in session.selected
        try { KeptPhotoStore(this).setKept(id, keep) }
        catch (_: Exception) { caption.text = "Não foi possível salvar sua decisão. Tente novamente."; return }
        session = session.copy(selected = if (keep) session.selected - id else session.selected + id)
        try { store.write(session) } catch (_: Exception) { /* Kept decision is already persisted. */ }
        updateControls()
    }

    private fun updateControls() {
        val photo = session.photos[session.index]
        caption.text = "${session.index + 1} de ${session.photos.size} • ${photo.name}"
        mark.text = if (photo.id in session.selected) "✓ Marcada • manter" else "Mantida • marcar para apagar"
        mark.contentDescription = if (photo.id in session.selected) "Desmarcar e manter esta foto" else "Marcar esta foto para apagar"
        previous.isEnabled = adjacent(-1) != null
        next.isEnabled = adjacent(1) != null
    }

    private fun adjacent(direction: Int): Int? = generateSequence(session.index + direction) { it + direction }
        .takeWhile { it in session.photos.indices }.firstOrNull { session.photos[it].id in session.selected }

    private fun showPhoto() {
        loadJob?.cancel()
        animation?.stop(); animation = null
        image.setImageDrawable(null)
        updateControls()
        val photo = session.photos[session.index]
        image.contentDescription = photo.name
        loadJob = lifecycleScope.launch {
            val drawable = withContext(Dispatchers.IO) {
                try {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, photoUri(photo.id))) { decoder, info, _ ->
                        val scale = (2560.0 / max(info.size.width, info.size.height).coerceAtLeast(1)).coerceAtMost(1.0)
                        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1))
                    }
                } catch (_: Exception) { null }
            }
            image.setImageDrawable(drawable)
            image.resetZoom()
            animation = drawable as? AnimatedImageDrawable
            animation?.start()
            if (drawable == null) caption.text = "Foto indisponível. Deslize para continuar."
        }
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    override fun onStop() { animation?.stop(); super.onStop() }
    override fun onStart() { super.onStart(); animation?.start() }
}

/** Native pinch/double-tap zoom and pan. Swipes navigate only at the fitted scale. */
private class ZoomPhotoView(context: Context, private val navigate: (Int) -> Unit) : ImageView(context) {
    private val transform = Matrix()
    private var zoom = 1f
    private var multiTouch = false
    private var startX = 0f
    private var startY = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            transform.postScale(target / zoom, target / zoom, detector.focusX, detector.focusY)
            zoom = target
            constrain()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1f) resetZoom() else {
                transform.postScale(3f, 3f, e.x, e.y); zoom = 3f; constrain()
            }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f && !scale.isInProgress) { transform.postTranslate(-distanceX, -distanceY); constrain() }
            return true
        }
    })
    init { scaleType = ScaleType.MATRIX; isClickable = true }
    fun resetZoom() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        zoom = 1f
        transform.setRectToRect(RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        imageMatrix = transform
    }
    private fun constrain() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        transform.mapRect(rect)
        fun offset(start: Float, end: Float, size: Float) = when {
            end - start <= size -> (size - start - end) / 2f
            start > 0 -> -start
            end < size -> size - end
            else -> 0f
        }
        transform.postTranslate(offset(rect.left, rect.right, width.toFloat()), offset(rect.top, rect.bottom, height.toFloat()))
        imageMatrix = transform
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); resetZoom() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouch = false; startX = event.x; startY = event.y
        }
        if (event.pointerCount > 1) multiTouch = true
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !multiTouch && zoom <= 1.01f) {
            val dx = event.x - startX
            if (abs(dx) > 64 * resources.displayMetrics.density && abs(dx) > abs(event.y - startY))
                navigate(if (dx < 0) 1 else -1)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
