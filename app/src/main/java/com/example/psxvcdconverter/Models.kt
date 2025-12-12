package com.example.psxvcdconverter

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Holds information about a game for the UI list.
 */
data class GameEntry(
    val name: String,
    val cueFile: DocumentFile?, // Nullable: If null, it means the source is missing (Orphan VCD)
    val isConverted: Boolean
)

/**
 * Holds the parsed data from a .cue file, ready for conversion.
 */
data class CueData(
    val binUris: List<Uri>,
    val tracks: Map<Int, TrackInfo>
)

/**
 * detailed track information for the VCD structure.
 */
data class TrackInfo(
    val mode: String,
    val indices: MutableMap<Int, Int>
)