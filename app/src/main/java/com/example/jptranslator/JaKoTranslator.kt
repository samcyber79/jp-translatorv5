package com.example.jptranslator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** ML Kit 기기 내 번역 (일본어 → 한국어). 결과는 LRU 캐시에 저장. */
class JaKoTranslator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    @Volatile private var modelReady = false

    suspend fun ensureModel() {
        if (modelReady) return
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        modelReady = true
    }

    suspend fun translate(text: String): String {
        synchronized(cache) { cache[text] }?.let { return it }
        ensureModel()
        val normalized = text.replace(Regex("[\\r\\n]+"), " ").trim()
        val out = translator.translate(normalized).await()
        val polished = polishNames(out)
        synchronized(cache) { cache[text] = polished }
        return polished
    }

    /**
     * 이전 대사를 함께 번역해 주어 생략된 주어/어미의 문맥을 보완한다.
     * ML Kit은 지시문을 이해하는 LLM이 아니므로, 앞 문장의 번역이
     * 결과의 접두부로 식별되는 경우에만 문맥 번역을 사용한다.
     * 식별 실패 시 단독 번역으로 되돌아가 다른 말풍선의 문장이 섞이지 않도록 한다.
     */
    suspend fun translateWithContext(current: String, previous: String?): String {
        val now = current.replace(Regex("[\\r\\n]+"), " ").trim()
        val before = previous?.replace(Regex("[\\r\\n]+"), " ")?.trim().orEmpty()
        if (before.isBlank() || now.isBlank() || before == now ||
            before.length > 130 || now.length > 180) return translate(current)
        val key = "context:$before\u0001$now"
        synchronized(cache) { cache[key] }?.let { return it }
        val fallback = translate(current)
        return try {
            val prefix = translate(before).trim().trimEnd('.', '。', '!', '?', '！', '？')
            val together = translator.translate("$before。 $now").await().trim()
            val candidate = if (together.startsWith(prefix) && prefix.length >= 3) {
                together.substring(prefix.length).trimStart(' ', '.', '。', '!', '?', '！', '？', ':', '：', '、', ',')
            } else ""
            val result = if (candidate.isNotBlank() && candidate.length <= fallback.length * 3 + 20) {
                polishNames(candidate)
            } else fallback
            synchronized(cache) { cache[key] = result }
            result
        } catch (_: Exception) { fallback }
    }

    // Conservative name correction: replace only complete names, not common words.
    private fun polishNames(text: String): String {
        var result = text
        for ((variant, official) in NAME_FIXES) result = result.replace(variant, official)
        return result
    }

    fun close() = translator.close()

    companion object {
        private val NAME_FIXES = linkedMapOf(
            "하쿠레이 레이무" to "하쿠레이 레이무",
            "키리사메 마리사" to "키리사메 마리사",
            "동방 로스트 워드" to "동방 로스트워드",
            "학원 아이돌 마스터" to "학원 아이돌 마스터"
        )
        /** 일본어·한국어 모델이 모두 기기에 있는지 */
        suspend fun isModelDownloaded(): Boolean {
            val mgr = RemoteModelManager.getInstance()
            val ja = TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()
            val ko = TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build()
            return mgr.isModelDownloaded(ja).await() && mgr.isModelDownloaded(ko).await()
        }
    }
}
