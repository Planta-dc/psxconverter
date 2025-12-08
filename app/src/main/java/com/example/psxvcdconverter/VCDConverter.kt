package com.example.psxvcdconverter

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Data classes to hold the parsed CUE information
data class CueData(val binUris: List<Uri>, val tracks: Map<Int, TrackInfo>)
data class TrackInfo(val mode: String, val indices: MutableMap<Int, Int>)

class VCDConverter(private val contentResolver: ContentResolver) {

    private val SECTOR_SIZE = 2352

    /**
     * Converts the game by reading from the source BIN Uris and writing directly
     * to the provided output stream (which points to the new VCD file).
     */
    fun convert(
        cueData: CueData,
        outputStream: OutputStream,
        onProgress: (Int, String) -> Unit
    ) {
        val binUris = cueData.binUris
        
        // 1. Calculate Total Size of all BIN files combined
        var totalBytes = 0L
        binUris.forEach { uri ->
            // Open file descriptor to get accurate size without reading the whole file
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                totalBytes += fd.statSize
            }
        }
        
        // Calculate total sectors (Raw size / 2352)
        val totalSectors = (totalBytes / SECTOR_SIZE).toInt()
        
        // 2. Generate the Table of Contents (TOC) based on parsed tracks
        val tocData = generateToc(cueData.tracks, totalSectors)

        // 3. Calculate Final Padded Size
        // POPS emulator requires the file size to be a multiple of 0x9300
        var isoSizePadded = totalBytes
        if (totalBytes % 0x9300 != 0L) {
            isoSizePadded += (0x9300 - (totalBytes % 0x9300))
        }

        // 4. Start Writing the VCD File
        onProgress(0, "Writing header...")
        
        // Wrap output stream in BufferedOutputStream for performance
        // (We don't close it here, MainActivity handles closing via 'use')
        val bos = BufferedOutputStream(outputStream)
        
        // -- Write TOC --
        bos.write(tocData)
        
        // -- Pad with zeros until offset 0x400 --
        if (tocData.size < 0x400) {
            val padTo400 = ByteArray(0x400 - tocData.size)
            bos.write(padTo400)
        }
        
        // -- Write POPS Header at 0x400 --
        // Magic signature: 6B 48 6E 20
        // Followed by Total Sectors count (written twice)
        val header = ByteArray(16).apply {
            this[0] = 0x6b; this[1] = 0x48; this[2] = 0x6e; this[3] = 0x20
            ByteBuffer.wrap(this, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalSectors)
            ByteBuffer.wrap(this, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalSectors)
        }
        bos.write(header)
        
        // -- Pad with zeros until offset 0x100000 (1MB) --
        // This is where the actual ISO data usually begins in a VCD
        val currentPos = 0x400 + 16
        val padToData = ByteArray(0x100000 - currentPos)
        bos.write(padToData)

        // 5. Stream the BIN files content
        var bytesWritten = 0L
        val buffer = ByteArray(64 * 1024) // 64KB buffer
        
        binUris.forEachIndexed { index, uri ->
            onProgress(0, "Merging Bin ${index + 1}/${binUris.size}...")
            
            contentResolver.openInputStream(uri)?.use { input ->
                val bufferedInput = BufferedInputStream(input)
                var len: Int
                while (bufferedInput.read(buffer).also { len = it } != -1) {
                    bos.write(buffer, 0, len)
                    bytesWritten += len
                    
                    // Update progress bar approx every 2MB written to avoid UI lag
                    if (bytesWritten % (2 * 1024 * 1024) == 0L) {
                        val percent = ((bytesWritten.toDouble() / isoSizePadded) * 100).toInt()
                        onProgress(percent, "Converting... $percent%")
                    }
                }
            }
        }

        // 6. Write Final Padding (to match 0x9300 alignment)
        onProgress(99, "Finalizing...")
        val remaining = isoSizePadded - bytesWritten
        if (remaining > 0) {
            // Write zeros in chunks to avoid OutOfMemory on large padding
            val padChunk = ByteArray(4096)
            var toWrite = remaining
            while (toWrite > 0) {
                val writeLen = if (toWrite > 4096) 4096 else toWrite.toInt()
                bos.write(padChunk, 0, writeLen)
                toWrite -= writeLen
            }
        }
        
        // Flush ensures all data is physically written to the file
        bos.flush()
    }

    /**
     * Generates the byte array representing the VCD Table of Contents.
     * Uses the standard PS1 structure where tracks > 1 have a +2 second offset.
     */
    private fun generateToc(tracks: Map<Int, TrackInfo>, totalSectors: Int): ByteArray {
        val toc = mutableListOf<Byte>().apply { 
            // Standard Fixed Header
            add(0x41); add(0x00); add(0xA0.toByte()); add(0x00); add(0x00); add(0x00); add(0x00); add(0x01); add(0x20); add(0x00)
            add(0x01); add(0x00); add(0xA1.toByte()); add(0x00); add(0x00); add(0x00); add(0x00); add(0x00); add(0x00); add(0x00)
            add(0x01); add(0x00); add(0xA2.toByte()); add(0x00); add(0x00); add(0x00); add(0x00); add(0x00); add(0x00); add(0x00)
        }
        
        // Set number of tracks
        toc[17] = bcd(tracks.size)
        
        // Set total length (lead-out)
        val (m, s, f) = sectorsToMsf(totalSectors)
        toc[27] = bcd(m); toc[28] = bcd(s); toc[29] = bcd(f)

        val sortedTracks = tracks.keys.sorted()
        for (tNum in sortedTracks) {
            val track = tracks[tNum]!!
            // Get start sector (Index 1 is usually the start, fallback to Index 0)
            val startSector = track.indices[1] ?: track.indices[0] ?: 0
            val (mt, st, ft) = sectorsToMsf(startSector)
            
            val buf = ByteArray(10)
            if (tNum == 1) {
                // Track 1 standard entry
                buf[0] = 0x41; buf[2] = bcd(tNum)
                // Track 1 usually starts at 00:02:00 in MSF, but we use calculated values
                // Note: Logic kept simple to match standard VCD creation tools
            } else {
                // Subsequent tracks have +2 seconds added to their MSF time in VCD TOC
                var sa = st + 2; var ma = mt
                if (sa >= 60) { sa -= 60; ma++ }
                
                buf[0] = 0x01; buf[2] = bcd(tNum)
                buf[3] = bcd(ma); buf[4] = bcd(sa); buf[5] = bcd(ft) // Absolute
                buf[7] = bcd(ma); buf[8] = bcd(sa); buf[9] = bcd(ft) // Relative
            }
            toc.addAll(buf.toList())
        }
        return toc.toByteArray()
    }

    // Helper: Integer to Binary Coded Decimal
    private fun bcd(i: Int): Byte = ((i / 10) shl 4 or (i % 10)).toByte()

    // Helper: Sectors to Minutes:Seconds:Frames
    private fun sectorsToMsf(sectors: Int): Triple<Int, Int, Int> {
        var temp = sectors
        val f = temp % 75
        temp /= 75
        val s = temp % 60
        temp /= 60
        val m = temp
        return Triple(m, s, f)
    }
}
