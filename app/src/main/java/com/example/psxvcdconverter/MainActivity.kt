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

    // UI Elements
    private lateinit var folderPathText: TextView
    private lateinit var changeFolderButton: Button
    private lateinit var refreshButton: Button
    private lateinit var convertAllButton: Button
    private lateinit var progressContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var gamesListContainer: LinearLayout

    private val vcdConverter by lazy { VCDConverter(contentResolver) }
    private lateinit var prefs: SharedPreferences

    // Helper Data Class
    // cueFile is nullable (?) because it might be missing if the user deleted the source
    data class GameEntry(
        val name: String, 
        val cueFile: DocumentFile?, 
        val isConverted: Boolean
    )

    // Variables for Batch Processing
    private var currentGameList: List<GameEntry> = emptyList()
    private var currentRootDoc: DocumentFile? = null
    private var currentAllFiles: Array<DocumentFile> = emptyArray()

    // File Picker Launcher
    private val directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Ignore error if permission was already granted
                }
                saveDefaultFolder(uri)
                loadGamesFromFolder(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Prevent screen from sleeping
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Bind Views
        folderPathText = findViewById(R.id.folder_path_text)
        changeFolderButton = findViewById(R.id.change_folder_button)
        refreshButton = findViewById(R.id.refresh_button)
        convertAllButton = findViewById(R.id.convert_all_button)
        progressContainer = findViewById(R.id.progress_container)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        gamesListContainer = findViewById(R.id.games_list_container)

        prefs = getSharedPreferences("psx_prefs", Context.MODE_PRIVATE)

        // 1. Change Folder Action
        changeFolderButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            directoryPickerLauncher.launch(intent)
        }
        
        // 2. Refresh Action
        refreshButton.setOnClickListener {
            val uriString = prefs.getString("default_folder_uri", null)
            if (uriString != null) {
                loadGamesFromFolder(Uri.parse(uriString))
            } else {
                Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 3. Convert All Action
        convertAllButton.setOnClickListener {
            promptConvertAll()
        }

        // On startup, load saved folder
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
                val doc = DocumentFile.fromTreeUri(this, uri)
                if (doc != null && doc.isDirectory) {
                    loadGamesFromFolder(uri)
                } else {
                    folderPathText.text = "Saved folder inaccessible."
                }
            } catch (e: Exception) {
                folderPathText.text = "Permission lost. Select folder again."
            }
        }
    }

    private fun loadGamesFromFolder(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: return@launch
            currentRootDoc = rootDoc // Save for batch reference
            
            withContext(Dispatchers.Main) {
                folderPathText.text = "Folder: ${rootDoc.name}"
                gamesListContainer.removeAllViews()
                convertAllButton.visibility = View.GONE
                
                val loadingView = TextView(this@MainActivity).apply { 
                    text = "Scanning..."
                    setTextColor(Color.WHITE)
                    setPadding(32, 32, 32, 32)
                    gravity = Gravity.CENTER
                }
                gamesListContainer.addView(loadingView)
            }

            val vcdFolder = rootDoc.findFile("VCD")
            val files = rootDoc.listFiles()
            currentAllFiles = files
            
            // A. Find Source Files (CUE)
            val cueFiles = files.filter { it.name?.endsWith(".cue", ignoreCase = true) == true }
            
            // B. Find Converted Files (VCD) inside VCD folder
            val vcdFiles = vcdFolder?.listFiles()?.filter { it.name?.endsWith(".VCD", ignoreCase = true) == true } ?: emptyArray()

            val combinedList = mutableListOf<GameEntry>()
            val processedNames = mutableSetOf<String>()

            // 1. Process Normal Games (CUE exists)
            for (cueFile in cueFiles) {
                val baseName = cueFile.name?.substringBeforeLast(".") ?: "game"
                val cleanName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val vcdName = "${cleanName}.VCD"
                
                // Check if it is already converted
                val isConverted = vcdFolder?.findFile(vcdName) != null
                
                combinedList.add(GameEntry(baseName, cueFile, isConverted))
                processedNames.add(cleanName.lowercase())
            }

            // 2. Process Orphaned VCDs (CUE missing, but VCD exists)
            for (vcdFile in vcdFiles) {
                val vcdBaseName = vcdFile.name?.substringBeforeLast(".") ?: "game"
                // If this name wasn't in the CUE list, it's an orphan
                if (!processedNames.contains(vcdBaseName.lowercase())) {
                    combinedList.add(GameEntry(vcdBaseName, null, true))
                }
            }

            val sortedList = combinedList.sortedBy { it.name }
            currentGameList = sortedList

            withContext(Dispatchers.Main) {
                populateGameList(rootDoc, sortedList, files)
            }
        }
    }

    private fun populateGameList(rootDoc: DocumentFile, games: List<GameEntry>, allFiles: Array<DocumentFile>) {
        gamesListContainer.removeAllViews()

        if (games.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No games found."
                setTextColor(Color.LTGRAY)
                setPadding(32, 32, 32, 32)
                gravity = Gravity.CENTER
            }
            gamesListContainer.addView(emptyView)
            return
        }
        
        // Show Convert All button only if there are actual CUE files waiting
        val convertibleGames = games.any { it.cueFile != null && !it.isConverted }
        convertAllButton.visibility = if (convertibleGames) View.VISIBLE else View.GONE

        for (game in games) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                setBackgroundColor(Color.parseColor("#252525"))
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 16)
                layoutParams = params
            }

            // Determine status
            val isSourceMissing = game.cueFile == null

            // Title
            val titleView = TextView(this).apply {
                text = if (game.isConverted) "✓ ${game.name}" else game.name
                textSize = 16f
                setTextColor(if (game.isConverted) Color.parseColor("#00FF00") else Color.WHITE)
            }

            // Subtitle Status
            val statusView = TextView(this).apply {
                if (isSourceMissing) {
                    text = "Status: Converted (Source Deleted)"
                    setTextColor(Color.parseColor("#FF6666")) // Reddish
                } else {
                    text = if (game.isConverted) "Status: Already Converted" else "Status: Ready"
                    setTextColor(Color.LTGRAY)
                }
                textSize = 12f
                setPadding(0, 8, 0, 24)
            }

            // Button
            val actionButton = Button(this).apply {
                textSize = 14f
                
                if (isSourceMissing) {
                    text = "SOURCE MISSING"
                    isEnabled = false
                    setBackgroundColor(Color.DKGRAY)
                    setTextColor(Color.LTGRAY)
                } else {
                    text = "CONVERT"
                    val btnColor = if (game.isConverted) Color.DKGRAY else Color.parseColor("#003DA5")
                    setBackgroundColor(btnColor)
                    setTextColor(Color.WHITE)
                    
                    setOnClickListener {
                        // Safe to assert non-null here due to isSourceMissing check
                        startConversion(rootDoc, game.cueFile!!, allFiles)
                    }
                }
            }

            row.addView(titleView)
            row.addView(statusView)
            row.addView(actionButton)
            gamesListContainer.addView(row)
        }
    }
    
    // --- BATCH PROCESSING LOGIC ---
    
    private fun promptConvertAll() {
        // Filter list: Must have CUE file AND not be converted yet
        val todoList = currentGameList.filter { it.cueFile != null && !it.isConverted }
        
        if (todoList.isEmpty()) {
            Toast.makeText(this, "Nothing new to convert!", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Convert All")
            .setMessage("Ready to convert ${todoList.size} games?")
            .setPositiveButton("Start") { _, _ ->
                runBatchConversion(todoList)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runBatchConversion(gamesToConvert: List<GameEntry>) {
        val root = currentRootDoc ?: return
        val files = currentAllFiles

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                toggleControls(false)
            }

            // Create VCD folder
            val vcdFolder = root.findFile("VCD") ?: root.createDirectory("VCD")
            if (vcdFolder == null) {
                withContext(Dispatchers.Main) {
                     Toast.makeText(this@MainActivity, "Failed to create VCD folder", Toast.LENGTH_LONG).show()
                     toggleControls(true)
                }
                return@launch
            }

            var successCount = 0

            for ((index, game) in gamesToConvert.withIndex()) {
                if (game.cueFile == null) continue

                try {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Converting ${index + 1} of ${gamesToConvert.size}:\n${game.name}"
                        progressBar.progress = 0
                    }

                    val rawName = game.cueFile.name?.substringBeforeLast(".") ?: "game"
                    val baseName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val finalFileName = "${baseName}.VCD"

                    // Parse
                    val cueData = parseCueFile(game.cueFile, files)
                    
                    // Create File
                    vcdFolder.findFile(finalFileName)?.delete()
                    val outputFile = vcdFolder.createFile("application/octet-stream", finalFileName) 
                        ?: throw Exception("File creation failed")

                    // Convert
                    contentResolver.openOutputStream(outputFile.uri)?.use { outputStream ->
                        vcdConverter.convert(cueData, outputStream) { progress, status ->
                             lifecycleScope.launch(Dispatchers.Main) {
                                progressBar.progress = progress
                                statusText.text = "Converting ${index + 1} of ${gamesToConvert.size}:\n${game.name}\n$status"
                            }
                        }
                    }
                    successCount++

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                toggleControls(true)
                Toast.makeText(this@MainActivity, "Batch finished! $successCount converted.", Toast.LENGTH_LONG).show()
                loadGamesFromFolder(root.uri) // Refresh list
            }
        }
    }

    // --- SINGLE FILE CONVERSION LOGIC ---

    private fun startConversion(rootDoc: DocumentFile, cueDoc: DocumentFile, allFiles: Array<DocumentFile>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawName = cueDoc.name?.substringBeforeLast(".") ?: "game"
                val baseName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

                withContext(Dispatchers.Main) {
                    toggleControls(false)
                    statusText.text = "Parsing CUE..."
                }

                val cueData = parseCueFile(cueDoc, allFiles)
                
                withContext(Dispatchers.Main) { statusText.text = "Creating output file..." }
                val vcdFolder = rootDoc.findFile("VCD") ?: rootDoc.createDirectory("VCD")
                    ?: throw Exception("Could not create 'VCD' folder.")

                val finalFileName = "${baseName}.VCD"
                vcdFolder.findFile(finalFileName)?.delete()
                
                val outputFile = vcdFolder.createFile("application/octet-stream", finalFileName) 
                    ?: throw Exception("Failed to create file: $finalFileName")

                contentResolver.openOutputStream(outputFile.uri)?.use { outputStream ->
                    vcdConverter.convert(cueData, outputStream) { progress, status ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressBar.progress = progress
                            statusText.text = status
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Finished!", Toast.LENGTH_SHORT).show()
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
    
    private fun toggleControls(enabled: Boolean) {
        changeFolderButton.isEnabled = enabled
        refreshButton.isEnabled = enabled
        convertAllButton.isEnabled = enabled
        progressContainer.visibility = if (enabled) View.GONE else View.VISIBLE
        
        for (i in 0 until gamesListContainer.childCount) {
            val row = gamesListContainer.getChildAt(i) as? LinearLayout
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
