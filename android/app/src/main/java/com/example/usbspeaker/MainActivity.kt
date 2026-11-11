package com.example.usbspeaker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val bg = Color.parseColor("#14151B")
    private val card = Color.parseColor("#22242E")
    private val inner = Color.parseColor("#14151B")
    private val fg = Color.parseColor("#ECECF2")
    private val sub = Color.parseColor("#9AA0B0")
    private val accent = Color.parseColor("#4F9CFF")
    private val green = Color.parseColor("#39D98A")
    private val orange = Color.parseColor("#F0A830")
    private val red = Color.parseColor("#FF5A5A")

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var boostLabel: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun cardLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(card)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(sub)
        setPadding(0, 0, 0, dp(6))
    }

    // ---------- 이퀄라이저 정보 (임시 세션으로 기기 사양 조회) ----------

    private data class EqInfo(
        val bands: Int, val minMb: Int, val maxMb: Int,
        val freqsHz: IntArray, val presets: List<String>
    )

    private fun <T> withDummyEq(block: (Equalizer) -> T): T? = try {
        val at = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(4096)
            .build()
        try {
            val eq = Equalizer(0, at.audioSessionId)
            try { block(eq) } finally { eq.release() }
        } finally { at.release() }
    } catch (_: Exception) { null }

    private fun queryEqInfo(): EqInfo? = withDummyEq { eq ->
        val n = eq.numberOfBands.toInt()
        val r = eq.bandLevelRange
        EqInfo(
            bands = n,
            minMb = r[0].toInt(),
            maxMb = r[1].toInt(),
            freqsHz = IntArray(n) { eq.getCenterFreq(it.toShort()) / 1000 },
            presets = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
        )
    }

    private fun queryPresetLevels(preset: Int, bands: Int): IntArray? = withDummyEq { eq ->
        eq.usePreset(preset.toShort())
        IntArray(bands) { eq.getBandLevel(it.toShort()).toInt() }
    }

    private fun fmtHz(h: Int) = if (h >= 1000) "${(h + 500) / 1000}kHz" else "${h}Hz"

    private fun fmtDb(mb: Int): String {
        val db = mb / 100
        return if (db > 0) "+${db}dB" else "${db}dB"
    }

    // ---------- 화면 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "USB 스피커"
            textSize = 24f
            setTextColor(fg)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        })

        // 상태 카드
        val statusCard = cardLayout()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sub) }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) }
        }
        statusText = TextView(this).apply {
            text = SpeakerService.lastStatus
            textSize = 15f
            setTextColor(fg)
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        statusCard.addView(statusRow)
        root.addView(statusCard)

        // PIN 카드
        val connCard = cardLayout()
        connCard.addView(sectionTitle("PC 화면에 표시된 PIN"))
        val pinEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "6자리 숫자"
            setHintTextColor(sub)
            setText(prefs.getString("pin", ""))
            textSize = 24f
            letterSpacing = 0.3f
            setTextColor(accent)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = roundedBg(inner, 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("pin", s?.toString() ?: "").apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        connCard.addView(pinEdit)
        root.addView(connCard)

        // 설정 카드 (지연 모드 + 증폭)
        val setCard = cardLayout()
        setCard.addView(sectionTitle("지연 모드"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("저지연 (영상·반응속도 우선)", "균형 (권장)", "안정 (끊김 방지 우선)")
            )
            setSelection(prefs.getInt("mode", 1))
            background = roundedBg(inner, 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("mode", pos).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        setCard.addView(spinner)

        boostLabel = sectionTitle("소리 증폭: ${prefs.getInt("boost_db", 0)} dB").apply {
            setPadding(0, dp(14), 0, dp(6))
        }
        setCard.addView(boostLabel)
        val seek = SeekBar(this).apply {
            max = 10
            progress = prefs.getInt("boost_db", 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    boostLabel.text = "소리 증폭: $v dB"
                    prefs.edit().putInt("boost_db", v).apply()
                    SpeakerService.instance?.applyBoost(v * 100)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        setCard.addView(seek)
        root.addView(setCard)

        // 이퀄라이저 카드
        root.addView(buildEqCard(prefs))

        // 시작/중지 버튼
        toggleBtn = Button(this).apply {
            text = "시작"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                if (SpeakerService.lastState == SpeakerService.STATE_STOPPED) {
                    startForegroundService(Intent(this@MainActivity, SpeakerService::class.java))
                } else {
                    stopService(Intent(this@MainActivity, SpeakerService::class.java))
                }
            }
        }
        root.addView(toggleBtn)

        // 도움말
        root.addView(TextView(this).apply {
            text = "사용 순서\n" +
                "1. 폰을 USB-C 케이블로 PC에 연결\n" +
                "2. 설정 > 연결 > 모바일 핫스팟 및 테더링 > USB 테더링 켜기\n" +
                "3. PC에서 UsbSpeakerPC.exe 실행\n" +
                "4. PC 화면의 PIN 6자리를 위에 입력하고 [시작]\n\n" +
                "볼륨은 폰의 볼륨 버튼으로 조절하세요."
            textSize = 13f
            setTextColor(sub)
            setLineSpacing(0f, 1.2f)
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun buildEqCard(prefs: android.content.SharedPreferences): LinearLayout {
        val eqCard = cardLayout()
        val info = queryEqInfo()

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "이퀄라이저"
            textSize = 15f
            setTextColor(fg)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (info == null || info.bands == 0) {
            eqCard.addView(titleRow)
            eqCard.addView(TextView(this).apply {
                text = "이 기기에서는 이퀄라이저를 사용할 수 없습니다."
                textSize = 13f
                setTextColor(sub)
                setPadding(0, dp(6), 0, 0)
            })
            return eqCard
        }

        val eqSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("eq_on", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("eq_on", on).apply()
                SpeakerService.instance?.setEqEnabled(on)
            }
        }
        titleRow.addView(eqSwitch)
        eqCard.addView(titleRow)

        // 프리셋
        eqCard.addView(sectionTitle("프리셋").apply { setPadding(0, dp(10), 0, dp(6)) })
        var suppressPreset = true
        lateinit var bandSeeks: Array<SeekBar>
        lateinit var bandVals: Array<TextView>

        val presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("직접 조절") + info.presets
            )
            background = roundedBg(inner, 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        eqCard.addView(presetSpinner)

        // 밴드 슬라이더
        val stepCount = (info.maxMb - info.minMb) / 100
        bandSeeks = Array(info.bands) { SeekBar(this) }
        bandVals = Array(info.bands) { TextView(this) }

        for (i in 0 until info.bands) {
            val mb = prefs.getInt("eq_b$i", 0).coerceIn(info.minMb, info.maxMb)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = fmtHz(info.freqsHz[i])
                textSize = 12f
                setTextColor(sub)
                layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            bandSeeks[i].apply {
                max = stepCount
                progress = (mb - info.minMb) / 100
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                        val newMb = info.minMb + v * 100
                        bandVals[i].text = fmtDb(newMb)
                        if (fromUser) {
                            prefs.edit().putInt("eq_b$i", newMb).putInt("eq_preset", -1).apply()
                            if (presetSpinner.selectedItemPosition != 0) {
                                suppressPreset = true
                                presetSpinner.setSelection(0)
                            }
                            SpeakerService.instance?.applyEqBand(i, newMb)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.addView(bandSeeks[i])
            bandVals[i].apply {
                text = fmtDb(mb)
                textSize = 12f
                setTextColor(fg)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(bandVals[i])
            eqCard.addView(row)
        }

        // 프리셋 선택 → 슬라이더/저장값 갱신 + 재생 중이면 즉시 적용
        presetSpinner.setSelection(prefs.getInt("eq_preset", -1) + 1)
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressPreset) { suppressPreset = false; return }
                if (pos == 0) {
                    prefs.edit().putInt("eq_preset", -1).apply()
                    return
                }
                val preset = pos - 1
                prefs.edit().putInt("eq_preset", preset).apply()
                val levels = queryPresetLevels(preset, info.bands)
                if (levels != null) {
                    val e = prefs.edit()
                    for (i in 0 until info.bands) {
                        val mb = levels[i].coerceIn(info.minMb, info.maxMb)
                        e.putInt("eq_b$i", mb)
                        bandSeeks[i].progress = (mb - info.minMb) / 100
                        bandVals[i].text = fmtDb(mb)
                    }
                    e.apply()
                }
                SpeakerService.instance?.applyEqPreset(preset)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        eqCard.addView(TextView(this).apply {
            text = "재생 중에도 바로 적용됩니다. 스위치를 켜야 동작해요."
            textSize = 12f
            setTextColor(sub)
            setPadding(0, dp(8), 0, 0)
        })
        return eqCard
    }

    private fun render(state: Int, s: String) {
        statusText.text = s
        val color = when (state) {
            SpeakerService.STATE_PLAYING -> green
            SpeakerService.STATE_SEARCHING -> orange
            SpeakerService.STATE_ERROR -> red
            else -> sub
        }
        (statusDot.background as GradientDrawable).setColor(color)
        if (state == SpeakerService.STATE_STOPPED) {
            toggleBtn.text = "시작"
            toggleBtn.background = roundedBg(accent)
        } else {
            toggleBtn.text = "중지"
            toggleBtn.background = roundedBg(red)
        }
    }

    override fun onResume() {
        super.onResume()
        SpeakerService.statusListener = { st, s -> render(st, s) }
        render(SpeakerService.lastState, SpeakerService.lastStatus)
    }

    override fun onPause() {
        super.onPause()
        SpeakerService.statusListener = null
    }
}
