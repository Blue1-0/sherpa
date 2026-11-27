package com.blue.voice.demo

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blue.voice.tts.VoiceTts
import com.blue.voice.ui.theme.VoiceTheme

/** TTS 测试页：进入页面才加载约 130MB 的中英双语模型，避免拖慢 App 启动。 */
class TtsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceTheme {
                TtsDemoScreen()
            }
        }
    }
}

@Composable
private fun TtsDemoScreen() {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("你好，欢迎使用离线语音合成。Hello, this is offline TTS.") }
    var state by remember { mutableStateOf(VoiceTts.state.name) }
    var modelInfo by remember { mutableStateOf(VoiceTts.modelInfo(context)) }
    var status by remember { mutableStateOf("") }
    var audioInfo by remember { mutableStateOf("") }
    var latencyInfo by remember { mutableStateOf("尚未合成") }
    var speed by remember { mutableStateOf(1.0f) }

    DisposableEffect(Unit) {
        TtsBus.listener = object : VoiceTts.Callback {
            override fun onState(newState: VoiceTts.State) {
                state = when (newState) {
                    VoiceTts.State.IDLE -> "空闲"
                    VoiceTts.State.INITIALIZING -> "初始化中（中英模型约 130MB）"
                    VoiceTts.State.READY -> "就绪"
                    VoiceTts.State.SYNTHESIZING -> "合成中"
                    VoiceTts.State.PLAYING -> "播放中"
                    VoiceTts.State.ERROR -> "错误"
                }
            }

            override fun onReady() {
                modelInfo = VoiceTts.modelInfo(context)
                status = "模型就绪，可以合成"
            }

            override fun onFirstAudioLatency(latencyMs: Int) {
                latencyInfo = "${latencyMs} ms（点击 → 开始播放）"
            }

            override fun onStart(text: String, sampleRate: Int, durationMs: Int) {
                status = "正在播放：${text.take(24)}"
                audioInfo = "采样率 ${sampleRate}Hz，时长 ${durationMs}ms"
            }

            override fun onComplete(text: String, frames: Int) {
                status = "播放完成"
                audioInfo = "采样数 $frames，文本：${text.take(24)}"
            }

            override fun onError(message: String) { status = "错误：$message" }
        }
        // init 是幂等的：已初始化时只替换回调，不会重复加载模型。
        VoiceTts.init(context.applicationContext as Application, callback = TtsBus.relay)
        onDispose { TtsBus.listener = null }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("文字转语音 Demo（中英双语，离线）")
            Text("状态：$state", modifier = Modifier.padding(top = 12.dp))
            Text("提示：$status", modifier = Modifier.padding(top = 4.dp))
            Text("模型：$modelInfo", modifier = Modifier.padding(top = 4.dp))
            Text("音频：$audioInfo", modifier = Modifier.padding(top = 4.dp))
            Text("首句耗时：$latencyInfo", modifier = Modifier.padding(top = 4.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("要合成的文本") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            Row(modifier = Modifier.padding(top = 12.dp)) {
                Button(
                    onClick = {
                        latencyInfo = "计时中…"
                        VoiceTts.synthesize(inputText, speed = speed)
                    },
                    enabled = VoiceTts.isReady,
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text("合成并播放") }

                Button(onClick = { VoiceTts.stop() }) { Text("停止播放") }
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { speed = if (speed == 1.0f) 1.2f else 1.0f }) {
                    Text("语速：${speed}（点击切换）")
                }
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = { VoiceTts.release() },
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text("释放引擎") }
                Button(onClick = {
                    VoiceTts.init(context.applicationContext as Application, callback = TtsBus.relay)
                }) { Text("重新初始化") }
            }

            Text(
                "中英双语离线合成，模型放外部私有目录不进 APK；按句边合成边播放，首句耗时见上方。",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
