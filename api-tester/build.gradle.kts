plugins {
    alias(libs.plugins.android.library)
}

// 结构对照 RC `api-tester/`：Java 与 Kotlin 各调一遍全部公开 API。
// **只编译、不运行** —— 编译不过就说明公开面发生了破坏性变更（比 api.txt 更硬的门禁：
// api.txt 只记录符号，这里验证 Java 调用方真的能用）。

android {
    namespace = "org.revdog.apitester"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
}
