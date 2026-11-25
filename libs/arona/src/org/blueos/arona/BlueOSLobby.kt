/*
 * SPDX-FileCopyrightText: 2026 m89wppy (BlueOS)
 * SPDX-License-Identifier: Apache-2.0
 *
 * Part of BlueOS by m89wppy: https://github.com/m89wppy/BlueOS
 * Reusing this code (as it is, changed or renamed)? Keep this notice, copy NOTICE and say
 * "based on BlueOS by m89wppy" with the link in your project's description. See LICENSE.
 */

package org.blueos.arona

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewConfiguration
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.math.Vector2
import com.esotericsoftware.spine.Animation
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.Event
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonData
import com.esotericsoftware.spine.android.AndroidAtlasAttachmentLoader
import com.esotericsoftware.spine.android.AndroidSkeletonDrawable
import com.esotericsoftware.spine.android.AndroidTextureAtlas
import com.esotericsoftware.spine.android.SkeletonRenderer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.badlogic.gdx.utils.FloatArray as GdxFloats

/**
 * 0.9 «Лобби»: the game's memorial lobbies (Spine 4.2, the user's own files) as a full-screen interactive scene —
 * the lock screen's mirror of Android's widget hub (spec: mockups/blueos-0.9-lobby.html).
 *
 * Files: [BlueOSMedia.ADDED]/lobby/<name>/<name>.skel + .atlas + pages. Some lobbies keep the room in a second
 * skeleton (<name>_background), drawn first with the same camera: either next to the main files or in a sibling
 * folder <name>_background/.
 */
object BlueOSLobbies {
    const val DIR = "lobby"
    private const val TAG = "BlueOSLobby"

    class Lobby(val name: String, val dir: File) {
        /**
         * The room in its own skeleton (its .skel; the .atlas has the same name), or null. The game names it
         * Hoshino_home_background, akari_bg or CH0060BG_home.
         */
        val background: File? get() {
            val base = name.removeSuffix("_home")
            // any letter case: Yuzu_home's room is yuzu_bg
            val want = listOf("${name}_background", "${base}_bg", "${base}BG_home").map { it.lowercase() }
            val dirs = listOf(dir) + (dir.parentFile?.listFiles { f -> f.isDirectory && f.name.lowercase() in want }.orEmpty())
            for (d in dirs) {
                val skel = d.listFiles()?.firstOrNull {
                    it.isFile && it.extension.equals("skel", true) && it.nameWithoutExtension.lowercase() in want
                } ?: continue
                if (File(d, skel.nameWithoutExtension + ".atlas").isFile) return skel
            }
            return null
        }
        /** Her name (SchaleDB): «CH0058_home» → «Hifumi (Swimsuit)»; unknown → «Shiroko_home» → «Shiroko». */
        val title: String get() = BlueOSLobbyNames.NAMES[voiceKey(name)]?.first
            ?: name.removeSuffix("_home").removeSuffix("_Home").replace('_', ' ')
        /** The game's music for this lobby (BGM id, «Theme_<id>»), 0 = unknown. */
        val bgm: Int get() = BlueOSLobbyNames.NAMES[voiceKey(name)]?.second ?: 0
    }

    /** Every lobby the user added (and any on /product), by name. */
    @JvmStatic
    fun list(): List<Lobby> {
        val out = LinkedHashMap<String, Lobby>()
        for (root in listOf("${BlueOSMedia.PRODUCT}/$DIR", "${BlueOSMedia.ADDED}/$DIR")) {
            File(root).listFiles { f -> f.isDirectory }?.forEach { d ->
                if (isBackground(d.name)) return@forEach
                if (File(d, "${d.name}.skel").isFile && File(d, "${d.name}.atlas").isFile) out.putIfAbsent(d.name, Lobby(d.name, d))
            }
        }
        return out.values.sortedBy { it.title.lowercase() }
    }

    /** A lobby's room skeleton, not a lobby of its own (see [Lobby.background]). */
    @JvmStatic
    fun isBackground(name: String) =
        name.endsWith("_background", true) || name.endsWith("_bg", true) || name.endsWith("BG_home", true)

    private val AUDIO = setOf("ogg", "wav", "mp3", "m4a", "opus", "flac")

    /** «Shiroko_home» → «shiroko»: whose voice lines (the importer's _voice/<key>/ folders). */
    @JvmStatic
    fun voiceKey(name: String) = name.lowercase().removeSuffix("_home").filter { it.isLetterOrDigit() }

