package com.blue.sherpa.demo

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
import com.blue.sherpa.tts.SherpaTts
import com.blue.sherpa.ui.theme.SherpaTheme

/** TTS 测试页：进入页面才加载 163MB 模型，避免拖慢 App 启动。 */
class TtsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SherpaTheme {
                TtsDemoScreen()
            }
        }
    }
}

@Composable
private fun TtsDemoScreen() {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("你好，小卓小卓。欢迎使用离线语音合成。") }
    var state by remember { mutableStateOf(SherpaTts.state.name) }
    var modelInfo by remember { mutableStateOf(SherpaTts.modelInfo(context)) }
    var status by remember { mutableStateOf("") }
    var audioInfo by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        TtsBus.listener = object : SherpaTts.Callback {
            override fun onState(newState: SherpaTts.State) {
                state = when (newState) {
                    SherpaTts.State.IDLE -> "空闲"
                    SherpaTts.State.INITIALIZING -> "初始化中（模型 163MB，首次稍慢）"
                    SherpaTts.State.READY -> "就绪"
                    SherpaTts.State.SYNTHESIZING -> "合成中"
                    SherpaTts.State.PLAYING -> "播放中"
                    SherpaTts.State.ERROR -> "错误"
                }
            }

            override fun onReady() {
                modelInfo = SherpaTts.modelInfo(context)
                status = "模型就绪，可以合成"
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
        SherpaTts.init(context.applicationContext as Application, callback = TtsBus.relay)
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
                    onClick = { SherpaTts.synthesize(inputText) },
                    enabled = SherpaTts.isReady,
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text("合成并播放") }

                Button(onClick = { SherpaTts.stop() }) { Text("停止播放") }
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = { SherpaTts.release() },
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text("释放引擎") }
                Button(onClick = {
                    SherpaTts.init(context.applicationContext as Application, callback = TtsBus.relay)
                }) { Text("重新初始化") }
            }

            Text(
                "模型从应用外部私有目录加载，不进 APK；首次进入本页需等待加载。",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
