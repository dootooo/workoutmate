package com.example.workoutmate

import android.os.Bundle
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * In-app compliance information: privacy, terms, permissions and third-party
 * notices. Keep this page consistent with the manifest and the release build.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_about)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version_format, versionName)

        findViewById<MaterialButton>(R.id.aboutBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
}
