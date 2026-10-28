package io.github.drtron1c.cloudphotos.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CloudPhotosTheme { AppRoot(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onBackgrounded()
    }
}
