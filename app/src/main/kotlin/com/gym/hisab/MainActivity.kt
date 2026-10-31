package com.gym.hisab

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gym.hisab.ui.HisabRoot
import com.gym.hisab.ui.HisabViewModel
import com.gym.hisab.ui.theme.HisabTheme

class MainActivity : AppCompatActivity() {
    private val vm: HisabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by vm.prefs.theme.collectAsStateWithLifecycle()
            HisabTheme(theme) { HisabRoot(vm) }
        }
    }
}
