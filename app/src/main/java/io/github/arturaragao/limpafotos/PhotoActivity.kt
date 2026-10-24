package io.github.arturaragao.limpafotos

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class PhotoActivity : ComponentActivity() {
    private var animation: AnimatedImageDrawable? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.background))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = intent.getStringExtra("name") }
        val caption = TextView(this).apply {
            text = intent.getStringExtra("name"); setTextColor(getColor(R.color.text)); gravity = Gravity.CENTER
        }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(caption)
        root.addView(Button(this).apply { text = "Voltar à revisão"; isAllCaps = false; setOnClickListener { finish() } })
        setContentView(root)
        root.requestApplyInsets()
        lifecycleScope.launch {
            val drawable = withContext(Dispatchers.IO) {
                try {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, photoUri(intent.getLongExtra("photoId", -1)))) { decoder, info, _ ->
                        val scale = (2048.0 / max(info.size.width, info.size.height).coerceAtLeast(1)).coerceAtMost(1.0)
                        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                    }
                } catch (_: Exception) { null }
            }
            image.setImageDrawable(drawable)
            animation = drawable as? AnimatedImageDrawable
            animation?.start()
            if (drawable == null) caption.text = "Foto indisponível. Volte e varra novamente."
        }
    }
    override fun onStop() { animation?.stop(); super.onStop() }
    override fun onStart() { super.onStart(); animation?.start() }
}
