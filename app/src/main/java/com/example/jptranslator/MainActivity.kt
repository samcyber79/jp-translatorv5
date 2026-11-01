package com.example.jptranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var overlayBtn: Button
    private lateinit var notifBtn: Button
    private lateinit var modelBtn: Button
    private lateinit var startBtn: Button
    private var downloading = false

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenTranslateService::class.java)
                .setAction(ScreenTranslateService.ACTION_START)
                .putExtra(ScreenTranslateService.EXTRA_CODE, res.resultCode)
                .putExtra(ScreenTranslateService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "번역 버튼이 화면에 떠 있어요", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "화면 캡처를 허용해야 번역할 수 있어요", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        fun text(s: String, size: Float = 15f, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(top), 0, dp(4))
        }
        fun button(onClick: () -> Unit) = Button(this).apply {
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }

        col.addView(text("일본어 화면번역", 26f, bold = true))
        col.addView(text("어떤 앱 위에서든 일본어(가로쓰기·세로쓰기)를 한국어로 덮어 보여줍니다.", 14f))

        col.addView(text("준비", 18f, bold = true, top = 20))
        overlayBtn = button {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        col.addView(overlayBtn)
        notifBtn = button {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 33) col.addView(notifBtn)
        modelBtn = button { downloadModel() }
        col.addView(modelBtn)

        col.addView(text("설정", 18f, bold = true, top = 20))
        col.addView(Switch(this).apply {
            text = "세로쓰기 원문은 번역도 세로로 표시"
            isChecked = Prefs.verticalRender(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setVerticalRender(this@MainActivity, v) }
            setPadding(0, dp(8), 0, dp(8))
        })
        col.addView(Switch(this).apply {
            text = "시작할 때 실시간 모드 켜기"
            isChecked = Prefs.liveOnStart(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setLiveOnStart(this@MainActivity, v) }
            setPadding(0, dp(8), 0, dp(8))
        })
        col.addView(text("AI 문맥 번역 (선택, 무료 티어 한도 내)", 18f, bold = true, top = 18))
        col.addView(text("AI 사용 시 대사가 외부 서비스로 전송됩니다. API 키는 이 기기에만 저장됩니다. 무료 한도 초과/통신 실패 시 오프라인 번역으로 자동 전환합니다.", 12f))
        col.addView(Switch(this).apply {
            text = "AI 문맥 번역 사용"
            isChecked = Prefs.aiEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAiEnabled(this@MainActivity, v) }
        })
        val keyInput = EditText(this).apply {
            hint = "Gemini API 키 (무료 티어)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.aiKey(this@MainActivity))
        }
        col.addView(keyInput)
        col.addView(button { Prefs.setAiKey(this, keyInput.text.toString()); Toast.makeText(this, "API 키 저장됨", Toast.LENGTH_SHORT).show() }.apply { text = "API 키 저장" })
        col.addView(text("번역할 작품", 15f, top = 10))
        val choices = listOf("일반 만화", "학원 아이돌 마스터", "동방 로스트워드")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(choices.indexOf(Prefs.persona(this@MainActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    Prefs.setPersona(this@MainActivity, choices[position])
                }
            }
        }
        col.addView(spinner)
        col.addView(text("캐릭터별 정확한 말투 적용은 화자 인식과 캐릭터 사전이 추가로 필요합니다.", 12f))
        col.addView(text("※ 설정은 번역기를 다시 시작하면 적용돼요.", 12f))

        startBtn = button { onStartStop() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minHeight = dp(56)
        }
        (startBtn.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        col.addView(startBtn)

        col.addView(text("사용법", 18f, bold = true, top = 24))
        col.addView(text(
            "• 떠 있는 「訳」 버튼 탭 → 지금 화면 번역 (다시 탭하면 지움)\n" +
                "• 버튼 길게 누르기 → 실시간 모드 켜기/끄기 (테두리가 초록색)\n" +
                "   실시간 모드: 스크롤·페이지 넘김이 멈추면 자동으로 다시 번역\n" +
                "• 버튼 드래그 → 위치 이동\n" +
                "• 화면을 가로/세로로 돌려도 그대로 동작\n" +
                "• 끄기: 알림의 [종료] 또는 상태바의 화면 공유 중지",
            14f,
        ))
        col.addView(text(
            "번역은 기기 안에서 처리돼요(오프라인). 번역 모델은 처음 한 번만 받으면 됩니다.",
            12f, top = 12,
        ).apply { gravity = Gravity.START })

        return ScrollView(this).apply { addView(col) }
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        overlayBtn.text = if (overlayOk) "✓ 다른 앱 위에 표시 허용됨" else "1. 다른 앱 위에 표시 허용하기"
        overlayBtn.isEnabled = !overlayOk

        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            notifBtn.text = if (ok) "✓ 알림 허용됨" else "2. 알림 허용하기 (번역/종료 버튼용)"
            notifBtn.isEnabled = !ok
        }

        if (!downloading) {
            lifecycleScope.launch {
                val ok = try { JaKoTranslator.isModelDownloaded() } catch (_: Exception) { false }
                if (downloading) return@launch
                modelBtn.text = if (ok) "✓ 번역 모델 준비됨" else "3. 번역 모델 받기 (일본어·한국어, 약 60MB)"
                modelBtn.isEnabled = !ok
            }
        }

        startBtn.text = if (ScreenTranslateService.running) "번역기 종료" else "▶ 번역기 시작"
    }

    // ------------------------------------------------------------------ actions

    private fun downloadModel() {
        downloading = true
        modelBtn.isEnabled = false
        modelBtn.text = "번역 모델 받는 중… (Wi-Fi 권장)"
        lifecycleScope.launch {
            val t = JaKoTranslator()
            try {
                t.ensureModel()
                Toast.makeText(this@MainActivity, "번역 모델 준비 완료", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "다운로드 실패: 인터넷 연결을 확인하세요", Toast.LENGTH_LONG).show()
            } finally {
                t.close()
                downloading = false
                refresh()
            }
        }
    }

    private fun onStartStop() {
        if (ScreenTranslateService.running) {
            startService(Intent(this, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_STOP))
            startBtn.postDelayed({ refresh() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시'를 허용해 주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // "전체 화면" 공유로 고정 (앱 하나만 공유하면 좌표가 맞지 않음)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }
}
