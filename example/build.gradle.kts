import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

// 最小手测 app。形态照 RC `examples/purchase-tester` 裁剪（那边有 5 个 fragment +
// navigation + viewBinding + 三个商店 flavor；这里只留手测真机清单需要的那几个动作）。
//
// **它的第二个身份是 consumer ProGuard 规则的门禁**：`assembleRelease` 开着
// `minifyEnabled true`，aar 里带出来的 `consumer-rules.pro` 漏了什么，R8 会在这里直接删掉，
// 然后 `scripts/r8-check.sh` 把「公开面还在不在」变成可断言的产物（mapping / seeds / usage）。
//
// **key 绝不入库**：从 `local.properties`（已在 `.gitignore` 里）读，缺失时编出一个空串，
// app 启动后显示「没配 key」而不是崩溃。见 `example/README.md`。

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProperty(name: String, fallback: String = ""): String =
    (project.findProperty(name) as String?) ?: localProperties.getProperty(name, fallback)

android {
    namespace = "org.revdog.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.revdog.example"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        // 手测 app 的版本号跟着 SDK 走：真机清单里「这次装的是哪版 SDK」一眼可见。
        versionName = project.property("VERSION_NAME") as String

        buildConfigField("String", "REVENUEDOG_API_KEY", "\"${localProperty("REVENUEDOG_API_KEY")}\"")
        buildConfigField("String", "REVENUEDOG_BASE_URL", "\"${localProperty("REVENUEDOG_BASE_URL")}\"")
        buildConfigField("String", "REVENUEDOG_APP_USER_ID", "\"${localProperty("REVENUEDOG_APP_USER_ID")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            // **门禁的全部意义在这一行**：release 构建走 R8，SDK 的 consumer 规则必须自足。
            isMinifyEnabled = true
            // 资源压缩关掉：手测 app 一个资源都没有，开了只会给门禁多引入一个变量。
            isShrinkResources = false
            // `proguard-rules.pro` 里**只有 `-print*` 指令、没有任何 keep**：
            // R8 的四份产物（mapping / seeds / usage / configuration）是 `scripts/r8-check.sh`
            // 的断言对象，默认 AGP 只写 mapping.txt。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没有签名配置：`assembleRelease` 产出 unsigned apk 就够了 ——
            // 门禁要的是「R8 跑完、公开面还在」，不是一个能上架的包。
            // 装到真机手测用 `installDebug`（真机清单的前置，见 device checklist §0）。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":purchases"))

    // **故意**把 org.json 加成真实依赖：它平时由 Android framework 提供、R8 当 library class
    // 不碰，于是「SDK 的 org.json keep 规则到底有没有用」根本验不到。有些宿主会（显式或
    // 传递地）把它拉进 classpath，那一刻 org.json 变成可裁剪的 program class ——
    // 这里复现那个场景，`scripts/r8-check.sh` 断言 SDK 的 consumer 规则把它 keep 住了。
    implementation(libs.json)

    detektPlugins(project(":detekt-rules"))
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin"))
}
