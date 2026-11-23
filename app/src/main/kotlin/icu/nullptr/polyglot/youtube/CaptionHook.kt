package icu.nullptr.polyglot.youtube

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.util.SparseArray
import android.view.View
import icu.nullptr.polyglot.captions.BilingualFormatter
import icu.nullptr.polyglot.captions.CaptionCue
import icu.nullptr.polyglot.captions.CaptionLanguageState
import icu.nullptr.polyglot.captions.CaptionLineMatcher
import icu.nullptr.polyglot.captions.CaptionSession
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.TranslationManager
import icu.nullptr.polyglot.util.hook
import icu.nullptr.polyglot.util.logD
import icu.nullptr.polyglot.util.logI
import icu.nullptr.polyglot.util.logV
import icu.nullptr.polyglot.util.logW
import icu.nullptr.polyglot.util.toMethod
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

object CaptionHook : BaseHook {
    override val name = "CaptionHook"
    override val totalHooks = 4

    private val session = CaptionSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rendererStates = WeakHashMap<Any, RendererState>()
    private val rendererSequence = AtomicLong(0L)
    private val sessionGeneration = AtomicLong(0L)
    private val applyingTranslatedText = ThreadLocal.withInitial { false }

    override fun install(dexkit: DexKitBridge): Int {
        var installed = 0

        if (installTimelineBuildHook(dexkit)) {
            installed++
        }

        if (installOverlayUpdateHooks(dexkit)) {
            installed++
        }

        if (installRenderTextHooks(dexkit)) {
            installed++
        }

        if (installCaptionLanguageHooks(dexkit)) {
            installed++
        }

        logI(name, "Installed $installed caption hook(s)")
        return installed
    }

    fun formatCaption(original: CharSequence?): CharSequence {
        val translated = session.translationFor(original?.toString())
        return BilingualFormatter.format(original, translated)
    }

    private fun installTimelineBuildHook(dexkit: DexKitBridge): Boolean {
        val method = dexkit.findCaptionTimelineBuildMethod()
            ?.toMethod() ?: run {
            logW(name, "Caption timeline builder not found")
            return false
        }

        hook(method) { chain ->
            val result = chain.proceed()
            if (module.config.enabled) {
                val newCues = observeTimeline(result)
                requestTranslations(newCues, source = "timeline")
                logObservedCueCount(newCues.size, source = "timeline")
            }
            result
        }

        logD(name, "Hooked caption timeline builder: ${method.shortName()}")
        return true
    }

    private fun installRenderTextHooks(dexkit: DexKitBridge): Boolean {
        val methods = dexkit.findCaptionRenderTextMethods()
            .map { it.toMethod() }
            .filter { method ->
                View::class.java.isAssignableFrom(method.declaringClass) &&
                        method.declaringClass.hasInstanceFieldAssignableTo(Editable::class.java)
            }
            .distinctBy { it.stableId() }

        var installed = 0
        for (method in methods) {
            hook(method) { chain ->
                if (applyingTranslatedText.get() == true) {
                    return@hook chain.proceed()
                }

                if (!module.config.enabled) {
                    return@hook chain.proceed()
                }

                val thisObject = chain.thisObject ?: return@hook chain.proceed()
                if (!thisObject.javaClass.hasInstanceFieldAssignableTo(Editable::class.java)) {
                    return@hook chain.proceed()
                }

                val original = chain.getArg(0) as? CharSequence
                    ?: return@hook chain.proceed()
                val normalizedOriginal = CaptionCue.clean(original.toString())
                rememberRendererState(thisObject, method, normalizedOriginal)

                if (session.observeRenderedText(original)) {
                    val length = normalizedOriginal.length
                    logV(name, "Observed rendered caption text, length=$length")
                }

                session.translatedCueContaining(normalizedOriginal)?.let { translation ->
                    val cue = CaptionCue.clean(translation.original)
                    val position = CaptionLineMatcher.lineStart(cue, normalizedOriginal)
                    if (position != null && position + normalizedOriginal.length == cue.length) {
                        // Last line: render the merged bilingual block here so the
                        // block stays aligned with the bottom of the caption.
                        val replacement = BilingualFormatter.format(cue, translation.translated)
                        return@hook chain.proceed(arrayOf(replacement))
                    }
                    if (position != null) {
                        // Earlier line of a multi-line cue: hide it immediately.
                        // Waiting for the last line view to appear kept the head
                        // line visible until the next full re-render.
                        return@hook chain.proceed(arrayOf(""))
                    }
                }

                val formatted = formatCaption(original)
                if (formatted === original || formatted.toString() == original.toString()) {
                    chain.proceed()
                } else {
                    chain.proceed(arrayOf(formatted))
                }
            }

            logD(name, "Hooked caption renderer: ${method.shortName()}")
            installed++
        }

        if (installed == 0) {
            logW(name, "Caption renderer not found")
        }
        return installed > 0
    }

