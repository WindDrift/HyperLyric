package com.lidesheng.hyperlyric.root.aitrans

import android.content.Context
import android.os.SystemClock
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class DetectedLanguage(
    val language: String,
    val languageTag: String,
    val confidence: Float,
    val secondConfidence: Float?,
    val hypothesisCount: Int
)

internal class SystemLanguageDetector {
    private companion object {
        const val TAG = "SystemLanguageDetector"
        const val DETECTION_TIMEOUT_MS = 2_000L
    }

    private val failureLogged = AtomicBoolean(false)

    @Volatile
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    suspend fun detect(text: String): DetectedLanguage? {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val localContext = context
            if (localContext == null) {
                logFailureOnce("context_unavailable", startedAt = startedAt)
                return null
            }

            val manager = localContext.getSystemService(TextClassificationManager::class.java)
            if (manager == null) {
                logFailureOnce("service_unavailable", startedAt = startedAt)
                return null
            }

            val classifier = manager.textClassifier
            if (classifier === TextClassifier.NO_OP) {
                logFailureOnce(
                    reason = "classifier_no_op",
                    classifierName = classifier.javaClass.name,
                    startedAt = startedAt
                )
                return null
            }

            val result = withTimeoutOrNull(DETECTION_TIMEOUT_MS) {
                runInterruptible {
                    classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
                }
            }
            if (result == null) {
                logFailureOnce(
                    reason = "timeout",
                    classifierName = classifier.javaClass.name,
                    startedAt = startedAt
                )
                return null
            }

            val hypotheses = (0 until result.localeHypothesisCount).map { index ->
                val locale = result.getLocale(index)
                locale to result.getConfidenceScore(locale)
            }.sortedByDescending { (_, confidence) -> confidence }
            val topHypothesis = hypotheses.firstOrNull()
            if (topHypothesis == null) {
                logFailureOnce(
                    reason = "no_hypotheses",
                    classifierName = classifier.javaClass.name,
                    startedAt = startedAt
                )
                return null
            }

            val (locale, confidence) = topHypothesis
            return DetectedLanguage(
                language = locale.language.lowercase(Locale.ROOT),
                languageTag = locale.toLanguageTag(),
                confidence = confidence,
                secondConfidence = hypotheses.getOrNull(1)?.second,
                hypothesisCount = hypotheses.size
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailureOnce(
                reason = "exception",
                startedAt = startedAt,
                throwable = e
            )
            return null
        }
    }

    private fun logFailureOnce(
        reason: String,
        classifierName: String? = null,
        startedAt: Long,
        throwable: Throwable? = null
    ) {
        if (!failureLogged.compareAndSet(false, true)) return
        val details = buildList {
            add("reason=$reason")
            classifierName?.let { add("classifier=$it") }
            add("elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        }.joinToString(", ")
        HookLogger.w(TAG, "系统语言识别不可用: $details", throwable)
    }
}
