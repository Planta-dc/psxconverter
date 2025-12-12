package com.example.psxvcdconverter

import android.app.Activity
import android.content.Intent
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

class MainActivity : AppCompatActivity() {

    // UI Variables
    private lateinit var folderPathText: TextView
    private lateinit var changeFolderButton: Button
    private lateinit var refreshButton: Button
    private lateinit var convertAllButton: Button
    private lateinit var progressContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var gamesListContainer: LinearLayout

    // Helpers (Delegating logic to other files)
    private val vcdConverter by lazy { VCDConverter(contentResolver) }
    private val cueParser by lazy { CueParser(contentResolver) }
    private val folderManager by lazy { FolderManager(this) }

    // State
    private var currentGameList: List<GameEntry> = emptyList()
    private var currentRootDoc: DocumentFile? = null
    private var currentAllFiles: Array<DocumentFile> = emptyArray()

    private val directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) { }
                folderManager.saveDefaultFolder(uri)
                loadGames(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Setup Views
        folderPathText = findViewById(R.id.folder_path_text)
        changeFolderButton = findViewById(R.id.change_folder_button)
        refreshButton = findViewById(R.id.refresh_button)
        convertAllButton = findViewById(R.id.convert_all_button)
        progressContainer = findViewById(R.id.progress_container)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        gamesListContainer = findViewById(R.id.games_list_container)

        changeFolderButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            directoryPickerLauncher.launch(intent)
        }

        refreshButton.setOnClickListener {
            folderManager.getSavedFolder()?.let { loadGames(it) } 
                ?: Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
        }

        convertAllButton.setOnClickListener { promptConvertAll() }

        folderManager.getSavedFolder()?.let { loadGames(it) }
    }

    private fun loadGames(uri: Uri) {
        lifecycleScope.launch {
            val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri) ?: return@launch
            currentRootDoc = rootDoc
            folderPathText.text = "Folder: ${rootDoc.name}"
            
            gamesListContainer.removeAllViews()
            convertAllButton.visibility = View.GONE
            gamesListContainer.addView(TextView(this@MainActivity).apply { 
                text = "Scanning..."; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(32,32,32,32) 
            })

            // Fetch list from FolderManager
            val games = folderManager.scanForGames(rootDoc)
            currentGameList = games
            currentAllFiles = rootDoc.listFiles()

            populateGameList(rootDoc, games)
        }
    }

    private fun populateGameList(rootDoc: DocumentFile, games: List<GameEntry>) {
        gamesListContainer.removeAllViews()
        if (games.isEmpty()) {
            gamesListContainer.addView(TextView(this).apply { 
                text = "No games found."; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(32,32,32,32) 
            })
            return
        }

        convertAllButton.visibility = if (games.any { it.cueFile != null && !it.isConverted }) View.VISIBLE else View.GONE

        for (game in games) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); setBackgroundColor(Color.parseColor("#252525"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            }

            val titleView = TextView(this).apply {
                text = if (game.isConverted) "✓ ${game.name}" else game.name
                textSize = 16f
                setTextColor(if (game.isConverted) Color.parseColor("#00FF00") else Color.WHITE)
            }

            val statusView = TextView(this).apply {
                text = if (game.cueFile == null) "Status: Source Deleted" else if (game.isConverted) "Status: Already Converted" else "Status: Ready"
                setTextColor(if (game.cueFile == null) Color.parseColor("#FF6666") else Color.LTGRAY)
                textSize = 12f; setPadding(0, 8, 0, 24)
            }

            val btn = Button(this).apply {
                textSize = 14f
                if (game.cueFile == null) {
                    text = "SOURCE MISSING"; isEnabled = false; setBackgroundColor(Color.DKGRAY); setTextColor(Color.LTGRAY)
                } else {
                    text = "CONVERT"
                    setBackgroundColor(if (game.isConverted) Color.DKGRAY else Color.parseColor("#003DA5"))
                    setTextColor(Color.WHITE)
                    setOnClickListener { startConversion(rootDoc, game.cueFile, currentAllFiles) }
                }
            }

            row.addView(titleView); row.addView(statusView); row.addView(btn)
            gamesListContainer.addView(row)
        }
    }

    private fun promptConvertAll() {
        val todo = currentGameList.filter { it.cueFile != null && !it.isConverted }
        if (todo.isEmpty()) return
        
        AlertDialog.Builder(this).setTitle("Convert All").setMessage("Convert ${todo.size} games?")
            .setPositiveButton("Start") { _, _ -> lifecycleScope.launch { runBatch(todo) } }
            .setNegativeButton("Cancel", null).show()
    }

    private suspend fun runBatch(todo: List<GameEntry>) {
        toggleControls(false)
        val vcdFolder = currentRootDoc?.findFile("VCD") ?: currentRootDoc?.createDirectory("VCD")
        if (vcdFolder == null) { toggleControls(true); return }

        for ((idx, game) in todo.withIndex()) {
            try {
                withContext(Dispatchers.Main) { 
                    statusText.text = "Batch: ${idx+1}/${todo.size}\n${game.name}"; progressBar.progress = 0 
                }
                performConversion(vcdFolder, game, currentAllFiles) { p, s ->
                    lifecycleScope.launch(Dispatchers.Main) { 
                        progressBar.progress = p; statusText.text = "Batch: ${idx+1}/${todo.size}\n${game.name}\n$s" 
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        toggleControls(true)
        Toast.makeText(this, "Batch Complete!", Toast.LENGTH_SHORT).show()
        currentRootDoc?.let { loadGames(it.uri) }
    }

    private fun startConversion(root: DocumentFile, cue: DocumentFile, files: Array<DocumentFile>) {
        lifecycleScope.launch {
            toggleControls(false)
            try {
                val vcdFolder = root.findFile("VCD") ?: root.createDirectory("VCD") ?: throw Exception("No VCD folder")
                val gameEntry = currentGameList.find { it.cueFile == cue } ?: GameEntry(cue.name ?: "Game", cue, false)
                
                performConversion(vcdFolder, gameEntry, files) { p, s ->
                    lifecycleScope.launch(Dispatchers.Main) { progressBar.progress = p; statusText.text = s }
                }
                Toast.makeText(this@MainActivity, "Done!", Toast.LENGTH_SHORT).show()
                loadGames(root.uri)
            } catch (e: Exception) {
                AlertDialog.Builder(this@MainActivity).setMessage(e.message).setPositiveButton("OK", null).show()
            } finally { toggleControls(true) }
        }
    }

    private suspend fun performConversion(vcdFolder: DocumentFile, game: GameEntry, files: Array<DocumentFile>, onProgress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        val cueDoc = game.cueFile!!
        val rawName = cueDoc.name?.substringBeforeLast(".") ?: "game"
        val baseName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        
        val cueData = cueParser.parse(cueDoc, files)
        
        val outFile = vcdFolder.findFile("${baseName}.VCD")?.apply { delete() } 
            ?: vcdFolder.createFile("application/octet-stream", "${baseName}.VCD")!!
            
        contentResolver.openOutputStream(outFile.uri)?.use { 
            vcdConverter.convert(cueData, it, onProgress) 
        }
    }

    private fun toggleControls(enabled: Boolean) {
        changeFolderButton.isEnabled = enabled; refreshButton.isEnabled = enabled; convertAllButton.isEnabled = enabled
        progressContainer.visibility = if (enabled) View.GONE else View.VISIBLE
        for (i in 0 until gamesListContainer.childCount) { (gamesListContainer.getChildAt(i) as? LinearLayout)?.getChildAt(2)?.isEnabled = enabled }
    }
}