    /**
     * A sound the lobby's animation asks for by [base] name (its Talk event's string, or an event's audio path without
     * folder and extension), any audio extension: in the lobby's own folder (and its sound/ voice/), then in the
     * importer's lobby/_voice/<key>/ and lobby/_music/.
     */
    @JvmStatic
    fun sound(lobby: Lobby, base: String): File? {
        // whose: the lobby's name, and the line's own prefix (Mashiro_swimsuit_home says CH0061_MemorialLobby_1;
        // the importer files voices by that prefix)
        val keys = listOfNotNull(voiceKey(lobby.name),
            base.substringBefore("_MemorialLobby", "").takeIf { it.isNotEmpty() }?.let(::voiceKey)).distinct()
        val dirs = listOf(lobby.dir, File(lobby.dir, "sound"), File(lobby.dir, "voice")) +
            listOf(BlueOSMedia.ADDED, BlueOSMedia.PRODUCT).flatMap { root ->
                keys.map { File("$root/$DIR/_voice/$it") } + File("$root/$DIR/_music")
            }
        val want = soundName(base)
        for (d in dirs) {
            d.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in AUDIO && soundName(it.nameWithoutExtension) == want }
                ?.let { return it }
        }
        return null
    }

    /**
     * The lobby's music by its number ([Lobby.bgm]): «Theme_12.ogg» as the game names it, or «BGM_12_….mp3» as some
     * lobby files do — next to the lobby or in the importer's lobby/_music/.
     */
    @JvmStatic
    fun tune(lobby: Lobby): File? {
        val id = lobby.bgm
        if (id <= 0) return null
        val rx = Regex("^(theme[ _]?0*$id|bgm[ _]?0*$id([ _].*)?)$", RegexOption.IGNORE_CASE)
        val dirs = listOf(lobby.dir, File(lobby.dir, "sound")) + listOf(BlueOSMedia.ADDED, BlueOSMedia.PRODUCT).map { File("$it/$DIR/_music") }
        for (d in dirs) {
            d.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in AUDIO && rx.matches(it.nameWithoutExtension) }
                ?.let { return it }
        }
        return null
    }

    /** Any letter case, no leading zeros: Nonomi's lobby asks for «Nonomi_MemorialLobby_01», the file is «…_1». */
    private fun soundName(s: String) = s.lowercase().replace(LEADING_ZEROS, "_$1")
    private val LEADING_ZEROS = Regex("_0+(\\d)")

    /** The chosen lobby (Secure [BlueOSSettings.LOBBY]); unset or gone = the first one. */
    @JvmStatic
    fun chosen(context: Context): Lobby? {
        val all = list()
        val want = Settings.Secure.getString(context.contentResolver, BlueOSSettings.LOBBY)
        return all.firstOrNull { it.name == want } ?: all.firstOrNull()
    }

    class Scene(val name: String, val atlas: AndroidTextureAtlas, val data: SkeletonData,
                val bgAtlas: AndroidTextureAtlas?, val bgData: SkeletonData?)

    /**
     * spine-android 4.2.12 draws [com.esotericsoftware.spine.BlendMode.multiply] like normal (its paint is SRC_OVER):
     * shadows and tints came out as a pale film. Android's MULTIPLY blend is the game's multiply over an opaque scene.
     */
    @JvmStatic
    fun fixBlend(atlas: AndroidTextureAtlas): AndroidTextureAtlas {
        for (t in atlas.textures) t.getPaint(com.esotericsoftware.spine.BlendMode.multiply)?.blendMode = android.graphics.BlendMode.MULTIPLY
        return atlas
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "BlueOSLobbyLoad").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    /** One scene at a time (~30–50 MB of bitmaps): the last one, until [release]. */
    private var cached: Scene? = null

    /** Main thread. [onReady] gets the scene on the main thread, or null if its files can't be read. */
    fun load(lobby: Lobby, onReady: (Scene?) -> Unit) {
        cached?.takeIf { it.name == lobby.name }?.let { onReady(it); return }
        io.execute {
            val t0 = SystemClock.elapsedRealtime()
            val s = try {
                val atlas = fixBlend(AndroidTextureAtlas.fromFile(File(lobby.dir, "${lobby.name}.atlas")))
                val data = SkeletonBinary(AndroidAtlasAttachmentLoader(atlas)).readSkeletonData(FileHandle(File(lobby.dir, "${lobby.name}.skel")))
                val bgSkel = lobby.background
                val bgAtlas = bgSkel?.let { fixBlend(AndroidTextureAtlas.fromFile(File(it.parentFile, it.nameWithoutExtension + ".atlas"))) }
                val bgData = if (bgSkel != null && bgAtlas != null)
                    SkeletonBinary(AndroidAtlasAttachmentLoader(bgAtlas)).readSkeletonData(FileHandle(bgSkel)) else null
                Scene(lobby.name, atlas, data, bgAtlas, bgData)
            } catch (t: Throwable) {
                Log.e(TAG, "Can't load the lobby ${lobby.name} from ${lobby.dir}", t)
                null
            }
            Log.i(TAG, "loaded ${lobby.name} in ${SystemClock.elapsedRealtime() - t0} ms" +
                (s?.let { " (${it.data.slots.size} slots${it.bgData?.let { b -> " + ${b.slots.size} background" } ?: ""})" } ?: ""))
            main.post { if (s != null) cached = s; onReady(s) }
        }
    }

    /** Lets the scene's bitmaps go (nobody shows the lobby any more). */
    fun release() { cached = null }
}

/**
 * The lobby itself: a portrait window into the landscape scene, centred on her face, with the game's touches —
 * a finger held or moved = she looks at it (Look_01, Touch_Eye_Key), on her head = pat (Pat_01, Touch_Point_Key),
 * release = LookEnd / PatEnd, a short tap = one of her lines (Talk_NN_A body + Talk_NN_M mouth). [enter] plays
 * Start_Idle_01 once. Draws only while attached, shown and [active].
 *
 * Debug: `setprop log.tag.BlueOSLobby DEBUG` prints the frame cost every 2 s.
 */
class BlueOSLobbyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), Choreographer.FrameCallback {

    /** The host shows the lobby (it's on the screen): only then the scene is animated and drawn. */
    var active = true
        set(value) { field = value; updateRunning() }

    /** Called once the scene is loaded (null = no lobby or broken files). */
    var onLoaded: ((BlueOSLobbies.Lobby?, Boolean) -> Unit)? = null

