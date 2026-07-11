package com.aiassist.overlay

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            Toast.makeText(this, "Bubble on hoyeche — apnar game/app e giye dekhun", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "Screen share allow na korle app kaj korbe na", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        val root = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }
        root.addView(layout)
        setContentView(root)

        layout.addView(sectionTitle("AI Overlay Assistant Setup"))

        // Provider selection
        layout.addView(label("AI Provider"))
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Gemini (free tier available)", "OpenAI-compatible (any provider)")
            )
            setSelection(if (prefs.provider == "gemini") 0 else 1)
        }
        layout.addView(providerSpinner)

        // API key
        layout.addView(label("API Key"))
        val apiKeyInput = EditText(this).apply {
            hint = "Paste your API key here"
            setText(prefs.apiKey)
        }
        layout.addView(apiKeyInput)

        // Base URL (for OpenAI-compatible)
        layout.addView(label("Base URL (only for OpenAI-compatible providers)"))
        val baseUrlInput = EditText(this).apply {
            hint = "https://api.openai.com/v1/chat/completions"
            setText(prefs.baseUrl)
        }
        layout.addView(baseUrlInput)

        // Model
        layout.addView(label("Model name"))
        val modelInput = EditText(this).apply {
            hint = "gemini-2.5-flash or gpt-4o-mini"
            setText(prefs.model)
        }
        layout.addView(modelInput)

        // Default command
        layout.addView(label("Default command (Ask button chapleI eta use hobe, mone thakbe)"))
        val commandInput = EditText(this).apply {
            setText(prefs.lastCommand)
        }
        layout.addView(commandInput)

        // Auto interval
        layout.addView(label("Auto-mode interval (seconds)"))
        val intervalInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.autoIntervalSeconds.toString())
        }
        layout.addView(intervalInput)

        val saveBtn = Button(this).apply { text = "Save Settings" }
        saveBtn.setOnClickListener {
            prefs.provider = if (providerSpinner.selectedItemPosition == 0) "gemini" else "openai_compatible"
            prefs.apiKey = apiKeyInput.text.toString().trim()
            prefs.baseUrl = baseUrlInput.text.toString().trim()
            prefs.model = modelInput.text.toString().trim()
            prefs.lastCommand = commandInput.text.toString().trim()
            prefs.autoIntervalSeconds = intervalInput.text.toString().toIntOrNull() ?: 5
            Toast.makeText(this, "Save hoyeche", Toast.LENGTH_SHORT).show()
        }
        layout.addView(saveBtn)

        layout.addView(sectionTitle("Start"))
        val startBtn = Button(this).apply { text = "Overlay Permission diye Start koro" }
        startBtn.setOnClickListener { requestPermissionsAndStart() }
        layout.addView(startBtn)

        val stopBtn = Button(this).apply { text = "Stop Overlay" }
        stopBtn.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Bondho kora hoyeche", Toast.LENGTH_SHORT).show()
        }
        layout.addView(stopBtn)
    }

    private fun requestPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Overlay permission diye abar 'Start' chapun", Toast.LENGTH_LONG).show()
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 32, 0, 16)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 16, 0, 4)
    }
}
