package io.github.arturaragao.limpafotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native recycled grid; no image-loading library or image/network upload. */
class PhotoAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val toggle: (Long) -> Unit,
    private val preview: (Photo) -> Unit
) : BaseAdapter() {
    private var photos: List<Photo> = emptyList()
    private var selected: Set<Long> = emptySet()
    var enabled = true
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun submit(value: List<Photo>, selection: Set<Long>) {
        photos = value; selected = selection; notifyDataSetChanged()
    }
    fun selection(value: Set<Long>) {
        if (selected == value) return
        selected = value; notifyDataSetChanged()
    }
    override fun getCount() = photos.size
    override fun getItem(position: Int) = photos[position]
    override fun getItemId(position: Int) = photos[position].meta.id
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder = (convertView?.tag as? Holder) ?: Holder()
        holder.bind(photos[position])
        return holder.root
    }

    private inner class Holder {
        val root = FrameLayout(context).apply {
            layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(144))
            background = rounded(context, R.color.surface, 18)
            clipToOutline = true
            isFocusable = true
        }
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val check = TextView(context).apply {
            gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE)
        }
        private val caption = TextView(context).apply {
            textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(0xC0000000.toInt())
            setPadding(dp(5), dp(4), dp(5), dp(4)); maxLines = 3
        }
        private var job: Job? = null
        private var signal: CancellationSignal? = null
        private var bound: String? = null
        init {
            root.tag = this
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            root.addView(check, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END))
            root.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { job?.cancel(); signal?.cancel(); bound = null }
            })
        }

        fun bind(photo: Photo) {
            val checked = photo.meta.id in selected
            check.text = if (checked) "✓" else "○"
            check.setBackgroundColor(if (checked) 0xD0006B5E.toInt() else 0x99000000.toInt())
            caption.text = buildString {
                if (photo.meta.favorite) append("★ Favorita • ")
                if (photo.keeper) append("Melhor do grupo • ")
                append(PhotoRules.formatMb(photo.meta.bytes))
            }
            root.contentDescription = "${photo.meta.name}, ${caption.text}, ${if (checked) "marcada" else "desmarcada"}"
            root.setOnClickListener { if (enabled) toggle(photo.meta.id) }
            root.setOnLongClickListener { preview(photo); true }
            val key = "${photo.meta.id}:${photo.meta.modified}"
            if (bound == key) return
            bound = key
            job?.cancel(); signal?.cancel()
            image.setImageDrawable(null)
            val cached = cache.get(key)
            if (cached != null) { image.setImageBitmap(cached); return }
            val cancel = CancellationSignal()
            signal = cancel
            job = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try { context.contentResolver.loadThumbnail(photoUri(photo.meta.id), Size(320, 320), cancel) }
                    catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    cache.put(key, bitmap)
                    if (bound == key) image.setImageBitmap(bitmap)
                }
            }
        }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
