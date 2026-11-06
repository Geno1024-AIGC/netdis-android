package com.geno1024.netdis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.geno1024.netdis.ui.theme.NetDisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetDisTheme {
                Scaffold { inner ->
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        Text("NetDis", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
    }
}
