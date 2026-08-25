/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import java.io.File

class DefaultFontProvider : FontProviderApi {
    private companion object {
        const val CONFIG_READ_RETRY_DELAY_MS = 1_000L
    }

    private data class FontConfig(
        val paths: Map<String, List<String>>,
        val fontsDir: File
    )

    @Volatile
    private var cachedFontConfig: FontConfig? = null
    @Volatile
    private var fontConfigRead = false
    private var nextFontConfigReadAt = 0L
    private val cachedFontTypefaceMap = mutableMapOf<String, Typeface?>()
    private val cachedTypefaceByPaths = mutableMapOf<List<String>, Typeface?>()
    private val retryableFontKeys = mutableSetOf<String>()
    @Volatile
    private var cachedFontSizeMap: MutableMap<String, Float>? = null
    @Volatile
    private var isLoading = false
    private var preloadPending = false
    private var cacheGeneration = 0L
    private val preloadCallbacks = mutableListOf<(MutableMap<String, Typeface?>) -> Unit>()

    override fun clearCache() {
        synchronized(this) {
            cacheGeneration++
            if (isLoading) preloadPending = true
            cachedFontConfig = null
            fontConfigRead = false
            nextFontConfigReadAt = 0L
            cachedFontTypefaceMap.clear()
            cachedTypefaceByPaths.clear()
            retryableFontKeys.clear()
            cachedFontSizeMap = null
            // The in-flight worker will observe the generation change before publishing.
        }
    }

