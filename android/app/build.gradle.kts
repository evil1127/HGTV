import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
// 预载版：上游仓库里没有 com.efs.sdk.plugin（APM 插桩插件）的来源，公开构建会失败，这里不套用。
// 崩溃上报的 SDK 依赖照常保留，只是少了页面性能插桩。
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hgtv.star"
    compileSdk = 36
    defaultConfig {
        // 预载版（.github/workflows/build-preload.yml）用另一个包名与名称打包，可以和正式版并存。
        applicationId = System.getenv("HGTV_APPLICATION_ID") ?: "com.hgtv.star"
        manifestPlaceholders["appLabel"] = System.getenv("HGTV_APP_LABEL") ?: "红果TV"
        minSdk = 19
        // 钉在 29：这是**传统存储模式**（`requestLegacyExternalStorage`）还能生效的最高版本，
        // 而传统模式下一个 `WRITE_EXTERNAL_STORAGE` 就等于整个 `/sdcard` 的读写权——
        // 会话镜像因此可以写自建的 `/sdcard/.hgtv/`（卸载不删），重装后直接恢复登录。
        // 30 起该开关被忽略，10 及以上就只能写自己的 `Android/data`（卸载必删）。
        // 代价：Google Play 要求 34+，本应用是自签分发、不进 Play，不受影响。
        targetSdk = 29
        multiDexEnabled = true
        versionCode = System.getenv("HGTV_VERSION_CODE")?.toInt() ?: 59
        versionName = System.getenv("HGTV_VERSION_NAME") ?: "1.2.4"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HGTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HGTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HGTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HGTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        // 两个构建类型都要钉主 dex：minSdk 19 时 Dalvik 在 MultiDex.install 之前只能看到 classes.dex，
        // 被 R8 改名/裁剪过的启动链路一旦落到第二个 dex，4.4 上会直接 NoClassDefFoundError。
        // debug 也用发布签名：这样 `adb install -r` 能在「线上正式包」和「本地调试包」之间
        // 直接覆盖安装，不必先卸载（卸载会清掉本机记录、收藏、登录态，验证时要重新走一遍）。
        // 代价：debug 包与正式包共用同一个签名密钥，不能再靠签名区分渠道；本项目自签分发，
        // 没有别的渠道，因此没有影响。密钥缺失时（例如没配 keystore.properties 的机器）
        // 回落到默认 debug 签名，构建不会因此失败。
        getByName("debug") {
            multiDexKeepProguard = file("multidex-keep.pro")
            signingConfig = runCatching { signingConfigs.getByName("delivery") }
                .getOrNull()?.takeIf { it.storeFile?.exists() == true && it.storePassword != null && it.keyAlias != null }
                ?: signingConfigs.getByName("debug")
        }
        getByName("release") { multiDexKeepProguard = file("multidex-keep.pro")
            // 预载版：没有发布密钥时退回 debug 签名，好让公开仓库也能打出经过 R8 精简的正式包。
            signingConfig = signingConfigs.getByName("delivery").takeIf { it.storeFile?.exists() == true } ?: signingConfigs.getByName("debug"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    lint {
        // targetSdk 钉在 29 是刻意的（见上面的注释：传统存储模式要它才能写 /sdcard/.hgtv/）。
        // `ExpiredTargetSdkVersion` 只在「上架 Google Play」这一前提下成立，本应用自签分发、
        // 不进 Play；不关掉它，`assembleRelease` 的 lintVitalRelease 会直接中断打包。
        disable += "ExpiredTargetSdkVersion"
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module")
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.vectordrawable:vectordrawable:1.1.0")
    implementation("androidx.media:media:1.6.0")
    implementation("com.google.zxing:core:3.4.1")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    implementation(project(":core")) { exclude(group = "org.json") }
    // 源码用的是 androidx.media3 的包名；1.1.1 与 ExoPlayer 2.19.1 同代，仍支持 Android 4.4（API 19）。
    implementation("androidx.media3:media3-exoplayer:1.1.1")
    implementation("androidx.media3:media3-ui:1.1.1")
    // 友盟：统计 + APM（崩溃 / ANR / 性能）。`common` 与 `asms` 用 `+` 是官方建议的写法，
    // `apm` 钉版本（1.9.13 是本项目验证过的那一版）。
    implementation("com.umeng.umsdk:common:+")
    implementation("com.umeng.umsdk:asms:+")
    implementation("com.umeng.umsdk:apm:1.9.13")
}
