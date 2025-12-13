package com.example.psxvcdconverter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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

    // Helpers
    private val vcdConverter by lazy { VCDConverter(contentResolver) }
    private val cueParser by lazy { CueParser(contentResolver) }
    private val folderManager by lazy { FolderManager(this) }
    private val gameIdScanner by lazy { GameIdScanner(contentResolver) }
    private lateinit var prefs: SharedPreferences

    // State
    private var currentGameList: List<GameEntry> = emptyList()
    private var currentRootDoc: DocumentFile? = null
    private var currentAllFiles: Array<DocumentFile> = emptyArray()
    private var renameWithId: Boolean = true

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

        // Setup Toolbar
        val toolbar: Toolbar = findViewById(R.id.my_toolbar)
        setSupportActionBar(toolbar)

        prefs = getSharedPreferences("psx_prefs", Context.MODE_PRIVATE)
        renameWithId = prefs.getBoolean("rename_with_id", true)

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

    // --- MENU LOGIC ---
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu?.findItem(R.id.action_rename_id)?.isChecked = renameWithId
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_rename_id -> {
                renameWithId = !item.isChecked
                item.isChecked = renameWithId
                prefs.edit().putBoolean("rename_with_id", renameWithId).apply()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "1.0" }
        AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage("PSX VCD Converter v$version\n\nCreated by Plant-dc\n\nThis app is FREE. If you paid for it, you were scammed.")
            .setPositiveButton("Close", null)
            .show()
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

            val games = folderManager.scanForGames(rootDoc)
            currentGameList = games
            currentAllFiles = rootDoc.listFiles()

            populateGameList(rootDoc, games)
        }
    }

    // --- UPDATED LIST DESIGN ---
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
            
            val isCompact = game.isConverted
            
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 24, 24, 24)
                
                // Dimmed background for converted items to push them to the "background" of user attention
                setBackgroundColor(if (isCompact) Color.parseColor("#1A1A1A") else Color.parseColor("#252525"))
                
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 8)
                layoutParams = params
                gravity = Gravity.CENTER_VERTICAL
            }

            // TEXT SECTION
            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(this).apply {
                text = if (game.isConverted) "✓ ${game.name}" else game.name
                textSize = 16f
                // Converted = Green, New = White
                setTextColor(if (game.isConverted) Color.parseColor("#00AA00") else Color.WHITE)
            }

            val statusView = TextView(this).apply {
                // If converted, hide the sub-text entirely to save space
                visibility = if (isCompact) View.GONE else View.VISIBLE
                
                text = if (game.cueFile == null) "Source Deleted" else "Ready"
                setTextColor(if (game.cueFile == null) Color.parseColor("#FF6666") else Color.LTGRAY)
                textSize = 12f
            }

            textLayout.addView(titleView)
            textLayout.addView(statusView)

            // BUTTON SECTION
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 100)
                
                if (game.isConverted) {
                    // IF CONVERTED: HIDE BUTTON COMPLETELY
                    visibility = View.GONE
                } else {
                    if (game.cueFile == null) {
                        text = "MISSING"
                        isEnabled = false
                        setBackgroundColor(Color.TRANSPARENT)
                        setTextColor(Color.DKGRAY)
                    } else {
                        text = "CONVERT"
                        textSize = 12f
                        setPadding(30, 0, 30, 0)
                        setBackgroundColor(Color.parseColor("#003DA5"))
                        setTextColor(Color.WHITE)
                        setOnClickListener { startConversion(rootDoc, game.cueFile!!, currentAllFiles) }
                    }
                }
            }

            row.addView(textLayout)
            row.addView(btn)
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
        var rawName = cueDoc.name?.substringBeforeLast(".") ?: "game"
        
        onProgress(0, "Parsing CUE...")
        val cueData = cueParser.parse(cueDoc, files)

        if (renameWithId) {
            onProgress(0, "Scanning for Game ID...")
            val firstBin = cueData.binUris.firstOrNull()
            val gameId = gameIdScanner.scan(firstBin)
            
            if (gameId != null && !rawName.contains(gameId)) {
                rawName = "${gameId}.${rawName}"
            }
        }

        val baseName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        
        val outFile = vcdFolder.findFile("${baseName}.VCD")?.apply { delete() } 
            ?: vcdFolder.createFile("application/octet-stream", "${baseName}.VCD")!!
            
        contentResolver.openOutputStream(outFile.uri)?.use { 
            vcdConverter.convert(cueData, it, onProgress) 
        }
    }

    private fun toggleControls(enabled: Boolean) {
        changeFolderButton.isEnabled = enabled
        refreshButton.isEnabled = enabled
        convertAllButton.isEnabled = enabled
        progressContainer.visibility = if (enabled) View.GONE else View.VISIBLE
        
        // Disable list buttons
        for (i in 0 until gamesListContainer.childCount) { 
            val row = gamesListContainer.getChildAt(i) as? LinearLayout
            // Button is at index 1 in the new layout
            row?.getChildAt(1)?.isEnabled = enabled 
        }
    }
}