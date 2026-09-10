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

android {
    namespace = "com.firefly.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.firefly.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 通过 instrumentation 参数把私密配置传进测试，避免写死在源码里
        listOf(
            "ff.smb.host", "ff.smb.share", "ff.smb.user",
            "ff.smb.pass", "ff.smb.domain", "ff.smb.root",
        ).forEach { key ->
            localSecrets[key]?.let { testInstrumentationRunnerArguments[key] = it }
        }

        ndk {
            // 电视 armeabi-v7a / arm64-v8a；模拟器 x86（缺了会直接崩）
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = false
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

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