    /**
     * Preload fonts asynchronously to avoid blocking UI thread.
     * Call this when keyboard is about to show.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        val generation = synchronized(this) {
            if (isLoading) {
                onComplete?.let(preloadCallbacks::add)
                preloadPending = true
                return
            }
            if ((fontConfigRead && retryableFontKeys.isEmpty()) ||
                SystemClock.elapsedRealtime() < nextFontConfigReadAt
            ) return
            onComplete?.let(preloadCallbacks::add)
            isLoading = true
            cacheGeneration
        }

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val (activeGeneration, config) = fontConfigSnapshot()
                val keys = config?.paths?.keys?.filterNot { it.endsWith("_size") }.orEmpty()
                if (activeGeneration != generation) return@Thread
                cacheFontSizes(config, activeGeneration)
                config?.let { fontConfig ->
                    keys.forEach { key ->
                        loadTypeface(key, fontConfig, activeGeneration)
                    }
                }
                val completion = synchronized(this) {
                    if (cacheGeneration != generation) {
                        null
                    } else {
                        preloadCallbacks.toList().also { preloadCallbacks.clear() } to
                            cachedFontTypefaceMap.toMutableMap()
                    }
                }
                if (completion != null) {
                    val (callbacks, fonts) = completion
                    Log.i(
                        "FcitxColdStart",
                        "font preload keys=${keys.size} duration=${SystemClock.elapsedRealtime() - startedAt}ms"
                    )
                    callbacks.forEach { it(fonts) }
                }
            } finally {
                val retry = synchronized(this) {
                    val shouldRetry = preloadPending && cacheGeneration != generation
                    preloadPending = false
                    isLoading = false
                    shouldRetry
                }
                if (retry) preloadFontsAsync()
            }
        }, "FcitxFontPreload").start()
    }

    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            requestPreloadIfNeeded()
            return synchronized(this) { cachedFontTypefaceMap.toMutableMap() }
        }

    override fun resolveTypeface(key: String, current: Typeface?): Typeface {
        val resolved = synchronized(this) {
            cachedFontTypefaceMap[key]
                ?: cachedFontTypefaceMap["font"]
        }
        if (resolved != null) return resolved
        requestPreloadIfNeeded()
        return current ?: Typeface.DEFAULT
    }

    private fun requestPreloadIfNeeded() {
        val needsPreload = synchronized(this) {
            (!fontConfigRead || retryableFontKeys.isNotEmpty()) &&
                !isLoading && SystemClock.elapsedRealtime() >= nextFontConfigReadAt
        }
        if (needsPreload) preloadFontsAsync()
    }

    private fun fontConfigSnapshot(): Pair<Long, FontConfig?> {
        val generation = synchronized(this) {
            cachedFontConfig?.let { return cacheGeneration to it }
            cacheGeneration
        }
        val loaded = readFontConfig()
        return synchronized(this) {
            if (cacheGeneration != generation) {
                cacheGeneration to null
            } else {
                val config = cachedFontConfig ?: loaded
                if (config != null) {
                    cachedFontConfig = config
                    fontConfigRead = true
                    nextFontConfigReadAt = 0L
                } else {
                    fontConfigRead = false
                    nextFontConfigReadAt = SystemClock.elapsedRealtime() + CONFIG_READ_RETRY_DELAY_MS
                }
                generation to config
            }
        }
    }

    private fun readFontConfig(): FontConfig? {
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot().getOrNull() ?: return null
        val fontsDir = snapshot.file?.parentFile ?: return null
        return FontConfig(snapshot.value, fontsDir)
    }

    private fun loadTypeface(key: String, config: FontConfig, generation: Long): Typeface? {
        val configuredPaths = config.paths[key].orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val validPaths = configuredPaths
            .map { File(config.fontsDir, it) }
            .filter(File::exists)
            .map(File::getAbsolutePath)

        if (configuredPaths.isNotEmpty() && validPaths.isEmpty()) {
            synchronized(this) {
                if (cacheGeneration == generation) {
                    retryableFontKeys.add(key)
                    nextFontConfigReadAt = SystemClock.elapsedRealtime() + CONFIG_READ_RETRY_DELAY_MS
                }
            }
            return null
        }

        synchronized(this) {
            if (cacheGeneration != generation) return null
            if (cachedFontTypefaceMap.containsKey(key)) return cachedFontTypefaceMap[key]
            if (cachedTypefaceByPaths.containsKey(validPaths)) {
                val cached = cachedTypefaceByPaths[validPaths]
                cachedFontTypefaceMap[key] = cached
                return cached
            }
        }

        val startedAt = SystemClock.elapsedRealtime()
        val loaded = runCatching {
            when {
                validPaths.isEmpty() -> null
                validPaths.size == 1 || android.os.Build.VERSION.SDK_INT < 29 ->
                    Typeface.createFromFile(validPaths.first())
                else -> buildCustomFallbackTypeface(validPaths)
            }
        }.getOrNull()

        synchronized(this) {
            if (cacheGeneration != generation) return null
            cachedTypefaceByPaths[validPaths] = loaded
            cachedFontTypefaceMap[key] = loaded
            retryableFontKeys.remove(key)
            if (retryableFontKeys.isEmpty()) nextFontConfigReadAt = 0L
        }
        if (validPaths.isNotEmpty()) {
            Log.i(
                "FcitxColdStart",
                "font key=$key files=${validPaths.size} loaded=${loaded != null} " +
                    "duration=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }
        return loaded
    }

    @androidx.annotation.RequiresApi(29)
    private fun buildCustomFallbackTypeface(
        validPaths: List<String>
    ): Typeface {
        val firstFamily = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(File(validPaths[0])).build()
        ).build()
        val builder = android.graphics.Typeface.CustomFallbackBuilder(firstFamily)
        for (i in 1 until validPaths.size) {
            val family = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(File(validPaths[i])).build()
            ).build()
            builder.addCustomFallback(family)
        }
        return builder.build()
    }

    override val fontSizeMap: MutableMap<String, Float>
        get() {
            cachedFontSizeMap?.let { return it }
            requestPreloadIfNeeded()
            return mutableMapOf()
        }

    private fun cacheFontSizes(config: FontConfig?, generation: Long) {
        val sizes = config?.paths
            ?.filterKeys { it.endsWith("_size") }
            ?.mapValues { (_, values) ->
                values.firstOrNull()?.trim()?.toFloatOrNull()?.coerceIn(8f, 72f)
            }
            ?.filterValues { it != null }
            ?.mapValues { it.value!! }
            ?.toMutableMap()
            ?: mutableMapOf()
        synchronized(this) {
            if (cacheGeneration == generation) cachedFontSizeMap = sizes
        }
    }
}
