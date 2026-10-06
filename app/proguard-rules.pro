# ===== Izuki JS ProGuard/R8 规则 =====

# 保留源码行号，便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 原生方法名不可混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===== JS 桥接层（通过反射调用 @JavascriptInterface 方法）=====
-keep class com.benton.izukijs.runtime.api.** { *; }
-keepclassmembers class com.benton.izukijs.runtime.api.** {
    @android.webkit.JavascriptInterface <methods>;
}

# ===== QuickJS =====
-keep class com.quickjs.** { *; }
-keepclassmembers class com.quickjs.** { *; }
-dontwarn com.quickjs.**

# ===== Shizuku / Sui =====
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# ===== OpenCV（JNI 按名注册，需完整保留）=====
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ===== MLKit =====
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ===== 系统组件 =====
-keep class com.benton.izukijs.IzukiApp { *; }
-keep class com.benton.izukijs.MainActivity { *; }
-keep class com.benton.izukijs.service.** { *; }
-keep class com.benton.izukijs.schedule.** { *; }

# ===== Kotlin / 协程 =====
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# 保留枚举的 values()/valueOf()
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
