package com.example.psxvcdconverter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    // UI Elements defined in the XML
    private lateinit var folderPathText: TextView
    private lateinit var changeFolderButton: Button
    private lateinit var progressContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var gamesListContainer: LinearLayout

    // Dependencies
    private val vcdConverter by lazy { VCDConverter(contentResolver) }
    private lateinit var prefs: SharedPreferences

    // Simple data class to hold game status before rendering
    data class GameEntry(val name: String, val cueFile: DocumentFile, val isConverted: Boolean)

    // Launcher for the Folder Picker
    private val directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                // Crucial: Persist permission so we can access this folder after app restart
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                saveDefaultFolder(uri)
                loadGamesFromFolder(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Prevent phone from sleeping during long conversions
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Bind Views
        folderPathText = findViewById(R.id.folder_path_text)
        changeFolderButton = findViewById(R.id.change_folder_button)
        progressContainer = findViewById(R.id.progress_container)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        gamesListContainer = findViewById(R.id.games_list_container)

        // Initialize Preferences to store folder path
        prefs = getSharedPreferences("psx_prefs", Context.MODE_PRIVATE)

        changeFolderButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            directoryPickerLauncher.launch(intent)
        }

        // Check if we have a folder saved from last time
        checkSavedFolder()
    }

    private fun saveDefaultFolder(uri: Uri) {
        prefs.edit().putString("default_folder_uri", uri.toString()).apply()
    }

    private fun checkSavedFolder() {
        val uriString = prefs.getString("default_folder_uri", null)
        if (uriString != null) {
            val uri = Uri.parse(uriString)
            try {
                // Verify we still have access
                val doc = DocumentFile.fromTreeUri(this, uri)
                if (doc != null && doc.isDirectory) {
                    loadGamesFromFolder(uri)
                } else {
                    folderPathText.text = "Saved folder inaccessible."
                }
            } catch (e: Exception) {
                folderPathText.text = "Permission lost. Please select folder again."
            }
        }
    }

    /**
     * Scans the folder for CUE files and checks conversion status
     */
    private fun loadGamesFromFolder(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: return@launch
            
            withContext(Dispatchers.Main) {
                folderPathText.text = rootDoc.uri.path
                gamesListContainer.removeAllViews()
                
                // Show temporary loading text
                val loadingView = TextView(this@MainActivity).apply { 
                    text = "Scanning directory..."
                    setTextColor(Color.WHITE)
                    setPadding(32, 32, 32, 32)
                    gravity = Gravity.CENTER
                }
                gamesListContainer.addView(loadingView)
            }

            // Look for the VCD output folder to check status
            val vcdFolder = rootDoc.findFile("VCD")
            
            // Find all files in one go to minimize I/O calls
            val files = rootDoc.listFiles()
            val cueFiles = files.filter { it.name?.endsWith(".cue", ignoreCase = true) == true }

            // Map files to GameEntries
            val gameEntries = cueFiles.map { cueFile ->
                val baseName = cueFile.name?.substringBeforeLast(".") ?: "game"
                // Sanitize name to match how we save it
                val cleanName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val vcdName = "${cleanName}.VCD"
                
                // Check if the VCD file already exists
                val isConverted = vcdFolder?.findFile(vcdName) != null
                
                GameEntry(baseName, cueFile, isConverted)
            }.sortedBy { it.name }

            withContext(Dispatchers.Main) {
                populateGameList(rootDoc, gameEntries, files)
            }
        }
    }

    /**
     * Dynamically builds the UI list of games
     */
    private fun populateGameList(rootDoc: DocumentFile, games: List<GameEntry>, allFiles: Array<DocumentFile>) {
        gamesListContainer.removeAllViews()

        if (games.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No .cue files found."
                setTextColor(Color.LTGRAY)
                setPadding(32, 32, 32, 32)
                gravity = Gravity.CENTER
            }
            gamesListContainer.addView(emptyView)
            return
        }

        for (game in games) {
            // Container for each game row
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(Color.parseColor("#252525")) // Dark Grey Row
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 4) // Spacing between rows
                layoutParams = params
            }

            // Game Title
            val titleView = TextView(this).apply {
                text = game.name
                textSize = 18f
                setTextColor(Color.WHITE)
                // If converted, show visual indicator
                if (game.isConverted) {
                    text = "✓ ${game.name}"
                    setTextColor(Color.parseColor("#00FF00")) // Bright Green
                }
            }

            // Status Subtitle
            val statusView = TextView(this).apply {
                text = if (game.isConverted) "Status: Converted" else "Status: Ready"
                textSize = 12f
                setTextColor(Color.LTGRAY)
                setPadding(0, 4, 0, 16)
            }

            // Action Button
            val actionButton = Button(this).apply {
                text = if (game.isConverted) "Re-Convert" else "Convert"
                // Different color for already converted games
                val bgData = if (game.isConverted) Color.DKGRAY else Color.parseColor("#003DA5") // PSX Blue
                setBackgroundColor(bgData)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    startConversion(rootDoc, game.cueFile, allFiles)
                }
            }

            row.addView(titleView)
            row.addView(statusView)
            row.addView(actionButton)
            gamesListContainer.addView(row)
        }
    }

    private fun startConversion(rootDoc: DocumentFile, cueDoc: DocumentFile, allFiles: Array<DocumentFile>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Prepare Name
                val rawName = cueDoc.name?.substringBeforeLast(".") ?: "game"
                val baseName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

                // UI Update: Disable controls
                withContext(Dispatchers.Main) {
                    toggleControls(false)
                    statusText.text = "Parsing CUE..."
                }

                // 1. Parse CUE
                val cueData = parseCueFile(cueDoc, allFiles)
                
                // 2. Prepare Output
                withContext(Dispatchers.Main) { statusText.text = "Creating output file..." }
                val vcdFolder = rootDoc.findFile("VCD") ?: rootDoc.createDirectory("VCD")
                    ?: throw Exception("Could not create 'VCD' folder.")

                val finalFileName = "${baseName}.VCD"
                // Delete old file if re-converting
                vcdFolder.findFile(finalFileName)?.delete()
                
                val outputFile = vcdFolder.createFile("application/octet-stream", finalFileName) 
                    ?: throw Exception("Failed to create file: $finalFileName")

                // 3. Run Conversion
                contentResolver.openOutputStream(outputFile.uri)?.use { outputStream ->
                    vcdConverter.convert(cueData, outputStream) { progress, status ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressBar.progress = progress
                            statusText.text = status
                        }
                    }
                }

                // Success
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Finished!", Toast.LENGTH_SHORT).show()
                    // Refresh list to update status colors
                    loadGamesFromFolder(rootDoc.uri)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Conversion Error")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    toggleControls(true)
                }
            }
        }
    }
    
    // Helper to lock UI during processing
    private fun toggleControls(enabled: Boolean) {
        changeFolderButton.isEnabled = enabled
        progressContainer.visibility = if (enabled) View.GONE else View.VISIBLE
        
        // Loop through list to disable/enable conversion buttons
        for (i in 0 until gamesListContainer.childCount) {
            val row = gamesListContainer.getChildAt(i) as? LinearLayout
            // Child 2 is the button in our layout structure
            row?.getChildAt(2)?.isEnabled = enabled
        }
    }

    private fun parseCueFile(cueDoc: DocumentFile, dirFiles: Array<DocumentFile>): CueData {
        val binUris = mutableListOf<Uri>()
        val tracks = mutableMapOf<Int, TrackInfo>()
        var currentFileUri: Uri? = null
        var currentFileLenBytes: Long = 0
        var globalSectorOffset = 0
        
        val filePattern = Pattern.compile("FILE \"(.*)\"", Pattern.CASE_INSENSITIVE)
        
        contentResolver.openInputStream(cueDoc.uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                var currentTrack = 0
                lines.forEach { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("FILE") -> {
                            if (currentFileUri != null) {
                                globalSectorOffset += (currentFileLenBytes / 2352).toInt()
                            }
                            var fname = ""
                            val matcher = filePattern.matcher(trimmed)
                            if (matcher.find()) fname = matcher.group(1) ?: ""
                            else {
                                val parts = trimmed.split(' ')
                                if (parts.size >= 3) fname = parts[1].replace("\"", "")
                            }
                            
                            // Get simple filename and find it in the dir
                            fname = File(fname.replace('\\', '/')).name
                            val targetDoc = dirFiles.find { it.name.equals(fname, ignoreCase = true) }
                                ?: throw Exception("BIN file missing: $fname")
                            
                            binUris.add(targetDoc.uri)
                            currentFileUri = targetDoc.uri
                            currentFileLenBytes = targetDoc.length()
                        }
                        trimmed.startsWith("TRACK") -> {
                            val parts = trimmed.split(' ')
                            if (parts.size >= 2) {
                                currentTrack = parts[1].toInt()
                                tracks[currentTrack] = TrackInfo(parts.getOrElse(2) { "MODE1/2352" }, mutableMapOf())
                            }
                        }
                        trimmed.startsWith("INDEX") -> {
                            val parts = trimmed.split(' ')
                            if (parts.size >= 3) {
                                val idxNum = parts[1].toInt()
                                val timestamp = parts[2]
                                val (m, s, f) = timestamp.split(':').map { it.toInt() }
                                val localSector = (m * 60 * 75) + (s * 75) + f
                                tracks[currentTrack]?.indices?.set(idxNum, globalSectorOffset + localSector)
                            }
                        }
                    }
                }
            }
        }
        if (binUris.isEmpty()) throw Exception("Invalid CUE file")
        return CueData(binUris, tracks)
    }
}
