package com.example.psxvcdconverter

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

class CueParser(private val contentResolver: ContentResolver) {

    /**
     * Reads a .cue file and finds the linked .bin files in the provided directory list.
     * Returns a CueData object containing the URIs and Track info.
     */
    fun parse(cueDoc: DocumentFile, dirFiles: Array<DocumentFile>): CueData {
        val binUris = mutableListOf<Uri>()
        val tracks = mutableMapOf<Int, TrackInfo>()
        var currentFileUri: Uri? = null
        var currentFileLenBytes: Long = 0
        var globalSectorOffset = 0
        
        // Regex to extract filename from lines like: FILE "Game.bin" BINARY
        val filePattern = Pattern.compile("FILE \"(.*)\"", Pattern.CASE_INSENSITIVE)
        
        contentResolver.openInputStream(cueDoc.uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                var currentTrack = 0
                lines.forEach { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("FILE") -> {
                            // If we moved to a new file, add the previous file's length to the global offset
                            if (currentFileUri != null) {
                                globalSectorOffset += (currentFileLenBytes / 2352).toInt()
                            }

                            // Extract filename
                            var fname = ""
                            val matcher = filePattern.matcher(trimmed)
                            if (matcher.find()) {
                                fname = matcher.group(1) ?: ""
                            } else {
                                // Fallback for simple spaces if quotes are missing
                                val parts = trimmed.split(' ')
                                if (parts.size >= 3) fname = parts[1].replace("\"", "")
                            }
                            
                            // Clean path separators to get just the filename
                            fname = File(fname.replace('\\', '/')).name
                            
                            // Find the file in the DocumentFile list (Case Insensitive search)
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
                                val timestamp = parts[2] // Format 00:00:00
                                val (m, s, f) = timestamp.split(':').map { it.toInt() }
                                
                                // Convert MSF to absolute Sector count
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