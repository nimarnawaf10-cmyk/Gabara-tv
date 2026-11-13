package com.gabara.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(64, 64, 64, 64)
        }

        val titleText = TextView(this).apply {
            text = "Gabara TV"
            textSize = 36f
            setTextColor(Color.parseColor("#FACC15"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitleText = TextView(this).apply {
            text = "مشاهدة جماعية لأهل منطقتك ضمن 5 كم"
            textSize = 18f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 48)
        }

        val statusText = TextView(this).apply {
            text = "✅ التطبيق يعمل بنجاح على جهازك!"
            textSize = 16f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val btnBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#E11D48"))
            cornerRadius = 32f
        }

        val testButton = Button(this).apply {
            text = "تجربة زر البث"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = btnBackground
            setPadding(48, 28, 48, 28)
            setOnClickListener {
                statusText.text = "🔥 ممتاز! جاهزون للمرحلة الجاية: رادار 5 كم"
                statusText.setTextColor(Color.parseColor("#4ADE80"))
            }
        }

        root.addView(titleText)
        root.addView(subtitleText)
        root.addView(statusText)
        root.addView(testButton)

        setContentView(root)
    }
}