    private var lobby: BlueOSLobbies.Lobby? = null
    private var main: AndroidSkeletonDrawable? = null
    private var bg: AndroidSkeletonDrawable? = null
    private val renderer = SkeletonRenderer()
    private var idle = "Idle_01"
    private var intro: String? = null
    private var talks = emptyList<String>()
    private var mouths = emptyMap<String, String?>()
    private var extras = emptyList<String>()
    private var extraWait = FloatArray(0)
    private var blink: String? = null
    private var blinkWait = 0f
    private var live = false
    private var canPat = false
    private var canLook = false
    private var eyeKey: String? = null
    private var pointKey: String? = null
    private var eyeBone: String? = null
    private var pointBone: String? = null
    private val bounds = RectF()
    private var focusX = 0f
    private var focusY: Float? = null

    // settings (re-read by [readSettings])
    private var zoom = 1f
    private var pan = 0f
    private var tiltOn = true
    private var fps = 60
    /** 0 silent, 1 her voice, 2 voice and the lobby's music ([BlueOSSettings.LOBBY_SOUND]) */
    private var soundMode = 0
    /** her greeting on opening: 0 every time, 1 only after an hour away, 2 never ([BlueOSSettings.LOBBY_INTRO]) */
    private var introMode = 1
    /** a finger came down during her greeting: she turns to it once the greeting is over */
    private var pending = false
    /** 0 landscape (turn the phone), 1 as the phone is held, 2 portrait ([BlueOSSettings.LOBBY_ORIENT]) */
    private var orient = 0

