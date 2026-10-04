# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

#-dontobfuscate


# ---- EZlator release keep rules / EZlator 릴리즈 유지 규칙 ----
# Release builds run R8 (minifyEnabled true) but this file had no rules. The app
# is called back from native code by name (JNI) and several libraries work
# through reflection, so renaming/removing them breaks only the RELEASE build.
# 릴리즈 빌드는 R8(minifyEnabled true)을 쓰는데 이 파일에 규칙이 하나도 없었음.
# 앱은 네이티브 코드에서 이름으로 역호출되고(JNI), 여러 라이브러리가 리플렉션으로
# 동작해서, 이름이 바뀌거나 지워지면 릴리즈 빌드에서만 깨짐

# App classes: JNI callbacks and reflection (e.g. Shortcut extras via Field)
# 앱 클래스: JNI 역호출과 리플렉션(예: Field로 접근하는 Shortcut 값)
-keep class com.winlator.** { *; }

# JavaSteam + depot downloader: protobuf messages, callbacks resolved by class,
# kotlinx.serialization (depot.config)
# JavaSteam + depot 다운로더: protobuf 메시지, 클래스로 찾는 콜백,
# kotlinx.serialization(depot.config)
-keep class in.dragonbra.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }

# Crypto provider swapped in at runtime by name (SteamCrypto) / 이름으로 교체되는 암호 공급자
-keep class org.bouncycastle.** { *; }

# QR code generator (ZXing) / QR 코드 생성기(ZXing)
-keep class com.google.zxing.** { *; }

# Libraries referencing optional classes that are not on Android
# 안드로이드에 없는 선택적 클래스를 참조하는 라이브러리 경고 무시
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn javax.naming.**
-dontwarn org.apache.commons.**
-dontwarn kotlinx.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**

# conscrypt / openjsse were removed from dependencies. Network
# libraries such as OkHttp may still mention them as optional TLS providers,
# so R8 is told not to fail on the missing classes in release builds.
# conscrypt / openjsse를 의존성에서 뺌. OkHttp 같은 네트워크 라이브러리가
# 선택적 TLS 공급자로 이름만 참조할 수 있으므로, 릴리즈 빌드에서 R8이 없는
# 클래스 때문에 실패하지 않게 함
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# zstd-jni: its native code looks up Java fields BY NAME (srcPos, dstPos, ...)
# through JNI GetFieldID. R8 renamed them in the first release build, and the
# app aborted on first launch while unpacking imagefs (logcat 2026-09-30 21:16:
# no "J" field "srcPos" in ZstdInputStreamNoFinalizer -> SIGABRT).
# Keep every class and member so the names match what the .so expects.
# zstd-jni: 네이티브 코드가 JNI GetFieldID로 Java 필드를 이름으로 찾음
# (srcPos, dstPos 등). 첫 릴리즈 빌드에서 R8이 이 이름을 바꿔서, 첫 실행 때
# imagefs 압축 해제 중 앱이 강제 종료됐음(로그캣 2026-09-30 21:16:
# ZstdInputStreamNoFinalizer에 "srcPos" 필드 없음 -> SIGABRT).
# .so가 기대하는 이름과 맞도록 클래스와 멤버를 전부 유지함
-keep class com.github.luben.zstd.** { *; }
