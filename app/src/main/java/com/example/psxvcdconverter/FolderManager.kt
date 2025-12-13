package com.example.psxvcdconverter

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderManager(private val context: Context) {
    
    private val prefs = context.getSharedPreferences("psx_prefs", Context.MODE_PRIVATE)

    fun saveDefaultFolder(uri: Uri) {
        prefs.edit().putString("default_folder_uri", uri.toString()).apply()
    }

    fun getSavedFolder(): Uri? {
        val uriString = prefs.getString("default_folder_uri", null)
        return if (uriString != null) Uri.parse(uriString) else null
    }

    suspend fun scanForGames(rootDoc: DocumentFile): List<GameEntry> = withContext(Dispatchers.IO) {
        val vcdFolder = rootDoc.findFile("VCD")
        val allFiles = rootDoc.listFiles()
        
        val cueFiles = allFiles.filter { it.name?.endsWith(".cue", ignoreCase = true) == true }
        // Use empty list if VCD folder doesn't exist yet
        val vcdFiles = vcdFolder?.listFiles()?.filter { it.name?.endsWith(".VCD", ignoreCase = true) == true } ?: emptyList()

        val combinedList = mutableListOf<GameEntry>()
        
        // Keep track of which VCDs we have matched to a CUE file
        // so we don't list them again as "Source Missing"
        val claimedVcds = mutableSetOf<String>()

        // 1. Process CUE Files
        for (cueFile in cueFiles) {
            val baseName = cueFile.name?.substringBeforeLast(".") ?: "game"
            val cleanName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            
            // SMART MATCHING LOGIC:
            // Check 1: Exact Match (Game.VCD)
            var matchedVcd = vcdFiles.find { it.name.equals("${cleanName}.VCD", ignoreCase = true) }
            
            // Check 2: ID Match (SLUS_XXX.XX.Game.VCD) - Look for file ending with .Name.VCD
            if (matchedVcd == null) {
                matchedVcd = vcdFiles.find { 
                    it.name != null && it.name!!.endsWith(".${cleanName}.VCD", ignoreCase = true) 
                }
            }

            if (matchedVcd != null) {
                // We found a VCD! Mark it as claimed so we don't show it as an orphan later
                matchedVcd.name?.let { claimedVcds.add(it) }
                combinedList.add(GameEntry(baseName, cueFile, true))
            } else {
                // Not converted yet
                combinedList.add(GameEntry(baseName, cueFile, false))
            }
        }

        // 2. Process Orphaned VCDs (VCDs that were NOT claimed by the loop above)
        for (vcdFile in vcdFiles) {
            val vcdName = vcdFile.name ?: continue
            
            if (!claimedVcds.contains(vcdName)) {
                // This VCD exists but has no matching CUE file in the folder
                // We try to extract a pretty name for display
                
                // If named "SLUS_000.00.Game.VCD", extract just "Game"
                // Logic: Find the first matching ID pattern, split after it
                var displayName = vcdName.substringBeforeLast(".") // Remove .VCD
                
                // If it looks like an ID Rename (contains dots), try to clean it up
                if (displayName.contains(".")) {
                    // Split by dot, take the last part (GameName) if logic holds
                    // Or just leave it as filename if unsure.
                    // For safety, let's just show the filename without extension.
                }

                combinedList.add(GameEntry(displayName, null, true))
            }
        }

        return@withContext combinedList.sortedBy { it.name }
    }
}
