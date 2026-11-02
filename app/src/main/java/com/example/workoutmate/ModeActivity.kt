package com.example.workoutmate

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

class ModeActivity : AppCompatActivity() {

    private lateinit var targetPanel: View
    private lateinit var targetValue: TextView
    private lateinit var minusButton: MaterialButton
    private lateinit var plusButton: MaterialButton
    private val modeButtons = mutableMapOf<Int, MaterialButton>()

    private var selectedMode = MODE_FREE
    private var targetIndex = 0
    private var kickerBaseTopMargin = 0
    private var aboutBaseTopMargin = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        // The liquid background is a normal View. Keep the window/decor
        // transparent so it can extend under the status bar on API 24+.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        setContentView(R.layout.activity_mode)
        findViewById<View>(R.id.modeRoot).setBackgroundColor(Color.TRANSPARENT)

        val modeKicker = findViewById<View>(R.id.modeKicker)
        val aboutButton = findViewById<View>(R.id.aboutButton)
        kickerBaseTopMargin =
            (modeKicker.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        aboutBaseTopMargin =
            (aboutButton.layoutParams as ViewGroup.MarginLayoutParams).topMargin

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.modeRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The liquid background extends under the transparent status bar;
            // only the title content is pushed below the status bar.
            view.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            (modeKicker.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                kickerBaseTopMargin + systemBars.top
            (aboutButton.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                aboutBaseTopMargin + systemBars.top
            insets
        }

        val modeFree = findViewById<MaterialButton>(R.id.modeFree)
        val modeMinute = findViewById<MaterialButton>(R.id.modeMinute)
        val modeTarget = findViewById<MaterialButton>(R.id.modeTarget)
        targetPanel = findViewById(R.id.targetPanel)
        targetValue = findViewById(R.id.targetValue)
        minusButton = findViewById(R.id.minusButton)
        plusButton = findViewById(R.id.plusButton)
        val enterButton = findViewById<MaterialButton>(R.id.enterButton)

        modeButtons[R.id.modeFree] = modeFree
        modeButtons[R.id.modeMinute] = modeMinute
        modeButtons[R.id.modeTarget] = modeTarget
        modeFree.setOnClickListener { selectMode(MODE_FREE) }
        modeMinute.setOnClickListener { selectMode(MODE_MINUTE) }
        modeTarget.setOnClickListener { selectMode(MODE_TARGET) }
        selectMode(MODE_FREE)

        minusButton.setOnClickListener { changeTarget(-1) }
        plusButton.setOnClickListener { changeTarget(1) }

        findViewById<MaterialButton>(R.id.aboutButton).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        enterButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_MODE, selectedMode)
                    .putExtra(EXTRA_TARGET, TARGET_VALUES[targetIndex])
            )
        }

        updateTargetValue()
        animateIn()
    }

    private fun selectMode(mode: Int) {
        selectedMode = mode
        val selectedId = when (mode) {
            MODE_MINUTE -> R.id.modeMinute
            MODE_TARGET -> R.id.modeTarget
            else -> R.id.modeFree
        }
        modeButtons.forEach { (id, button) ->
            button.isSelected = id == selectedId
        }
        targetPanel.visibility =
            if (selectedMode == MODE_TARGET) View.VISIBLE else View.GONE
    }

    private fun animateIn() {
        val views = listOf(
            findViewById<View>(R.id.modeKicker),
            findViewById<View>(R.id.modeTitle),
            findViewById<View>(R.id.modeSubtitle),
            findViewById<View>(R.id.modeGroup),
            findViewById<View>(R.id.enterButton)
        )
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 22f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 55L)
                .setDuration(460L)
                .start()
        }
    }

    private fun changeTarget(delta: Int) {
        targetIndex = (targetIndex + delta).coerceIn(0, TARGET_VALUES.lastIndex)
        updateTargetValue()
    }

    private fun updateTargetValue() {
        targetValue.text = TARGET_VALUES[targetIndex].toString()
        minusButton.isEnabled = targetIndex > 0
        plusButton.isEnabled = targetIndex < TARGET_VALUES.lastIndex
        minusButton.alpha = if (minusButton.isEnabled) 1f else 0.35f
        plusButton.alpha = if (plusButton.isEnabled) 1f else 0.35f
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TARGET = "extra_target"

        const val MODE_FREE = 0
        const val MODE_MINUTE = 1
        const val MODE_TARGET = 2

        val TARGET_VALUES = intArrayOf(100, 300, 500, 1000, 2000)
    }
}
