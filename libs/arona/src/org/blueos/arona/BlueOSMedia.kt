/*
 * SPDX-FileCopyrightText: 2026 m89wppy (BlueOS)
 * SPDX-License-Identifier: Apache-2.0
 *
 * Part of BlueOS by m89wppy: https://github.com/m89wppy/BlueOS
 * Reusing this code (as it is, changed or renamed)? Keep this notice, copy NOTICE and say
 * "based on BlueOS by m89wppy" with the link in your project's description. See LICENSE.
 */

package org.blueos.arona

import android.os.SystemClock
import java.io.File

/**
 * 0.8.1: where the characters' files are. A build made with the game's files has them on /product; a public build
 * (no files from the game) gets them from the user — Settings → «Файлы персонажа» (BlueOSAssetsActivity) copies them
 * to /data/misc/blueos in the same layout (sepolicy blueos_media_file: Settings writes, SystemUI, Launcher3 and
 * Wallpaper & style read). /product wins when both have a file.
 */
object BlueOSMedia {
    const val PRODUCT = "/product/media/blueos"
    const val ADDED = "/data/misc/blueos"

    /** [rel] on /product if [probe] (a file in it, or the folder itself) is there, else the user's copy. */
    @JvmStatic
    fun dir(rel: String, probe: String? = null): String =
        if (exists("$PRODUCT/$rel", probe)) "$PRODUCT/$rel" else "$ADDED/$rel"

    /** [probe] in [rel] on /product or in the user's copy. */
    @JvmStatic
    fun has(rel: String, probe: String): Boolean = exists("$PRODUCT/$rel", probe) || exists("$ADDED/$rel", probe)

    /** 0.9: the 3D chibis' folder under [ADDED] (Settings' importer puts every usable .glb of the user's here). */
    const val CHIBI = "chibi"

    /** 0.9: the user's chibi models, by name; names in [hidden] (without .glb) are left out. */
    @JvmStatic
    fun chibis(hidden: Set<String> = emptySet()): List<File> =
        (File("$ADDED/$CHIBI").listFiles { f -> f.isFile && f.name.endsWith(".glb", true) } ?: emptyArray())
            .filter { it.name.dropLast(4) !in hidden }
            .sortedBy { it.name.lowercase() }

    /** 0.9: at most this many chibis on the screen at once (each one costs: 3 ≈ 43 % of a core idle). */
    const val CHIBI_MAX = 5
    /** 0.9: how many are on the screen before the user picks (a whole collection is ~290 models). */
    const val CHIBI_FIRST = 3

    /**
     * 0.9: the chibis on the screen. [shown] = Secure blueos_chibi_shown (names without .glb, ';'); null = never
     * picked → the first [CHIBI_FIRST]. A new import adds nobody (the user: all 200-odd models came on at once).
     */
    @JvmStatic
    fun chibisShown(shown: String?): List<File> {
        val all = chibis()
        val want = shown?.split(';')?.filter { it.isNotBlank() }?.toSet() ?: return all.take(CHIBI_FIRST)
        return all.filter { it.name.dropLast(4) in want }.take(CHIBI_MAX)
    }

    // asked often (BlueOSSettings reads «is there a character» with every setting) — a file check every 2 s is plenty
    private val cache = HashMap<String, Pair<Long, Boolean>>()

    private fun exists(dir: String, probe: String?): Boolean {
        val key = "$dir/${probe ?: ""}"
        val now = SystemClock.uptimeMillis()
        synchronized(cache) { cache[key]?.let { (at, v) -> if (now - at < 2000) return v } }
        val v = if (probe == null) File(dir).isDirectory else File(dir, probe).isFile
        synchronized(cache) { cache[key] = now to v }
        return v
    }
}
