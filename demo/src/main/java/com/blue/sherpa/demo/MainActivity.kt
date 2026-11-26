package com.blue.sherpa.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blue.sherpa.ui.theme.SherpaTheme

/** Demo 入口：两个功能独立进入，避免 ASR 与 TTS 的模型加载互相拖慢。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SherpaTheme {
                val context = LocalContext.current
                Scaffold { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp),
                    ) {
                        Text("ASR TTS Android Demo")
                        Text(
                            "ASR 与 TTS 模型都在外部目录，不进 APK。",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = {
                                context.startActivity(Intent(context, AsrActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        ) { Text("语音识别 ASR（中英双语 + 唤醒）") }
                        Button(
                            onClick = {
                                context.startActivity(Intent(context, TtsActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("文字转语音 TTS（中英双语）") }
                    }
                }
            }
        }
    }
}
