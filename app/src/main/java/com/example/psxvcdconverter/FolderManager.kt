package com.example.psxvcdconverter

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderManager(private val context: Context) {
    
    private val prefs = context.getSharedPreferences("psx_prefs", Context.MODE_PRIVATE)

    /**
     * Saves the selected folder URI to SharedPreferences.
     */
    fun saveDefaultFolder(uri: Uri) {
        prefs.edit().putString("default_folder_uri", uri.toString()).apply()
    }

    /**
     * Retrieves the saved folder URI, or null if none exists.
     */
    fun getSavedFolder(): Uri? {
        val uriString = prefs.getString("default_folder_uri", null)
        return if (uriString != null) Uri.parse(uriString) else null
    }

    /**
     * Scans the directory for CUE files and VCD files.
     * Matches them together to determine conversion status.
     * Also finds "Orphaned" VCDs (VCDs where the source CUE/BIN is deleted).
     */
    suspend fun scanForGames(rootDoc: DocumentFile): List<GameEntry> = withContext(Dispatchers.IO) {
        val vcdFolder = rootDoc.findFile("VCD")
        val allFiles = rootDoc.listFiles()
        
        // 1. Find Source Files (CUE)
        val cueFiles = allFiles.filter { it.name?.endsWith(".cue", ignoreCase = true) == true }
        
        // 2. Find Converted Files (VCD)
        // Note: Using emptyList() if folder is null to avoid type errors
        val vcdFiles = vcdFolder?.listFiles()?.filter { it.name?.endsWith(".VCD", ignoreCase = true) == true } ?: emptyList()

        val combinedList = mutableListOf<GameEntry>()
        val processedNames = mutableSetOf<String>()

        // Match CUEs to VCDs
        for (cueFile in cueFiles) {
            val baseName = cueFile.name?.substringBeforeLast(".") ?: "game"
            // Sanitize name to match the file system safe name we use for VCDs
            val cleanName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val vcdName = "${cleanName}.VCD"
            
            // Check if VCD exists
            val isConverted = vcdFolder?.findFile(vcdName) != null
            
            combinedList.add(GameEntry(baseName, cueFile, isConverted))
            processedNames.add(cleanName.lowercase())
        }

        // Find Orphans (VCDs with no CUE)
        for (vcdFile in vcdFiles) {
            val vcdBaseName = vcdFile.name?.substringBeforeLast(".") ?: "game"
            // If we haven't processed this name via CUE list, it's an orphan
            if (!processedNames.contains(vcdBaseName.lowercase())) {
                combinedList.add(GameEntry(vcdBaseName, null, true))
            }
        }

        // Return sorted list
        return@withContext combinedList.sortedBy { it.name }
    }
}