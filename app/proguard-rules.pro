# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Jsoup
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Kotlin
-dontwarn kotlinx.coroutines.**