    private fun installCaptionLanguageHooks(dexkit: DexKitBridge): Boolean {
        val trackClassName = dexkit.findCaptionTrackClassName() ?: run {
            logW(name, "Caption track class not found")
            return false
        }

        val methods = dexkit.findCaptionTrackStateMethods(trackClassName)
            .map { it.toMethod() }
            .distinctBy { it.stableId() }

        if (methods.isEmpty()) {
            logW(name, "Caption language methods not found")
            return false
        }

        for (method in methods) {
            hook(method) { chain ->
                observeCaptionTrack(chain.getArg(0), "${method.shortName()} arg0")
                val result = chain.proceed()
                result
            }
        }

        logD(name, "Hooked caption language trackers: ${methods.size}")
        return true
    }

    private fun installOverlayUpdateHooks(dexkit: DexKitBridge): Boolean {
        val methods = dexkit.findCaptionOverlayUpdateMethods()
            .map { it.toMethod() }
            .filter { method ->
                View::class.java.isAssignableFrom(method.declaringClass) &&
                        method.declaringClass.hasInstanceFieldAssignableTo(SparseArray::class.java)
            }
            .distinctBy { it.stableId() }

        var installed = 0
        for (method in methods) {
            hook(method) { chain ->
                if (module.config.enabled) {
                    val newCues = observeCueList(chain.getArg(0))
                    requestTranslations(newCues, source = "overlay")
                    logObservedCueCount(newCues.size, source = "overlay")
                }
                chain.proceed()
            }

            logD(name, "Hooked caption overlay update: ${method.shortName()}")
            installed++
        }

        if (installed == 0) {
            logW(name, "Caption overlay update methods not found")
        }
        return installed > 0
    }

    private fun observeTimeline(result: Any?): List<CaptionCue> {
        if (result == null) return emptyList()

        val lists = result.instanceFieldValues()
            .filterIsInstance<List<*>>()
            .filter { it.isNotEmpty() }
        val texts = lists.firstOrNull { list -> list.any { it is CharSequence } } ?: return emptyList()
        val timeLists = lists.filter { list -> list.all { it is Number } }
        if (timeLists.size < 2) return emptyList()

        val (starts, ends) = orderedTimelineTimeLists(timeLists[0], timeLists[1])

        val size = minOf(starts.size, ends.size, texts.size)
        if (size == 0) return emptyList()

        val cues = ArrayList<CaptionCue>(size)
        for (index in 0 until size) {
            val text = texts[index] as? CharSequence ?: continue
            val normalized = CaptionCue.clean(text.toString())
            if (normalized.isEmpty()) continue

            cues.add(
                CaptionCue(
                    videoId = "",
                    startMs = (starts[index] as? Number)?.toLong() ?: CaptionCue.UNKNOWN_TIME_MS,
                    endMs = (ends[index] as? Number)?.toLong() ?: CaptionCue.UNKNOWN_TIME_MS,
                    text = normalized,
                ),
            )
        }
        return session.observeNewCues(cues)
    }

    private fun observeCueList(arg: Any?): List<CaptionCue> {
        val list = arg as? List<*> ?: return emptyList()
        val cues = list.mapNotNull { item ->
            item?.toCaptionCue()
        }
        session.updateActiveCues(cues)
        return session.observeNewCues(cues)
    }

    private fun Any.toCaptionCue(): CaptionCue? {
        val text = instanceFieldValues()
            .filterIsInstance<CharSequence>()
            .map { CaptionCue.clean(it.toString()) }
            .filter { it.isNotEmpty() }
            .maxByOrNull { it.length }
            ?: return null

        return CaptionCue(
            videoId = "",
            startMs = firstInstanceFieldValue<Long>() ?: CaptionCue.UNKNOWN_TIME_MS,
            endMs = CaptionCue.UNKNOWN_TIME_MS,
            text = text,
            windowId = firstInstanceFieldValue<Int>() ?: CaptionCue.UNKNOWN_WINDOW_ID,
        )
    }

    private fun orderedTimelineTimeLists(first: List<*>, second: List<*>): Pair<List<*>, List<*>> {
        val comparableSize = minOf(first.size, second.size)
        var firstBeforeSecond = 0
        var secondBeforeFirst = 0
        for (index in 0 until comparableSize) {
            val a = (first[index] as? Number)?.toLong() ?: continue
            val b = (second[index] as? Number)?.toLong() ?: continue
            if (a <= b) firstBeforeSecond++ else secondBeforeFirst++
        }
        return if (firstBeforeSecond >= secondBeforeFirst) {
            first to second
        } else {
            second to first
        }
    }

