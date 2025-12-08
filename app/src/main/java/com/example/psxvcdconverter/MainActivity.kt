package com.example.psxvcdconverter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
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

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var convertButton: Button

    // Lazy initialization of the converter
    private val vcdConverter by lazy { VCDConverter(contentResolver) }

    // The result launcher for the Folder Picker
    private val directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                // Persist permissions so we can read/write to this folder
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                handleDirectorySelection(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
            // Keep screen on so conversion doesn't die if phone locks
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)


        // Bind UI elements
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        convertButton = findViewById(R.id.convert_button)

        convertButton.setOnClickListener {
            // Open the system file picker to select a FOLDER
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            // Explicitly ask for Write permission to create the "VCD" folder later
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            directoryPickerLauncher.launch(intent)
        }
    }

    private fun handleDirectorySelection(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val directory = DocumentFile.fromTreeUri(this@MainActivity, uri)
            
            if (directory == null || !directory.isDirectory) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not access directory.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // List all files to find .cue files and later match .bin files
            val files = directory.listFiles()
            val cueFiles = files.filter { it.name?.endsWith(".cue", ignoreCase = true) == true }

            withContext(Dispatchers.Main) {
                if (cueFiles.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No .cue files found in this folder.", Toast.LENGTH_LONG).show()
                } else if (cueFiles.size == 1) {
                    // Exactly one CUE file found, proceed automatically
                    startConversion(directory, cueFiles[0], files)
                } else {
                    // Multiple CUE files found, show a dialog
                    showSelectionDialog(directory, cueFiles, files)
                }
            }
        }
    }

    private fun showSelectionDialog(directory: DocumentFile, cueFiles: List<DocumentFile>, allFiles: Array<DocumentFile>) {
        val names = cueFiles.map { it.name ?: "Unknown" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Game to Convert")
            .setItems(names) { _, which ->
                // User clicked an item, start conversion for that specific file
                startConversion(directory, cueFiles[which], allFiles)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startConversion(rootDoc: DocumentFile, cueDoc: DocumentFile, allFiles: Array<DocumentFile>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // UI Update: Start
                withContext(Dispatchers.Main) {
                    convertButton.isEnabled = false
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    statusText.text = "Parsing ${cueDoc.name}..."
                }

                // 1. Parse the CUE file to find the BIN files
                val cueData = parseCueFile(cueDoc, allFiles)
                
                // 2. Prepare Output Directory ("VCD" folder)
                withContext(Dispatchers.Main) { statusText.text = "Creating VCD folder..." }
                
                // Check if VCD folder exists, if not create it
                val vcdFolder = rootDoc.findFile("VCD") ?: rootDoc.createDirectory("VCD")
                if (vcdFolder == null) {
                    throw Exception("Failed to create 'VCD' subfolder. Check permissions.")
                }

                // Determine filename (Game.cue -> Game.VCD)
                val cleanName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val finalFileName = "${cleanName}.VCD"
                
                // Delete existing file if it exists (to allow overwriting)
                vcdFolder.findFile(finalFileName)?.delete()
                
                // Create the new empty VCD file
                val outputFile = vcdFolder.createFile("application/octet-stream", finalFileName) 
                    ?: throw Exception("Failed to create output file: $finalFileName")

                // 3. Run the Conversion
                // We open the stream here and pass it to the converter
                contentResolver.openOutputStream(outputFile.uri)?.use { outputStream ->
                    vcdConverter.convert(
                        cueData,
                        outputStream // Direct stream to the file on SD card/storage
                    ) { progress, status ->
                        // Update Progress Callback
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressBar.progress = progress
                            statusText.text = status
                        }
                    }
                }

                // UI Update: Success
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Conversion Successful!", Toast.LENGTH_LONG).show()
                    statusText.text = "Saved to: .../${rootDoc.name}/VCD/${finalFileName}"
                }

            } catch (e: Exception) {
                e.printStackTrace()
                // UI Update: Failure
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Conversion Failed")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                // UI Update: Reset
                withContext(Dispatchers.Main) {
                    convertButton.isEnabled = true
                    progressBar.visibility = View.INVISIBLE
                }
            }
        }
    }

    /**
     * Reads the .cue file line by line and matches the "FILE" entries 
     * to actual DocumentFiles in the directory.
     */
    private fun parseCueFile(cueDoc: DocumentFile, dirFiles: Array<DocumentFile>): CueData {
        val binUris = mutableListOf<Uri>()
        val tracks = mutableMapOf<Int, TrackInfo>()
        var currentFileUri: Uri? = null
        var currentFileLenBytes: Long = 0
        var globalSectorOffset = 0
        
        // Regex to extract filename from: FILE "Game (Track 1).bin" BINARY
        val filePattern = Pattern.compile("FILE \"(.*)\"", Pattern.CASE_INSENSITIVE)
        
        contentResolver.openInputStream(cueDoc.uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                var currentTrack = 0
                lines.forEach { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("FILE") -> {
                            // If we finished a previous file, add its length to offset
                            if (currentFileUri != null) {
                                globalSectorOffset += (currentFileLenBytes / 2352).toInt()
                            }

                            // Extract filename
                            var fname = ""
                            val matcher = filePattern.matcher(trimmed)
                            if (matcher.find()) {
                                fname = matcher.group(1) ?: ""
                            } else {
                                // Fallback for simple spaces
                                val parts = trimmed.split(' ')
                                if (parts.size >= 3) fname = parts[1].replace("\"", "")
                            }

                            // Clean path separators and get simple name
                            fname = File(fname.replace('\\', '/')).name
                            
                            // Find the file in the DocumentFile list (Case Insensitive)
                            val targetDoc = dirFiles.find { it.name.equals(fname, ignoreCase = true) }
                                ?: throw Exception("Required BIN file not found: $fname")
                            
                            binUris.add(targetDoc.uri)
                            currentFileUri = targetDoc.uri
                            currentFileLenBytes = targetDoc.length()
                        }
                        trimmed.startsWith("TRACK") -> {
                            val parts = trimmed.split(' ')
                            if (parts.size >= 2) {
                                currentTrack = parts[1].toInt()
                                val mode = parts.getOrElse(2) { "MODE1/2352" }
                                tracks[currentTrack] = TrackInfo(mode, mutableMapOf())
                            }
                        }
                        trimmed.startsWith("INDEX") -> {
                            val parts = trimmed.split(' ')
                            if (parts.size >= 3) {
                                val idxNum = parts[1].toInt() // Usually 0 or 1
                                val timestamp = parts[2] // 00:00:00
                                val (m, s, f) = timestamp.split(':').map { it.toInt() }
                                
                                // MSF to Sectors logic
                                val localSector = (m * 60 * 75) + (s * 75) + f
                                
                                tracks[currentTrack]?.indices?.set(idxNum, globalSectorOffset + localSector)
                            }
                        }
                    }
                }
            }
        }
        
        if (binUris.isEmpty()) {
             throw Exception("Invalid CUE file: No 'FILE' entries found.")
        }
        
        return CueData(binUris, tracks)
    }
}

