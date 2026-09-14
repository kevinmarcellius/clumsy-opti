package com.example.codexlimits

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class DiagnosticsActivity : Activity() {
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        logText = findViewById(R.id.diagnostic_log)
        logText.setTextIsSelectable(true)

        findViewById<Button>(R.id.reload_log).setOnClickListener { showLog() }

        findViewById<Button>(R.id.copy_log).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Codex widget diagnostics", DiagnosticLog.read(this)))
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_log).setOnClickListener {
            DiagnosticLog.clear(this)
            showLog()
        }
        findViewById<Button>(R.id.back_to_app).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        showLog()
    }

    private fun showLog() {
        logText.text = DiagnosticLog.read(this)
    }
}
