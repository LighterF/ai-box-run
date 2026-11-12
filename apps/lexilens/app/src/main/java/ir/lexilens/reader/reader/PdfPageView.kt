package ir.lexilens.reader.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import ir.lexilens.reader.pdf.PageRenderer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PdfPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(index: Int)
        fun onNeedPage(index: Int, targetWidth: Int)
        fun onLongPressPage(index: Int, nx: Float, ny: Float, viewX: Float, viewY: Float)
        fun onTap()
        fun onZoomChanged(zoomed: Boolean)
    }

    var listener: Listener? = null
    private var renderer: PageRenderer? = null
    private var pageCount = 0
    var pageIndex = 0
        private set
    var fitWidth = false
        set(value) {
            field = value
            resetZoom()
            recalcBase()
            requestAround()
            invalidate()
        }

    private val bitmaps = HashMap<Int, Bitmap>()
    private val requestedWidths = HashMap<Int, Int>()
    private var baseScale = 1f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var dragOffset = 0f
    private var isTurning = false
    private var loadingAngle = 0f
    private val pad = 6f * resources.displayMetrics.density

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint().apply { color = Color.rgb(12, 17, 15) }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 234, 212)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bmp = bitmaps[pageIndex]
                val oldZoom = zoom
                val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
                if (abs(newZoom - oldZoom) < 0.0005f) return true
                // Keep the content under the fingers stable while zooming.
                val focusX = detector.focusX
                val focusY = detector.focusY
                val contentX = (focusX - tx) / (baseScale * oldZoom)
                val contentY = (focusY - ty) / (baseScale * oldZoom)
                zoom = newZoom
                tx = focusX - contentX * baseScale * zoom
                ty = focusY - contentY * baseScale * zoom
                clampTranslation(bmp)
                listener?.onZoomChanged(zoom > 1.01f)
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom <= 1.02f) resetZoom() else requestDetail()
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaleDetector.isInProgress) return true
                val bmp = bitmaps[pageIndex]
                if (isPanning(bmp)) {
                    tx -= dx
                    ty -= dy
                    clampTranslation(bmp)
                    invalidate()
                } else if (!isTurning && zoom <= 1.01f) {
                    dragOffset -= dx
                    invalidate()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (isPanning(bitmaps[pageIndex])) return false
                if (abs(dragOffset) > 20f || abs(vx) > 450f) {
                    val forward = if (abs(dragOffset) > 20f) dragOffset < 0 else vx < 0
                    commitTurn(forward)
                } else {
                    animateDragBack()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isTurning) return
                val bmp = bitmaps[pageIndex] ?: return
                val x = e.x - (if (zoom <= 1.01f) dragOffset else 0f)
                val drawScale = baseScale * zoom
                val bx = (x - tx) / drawScale
                val by = (e.y - ty) / drawScale
                if (bx < 0 || by < 0 || bx > bmp.width || by > bmp.height) return
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                listener?.onLongPressPage(pageIndex, bx / bmp.width, by / bmp.height, e.x, e.y)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.2f) {
                    resetZoom()
                } else {
                    val bmp = bitmaps[pageIndex]
                    val contentX = (e.x - tx) / (baseScale * zoom)
                    val contentY = (e.y - ty) / (baseScale * zoom)
                    zoom = 2.2f
                    tx = e.x - contentX * baseScale * zoom
                    ty = e.y - contentY * baseScale * zoom
                    clampTranslation(bmp)
                    listener?.onZoomChanged(true)
                    requestDetail()
                }
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                listener?.onTap()
                return true
            }
        })

    init {
        isClickable = true
        isFocusable = true
    }

    fun setDocument(renderer: PageRenderer, pageCount: Int, startPage: Int) {
        this.renderer = renderer
        this.pageCount = pageCount
        pageIndex = startPage.coerceIn(0, max(0, pageCount - 1))
        bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps.clear()
        requestedWidths.clear()
        resetZoom()
        recalcBase()
        requestAround()
        invalidate()
    }

    fun setPageBitmap(index: Int, bitmap: Bitmap) {
        val current = index == pageIndex
        var focusX = 0f
        var focusY = 0f
        var preserveFocus = false
        if (current && zoom > 1.01f) {
            val previous = bitmaps[index]
            if (previous != null && width > 0 && height > 0) {
                val oldScale = baseScale * zoom
                if (oldScale > 0f) {
                    focusX = (width / 2f - tx) / oldScale
                    focusY = (height / 2f - ty) / oldScale
                    preserveFocus = true
                }
            }
        }
        val old = bitmaps.put(index, bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
        if (current) {
            recalcBase()
            if (preserveFocus) {
                val newScale = baseScale * zoom
                tx = width / 2f - focusX * newScale
                ty = height / 2f - focusY * newScale
                clampTranslation(bitmap)
            } else {
                resetTranslationForZoom()
            }
            if (bitmap.width > DETAIL_WIDTH_THRESHOLD) {
                recycleOtherDetailBitmaps(index)
            }
        }
        trimCache()
        invalidate()
    }

    fun bitmapWidth(index: Int): Int = bitmaps[index]?.width ?: 0

    fun goToPage(index: Int, animate: Boolean = false) {
        val target = index.coerceIn(0, max(0, pageCount - 1))
        if (target == pageIndex) return
        pageIndex = target
        resetZoom()
        recalcBase()
        requestAround()
        listener?.onPageChanged(pageIndex)
        invalidate()
    }

    fun currentBitmap(): Bitmap? = bitmaps[pageIndex]

    fun release() {
        bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps.clear()
        listener = null
        renderer = null
    }

    private fun requestAround() {
        if (renderer == null) return
        val width = (width * 1.35f).toInt().coerceIn(900, 2600)
        listener?.onNeedPage(pageIndex, width)
        if (pageIndex > 0) listener?.onNeedPage(pageIndex - 1, width)
        if (pageIndex < pageCount - 1) listener?.onNeedPage(pageIndex + 1, width)
    }

    private fun trimCache() {
        val keep = setOf(pageIndex - 1, pageIndex, pageIndex + 1)
        val it = bitmaps.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val drop = e.key !in keep ||
                (e.key != pageIndex && e.value.width > DETAIL_WIDTH_THRESHOLD)
            if (drop) {
                if (!e.value.isRecycled) e.value.recycle()
                it.remove()
            }
        }
        requestedWidths.keys.retainAll(setOf(pageIndex))
    }

    private fun recycleOtherDetailBitmaps(keepIndex: Int) {
        val it = bitmaps.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key != keepIndex && e.value.width > DETAIL_WIDTH_THRESHOLD) {
                if (!e.value.isRecycled) e.value.recycle()
                it.remove()
            }
        }
    }

    private fun requestDetail() {
        if (width <= 0 || height <= 0 || zoom <= 1.2f) return
        val bmp = bitmaps[pageIndex] ?: return
        val needed = (width * zoom * 1.15f).toInt().coerceIn(1200, MAX_DETAIL_WIDTH)
        if (bmp.width >= (needed * 0.85f).toInt()) return
        if ((requestedWidths[pageIndex] ?: 0) >= needed) return
        requestedWidths[pageIndex] = needed
        listener?.onNeedPage(pageIndex, needed)
    }

    private fun isPanning(bmp: Bitmap?): Boolean {
        if (zoom > 1.01f) return true
        if (bmp == null) return false
        val scaledH = bmp.height * baseScale
        return fitWidth && scaledH > height - 2 * pad + 1f
    }

    private fun recalcBase() {
        val bmp = bitmaps[pageIndex]
        val w = bmp?.width ?: renderer?.let { runCatching { it.pageSize(pageIndex).first }.getOrNull() } ?: 1
        val h = bmp?.height ?: renderer?.let { runCatching { it.pageSize(pageIndex).second }.getOrNull() } ?: 1
        if (w <= 1 || h <= 1 || width <= 0 || height <= 0) return
        val availW = (width - 2 * pad).coerceAtLeast(1f)
        val availH = (height - 2 * pad).coerceAtLeast(1f)
        baseScale = if (fitWidth) {
            availW / w
        } else {
            min(availW / w, availH / h)
        }
    }

    private fun resetTranslationForZoom() {
        val bmp = bitmaps[pageIndex] ?: return
        val drawScale = baseScale * zoom
        val scaledW = bmp.width * drawScale
        val scaledH = bmp.height * drawScale
        if (scaledW <= width) tx = (width - scaledW) / 2f else clampTx(bmp)
        if (scaledH <= height) ty = (height - scaledH) / 2f else {
            ty = ty.coerceIn(height - scaledH - pad, pad)
        }
        if (fitWidth && zoom <= 1.01f) {
            tx = (width - scaledW) / 2f
            if (scaledH > height) ty = pad
        }
        invalidate()
    }

    private fun clampTx(bmp: Bitmap): Float {
        val scaledW = bmp.width * baseScale * zoom
        tx = if (scaledW <= width) (width - scaledW) / 2f
        else tx.coerceIn(width - scaledW - pad, pad)
        return tx
    }

    private fun clampTranslation(bmp: Bitmap?) {
        if (bmp == null) return
        clampTx(bmp)
        val scaledH = bmp.height * baseScale * zoom
        ty = if (scaledH <= height) (height - scaledH) / 2f
        else ty.coerceIn(height - scaledH - pad, pad)
    }

    private fun resetZoom() {
        zoom = 1f
        dragOffset = 0f
        isTurning = false
        resetTranslationForZoom()
        listener?.onZoomChanged(false)
    }

    private fun commitTurn(forward: Boolean) {
        if (isTurning) return
        val target = if (forward) pageIndex + 1 else pageIndex - 1
        if (target !in 0 until pageCount) {
            animateDragBack()
            return
        }
        isTurning = true
        val direction = if (forward) -1f else 1f
        val animator = ValueAnimator.ofFloat(dragOffset, direction * width)
        animator.duration = 220
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            dragOffset = it.animatedValue as Float
            invalidate()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                dragOffset = 0f
                isTurning = false
                pageIndex = target
                resetZoom()
                recalcBase()
                requestAround()
                listener?.onPageChanged(pageIndex)
                invalidate()
            }
        })
        animator.start()
    }

    private fun animateDragBack() {
        if (dragOffset == 0f) return
        val animator = ValueAnimator.ofFloat(dragOffset, 0f)
        animator.duration = 180
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            dragOffset = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcBase()
        resetTranslationForZoom()
        requestAround()
        requestDetail()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgPaint.color)
        val bmp = bitmaps[pageIndex]
        if (bmp == null) {
            drawSpinner(canvas)
            return
        }
        val drawScale = baseScale * zoom
        val dst = RectF(
            tx + dragOffset,
            ty,
            tx + dragOffset + bmp.width * drawScale,
            ty + bmp.height * drawScale,
        )

        if (abs(dragOffset) > 0.5f && zoom <= 1.01f) {
            val neighborIndex = if (dragOffset > 0) pageIndex - 1 else pageIndex + 1
            val neighbor = bitmaps[neighborIndex]
            if (neighbor != null) {
                val nx = if (dragOffset > 0) tx - width + dragOffset else tx + width + dragOffset
                val ndst = RectF(nx, ty, nx + neighbor.width * drawScale, ty + neighbor.height * drawScale)
                canvas.drawBitmap(neighbor, null, ndst, bitmapPaint)
            }
        }

        canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(45, 255, 255, 255)
        }
        canvas.drawRect(dst, border)
    }

    private fun drawSpinner(canvas: Canvas) {
        loadingAngle = (loadingAngle + 7f) % 360f
        val r = 18f * resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, loadingAngle, 250f, false, spinnerPaint)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (!isTurning && abs(dragOffset) > 0f && zoom <= 1.01f) {
                if (abs(dragOffset) > width * 0.22f) {
                    commitTurn(dragOffset < 0)
                } else {
                    animateDragBack()
                }
            }
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    companion object {
        private const val MAX_DETAIL_WIDTH = 4000
        private const val DETAIL_WIDTH_THRESHOLD = 1800
    }
}
