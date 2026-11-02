package com.example.workoutmate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.sin

/**
 * Animated "acid liquid" backdrop.
 *
 * API 33+ uses an AGSL RuntimeShader (fbm noise + domain warping). Older
 * devices keep the original Canvas implementation as a fallback, so the app
 * still runs on Android 7–12. The shader is compiled once in init; if anything
 * goes wrong we permanently fall back instead of drawing a blank background.
 */
class AcidLiquidBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Blob(
        val color: Int,
        val baseX: Float,
        val baseY: Float,
        val radius: Float,
        val phase: Float,
        val drift: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain: android.graphics.Bitmap by lazy { createGrain() }
    private var progress = 0f

    /** Created only on API 33+, otherwise stays null and the Canvas path runs. */
    private var runtimeShader: Shader? = null
    private var lastFrameAt = 0L

    private val blobs = listOf(
        Blob(Color.argb(220, 183, 255, 60), 0.18f, 0.28f, 0.55f, 0.10f, 0.60f),
        Blob(Color.argb(200, 0, 229, 255), 0.82f, 0.20f, 0.52f, 0.48f, 0.72f),
        Blob(Color.argb(190, 124, 77, 255), 0.72f, 0.76f, 0.62f, 0.80f, 0.50f),
        Blob(Color.argb(150, 255, 61, 129), 0.22f, 0.82f, 0.46f, 0.25f, 0.82f),
        Blob(Color.argb(130, 255, 107, 53), 0.52f, 0.48f, 0.40f, 0.64f, 0.38f)
    )

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 18_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt >= FRAME_INTERVAL_MS) {
                lastFrameAt = now
                invalidate()
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.blendMode = android.graphics.BlendMode.SCREEN
        } else {
            @Suppress("DEPRECATION")
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimeShader = createShaderOrNull()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
            drawShader(canvas, w, h)
        } else {
            drawCanvasFallback(canvas, w, h)
        }

        // Darken the top strip and corners. The cards are translucent, so this
        // keeps bright blobs from washing out the selected state and labels.
        scrimPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(105, 3, 5, 10),
                Color.argb(45, 3, 5, 10),
                Color.TRANSPARENT,
                Color.argb(30, 3, 5, 10)
            ),
            floatArrayOf(0f, 0.24f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawShader(canvas: Canvas, w: Float, h: Float) {
        runtimeShader?.let { Api33.draw(canvas, it, shaderPaint, w, h, progress * 6.2f) }
    }

    private fun drawCanvasFallback(canvas: Canvas, w: Float, h: Float) {
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.rgb(5, 8, 14),
                Color.rgb(7, 13, 20),
                Color.rgb(9, 12, 21)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, basePaint)

        for (blob in blobs) {
            val angle = (progress * 2f * PI.toFloat() * blob.drift) + blob.phase * 2f * PI.toFloat()
            val cx = w * (blob.baseX + 0.10f * sin(angle))
            val cy = h * (blob.baseY + 0.12f * sin(angle * 1.3f + 0.7f))
            val radius = w * blob.radius * (1f + 0.10f * sin(angle * 0.8f))
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    blob.color,
                    Color.argb(50, Color.red(blob.color), Color.green(blob.color), Color.blue(blob.color)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.alpha = 255
            canvas.drawCircle(cx, cy, radius, paint)
        }

        grainPaint.shader = Shader.TileMode.REPEAT.let {
            android.graphics.BitmapShader(grain, it, it)
        }
        grainPaint.alpha = 18
        canvas.drawRect(0f, 0f, w, h, grainPaint)

        // Subtle vignette keeps the white typography readable.
        vignettePaint.shader = RadialGradient(
            w * 0.5f, h * 0.38f, h * 0.8f,
            intArrayOf(Color.TRANSPARENT, Color.argb(150, 3, 5, 10)),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createShaderOrNull(): Shader? {
        return Api33.createShader(AGSL_LIQUID)
    }

    /**
     * Keep every direct RuntimeShader reference in this nested object so
     * Android 12 and below never need to resolve the API 33 class.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun createShader(source: String): Shader? {
            return try {
                RuntimeShader(source)
            } catch (error: Throwable) {
                Log.w(TAG, "AGSL background unavailable; using Canvas fallback", error)
                null
            }
        }

        fun draw(
            canvas: Canvas,
            shader: Shader,
            paint: Paint,
            w: Float,
            h: Float,
            time: Float
        ) {
            val runtimeShader = shader as RuntimeShader
            runtimeShader.setFloatUniform("uResolution", w, h)
            runtimeShader.setFloatUniform("uTime", time)
            paint.shader = runtimeShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun createGrain(): android.graphics.Bitmap {
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val random = kotlin.random.Random(7)
        for (i in pixels.indices) {
            val v = random.nextInt(180, 255)
            pixels[i] = Color.argb(random.nextInt(10, 34), v, v, v)
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    companion object {
        private const val TAG = "AcidLiquid"
        private const val FRAME_INTERVAL_MS = 33L

        /**
         * OGL-style value-noise fbm with two domain-warp passes. Keep this
         * valid AGSL: it is compiled by RuntimeShader on API 33+.
         */
        private const val AGSL_LIQUID = """
            uniform float2 uResolution;
            uniform float uTime;

            float hash21(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            float valueNoise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash21(i);
                float b = hash21(i + float2(1.0, 0.0));
                float c = hash21(i + float2(0.0, 1.0));
                float d = hash21(i + float2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            float fbm(float2 p) {
                float v = 0.0;
                float amp = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += amp * valueNoise(p);
                    p = p * 2.03 + float2(11.7, 7.3);
                    amp *= 0.5;
                }
                return v;
            }

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / uResolution;
                uv.x *= uResolution.x / uResolution.y;
                float t = uTime;
                float2 p = uv * 2.1;

                float2 q = float2(
                    fbm(p + float2(0.0, t * 0.035)),
                    fbm(p + float2(5.2, 1.3) - t * 0.028)
                );
                float2 r = float2(
                    fbm(p + 4.0 * q + float2(1.7, 9.2) + t * 0.030),
                    fbm(p + 4.0 * q + float2(8.3, 2.8) - t * 0.024)
                );
                float f = fbm(p + 4.0 * r);

                float3 acid = float3(0.72, 1.00, 0.24);
                float3 cyan = float3(0.00, 0.90, 1.00);
                float3 violet = float3(0.49, 0.30, 1.00);
                float3 pink = float3(1.00, 0.24, 0.50);

                float3 col = mix(float3(0.012, 0.020, 0.035), violet * 0.72, smoothstep(0.25, 0.92, f));
                col = mix(col, cyan * 0.58, smoothstep(0.12, 0.82, q.x) * 0.34);
                col = mix(col, acid * 0.52, smoothstep(0.52, 0.94, r.y) * 0.30);
                col = mix(col, pink * 0.36, smoothstep(0.62, 0.98, q.y) * 0.18);

                float2 centered = (uv - float2(0.5, 0.42)) * float2(1.0, 1.25);
                float vignette = smoothstep(0.20, 0.82, length(centered));
                col *= 1.0 - 0.52 * vignette;

                // Top of the screen sits behind the title/cards: keep it dark.
                float topShade = 1.0 - smoothstep(0.0, 0.34, uv.y);
                col *= 1.0 - 0.28 * topShade;

                float grain = hash21(fragCoord + float2(t * 60.0, t * 43.0));
                col += (grain - 0.5) * 0.022;

                return half4(clamp(col, 0.0, 1.0), 1.0);
            }
        """
    }
}
