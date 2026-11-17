/*
 * SPDX-FileCopyrightText: 2026 GDmio (BlueOS)
 * SPDX-License-Identifier: Apache-2.0
 *
 * Part of BlueOS by GDmio: https://github.com/GDmio/BlueOS
 * Reusing this code (as it is, changed or renamed)? Keep this notice, copy NOTICE and say
 * "based on BlueOS by GDmio" with the link in your project's description. See LICENSE.
 */

package org.blueos.arona

import android.app.WallpaperManager
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Region
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * BlueOS 0.9 «Желе на обоях» (approved mockup mockups/blueos-0.9-wiggle.html, like OriginOS 7's Wiggle): parts of
 * the wallpaper the user marks spring like jelly when the phone moves and when a finger pulls them.
 *
 * Settings.Secure [KEY] (JSON): `on`, `lock`, `home` (where it works) and `pics` — the areas of each picture, keyed
 * by [picKey] (which wallpaper + its id: another picture = no areas, they belong to the old one). An area is in the
 * picture's own coordinates: centre `x`/`y` as fractions of its width / height, radii `rx`/`ry` as fractions of its
 * width (a circle stays round), `r` = turn in radians, or `pts` = a shape drawn by the finger (x, y fractions).
 *
 * The same code draws it in the wallpaper (SystemUI BlueOSWallpaperWeather) and in the editor («Обои и стиль» →
 * «Желе на обоях»): [Sim] = one damped spring per area, [Masks] = each area's soft shape, [Renderer] = an AGSL
 * shader that moves the picture inside the areas by their springs (the edges fade into the still picture).
 */
object BlueOSJelly {
    const val KEY = "blueos_wall_jelly"
    /**
     * Written by the home screen's wallpaper (SystemUI) for the launcher: "picKey|picW|picH|left|top|right|bottom" =
     * which picture and which part of it (picture px) fills the screen, so the launcher can tell on a finger's down
     * whether it is on an area ([homeHit]) without asking SystemUI. Empty = no areas.
     */
    const val VIEW_KEY = "blueos_wall_jelly_view"
    const val MAX = 4
    const val CIRCLE = 0
    const val OVAL = 1
    const val RECT = 2
    const val FREE = 3

    /** Commands the launcher sends with WallpaperManager.sendWallpaperCommand: a finger on the home screen. */
    const val CMD_DOWN = "org.blueos.jelly.down"
    const val CMD_MOVE = "org.blueos.jelly.move"
    const val CMD_UP = "org.blueos.jelly.up"

    class Area(
        var shape: Int = OVAL,
        var x: Float = .5f, var y: Float = .5f,
        var rx: Float = .2f, var ry: Float = .14f,
        var rot: Float = 0f,
        var strength: Int = 50, var spring: Int = 50, var soft: Int = 50,
        var motion: Boolean = true, var touch: Boolean = true,
        var pts: FloatArray? = null,
    ) {
        fun copy() = Area(shape, x, y, rx, ry, rot, strength, spring, soft, motion, touch, pts?.copyOf())

        fun json() = JSONObject().apply {
            put("k", shape); put("x", x.toDouble()); put("y", y.toDouble()); put("rx", rx.toDouble()); put("ry", ry.toDouble())
            put("r", rot.toDouble()); put("s", strength); put("p", spring); put("e", soft)
            put("m", if (motion) 1 else 0); put("t", if (touch) 1 else 0)
            pts?.let { p -> put("pts", JSONArray().apply { p.forEach { put(it.toDouble()) } }) }
        }

        companion object {
            fun of(o: JSONObject) = Area(
                o.optInt("k", OVAL), o.optDouble("x", .5).toFloat(), o.optDouble("y", .5).toFloat(),
                o.optDouble("rx", .2).toFloat(), o.optDouble("ry", .14).toFloat(), o.optDouble("r", 0.0).toFloat(),
                o.optInt("s", 50), o.optInt("p", 50), o.optInt("e", 50), o.optInt("m", 1) != 0, o.optInt("t", 1) != 0,
                o.optJSONArray("pts")?.let { a -> FloatArray(a.length()) { a.optDouble(it).toFloat() } }
                    ?.takeIf { it.size >= 6 })
        }
    }

    class Config(var on: Boolean = false, var lock: Boolean = true, var home: Boolean = true,
                 val pics: MutableMap<String, MutableList<Area>> = mutableMapOf()) {
        fun areas(key: String?): List<Area> = key?.let { pics[it] }.orEmpty()
        val any get() = on && pics.values.any { it.isNotEmpty() }
        fun json() = JSONObject().apply {
            put("on", if (on) 1 else 0); put("lock", if (lock) 1 else 0); put("home", if (home) 1 else 0)
            put("pics", JSONObject().apply {
                pics.forEach { (k, v) -> if (v.isNotEmpty()) put(k, JSONArray().apply { v.forEach { put(it.json()) } }) }
            })
        }
    }

    fun read(cr: ContentResolver): Config {
        val s = Settings.Secure.getString(cr, KEY) ?: return Config()
        return try {
            val o = JSONObject(s)
            val pics = mutableMapOf<String, MutableList<Area>>()
            o.optJSONObject("pics")?.let { p ->
                p.keys().forEach { k ->
                    val a = p.optJSONArray(k) ?: return@forEach
                    pics[k] = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Area::of) }.take(MAX).toMutableList()
                }
            }
            Config(o.optInt("on", 0) != 0, o.optInt("lock", 1) != 0, o.optInt("home", 1) != 0, pics)
        } catch (e: Exception) {
            Config()
        }
    }

    fun write(cr: ContentResolver, c: Config) {
        // the areas of pictures no longer set are dropped: they can never come back (a new picture = a new id)
        Settings.Secure.putString(cr, KEY, c.json().toString())
    }

    /**
     * 0.9: the jelly works only while the wallpaper stays still («Прокрутка обоев» off, [BlueOSSettings.WALL_SCROLL]):
     * a scrolling wallpaper would carry the areas away from where the finger and the eye expect them.
     */
    private var hitView: String? = null
    private var hitSim: Sim? = null

    /**
     * The launcher: whether screen px ([screenW]x[screenH]) are on an area of the home screen's picture, from
     * [VIEW_KEY]. Read on a finger's down only.
     */
    @JvmStatic
    fun homeHit(cr: ContentResolver, cfg: Config, x: Float, y: Float, screenW: Int, screenH: Int): Boolean = try {
        val v = Settings.Secure.getString(cr, VIEW_KEY).orEmpty()
        val p = v.split('|')
        if (p.size < 7 || screenW <= 0 || screenH <= 0) false else {
            val areas = cfg.areas(p[0])
            val w = p[1].toFloat(); val h = p[2].toFloat()
            val l = p[3].toFloat(); val t = p[4].toFloat(); val r = p[5].toFloat(); val b = p[6].toFloat()
            if (areas.isEmpty() || w <= 0f || h <= 0f) false else {
                val ck = v + "#" + Settings.Secure.getString(cr, KEY)
                val sim = hitSim.takeIf { ck == hitView } ?: Sim(areas, w, h).also { hitSim = it; hitView = ck }
                sim.hit(l + x / screenW * (r - l), t + y / screenH * (b - t))
            }
        }
    } catch (e: RuntimeException) { false }

    @JvmStatic fun allowed(cr: ContentResolver): Boolean = !BlueOSSettings.on(cr, BlueOSSettings.WALL_SCROLL)

    /**
     * The part of the picture the screen shows, in picture px. [crop] = what the system shows for this screen size
     * (WallpaperManager.getBitmapCrops, relative to the stored picture; it may be wider than the screen, for
     * scrolling), [xOffset] = the launcher's offset (0.5 with «Прокрутка обоев» off) — placed as
     * WallpaperController.updateWallpaperOffset places the wallpaper.
     */
    @JvmStatic
    fun visible(crop: Rect, screenW: Int, screenH: Int, xOffset: Float): RectF {
        val sr = screenW / screenH.coerceAtLeast(1).toFloat()
        val cw = crop.width().toFloat()
        val ch = crop.height().toFloat()
        return if (ch > 0f && cw / ch >= sr) {
            val w = ch * sr
            val l = crop.left + (cw - w) * xOffset.coerceIn(0f, 1f)
            RectF(l, crop.top.toFloat(), l + w, crop.bottom.toFloat())
        } else {
            val h = cw / sr
            val t = crop.top + (ch - h) * .5f
            RectF(crop.left.toFloat(), t, crop.right.toFloat(), t + h)
        }
    }

    /** «s:<id>» for the home (system) picture — also the lock screen's when it has none of its own — «l:<id>» the lock one. */
    fun picKey(which: Int, id: Int): String =
        (if (which and WallpaperManager.FLAG_SYSTEM == 0 && which and WallpaperManager.FLAG_LOCK != 0) "l:" else "s:") + id

    // ------------------------------------------------------------------ shapes, in picture pixels

    fun path(a: Area, w: Float, h: Float): Path {
        val p = Path()
        val pts = a.pts
        if (a.shape == FREE && pts != null) {
            p.moveTo(pts[0] * w, pts[1] * h)
            var i = 2
            while (i + 1 < pts.size) { p.lineTo(pts[i] * w, pts[i + 1] * h); i += 2 }
            p.close()
            return p
        }
        val rx = a.rx * w
        val ry = (if (a.shape == CIRCLE) a.rx else a.ry) * w
        val r = RectF(-rx, -ry, rx, ry)
        when (a.shape) {
            RECT -> p.addRoundRect(r, min(rx, ry) * .35f, min(rx, ry) * .35f, Path.Direction.CW)
            else -> p.addOval(r, Path.Direction.CW)
        }
        p.transform(Matrix().apply { setRotate(Math.toDegrees(a.rot.toDouble()).toFloat()); postTranslate(a.x * w, a.y * h) })
        return p
    }

    /** The middle of the area and its smaller radius, picture px. */
    fun centre(a: Area, w: Float, h: Float): FloatArray {
        val pts = a.pts
        if (a.shape == FREE && pts != null) {
            val b = RectF().also { path(a, w, h).computeBounds(it, true) }
            return floatArrayOf(b.centerX(), b.centerY(), min(b.width(), b.height()) / 2f)
        }
        return floatArrayOf(a.x * w, a.y * h, min(a.rx, if (a.shape == CIRCLE) a.rx else a.ry) * w)
    }

    // ------------------------------------------------------------------ soft masks

    /** [rgb]: areas 0–2 in red / green / blue (alpha always full: premultiplied bitmaps lose colour under alpha), [extra]: area 3 in red. */
    class Masks(val rgb: Bitmap, val extra: Bitmap, val scale: Float)

    /** Each area's shape, blurred by its «Мягкость края», at a quarter of the picture's size. */
    fun masks(areas: List<Area>, w: Int, h: Int): Masks {
        val scale = (270f / w).coerceAtMost(.25f).coerceAtLeast(.05f)
        val mw = (w * scale).toInt().coerceAtLeast(1)
        val mh = (h * scale).toInt().coerceAtLeast(1)
        val one = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        val c = Canvas(one)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ch = Array(MAX) { ByteArray(0) }
        for (i in 0 until MAX) {
            val a = areas.getOrNull(i) ?: continue
            one.eraseColor(0)
            val size = centre(a, w.toFloat(), h.toFloat())[2] * scale
            val blur = size * (.05f + a.soft / 100f * .45f)
            paint.maskFilter = if (blur >= .5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            val p = path(a, w.toFloat(), h.toFloat())
            p.transform(Matrix().apply { setScale(scale, scale) })
            c.drawPath(p, paint)
            val buf = java.nio.ByteBuffer.allocate(one.byteCount)
            one.copyPixelsToBuffer(buf)
            val raw = buf.array()
            val row = one.rowBytes
            ch[i] = ByteArray(mw * mh) { j -> raw[(j / mw) * row + j % mw] }
        }
        val px = IntArray(mw * mh)
        val px2 = IntArray(mw * mh)
        for (j in px.indices) {
            fun v(i: Int) = if (ch[i].isEmpty()) 0 else ch[i][j].toInt() and 0xff
            px[j] = (0xff shl 24) or (v(0) shl 16) or (v(1) shl 8) or v(2)
            px2[j] = (0xff shl 24) or (v(3) shl 16)
        }
        val rgb = Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888)
        val extra = Bitmap.createBitmap(px2, mw, mh, Bitmap.Config.ARGB_8888)
        one.recycle()
        return Masks(rgb, extra, scale)
    }

    // ------------------------------------------------------------------ the springs

    /** One damped spring per area, in picture px; [step] says whether anything still moves. */
    class Sim(val areas: List<Area>, val w: Float, val h: Float, unitPx: Float = w / 1080f) {
        val n = areas.size.coerceAtMost(MAX)
        val x = FloatArray(MAX)
        val y = FloatArray(MAX)
        val vx = FloatArray(MAX)
        val vy = FloatArray(MAX)
        private val c = Array(n) { centre(areas[it], w, h) }
        private val regions = Array(n) { i ->
            Region().apply { setPath(path(areas[i], w, h), Region(0, 0, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))) }
        }
        /**
         * picture px per screen px × 1080/1080: the strengths are tuned on the phone's 1080 px wide screen. The
         * wallpaper shows only a part of a wide picture, so its caller passes the part's width / the screen's.
         */
        private val unit = unitPx.coerceAtLeast(.01f)
        private var touchI = -1
        private var sx = 0f
        private var sy = 0f
        private var tx = 0f
        private var ty = 0f
        var moving = false
            private set

        fun centreX(i: Int) = c[i][0]
        fun centreY(i: Int) = c[i][1]

        /** A finger came down at picture px: true if it took an area that listens to touches. */
        fun down(px: Float, py: Float): Boolean {
            touchI = -1
            for (i in n - 1 downTo 0) {
                if (areas[i].touch && regions[i].contains(px.toInt(), py.toInt())) { touchI = i; break }
            }
            if (touchI < 0) return false
            sx = px; sy = py; tx = px; ty = py
            moving = true
            return true
        }

        fun move(px: Float, py: Float) { if (touchI >= 0) { tx = px; ty = py } }
        /**
         * Where the areas can change the picture now, picture px: their shapes, plus their soft edges (the masks are
         * blurred by up to the area's size) and how far the springs pull. The jelly is drawn only there.
         */
        fun reach(): RectF {
            reachBox.setEmpty()
            for (i in 0 until n) {
                val b = bounds[i]
                // the soft edge reaches ≤ ~0.85 × the smaller radius past the shape; the spring's pull on top
                val pad = c[i][2] + 1.5f * hypot(x[i], y[i]) + 8f
                if (reachBox.isEmpty) reachBox.set(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                else reachBox.union(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
            }
            return reachBox
        }
        private val bounds = Array(n) { regions[it].bounds }
        private val reachBox = RectF()

        /** Whether picture px are on an area that listens to touches (nothing starts). */
        fun hit(px: Float, py: Float): Boolean {
            for (i in n - 1 downTo 0) if (areas[i].touch && regions[i].contains(px.toInt(), py.toInt())) return true
            return false
        }
        fun up() { touchI = -1 }
        val held get() = touchI >= 0

        /**
         * [ax] / [ay] = the phone's own acceleration in m/s², screen axes (x right, y down): the jelly lags behind
         * the phone. Returns true while anything moves (or a finger holds an area).
         */
        fun step(dt: Float, ax: Float, ay: Float): Boolean {
            var any = touchI >= 0
            for (i in 0 until n) {
                val a = areas[i]
                val r = c[i][2]
                val maxOff = r * (.15f + a.strength / 100f * .55f)
                val k = 60f + a.spring * 4.4f
                val damp = 2f * sqrt(k) * (.55f - a.spring / 100f * .45f)
                var fx = -k * x[i] - damp * vx[i]
                var fy = -k * y[i] - damp * vy[i]
                if (a.motion) {
                    val g = MOTION_GAIN * unit * (a.strength / 50f)
                    fx -= ax * g
                    fy -= ay * g
                }
                if (i == touchI) {
                    val dx = tx - sx
                    val dy = ty - sy
                    val l = hypot(dx, dy)
                    val m = min(1f, maxOff / l.coerceAtLeast(1f))
                    fx += (dx * m - x[i]) * k * 1.6f
                    fy += (dy * m - y[i]) * k * 1.6f
                }
                vx[i] += fx * dt; vy[i] += fy * dt
                x[i] += vx[i] * dt; y[i] += vy[i] * dt
                val l = hypot(x[i], y[i])
                if (l > maxOff * 1.4f) { x[i] *= maxOff * 1.4f / l; y[i] *= maxOff * 1.4f / l }
                if (abs(x[i]) + abs(y[i]) > .3f * unit || abs(vx[i]) + abs(vy[i]) > 4f * unit) any = true
                else if (i != touchI) { x[i] = 0f; y[i] = 0f; vx[i] = 0f; vy[i] = 0f }
            }
            moving = any
            return any
        }

        /** How much the area squashes along its push now (fast = more). */
        fun squash(i: Int) = (hypot(vx[i], vy[i]) / (9000f * unit)).coerceAtMost(.25f) * (areas[i].strength / 60f)

        /** A push of every area at once ([vx], [vy] screen px/s): `adb shell am broadcast` testing, see SystemUI. */
        fun poke(vx: Float, vy: Float) {
            for (i in 0 until n) { this.vx[i] += vx * unit; this.vy[i] += vy * unit }
            moving = n > 0
        }

        fun still() { for (i in 0 until MAX) { x[i] = 0f; y[i] = 0f; vx[i] = 0f; vy[i] = 0f }; touchI = -1; moving = false }
    }

    /**
     * px/s² of push per m/s² of the phone's own acceleration, at strength 50, in screen px. 0.9.0 had 70 (and per
     * px of the whole picture, not of the part on the screen): ~8× weaker than the approved mockup, where a hand's
     * move of 1 m/s² (≈ 16 000 screen px/s² on a 6.6 cm wide screen) pushed by .035 per px/s² ≈ 570 — the jelly
     * barely moved on the phone. A walk or a turn of the hand now shows, a shake swings it to the edge.
     */
    const val MOTION_GAIN = 600f
    /** the phone's own acceleration below this (m/s²) is a still hand */
    const val DEADZONE = .35f

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the picture moved inside the areas, over what is already drawn (outside the areas: nothing). [dst] = where
     * the picture lies on the canvas. Hardware canvases only (AGSL).
     */
    class Renderer {
        private val shader = RuntimeShader(AGSL)
        private val paint = Paint().apply { shader = this@Renderer.shader }
        private val m = Matrix()
        private var masks: Masks? = null
        private var maskRgb: BitmapShader? = null
        private var maskExtra: BitmapShader? = null
        private var pic: Bitmap? = null
        private var picShader: BitmapShader? = null

        fun setMasks(mk: Masks?) {
            masks = mk
            maskRgb = mk?.let { BitmapShader(it.rgb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            maskExtra = mk?.let { BitmapShader(it.extra, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val k = 1f / (mk?.scale ?: 1f)
            maskRgb?.setLocalMatrix(Matrix().apply { setScale(k, k) })
            maskExtra?.setLocalMatrix(Matrix().apply { setScale(k, k) })
        }

        fun draw(canvas: Canvas, picture: Bitmap, dst: Rect, sim: Sim) {
            if (!masksIn()) return
            if (picture !== pic) { pic = picture; picShader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val ps = picShader ?: return
            shader.setInputShader("pic", ps)
            uniforms(sim, picture.width.toFloat(), picture.height.toFloat())
            shader.setFloatUniform("toPic", 1f, 1f)
            shader.setFloatUniform("org", 0f, 0f)
            shader.setFloatUniform("full", 0f)
            // the shader works in picture px: map them onto dst
            m.setRectToRect(RectF(0f, 0f, picture.width.toFloat(), picture.height.toFloat()), RectF(dst), Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(m)
            canvas.drawRect(dst, paint)
        }

        /**
         * The jelly as an effect on a whole drawn scene of [outW]×[outH] px (the wallpaper with the weather in it):
         * the areas move the scene itself, so the weather's haze moves with the picture. Before (2026-11-13) the
         * moving areas were drawn again from the plain picture over the scene — the haze vanished there while they
         * moved, and the glass widgets over them showed the sharp picture.
         */
        fun effect(sim: Sim, outW: Int, outH: Int, box: Rect? = null): RenderEffect? {
            if (!masksIn() || outW <= 0 || outH <= 0) return null
            uniforms(sim, sim.w, sim.h)
            shader.setFloatUniform("toPic", sim.w / outW, sim.h / outH)
            // [box] = the node holds only this part of the scene (its own px start at the box's corner)
            shader.setFloatUniform("org", (box?.left ?: 0).toFloat(), (box?.top ?: 0).toFloat())
            shader.setFloatUniform("full", 1f)
            shader.setLocalMatrix(null)
            return RenderEffect.createRuntimeShaderEffect(shader, "pic")
        }

        private fun masksIn(): Boolean {
            val rgb = maskRgb ?: return false
            val ex = maskExtra ?: return false
            shader.setInputShader("maskA", rgb)
            shader.setInputShader("maskB", ex)
            return true
        }

        private fun uniforms(sim: Sim, picW: Float, picH: Float) {
            val o = FloatArray(8)
            val cc = FloatArray(8)
            val sq = FloatArray(4)
            for (i in 0 until sim.n) {
                o[i * 2] = sim.x[i]; o[i * 2 + 1] = sim.y[i]
                cc[i * 2] = sim.centreX(i); cc[i * 2 + 1] = sim.centreY(i)
                sq[i] = sim.squash(i)
            }
            shader.setFloatUniform("o01", o[0], o[1], o[2], o[3])
            shader.setFloatUniform("o23", o[4], o[5], o[6], o[7])
            shader.setFloatUniform("c01", cc[0], cc[1], cc[2], cc[3])
            shader.setFloatUniform("c23", cc[4], cc[5], cc[6], cc[7])
            shader.setFloatUniform("sq", sq[0], sq[1], sq[2], sq[3])
            shader.setFloatUniform("picSize", picW, picH)
        }

        companion object {
            private const val AGSL = """
                uniform shader pic; uniform shader maskA; uniform shader maskB;
                uniform float4 o01; uniform float4 o23; uniform float4 c01; uniform float4 c23; uniform float4 sq;
                uniform float2 picSize; uniform float2 toPic; uniform float2 org; uniform float full;
                float2 push(float2 p, float w, float2 off, float2 c, float s) {
                    w = w * w * (3.0 - 2.0 * w);
                    float l = length(off);
                    float2 dir = l > 0.01 ? off / l : float2(0.0, 1.0);
                    // the inside follows the spring, squashed a little along the push
                    return w * (off + dir * dot(p - c, dir) * s);
                }
                half4 main(float2 p0) {
                    // toPic: picture px per output px (1 when drawn from the picture, see effect())
                    float2 p = (p0 + org) * toPic;
                    half4 a = maskA.eval(p);
                    half4 b = maskB.eval(p);
                    float2 q = p;
                    q -= push(p, float(a.r), o01.xy, c01.xy, sq.x);
                    q -= push(p, float(a.g), o01.zw, c01.zw, sq.y);
                    q -= push(p, float(a.b), o23.xy, c23.xy, sq.z);
                    q -= push(p, float(b.r), o23.zw, c23.zw, sq.w);
                    float cover = clamp(max(max(float(a.r), float(a.g)), max(float(a.b), float(b.r))) * 40.0, 0.0, 1.0);
                    q = clamp(q, float2(0.5), picSize - 0.5);
                    half4 c = pic.eval(q / toPic - org);
                    return full > 0.5 ? c : c * half(cover);
                }
            """
        }
    }

    // ------------------------------------------------------------------ fingers on the lock screen (SystemUI)

    /**
     * The lock screen's touches (screen px) for the wallpaper — fed by the shade window like AronaGaze, but only
     * when the finger isn't on the character ([blocked], set by the lock screen's character section).
     */
    object LockTouch {
        private val listeners = CopyOnWriteArrayList<(Int, Float, Float) -> Unit>()
        private val hits = CopyOnWriteArrayList<(Float, Float) -> Boolean>()
        @JvmStatic @Volatile var blocked: ((Float, Float) -> Boolean)? = null

        fun add(l: (Int, Float, Float) -> Unit): () -> Unit { listeners += l; return { listeners -= l } }

        /** The lock screen's wallpaper answers whether screen px are on one of its areas. */
        fun addHit(h: (Float, Float) -> Boolean): () -> Unit { hits += h; return { hits -= h } }

        private var candidate = false
        private var claimed = false
        private var downX = 0f
        private var downY = 0f

        /**
         * 0.9 (the user, 2026-11-13: as when patting Arona / Plana): a finger that came down on an area and moved
         * [slopPx] owns the gesture — the shade, the bouncer and the rest get [cancel], the moves go to the jelly
         * only. A tap, or a finger elsewhere, works as before. true = the caller must not pass [e] on.
         */
        @JvmStatic
        fun claim(e: android.view.MotionEvent, slopPx: Float, cancel: Runnable): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    claimed = false
                    downX = e.rawX; downY = e.rawY
                    candidate = listeners.isNotEmpty() && blocked?.invoke(e.rawX, e.rawY) != true &&
                        hits.any { it(e.rawX, e.rawY) }
                    return false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (claimed) { dispatch(e); return true }
                    if (candidate && hypot(e.rawX - downX, e.rawY - downY) > slopPx) {
                        claimed = true
                        candidate = false
                        cancel.run()
                        dispatch(e)
                        return true
                    }
                    return false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val was = claimed
                    claimed = false
                    candidate = false
                    if (was) dispatch(e)
                    return was
                }
                else -> { if (claimed) dispatch(e); return claimed }
            }
        }

        @JvmStatic
        fun dispatch(e: android.view.MotionEvent) {
            if (listeners.isEmpty()) return
            listeners.forEach { it(e.actionMasked, e.rawX, e.rawY) }
        }
    }
}
