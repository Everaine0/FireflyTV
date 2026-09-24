plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 本机私密配置：真 NAS 的 SMB 测试账号。
 * 存在根目录的 local.properties（已 gitignore，模板见 local.properties.example）。
 * 只用于插桩测试；应用本身不读这些值 —— 它的配置来自电视上的二维码配置页。
 */
val localSecrets: Map<String, String> = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || !t.contains('=')) return@mapNotNull null
        val k = t.substringBefore('=').trim()
        val v = t.substringAfter('=').trim()
        if (v.isEmpty()) null else k to v
    }
    ?.toMap()
    ?: emptyMap()

/**
 * 同一批私密配置也可以从**环境变量**给（`ff.smb.host` → `FF_SMB_HOST`）。
 *
 * 为什么留这条路：临时借来的凭据不该落到任何文件里 —— 设一次环境变量、用完即散，
 * 既不会进 local.properties，也不会被别的脚本顺手读走。
 *
 * ```
 * $env:FF_SMB_HOST='...'; $env:FF_SMB_SHARE='...'
 * ```
 *
 * 优先级：环境变量赢过 local.properties（临时借用时应该能盖住本机常驻那份）。
 */
val envSecrets: Map<String, String> = System.getenv()
    .filterKeys { it.startsWith("FF_") }
    .mapKeys { (k, _) -> "ff." + k.removePrefix("FF_").lowercase().replace('_', '.') }

val testSecrets: Map<String, String> = localSecrets + envSecrets

android {
    namespace = "com.firefly.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.firefly.tv"
        minSdk = 21
        targetSdk = 34
        // ---- 版本号规则 ----
        // versionCode 每次发版 +1（安卓用它判断「能不能覆盖安装」），
        // versionName 用 x.y.z：修 bug 只动 z，加功能动 y，不兼容的改动动 x。
        //
        // 为什么要较真：同一台电视上装过哪个包、用户报障时手上是哪个版本，
        // 全靠这两个数说话。一直写 1 / 1.0 的话，出了问题连「是不是已经修过的那版」
        // 都判断不了 —— 而这台电视上没有 adb，问不到别的。
        //
        // 版本号在电视上**看得见**：诊断页标题、配置页底部（见 DiagScreen / ConfigServer）。
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 通过 instrumentation 参数把私密配置传进测试，避免写死在源码里
        listOf(
            "ff.smb.host", "ff.smb.share", "ff.smb.user",
            "ff.smb.pass", "ff.smb.domain", "ff.smb.root",
            // 天气私钥是临时的、以后可能换，所以同样不写进仓库
            "ff.weather.key", "ff.weather.location",
            // 直播源探测目标（NetworkDiagTest）：host:port,host:port —— 因运营商而异
            "ff.live.targets",
        ).forEach { key ->
            testSecrets[key]?.let { testInstrumentationRunnerArguments[key] = it }
        }

        ndk {
            // 电视 armeabi-v7a / arm64-v8a；模拟器 x86（缺了会直接崩）。
            // 三个 ABI 都留在包里 = 同一个 APK 电视和模拟器都能装（正式包 ~28MB，
            // 其中 native 库占 24MB）。只想给电视发小包的话，把 x86 去掉即可减 17MB。
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 正式包：压缩 + 摇树 + 去掉资源里没人引用的东西。
            // 规则见 proguard-rules.pro（ijkplayer 的 JNI 回调、smbj/bcprov 的反射、
            // zxing 都要 keep，否则是「装上能开、一播就崩」那类最难查的问题）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // ⚠️ 正式包**复用 debug 签名**，这是有意的：
            // 电视上现在装的就是 debug 包，签名不同会直接装不上去（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
            // 而卸载会把配置和观看记录一起清掉 —— 用户得重新扫码填一遍 NAS。
            // 将来要换成真正的发布签名，就在 local.properties 里加
            //   ff.keystore.path / ff.keystore.pass / ff.keystore.alias / ff.keystore.keypass
            // 然后把这一行改成用它（模板见 local.properties.example）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // smbj / bcprov / android.icu.ChineseCalendar 在 API 21 上都依赖脱糖
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            // 被测类里有 android.util.Log 调用；单测跑在 JVM 上，需要让它返回默认值而不是抛异常
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 播放内核：ijkplayer（本地 AAR）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // SMB2/3
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    // 二维码
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // 注意：**不要**指望给单测加一个真的 org.json。
    // testOptions 里开了 returnDefaultValues，AGP 的 mockable-android.jar 会排在
    // 依赖前面，`JSONObject.optString()` 照样返回 null（实测过）。
    // 所以跟 JSON 有关的断言都放在 androidTest（见 WeatherClientParseTest）。

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
