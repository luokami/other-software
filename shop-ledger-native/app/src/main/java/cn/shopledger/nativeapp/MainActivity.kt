package cn.shopledger.nativeapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity: ComponentActivity() {
    private val model: LedgerModel by viewModels()
    private val jsonFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.exported(it) }
    private val csvFile=registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { model.exported(it) }
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { model.imported(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        model.initialize()
        lifecycleScope.launch { model.files.collect { event -> when(event) {
            is FileEvent.Export -> if(event.file.mime=="application/json")jsonFile.launch(event.file.name) else csvFile.launch(event.file.name)
            FileEvent.Import -> importFile.launch(arrayOf("application/json","text/plain","application/octet-stream"))
        } } }
        setContent { LedgerApp(model) }
    }
    override fun onPause() { model.lockForBackground();super.onPause() }
    override fun onResume() { super.onResume();model.resume() }
}
