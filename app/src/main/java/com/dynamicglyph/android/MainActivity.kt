package com.dynamicglyph.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Loads the reference tables, asks for the two permissions Android needs, and
 * starts or stops the capture service.
 *
 * Replaces the iOS SwiftUI setup flow. The tables are third-party data that is
 * not redistributed with this project, so the user has to supply them once; they
 * are copied into the app's own storage so this is not a per-launch chore.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val REQUEST_OVERLAY = 1001
        const val GLYPH_FILE = "glyph_line.csv"
        const val SEQUENCE_FILE = "glyph.csv"
    }

    private var projectionResultCode = 0
    private var projectionData: Intent? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            projectionResultCode = result.resultCode
            projectionData = data
            startCaptureService()
        } else {
            toast(getString(R.string.permission_capture_required))
        }
    }

    private val glyphPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importTable(it, GLYPH_FILE, "glyph table") } }

    private val sequencePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importTable(it, SEQUENCE_FILE, "sequence table") } }

    /**
     * Android 13 and later hide notifications, including the foreground
     * service's, until this is granted. The service runs either way, so a refusal
     * only costs the status-bar notice that the capture is active.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nothing to do either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            GlyphTheme {
                MainScreen()
            }
        }
    }

    @Composable
    private fun MainScreen() {
        val context = LocalContext.current
        var hasGlyph by remember { mutableStateOf(tableFile(GLYPH_FILE).exists()) }
        var hasSequence by remember { mutableStateOf(tableFile(SEQUENCE_FILE).exists()) }
        var running by remember { mutableStateOf(GlyphCaptureService.isRunning) }

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0F14)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Dynamic Glyph",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF7B78FF)
                )
                Text(
                    text = "Glyph hack recognition for Ingress",
                    fontSize = 13.sp,
                    color = Color(0xFF9FA0B8)
                )

                Spacer(Modifier.height(8.dp))

                Section("Reference tables") {
                    Text(
                        text = "These are third-party data and are not bundled. " +
                            "Point the app at your own copies once.",
                        fontSize = 12.sp,
                        color = Color(0xFF9FA0B8),
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    TableButton(
                        label = if (hasGlyph) "glyph_line.csv loaded" else "Load glyph_line.csv",
                        loaded = hasGlyph
                    ) {
                        glyphPicker.launch(arrayOf("*/*"))
                    }
                    Spacer(Modifier.height(8.dp))
                    TableButton(
                        label = if (hasSequence) "glyph.csv loaded" else "Load glyph.csv",
                        loaded = hasSequence
                    ) {
                        sequencePicker.launch(arrayOf("*/*"))
                    }
                    if (hasGlyph && hasSequence) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Both tables present.",
                            fontSize = 12.sp,
                            color = Color(0xFF73D9F2)
                        )
                    }
                }

                Section("Capture") {
                    Button(
                        onClick = {
                            if (!hasGlyph || !hasSequence) {
                                toast("Load both tables first")
                                return@Button
                            }
                            onStartCapture()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7B78FF),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (running) "Restart capture" else getString(R.string.start_capture))
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            startService(
                                Intent(context, GlyphCaptureService::class.java).apply {
                                    action = GlyphCaptureService.ACTION_STOP
                                }
                            )
                            running = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A2030),
                            contentColor = Color(0xFFFF8A8A)
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(getString(R.string.stop_capture))
                    }
                }

                Section("Before you start") {
                    Text(
                        text = """
                            1. Load glyph_line.csv and glyph.csv.
                            2. Grant the overlay permission when asked: the recognised
                               sequence is drawn in a floating panel over the game.
                            3. Grant screen capture when the system asks.

                            The panel can be dragged. It is a picture of what the
                            recogniser saw, not advice from the game.
                        """.trimIndent(),
                        fontSize = 12.sp,
                        color = Color(0xFF9FA0B8),
                        lineHeight = 18.sp
                    )
                }

                Section("Known differences from the iOS build") {
                    Text(
                        text = """
                            The layout fractions were measured on an iPhone with a
                            Dynamic Island. Android status bars differ, so the header
                            band may need recalibration on your device.

                            OCR is ML Kit instead of Vision, with one Latin model and
                            no fast/accurate split. Only English header phrases are
                            defined, matching the original's recorded language.

                            There is no Live Activity equivalent; the overlay is the
                            only display surface.
                        """.trimIndent(),
                        fontSize = 12.sp,
                        color = Color(0xFF9FA0B8),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF0F0FF)
                )
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }

    @Composable
    private fun TableButton(label: String, loaded: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (loaded) Color(0xFF1E2A22) else Color(0xFF232338),
                contentColor = if (loaded) Color(0xFF73D9F2) else Color(0xFFB9B6FF)
            )
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp)
        }
    }

    // -- Tables --

    private fun tableFile(name: String): File = File(filesDir, name)

    private fun importTable(uri: Uri, name: String, label: String) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            toast("Could not read that file")
            return
        }
        try {
            tableFile(name).writeText(text)
            toast("$label loaded (${text.length} characters)")
        } catch (e: Exception) {
            toast("Could not save that file")
            return
        }
        // Recreate so the composable re-reads the files' existence.
        recreate()
    }

    private fun readTable(name: String): String? = try {
        val file = tableFile(name)
        if (file.exists()) file.readText() else null
    } catch (e: Exception) {
        null
    }

    // -- Capture --

    private fun onStartCapture() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.permission_overlay_required))
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQUEST_OVERLAY
            )
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService() {
        val glyph = readTable(GLYPH_FILE)
        val sequence = readTable(SEQUENCE_FILE)
        if (glyph == null || sequence == null) {
            toast("Load both tables first")
            return
        }

        val intent = Intent(this, GlyphCaptureService::class.java).apply {
            action = GlyphCaptureService.ACTION_START
            putExtra(GlyphCaptureService.EXTRA_RESULT_CODE, projectionResultCode)
            putExtra(GlyphCaptureService.EXTRA_RESULT_DATA, projectionData)
            putExtra(GlyphCaptureService.EXTRA_GLYPH_CSV, glyph)
            putExtra(GlyphCaptureService.EXTRA_SEQUENCE_CSV, sequence)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        toast("Capture starting")
    }

    @Deprecated("Overlay permission returns through the legacy path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                onStartCapture()
            } else {
                toast(getString(R.string.permission_overlay_required))
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun GlyphTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7B78FF),
            onPrimary = Color.White,
            surface = Color(0xFF1A1A2E),
            onSurface = Color(0xFFF0F0FF),
            background = Color(0xFF0D0F14),
            onBackground = Color(0xFFF0F0FF)
        ),
        content = content
    )
}
