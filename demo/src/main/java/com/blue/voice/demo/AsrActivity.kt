package com.blue.voice.demo

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.blue.voice.asr.VoiceAsr
import com.blue.voice.ui.theme.VoiceTheme

/**
 * ASR 测试页：引擎在 [VoiceApp] 里 init 一次，这里只注册回调并调用 start/stop。
 * 模型准备：python tools/fetch_sherpa_asr.py --push --package com.blue.voice.demo
 */
class AsrActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceTheme {
                AsrDemoScreen()
            }
        }
    }
}

@Composable
private fun AsrDemoScreen() {
    val context = LocalContext.current
    var modelInfo by remember { mutableStateOf(VoiceAsr.modelInfo(context)) }
    var state by remember { mutableStateOf(VoiceAsr.state.name) }
    var listening by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var resultIsFinal by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var wakeWordText by remember { mutableStateOf("（等待唤醒词）") }
    var wakeActive by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            VoiceAsr.start()
        } else {
            status = "录音权限被拒绝"
        }
    }

    DisposableEffect(Unit) {
        AsrBus.listener = object : VoiceAsr.Callback {
            override fun onState(newState: VoiceAsr.State) {
                state = when (newState) {
                    VoiceAsr.State.IDLE -> "空闲"
                    VoiceAsr.State.INITIALIZING -> "初始化中"
                    VoiceAsr.State.READY -> "就绪"
                    VoiceAsr.State.WAKING -> "唤醒监听中"
                    VoiceAsr.State.LISTENING -> "识别中"
                    VoiceAsr.State.ERROR -> "错误"
                }
                listening = newState == VoiceAsr.State.LISTENING
                if (newState == VoiceAsr.State.IDLE ||
                    newState == VoiceAsr.State.READY ||
                    newState == VoiceAsr.State.ERROR
                ) {
                    wakeActive = false
                }
                if (newState == VoiceAsr.State.LISTENING && wakeActive) {
                    status = "已唤醒，正在识别（请说话）"
                }
            }

            override fun onReady() {
                modelInfo = VoiceAsr.modelInfo(context)
                status = "模型就绪，可以开始识别"
            }

            override fun onPartial(text: String) {
                resultText = text
                resultIsFinal = false
            }

            override fun onFinal(text: String) {
                resultText = text.ifEmpty { "（无识别结果）" }
                resultIsFinal = true
            }

            override fun onWakeWord(keyword: String) {
                wakeWordText = "「$keyword」"
                wakeActive = true
                status = "已唤醒：$keyword"
            }

            override fun onError(message: String) { status = "错误: $message" }
        }
        onDispose { AsrBus.listener = null }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("语音转文字 Demo（中英双语流式）")
            val wakeStatusText = when {
                !wakeActive -> "等待唤醒（默认「小卓小卓」）"
                listening -> "已唤醒 → 自动识别中"
                else -> "已唤醒 → 等待识别"
            }
            Text("唤醒状态：$wakeStatusText", modifier = Modifier.padding(top = 12.dp))
            Text("最近唤醒词：$wakeWordText", modifier = Modifier.padding(top = 4.dp))
            Text(
                "提示：开启唤醒后无需点「开始识别」，说出唤醒词后直接说话即可。",
                modifier = Modifier.padding(top = 4.dp),
            )
            Text("模型：$modelInfo", modifier = Modifier.padding(top = 4.dp))

            Row(modifier = Modifier.padding(top = 16.dp)) {
                Button(
                    onClick = {
                        if (listening) return@Button
                        if (!VoiceAsr.isReady) {
                            status = "模型尚未就绪，请稍候"
                            return@Button
                        }
                        if (VoiceAsr.hasRecordPermission(context)) {
                            VoiceAsr.start()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text("开始识别") }

                Button(onClick = {
                    wakeActive = false
                    VoiceAsr.stop()
                }) { Text("停止识别") }
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    wakeActive = false
                    VoiceAsr.cancel()
                }, modifier = Modifier.padding(end = 12.dp)) {
                    Text("取消")
                }
                Button(onClick = { VoiceAsr.release() }) { Text("释放引擎") }
            }

            Text("提示：$status", modifier = Modifier.padding(top = 12.dp))
            Text(
                text = "状态：$state" + if (resultIsFinal) "（最终结果）" else "（实时）",
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedTextField(
                value = resultText,
                onValueChange = {},
                label = { Text("识别结果") },
                readOnly = true,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Button(
                onClick = {
                    resultText = ""
                    status = ""
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("清空文本") }
        }
    }
}
