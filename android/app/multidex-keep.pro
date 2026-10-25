# 主 dex 必须包含的类：Android 4.4 的 Dalvik 在 MultiDex.install 之前只能看到 classes.dex。
-keep class com.hgtv.star.MainActivity { *; }
-keep class androidx.multidex.** { *; }