    // landscape: the scene is drawn turned by [rot] degrees (0, 90 or -90) in the portrait lock screen
    private var rot = 0f
    /** the phone's orientation (OrientationEventListener degrees; -1 unknown): which way it is turned */
    private var orientDeg = -1
    /** The scene is turned sideways. */
    val sideways get() = rot != 0f
    /** How the scene is turned on the portrait screen: 0, 90 or -90 degrees clockwise (the host turns its clock so). */
    val turn get() = rot
    /** The screen as the scene sees it: turned sideways in landscape. */
    private val lw get() = if (rot != 0f) height else width
    private val lh get() = if (rot != 0f) width else height
    private val orientation by lazy {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                orientDeg = o
                val r = wantedRot()
                if (r != rot) { rot = r; mode = AronaView.NONE; pending = false; invalidate() }
            }
        }
    }

    /**
     * Which way to draw: turned the phone's left side up (sensor 90°) → the scene turned −90°, right side up (270°,
     * the usual landscape) → +90°. Landscape mode stays sideways while the phone is upright or flat (it opens turned
     * for the usual landscape); «as held» goes back to portrait when the phone is upright.
     */
    private fun wantedRot(): Float {
        if (orient == 2) return 0f
        val d = orientDeg
        return when {
            d in 60..120 -> -90f
            d in 240..300 -> 90f
            orient == 1 && d >= 0 && (d < 30 || d > 330) -> 0f
            orient == 1 -> if (d < 0) 0f else rot
            else -> if (rot != 0f) rot else 90f
        }
    }

    /** The biggest rectangle the scene paints completely in her idle pose: the frame never leaves it (no black). */
    private val safe = RectF()

    // camera: centre + size in Spine units (y up)
    private var camX = 0f
    private var camY = 0f
    private var camW = 1f
    private var camH = 1f

    private var running = false
    private var lastFrame = 0L
    private var acc = 0f
    private var releaseMotion: (() -> Unit)? = null

    private var mode = AronaView.NONE
    private var targetX = 0f
    private var targetY = 0f
    private val off = Vector2()
    private val tmp = Vector2()

    init {
        readSettings()
    }

    /** Loads [l] (null = the chosen one). */
    fun show(l: BlueOSLobbies.Lobby? = BlueOSLobbies.chosen(context)) {
        lobby = l
        if (l == null) { main = null; bg = null; updateRunning(); invalidate(); onLoaded?.invoke(null, false); return }
        BlueOSLobbies.load(l) { s ->
            if (lobby !== l) return@load
            if (s == null) { onLoaded?.invoke(l, false); return@load }
            start(s)
            onLoaded?.invoke(l, true)
        }
    }

    /** Lets the scene go (the lock screen is gone): nothing drawn, no sound, its bitmaps can be freed. */
    fun clear() {
        lobby = null
        main?.animationState?.clearListeners()
        main = null
        bg = null
        stopVoice(); stopMusic()
        updateRunning()
        invalidate()
    }

    fun readSettings() {
        val cr = context.contentResolver
        zoom = BlueOSSettings.int(cr, BlueOSSettings.LOBBY_ZOOM).coerceIn(100, 180) / 100f
        pan = BlueOSSettings.int(cr, BlueOSSettings.LOBBY_PAN).coerceIn(-100, 100) / 100f
        tiltOn = BlueOSSettings.int(cr, BlueOSSettings.LOBBY_TILT) != 0
        fps = if (BlueOSSettings.int(cr, BlueOSSettings.LOBBY_FPS) == 30) 30 else 60
        soundMode = BlueOSSettings.int(cr, BlueOSSettings.LOBBY_SOUND).coerceIn(0, 2)
        introMode = BlueOSSettings.int(cr, BlueOSSettings.LOBBY_INTRO).coerceIn(0, 2)
        orient = BlueOSSettings.int(cr, BlueOSSettings.LOBBY_ORIENT).coerceIn(0, 2)
        rot = wantedRot()
        if (soundMode == 2 && running && musicPlayer == null) startTune()
        if (soundMode < 2) stopMusic()
        if (soundMode < 1) stopVoice()
        if (running) updateMotion()
        invalidate()
    }

    private fun start(s: BlueOSLobbies.Scene) {
        val d = AndroidSkeletonDrawable(s.atlas, s.data)
        val data = s.data
        fun has(n: String) = data.findAnimation(n) != null
        // the game's own animations only (not the artists' «Dev/…» folders)
        val names = data.animations.map { it.name }.filter { '/' !in it }
        idle = listOf("Idle_01", "Idle_00", "Idle", "idle").firstOrNull(::has)
            ?: names.firstOrNull { it.contains("idle", true) && !it.contains("touch", true) && !it.contains("start", true) && !it.endsWith("_R", true) }
            ?: names.firstOrNull() ?: ""
        intro = "Start_$idle".takeIf(::has) ?: names.firstOrNull { it.startsWith("Start_Idle", true) }   // CH0264: Idle + Start_Idle_01
        talks = names.filter { Regex("^Talk_\\d+_A$").matches(it) }
        // a line's mouth: Talk_01_M, or in a folder (talk1/Talk_01_M — 27 lobbies)
        mouths = talks.associateWith { a ->
            val m = a.removeSuffix("_A") + "_M"
            data.animations.firstOrNull { it.name == m }?.name ?: data.animations.firstOrNull { it.name.endsWith("/$m", true) }?.name
        }
        // what the game plays now and then over her idle pose: «…_R» (ears, sweat, light, snow, butterflies, a
        // variation of her pose) and her blink (Eye_Close_01)
        extras = names.filter { it.endsWith("_R", true) && it != idle }
        blink = names.firstOrNull { it.equals("Eye_Close_01", true) } ?: names.firstOrNull { it.startsWith("Eye_Close", true) }
        extraWait = FloatArray(extras.size) { 1f + (Math.random() * 4).toFloat() }
        blinkWait = 2f
        d.animationStateData.defaultMix = 0.2f
        d.skeleton.setToSetupPose()
        d.animationState.addListener(sounds)
        stopVoice(); stopMusic()
        main = d
        // the touch rig: anchors Touch_Point (head) / Touch_Eye (face) and the key bones the game moves toward the
        // finger. Some are misspelled (Touch_Point_Kye — 9 lobbies), the newest have no Touch_Eye_Key (20 lobbies):
        // looking then is the Look animation alone; patting needs the head's key bone.
        val bones = data.bones.map { it.name }
        fun key(prefix: String) = bones.firstOrNull { it.equals("${prefix}_Key", true) } ?: bones.firstOrNull { it.lowercase().startsWith("${prefix}_k") }
        eyeKey = key("touch_eye")
        pointKey = key("touch_point")
        eyeBone = bones.firstOrNull { it.equals("Touch_Eye", true) }
        pointBone = bones.firstOrNull { it.equals("Touch_Point", true) }
        canPat = pointKey != null && pointBone != null
        canLook = eyeBone != null || eyeKey != null
        live = canPat || canLook
        val b = boundsOf(data, idle) ?: RectF(data.x, data.y, data.x + data.width, data.y + data.height)
        bounds.set(b)
        val her = RectF(b)                               // her own skeleton (the room's may reach much farther)
        focusX = bounds.centerX()
        focusY = null
        var fy = bounds.centerY()
        if (live) {                                       // where her face is in the idle pose
            val probe = Skeleton(data)
            posed(probe, data, idle, 0f)
            pointBone?.let { probe.findBone(it) }?.let { focusX = it.worldX; fy = it.worldY }
            (eyeBone ?: pointBone)?.let { probe.findBone(it) }?.let { focusY = it.worldY; fy = it.worldY }
        }
        bg = if (s.bgAtlas != null && s.bgData != null) AndroidSkeletonDrawable(s.bgAtlas, s.bgData).also { b2 ->
            b2.skeleton.setToSetupPose()
            bgIdle(s.bgData)?.let { b2.animationState.setAnimation(0, it, true) }
        } else null
        s.bgData?.let { bd -> boundsOf(bd, bgIdle(bd) ?: "")?.let { bounds.union(it) } }
        safe.setEmpty()
        try { painted(s, RectF(bounds), focusX, fy, her)?.let { safe.set(it) } } catch (e: Exception) { Log.w(TAG, "can't measure ${s.name}", e) }
        Log.i(TAG, "${s.name}: scene ${bounds.toShortString()}, painted ${safe.toShortString()}, live=$live, idle=$idle, " +
            "intro=$intro, talks=${talks.size} mouths=${mouths.values.count { it != null }}, extras=$extras, blink=$blink, " +
            "pat=$canPat($pointKey) look=$canLook($eyeKey)")
        mode = AronaView.NONE
        off.set(0f, 0f)
        updateRunning()
        enter()                                           // after: the intro's sound events need her running
        invalidate()
    }

    /**
     * The lobby came on the screen: her greeting once (Start_Idle_01), then the idle loop. The greeting is skipped
     * when [introMode] says so (never, or this lobby was on the screen less than an hour ago); its music still starts.
     */
    fun enter() {
        val d = main ?: return
        val st = d.animationState
        st.clearTracks()
        d.skeleton.setSlotsToSetupPose()                  // a greeting cut short leaves e.g. Shiroko's left eye hidden
        mode = AronaView.NONE; pending = false
        val i = intro
        val name = lobby?.name
        val now = SystemClock.elapsedRealtime()
        val last = name?.let { seenAt[it] }
        val skip = when (introMode) { 0 -> false; 2 -> true; else -> last != null && now - last < HOUR_MS }
        if (running && name != null) seenAt[name] = now
        if (i != null && !skip) { st.setAnimation(0, i, false); st.addAnimation(0, idle, true, 0f) }
        else {
            // the greeting skipped: the idle loop from the setup pose — where the game is after the greeting too
            // (mixing out, what only the greeting moved goes back to its setup pose: Yuuka (Track) ends it zoomed in)
            d.skeleton.setToSetupPose()
            if (idle.isNotEmpty()) st.setAnimation(0, idle, true)
            if (i != null) introMusic(i)
        }
        startTune()
        rot = wantedRot()
        step(0f)
    }

    /** The lobby's music starts in its greeting (Hoshino): with the greeting skipped, start it here. */
    private fun introMusic(anim: String) {
        val a = main?.skeleton?.data?.findAnimation(anim) ?: return
        for (t in a.timelines) if (t is Animation.EventTimeline) for (e in t.events) {
            val p = e.data.audioPath?.takeIf { it.isNotBlank() } ?: continue
            val base = p.substringAfterLast('/').substringBeforeLast('.')
            if (base.contains("bgm", true)) { music(base); return }
        }
    }

    /** Her greeting is still playing (or fading into the idle loop): she doesn't react to touches yet. */
    private fun inIntro(): Boolean {
        val i = intro ?: return false
        val e = main?.animationState?.getCurrent(0) ?: return false
        return e.animation.name == i || e.mixingFrom?.animation?.name == i
    }

    /** [sk] in the pose of [anim] at [t] (from the setup pose). */
    private fun posed(sk: Skeleton, data: SkeletonData, anim: String?, t: Float) {
        sk.setToSetupPose()
        anim?.let { data.findAnimation(it) }?.apply(sk, 0f, t, false, null, 1f, Animation.MixBlend.setup, Animation.MixDirection.`in`)
        sk.updateWorldTransform(Skeleton.Physics.reset)
    }

    private fun bgIdle(d: SkeletonData) =
        listOf("Idle_01", "Idle_00").firstOrNull { d.findAnimation(it) != null } ?: d.animations.firstOrNull()?.name

    /**
     * Draws the idle pose (room + her) small, over [b], and finds the biggest rectangle around her face ([fx], [fy])
     * that it paints completely (alpha ≥ 200 in every cell): the room she is in, not a far piece of the scene
     * (Hoshino's whale shark, Akari's sushi belt). Null when that is less than a fifth of her own skeleton [her] (a
     * scene on black, like CH9999's panels: then the whole scene is shown).
     */
    private fun painted(s: BlueOSLobbies.Scene, b: RectF, fx: Float, fy: Float, her: RectF): RectF? {
        if (b.width() <= 0f || b.height() <= 0f) return null
        val cols = 192
        val rows = (cols * b.height() / b.width()).toInt().coerceIn(16, 512)
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.scale(cols / b.width(), -rows / b.height())
            c.translate(-b.left, -b.bottom)
            fun draw(data: SkeletonData, anim: String?) {
                val sk = Skeleton(data)
                posed(sk, data, anim, 0f)
                renderer.renderToCanvas(c, renderer.render(sk))
            }
            s.bgData?.let { draw(it, bgIdle(it)) }
            draw(s.data, idle)
            val px = IntArray(cols * rows)
            bmp.getPixels(px, 0, cols, 0, 0, cols, rows)
            // the largest rectangle of painted cells that holds her face's cell (fc, fr): per row, the painted run
            // above each column is a histogram; from her column it grows toward the higher side while it still
            // reaches up to her row
            val fc = ((fx - b.left) / b.width() * cols).toInt().coerceIn(0, cols - 1)
            val fr = ((b.bottom - fy) / b.height() * rows).toInt().coerceIn(0, rows - 1)
            val hgt = IntArray(cols)
            var best = 0; var bl = 0; var br = 0; var bt = 0; var bb = 0
            for (row in 0 until rows) {
                for (col in 0 until cols) hgt[col] = if ((px[row * cols + col] ushr 24) >= 200) hgt[col] + 1 else 0
                if (row < fr) continue
                val need = row - fr + 1
                if (hgt[fc] < need) continue
                var l = fc; var r = fc; var mn = hgt[fc]
                while (true) {
                    val area = mn * (r - l + 1)
                    if (area > best) { best = area; bl = l; br = r + 1; bb = row + 1; bt = row + 1 - mn }
                    val left = if (l > 0) hgt[l - 1] else -1
                    val right = if (r < cols - 1) hgt[r + 1] else -1
                    val next = max(left, right)
                    if (next < need) break
                    if (left >= right) l-- else r++
                    mn = min(mn, next)
                }
            }
            val sx = b.width() / cols
            val sy = b.height() / rows
            // her skeleton may be huge too (Akari: the sushi belt); a usual room is about 3600 x 2400
            if (best * sx * sy < min(her.width() * her.height(), 3600f * 2400f) / 5) return null
            // half a cell in: the edge cells are only partly painted
            return RectF(b.left + (bl + .5f) * sx, b.bottom - (bb - .5f) * sy, b.left + (br - .5f) * sx, b.bottom - (bt + .5f) * sy)
        } finally {
            bmp.recycle()
        }
    }

    /** Bounds of every visible attachment over the idle animation (6 samples), on a scratch skeleton. */
    private fun boundsOf(data: SkeletonData, anim: String): RectF? {
        val sk = Skeleton(data)
        val a = data.findAnimation(anim)
        val o = Vector2()
        val size = Vector2()
        val temp = GdxFloats()
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        val dur = a?.duration ?: 0f
        for (i in 0..6) {
            posed(sk, data, a?.name, dur * i / 6f)
            for (slot in sk.slots) if (slot.color.a < 0.02f) slot.attachment = null
            sk.getBounds(o, size, temp)
            if (size.x > 0 && o.x.isFinite()) {
                x0 = min(x0, o.x); y0 = min(y0, o.y); x1 = max(x1, o.x + size.x); y1 = max(y1, o.y + size.y)
            }
        }
        return if (x1 > x0) RectF(x0, y0, x1, y1) else null
    }

    // ---------------------------------------------------------------- camera

    /**
     * The frame fills the screen and stays inside [safe] (what the scene really paints — Hoshino's fish and Akari's
     * sushi belt reach far past the room): as much of the scene as the screen's shape allows, [zoom] closer, around
     * her face. Portrait shows the full height and about a third of the width, landscape about the full width.
     */
    private fun frameCamera() {
        val W = lw; val H = lh
        if (W == 0 || H == 0) return
        val r = if (safe.width() > 0f) safe else bounds
        val bw = r.width()
        val bh = r.height()
        val aspect = W.toFloat() / H
        val w = min(bw, bh * aspect) / zoom
        val h = w / aspect
        val room = (bw - w) / 2f
        val cx0 = r.centerX()
        var x = focusX + pan * room + tiltNow() * 0.12f * w
        x = x.coerceIn(cx0 - room, cx0 + room)
        val roomY = (bh - h) / 2f
        var y = r.centerY()
        val fy = focusY
        if (fy != null && roomY > 0) y = (fy - h * 0.12f).coerceIn(r.top + h / 2f, r.bottom - h / 2f)   // face a little above the middle
        camX = x; camY = y; camW = w; camH = h
    }

    /** −1…1: looking around by tilting. Sideways the phone's own tilt axes are the wrong ones: how far it is turned from straight. */
    private fun tiltNow(): Float {
        if (!tiltOn) return 0f
        if (rot == 0f) return BlueOSMotion.tilt[0]
        val d = orientDeg.takeIf { it >= 0 } ?: return 0f
        val dev = (if (rot > 0f) d - 270 else d - 90).toFloat()
        return (dev / 30f).coerceIn(-1f, 1f) * (if (rot > 0f) 1f else -1f)
    }

    private fun worldX(px: Float) = camX + (px / lw - 0.5f) * camW
    private fun worldY(py: Float) = camY - (py / lh - 0.5f) * camH

    /** A touch on the screen → where it is on the (maybe turned) scene, in [lw]×[lh] pixels. */
    private val pt = FloatArray(2)
    private fun logical(x: Float, y: Float): FloatArray {
        val dx = x - width / 2f; val dy = y - height / 2f
        when {
            rot > 0f -> { pt[0] = dy + lw / 2f; pt[1] = -dx + lh / 2f }
            rot < 0f -> { pt[0] = -dy + lw / 2f; pt[1] = dx + lh / 2f }
            else -> { pt[0] = x; pt[1] = y }
        }
        return pt
    }

    // ---------------------------------------------------------------- her touches

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (main == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = e.eventTime; downX = e.x; downY = e.y; moved = false
                val p = logical(e.x, e.y)
                herDown(worldX(p[0]), worldY(p[1]))
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > slop) moved = true
                val p = logical(e.x, e.y)
                targetX = worldX(p[0]); targetY = worldY(p[1])
            }
            MotionEvent.ACTION_UP -> herUp(!moved && e.eventTime - downAt < 300)
            MotionEvent.ACTION_CANCEL -> herUp(false)
        }
        return true
    }

    private fun herDown(wx: Float, wy: Float) {
        val d = main ?: return
        if (!live) return
        // during the greeting her eyes are closed / hidden (Shiroko's left eye is empty): looking at the finger
        // then showed a half-open eye or none at all. Wait for the greeting to end.
        if (inIntro()) { pending = true; targetX = wx; targetY = wy; return }
        val head = pointBone?.let { d.skeleton.findBone(it) }
        val onHead = canPat && head != null && hypot(wx - head.worldX, wy - head.worldY) < 260f
        if (!onHead && !canLook) return
        mode = if (onHead) AronaView.PAT else AronaView.LOOK
        targetX = wx; targetY = wy
        val n = if (onHead) "Pat_01" else "Look_01"
        play(TRACK_LOOK, "${n}_A", true)
        play(TRACK_LOOK + 1, "${n}_M", true)
    }

    private fun herUp(quickTap: Boolean) {
        val d = main ?: return
        val st = d.animationState
        pending = false
        if (inIntro()) return                             // no lines over her greeting
        if (live && mode != AronaView.NONE) {
            val n = if (mode == AronaView.PAT) "PatEnd_01" else "LookEnd_01"
            play(TRACK_LOOK, "${n}_A", false); st.addEmptyAnimation(TRACK_LOOK, 0.2f, 0f)
            play(TRACK_LOOK + 1, "${n}_M", false); st.addEmptyAnimation(TRACK_LOOK + 1, 0.2f, 0f)
            mode = AronaView.NONE
        }
        // a short tap: one of her lobby lines (Talk_NN_A = body, _M = mouth)
        if (quickTap && talks.isNotEmpty() && st.getCurrent(TRACK_TALK) == null) {
            val a = talks.random()
            st.setAnimation(TRACK_TALK, a, false); st.addEmptyAnimation(TRACK_TALK, 0.3f, 0f)
            mouths[a]?.let { m -> st.setAnimation(TRACK_TALK + 1, m, false); st.addEmptyAnimation(TRACK_TALK + 1, 0.3f, 0f) }
        }
    }

    /**
     * Over her idle pose, as the game does: each «…_R» now and then (an ear twitch, sweat, light, snow, a
     * variation of her pose) after a short random pause, and a blink every 2.5–6 s. Not during her greeting.
     */
    private fun extrasStep(dt: Float, st: AnimationState) {
        if (inIntro()) return
        for (i in extras.indices) {
            val track = TRACK_EXTRAS + i
            if (track >= TRACK_LOOK) break
            if (st.getCurrent(track) != null) continue
            extraWait[i] -= dt
            if (extraWait[i] > 0f) continue
            val e = st.setAnimation(track, extras[i], false)
            st.addEmptyAnimation(track, 0.3f, 0f)
            extraWait[i] = 1f + (Math.random() * 5).toFloat()
            e.mixDuration = 0.3f
        }
        val b = blink ?: return
        if (st.getCurrent(TRACK_BLINK) != null) return
        blinkWait -= dt
        if (blinkWait > 0f) return
        st.setAnimation(TRACK_BLINK, b, false)
        st.addEmptyAnimation(TRACK_BLINK, 0.1f, 0f)
        blinkWait = 2.5f + (Math.random() * 3.5).toFloat()
    }

    private fun play(track: Int, name: String, loop: Boolean) {
        val d = main ?: return
        if (d.skeleton.data.findAnimation(name) != null) d.animationState.setAnimation(track, name, loop)
        else d.animationState.setEmptyAnimation(track, 0.1f)
    }

    // ---------------------------------------------------------------- animation

    private fun step(dt: Float) {
        val d = main ?: return
        val sk = d.skeleton
        val st = d.animationState
        if (pending && !inIntro()) { pending = false; herDown(targetX, targetY) }   // the finger is still down
        extrasStep(dt, st)
        st.update(dt)
        st.apply(sk)
        sk.update(dt)
        sk.updateWorldTransform(Skeleton.Physics.update)
        if (live) {
            var dx = 0f; var dy = 0f
            val pat = mode == AronaView.PAT
            if (mode != AronaView.NONE) {
                val anchor = (if (pat) pointBone else eyeBone ?: pointBone)?.let { sk.findBone(it) }
                if (anchor != null) {
                    dx = targetX - anchor.worldX; dy = targetY - anchor.worldY
                    val r = if (pat) 120f else 160f
                    val len = hypot(dx, dy)
                    if (len > r) { dx *= r / len; dy *= r / len }
                    if (pat) dy *= 0.35f
                }
            }
            val k = 1f - exp(-dt * 10f)
            off.x += (dx - off.x) * k; off.y += (dy - off.y) * k
            for (name in listOfNotNull(eyeKey, pointKey)) {
                val b = sk.findBone(name) ?: continue
                val p = b.parent ?: continue
                val use = mode == AronaView.NONE || name == (if (pat) pointKey else eyeKey)
                tmp.set(p.worldX + (if (use) off.x else 0f), p.worldY + (if (use) off.y else 0f))
                p.worldToLocal(tmp)
                b.x = tmp.x; b.y = tmp.y
            }
            sk.updateWorldTransform(Skeleton.Physics.pose)
        }
        bg?.let { b ->
            b.animationState.update(dt)
            b.animationState.apply(b.skeleton)
            b.skeleton.update(dt)
            b.skeleton.updateWorldTransform(Skeleton.Physics.update)
        }
    }

    // ---------------------------------------------------------------- sound (as in the game: the animation's events)

    /**
     * The game's lobbies say when a line starts: a «Talk» event whose string is the voice file's name
     * (Shiroko_MemorialLobby_1), at the moment her mouth starts moving in Talk_NN_M / Start_Idle_01. Some also carry
     * events with an audio path: the lobby's music (BGM_…) at the start, the voice again (skipped: Talk has it).
     */
    private val sounds = object : AnimationState.AnimationStateAdapter() {
        override fun event(entry: AnimationState.TrackEntry, event: Event) {
            val data = event.data
            if (data.name == "Talk") {
                event.string?.takeIf { it.isNotBlank() }?.let { voice(it) }
                return
            }
            val path = data.audioPath?.takeIf { it.isNotBlank() } ?: return
            val base = path.substringAfterLast('/').substringBeforeLast('.')
            when {
                base.contains("bgm", true) -> music(base)
                base.contains("memoriallobby", true) -> {}
                else -> voice(base)
            }
        }
    }

    private var voicePlayer: MediaPlayer? = null
    private var musicPlayer: MediaPlayer? = null
    private var musicName: String? = null

    /** Sound only while she is on the screen, with the ringer on (not silent / vibrate). */
    private fun canSound(): Boolean {
        if (!running) return false
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    private fun player(f: File, speech: Boolean) = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(if (speech) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build())
        setDataSource(f.path)
        prepare()
    }

    private fun voice(base: String) {
        if (soundMode < 1 || !canSound()) return
        val l = lobby ?: return
        val f = BlueOSLobbies.sound(l, base) ?: run { Log.i(TAG, "no voice file $base"); return }
        stopVoice()
        voicePlayer = try {
            player(f, speech = true).apply {
                setOnCompletionListener { if (voicePlayer === it) stopVoice() }
                start()
            }
        } catch (e: Exception) { Log.w(TAG, "can't play ${f.name}", e); null }
    }

    /** An event asks for the lobby's music by its file name (only Hoshino's do); its number finds it too. */
    private fun music(base: String) {
        val l = lobby ?: return
        if (soundMode < 2 || !canSound()) return
        val f = BlueOSLobbies.sound(l, base) ?: BlueOSLobbies.tune(l) ?: run { Log.i(TAG, "no music file $base"); return }
        playMusic(f)
    }

    /**
     * The lobby's own music, as in the game: by its number (SchaleDB's MemoryLobbyBGM → «Theme_<id>»), looping, from
     * the moment she comes on the screen — most lobbies don't name it in their animation.
     */
    private fun startTune() {
        val l = lobby ?: return
        if (soundMode < 2 || !canSound()) return
        val f = BlueOSLobbies.tune(l) ?: run { if (l.bgm > 0) Log.i(TAG, "no music file for ${l.name}: Theme_${l.bgm}"); return }
        playMusic(f)
    }

    private fun playMusic(f: File) {
        if (musicName.equals(f.path, true) && musicPlayer != null) return      // already playing (Talk_NN_M starts it again)
        val am = context.getSystemService(AudioManager::class.java)
        if (am?.isMusicActive == true && musicPlayer == null) return            // the user's own music wins
        stopMusic()
        musicPlayer = try {
            player(f, speech = false).apply { isLooping = true; setVolume(MUSIC_VOLUME, MUSIC_VOLUME); start() }
        } catch (e: Exception) { Log.w(TAG, "can't play ${f.name}", e); null }
        musicName = f.path
    }

    private fun stopVoice() {
        voicePlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        voicePlayer = null
    }

    private fun stopMusic() {
        musicPlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        musicPlayer = null
        musicName = null
    }

    // frame cost, for `setprop log.tag.BlueOSLobby DEBUG`
    private var statAt = 0L
    private var statFrames = 0
    private var statStepNs = 0L
    private var statDrawNs = 0L

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val dt = if (lastFrame == 0L) 0f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = frameTimeNanos
        acc += dt
        if (acc < 1f / fps - 0.002f) return
        val t0 = System.nanoTime()
        step(acc.coerceAtMost(0.05f))
        statStepNs += System.nanoTime() - t0
        acc = 0f
        statFrames++
        invalidate()
        val now = SystemClock.uptimeMillis()
        if (statAt == 0L) statAt = now
        if (now - statAt >= 2000) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                val n = max(1, statFrames)
                Log.d(TAG, String.format(java.util.Locale.US, "frame: %.1f fps, step %.2f ms, draw %.2f ms (UI thread), %dx%d",
                    statFrames * 1000f / (now - statAt), statStepNs / 1e6f / n, statDrawNs / 1e6f / n, width, height))
            }
            statAt = now; statFrames = 0; statStepNs = 0; statDrawNs = 0
        }
    }

    private fun updateRunning(detaching: Boolean = false) {
        val want = !detaching && isAttachedToWindow && isShown && active && main != null
        if (want == running) return
        running = want
        if (want) {
            lastFrame = 0L; acc = 0f; statAt = 0L
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            Choreographer.getInstance().removeFrameCallback(this)
            stopVoice(); stopMusic()                          // she is off the screen: quiet at once
            lobby?.name?.let { seenAt[it] = SystemClock.elapsedRealtime() }   // «not opened for an hour» counts from here
        }
        updateMotion()
    }

    private var orientOn = false

    private fun updateMotion() {
        val want = running && tiltOn
        if (want && releaseMotion == null) releaseMotion = BlueOSMotion.acquire(context)
        else if (!want) { releaseMotion?.invoke(); releaseMotion = null }
        // which way the phone is turned: only while she is on the screen and the setting can turn the scene
        val o = running && orient != 2
        if (o != orientOn) {
            orientOn = o
            if (o && orientation.canDetectOrientation()) orientation.enable() else orientation.disable()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { updateRunning(detaching = true); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateRunning() }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val d = main ?: return
        val t0 = System.nanoTime()
        frameCamera()
        val s = lw / camW
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        if (rot != 0f) canvas.rotate(rot)
        canvas.scale(s, -s)
        canvas.translate(-camX, -camY)
        bg?.let { renderer.renderToCanvas(canvas, renderer.render(it.skeleton)) }
        renderer.renderToCanvas(canvas, renderer.render(d.skeleton))
        canvas.restore()
        statDrawNs += System.nanoTime() - t0
    }

    companion object {
        private const val TAG = "BlueOSLobby"
        // tracks: 0 her idle (and greeting), then the «…_R» extras, over them looking / patting, her lines, the blink
        private const val TRACK_EXTRAS = 1
        private const val TRACK_LOOK = 20
        private const val TRACK_TALK = 22
        private const val TRACK_BLINK = 24
        private const val MUSIC_VOLUME = 0.45f
        private const val HOUR_MS = 3_600_000L
        /** When each lobby was last on the screen (this process; a reboot = she greets again). */
        private val seenAt = HashMap<String, Long>()
    }
}
