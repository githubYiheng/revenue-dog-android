plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 自定义 detekt 规则（结构对照 RC `detekt-rules/`）。
// 存在理由只有一个：public enum 会破坏二进制兼容——后端加一个枚举值，
// 宿主里穷尽的 `when` 就会在源码层面炸掉。

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.junit)
    testImplementation(libs.assertJ)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
