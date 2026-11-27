# ============================================================
# lib_tts 消费者混淆规则（随 AAR 自动合并到使用方的 R8 配置）
# ============================================================

# 引擎 JNI 的 native 方法由 C++ 按方法名查找，混淆/裁剪会导致
# UnsatisfiedLinkError / NoSuchMethodError。引擎 AAR 自带 proguard.txt 为空，
# 必须由本库显式保住。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 本库公开 API：门面 VoiceTts、Config/Callback、模型目录约定、播放封装类。
# 使用方通过 AAR 二进制引用，保住避免回调接口方法被重命名或裁剪。
-keep class com.blue.voice.tts.** { *; }

# 保留注解/泛型签名/内部类信息（Kotlin 数据类、协程回调场景）
-keepattributes *Annotation*, Signature, Exceptions, InnerClasses
-dontwarn com.k2fsa.sherpa.onnx.**