    private inline fun <reified T> Any.firstInstanceFieldValue(): T? =
        instanceFieldValues().firstNotNullOfOrNull { it as? T }

    private fun Any.instanceFieldValues(): List<Any?> {
        val values = ArrayList<Any?>()
        var clazz: Class<*>? = javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                values += field.getValueOrNull(this)
            }
            clazz = clazz.superclass
        }
        return values
    }

    private fun Field.getValueOrNull(instance: Any): Any? =
        runCatching {
            isAccessible = true
            get(instance)
        }.getOrNull()

    private fun Class<*>.hasInstanceFieldAssignableTo(type: Class<*>): Boolean {
        var clazz: Class<*>? = this
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) && type.isAssignableFrom(field.type)) {
                    return true
                }
            }
            clazz = clazz.superclass
        }
        return false
    }

    private fun logObservedCueCount(newCueCount: Int, source: String) {
        if (newCueCount <= 0) return

        val total = session.observedCueCount()
        if (total <= 5 || total % 25 == 0) {
            logV(name, "Observed $newCueCount new caption cue(s) from $source, total=$total")
        }
    }

    private fun requestTranslations(cues: List<CaptionCue>, source: String) {
        for (cue in cues) {
            requestTranslation(cue.text, source)
        }
    }

    private fun requestTranslation(text: String, source: String) {
        if (session.translationFor(text) != null) return

        val sourceLanguage = CaptionLanguageState.currentSourceLanguage()
        val generation = sessionGeneration.get()
        TranslationManager.enqueueCaption(
            text = text,
            context = "YouTube subtitle $source",
            sourceLanguage = sourceLanguage,
        ) { original, translated ->
            if (generation != sessionGeneration.get()) return@enqueueCaption

            session.putTranslation(original, translated)
            logV(name, "Translated caption from $source, sourceLanguage=$sourceLanguage, length=${original.length}")
            refreshVisibleRenderers(original, source)
        }
    }

    private fun observeCaptionTrack(track: Any?, source: String) {
        if (!CaptionLanguageState.updateFromCaptionTrack(track, source)) return

        sessionGeneration.incrementAndGet()
        TranslationManager.reset()
        session.clear()
        synchronized(rendererStates) {
            rendererStates.clear()
        }
        rendererSequence.set(0L)
    }

    private fun rememberRendererState(renderer: Any, method: Method, normalizedText: String) {
        if (normalizedText.isEmpty()) return

        synchronized(rendererStates) {
            rendererStates[renderer] = RendererState(
                method = method,
                normalizedText = normalizedText,
                sequence = rendererSequence.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun refreshVisibleRenderers(original: String, source: String) {
        val normalized = CaptionCue.clean(original)
        if (normalized.isEmpty()) return

        mainHandler.post {
            applyCueTranslation(normalized, source)
        }
    }

    /**
     * Merges the views of a multi-line cue into one bilingual block.
     *
     * Every renderer view whose text is a whole-word line of [normalized] is
     * collected from the full renderer history (no time window: the first line
     * is often rendered seconds before its translation arrives). The block is
     * written into the bottom-most line view and the other line views are
     * cleared, which merges the original lines without raising the caption.
     */
    private fun applyCueTranslation(normalized: String, source: String) {
        val formatted = formatCaption(normalized)
        if (formatted.toString() == normalized) return

        val now = System.currentTimeMillis()
        val snapshot = synchronized(rendererStates) {
            rendererStates.entries.map { (renderer, state) -> renderer to state }
        }
        if (snapshot.isEmpty()) return

        val candidates = ArrayList<CueLineCandidate>()
        for ((renderer, state) in snapshot) {
            val text = state.normalizedText
            if (text.isEmpty()) continue
            val position = if (text == normalized) {
                normalized.length
            } else {
                CaptionLineMatcher.lineStart(normalized, text) ?: continue
            }
            candidates += CueLineCandidate(renderer, state, position)
        }
        if (candidates.isEmpty()) return

        // The bottom-most line becomes the anchor; the newest view wins on ties.
        val anchor = candidates.maxWithOrNull(
            compareBy<CueLineCandidate> { it.position }
                .thenBy { it.state.updatedAtMs },
        ) ?: return

        for (candidate in candidates) {
            if (candidate.renderer === anchor.renderer) continue
            clearRenderer(candidate.renderer, candidate.state, source, normalized.length)
        }

        logV(
            name,
            "Merged caption lines from $source, views=${candidates.size}, " +
                "anchor=${anchor.position}, length=${normalized.length}",
        )
        invokeRenderer(anchor.renderer, anchor.state.method, formatted, source, normalized.length)
        markRendered(anchor.renderer, anchor.state.method, normalized, now)
    }

    private fun clearRenderer(
        renderer: Any,
        state: RendererState,
        source: String,
        originalLength: Int,
    ) {
        invokeRenderer(renderer, state.method, "", source, originalLength)
        synchronized(rendererStates) {
            rendererStates.remove(renderer)
        }
    }

    private fun markRendered(renderer: Any, method: Method, text: String, now: Long) {
        synchronized(rendererStates) {
            rendererStates[renderer] = RendererState(
                method = method,
                normalizedText = text,
                sequence = rendererSequence.incrementAndGet(),
                updatedAtMs = now,
            )
        }
    }

    private fun invokeRenderer(
        renderer: Any,
        method: Method,
        text: CharSequence,
        source: String,
        originalLength: Int,
    ) {
        runCatching {
            applyingTranslatedText.set(true)
            method.invoke(renderer, text)
        }.onSuccess {
            if (text.isNotEmpty()) {
                logV(name, "Refreshed visible caption from $source, length=$originalLength")
            }
        }.onFailure { e ->
            logW(name, "Unable to refresh visible caption", e)
        }.also {
            applyingTranslatedText.set(false)
        }
    }

    private fun Executable.shortName(): String =
        "${declaringClass.name}#$name(${parameterTypes.joinToString { it.simpleName }})"

    private fun Executable.stableId(): String =
        "${declaringClass.name}#$name#${parameterTypes.joinToString { it.name }}"

    private fun DexKitBridge.findCaptionTimelineBuildMethod(): MethodData? {
        val addLineMethod = findMethod {
            matcher {
                returnType("void")
                paramTypes("java.lang.CharSequence", "long", "long")
                usingEqStrings(NON_DECREASING_SUBTITLE_TIME_ERROR)
            }
        }.singleOrNull()

        val builderClass = addLineMethod?.declaredClass ?: return null
        val candidates = builderClass.methods.filter { method ->
            method.isMethod &&
                    method.paramCount == 0 &&
                    method.returnTypeName != "void" &&
                    method.returnType?.hasAtLeastListFields(count = 3) == true
        }

        return candidates.singleOrNull() ?: candidates.firstOrNull()
    }

    private fun ClassData.hasAtLeastListFields(count: Int): Boolean =
        fields.count { !Modifier.isStatic(it.modifiers) && it.typeName == "java.util.List" } >= count

    private fun ClassData.hasInstanceFieldTypeInHierarchy(typeName: String): Boolean {
        var clazz: ClassData? = this
        while (clazz != null) {
            if (clazz.fields.any { !Modifier.isStatic(it.modifiers) && it.typeName == typeName }) {
                return true
            }
            clazz = clazz.superClass
        }
        return false
    }

    private fun DexKitBridge.findCaptionRenderTextMethods(): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes("java.lang.CharSequence")
            }
        }.filter { method ->
            method.declaredClass?.hasInstanceFieldTypeInHierarchy(EDITABLE_TYPE) == true
            && !method.isConstructor
        }

    private fun DexKitBridge.findCaptionOverlayUpdateMethods(): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes("java.util.List")
            }
        }.filter { method ->
            method.declaredClass?.hasInstanceFieldTypeInHierarchy(SPARSE_ARRAY_TYPE) == true
            && !method.isConstructor
        }

    private fun DexKitBridge.findCaptionTrackClassName(): String? =
        findMethod {
            matcher {
                returnType("boolean")
                usingEqStrings(AUTO_TRANSLATE_CAPTIONS_OPTION)
            }
        }.singleOrNull { method -> method.isMethod && method.paramCount == 0 }
            ?.toMethod()
            ?.declaringClass
            ?.name

    private fun DexKitBridge.findCaptionTrackStateMethods(trackClassName: String): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes(trackClassName)
                usingEqStrings(MENU_ITEM_CAPTIONS_KEY)
            }
        }.filter { method ->
            method.isMethod &&
                    !Modifier.isAbstract(method.modifiers)
        }

    private const val NON_DECREASING_SUBTITLE_TIME_ERROR =
        "subtitles are not given in non-decreasing start time order"
    private const val AUTO_TRANSLATE_CAPTIONS_OPTION = "AUTO_TRANSLATE_CAPTIONS_OPTION"
    private const val MENU_ITEM_CAPTIONS_KEY = "menu_item_captions"
    private const val EDITABLE_TYPE = "android.text.Editable"
    private const val SPARSE_ARRAY_TYPE = "android.util.SparseArray"

    private data class RendererState(
        val method: Method,
        val normalizedText: String,
        val sequence: Long,
        val updatedAtMs: Long,
    )

    private data class CueLineCandidate(
        val renderer: Any,
        val state: RendererState,
        val position: Int,
    )
}
