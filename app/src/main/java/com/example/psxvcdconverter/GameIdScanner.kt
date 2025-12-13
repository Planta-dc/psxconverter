package com.example.psxvcdconverter

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.util.regex.Pattern

class GameIdScanner(private val contentResolver: ContentResolver) {

    /**
     * Scans the beginning of the BIN file to find the Game ID (e.g. SLUS_000.00).
     * Mimics the Python script logic: Checks SYSTEM.CNF pattern first, then Raw Header pattern.
     */
    fun scan(binUri: Uri?): String? {
        if (binUri == null) return null

        try {
            contentResolver.openInputStream(binUri)?.use { stream ->
                // We read the first 512KB. This covers the PVD (Sector 16) 
                // and usually the Root directory + SYSTEM.CNF content in most games.
                // Reading the whole ISO structure via Stream is slow, this "Raw Scan" is faster/safer on Android.
                val buffer = ByteArray(512 * 1024)
                val bytesRead = BufferedInputStream(stream).read(buffer)
                
                if (bytesRead <= 0) return null

                // Convert to String for Regex searching (Replace nulls to avoid cutoffs)
                // Using Latin-1 (ISO-8859-1) preserves byte values 1:1 better than UTF-8 for binary data
                val rawData = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)

                // PRIORITY 1: SYSTEM.CNF "BOOT" Pattern
                // Python: BOOT\s*=\s*cdrom:/?([A-Z]{4})[_ -](\d{3})\.?(\d{2})
                val bootPattern = Pattern.compile("BOOT\\s*=\\s*cdrom:/?([A-Z]{4})[_ -](\\d{3})\\.?(\\d{2})", Pattern.CASE_INSENSITIVE)
                val bootMatch = bootPattern.matcher(rawData)
                if (bootMatch.find()) {
                    val region = bootMatch.group(1)?.uppercase()
                    val major = bootMatch.group(2)
                    val minor = bootMatch.group(3)
                    return "${region}_${major}.${minor}"
                }

                // PRIORITY 2: Raw Header Pattern (e.g. SCPS-10005)
                // Python: (S[LC][EUPJ][SM])[-_ ]*(\d{3})[\.]?(\d{2})
                val rawPattern = Pattern.compile("(S[LC][EUPJ][SM])[-_ ]*(\\d{3})[\\.]?(\\d{2})", Pattern.CASE_INSENSITIVE)
                val rawMatch = rawPattern.matcher(rawData)
                if (rawMatch.find()) {
                    val region = rawMatch.group(1)?.uppercase()
                    val major = rawMatch.group(2)
                    val minor = rawMatch.group(3)
                    return "${region}_${major}.${minor}"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}