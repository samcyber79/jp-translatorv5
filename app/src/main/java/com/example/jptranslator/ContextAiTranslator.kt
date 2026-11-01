package com.example.jptranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 선택형 Gemini 무료 티어 번역. API 키를 앱에 하드코딩하지 않는다. */
class ContextAiTranslator(private val context: Context) {
    private val cache = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 250
    }
    suspend fun translate(current: String, previous: String?, scene: String = ""): String? {
        val key = Prefs.aiKey(context).trim()
        if (!Prefs.aiEnabled(context) || key.isEmpty()) return null
        val mode = Prefs.persona(context)
        val cacheKey = "$mode|$previous|$scene|$current"
        synchronized(cache) { cache[cacheKey] }?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                val prompt = """당신은 일본어 게임·만화의 한국어 현지화 전문 번역가다.
규칙:
1. [번역 대상]의 일본어만 자연스러운 한국어로 번역한다. [화면 문맥]과 [직전 문장]은 참고용이며 출력하지 않는다.
2. 문맥에 맞게 생략된 주어, 지시어, 호칭을 해석하되 원문에 없는 사실·감정·설명을 추가하지 않는다.
3. 원문의 존댓말/반말, 문장 종류, 말끝의 뉘앙스와 감탄·주저를 최대한 보존한다. 화자를 모르면 성격이나 말투를 임의로 창작하지 않는다.
4. 게임 UI, 수치, 아이템명, 고유명사, 변수 및 기호를 보존한다. 널리 확립된 한국어 공식 명칭을 확실히 아는 경우만 사용한다.
5. 일본어 OCR 오류가 명백하고 화면 문맥으로 복원 가능한 경우에만 최소한으로 복원한다. 불확실하면 추측하지 않는다.
6. 번역문만 출력한다. 해설, 따옴표, 머리말, 마크다운은 쓰지 않는다.
작품 분류: $mode
[화면 문맥]
${scene.take(1400)}
[직전 문장]
${previous?.take(250) ?: "(없음)"}
[번역 대상]
${current.take(700)}"""
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent")
                val conn = (url.openConnection() as HttpURLConnection)
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3500
                    conn.readTimeout = 6500
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.doOutput = true
                    val payload = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 450))
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode !in 200..299) return@withContext null
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val out = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text").trim()
                    if (out.isBlank() || out.length > current.length * 4 + 80 || out.contains("[번역 대상]")) null else out.also {
                        synchronized(cache) { cache[cacheKey] = it }
                    }
                } finally { conn.disconnect() }
            }
        } catch (_: Exception) { null }
    }
}
