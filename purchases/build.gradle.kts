import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.poko)
    alias(libs.plugins.detekt)
    alias(libs.plugins.metalava)
    `maven-publish`
}

// Kotlin 支持来自 AGP 9 的内置集成（`android.builtInKotlin`，默认开）：
// 不再 apply `org.jetbrains.kotlin.android`，它与 AGP 9 的 new DSL 不兼容。

android {
    namespace = "org.revdog.purchases"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // consumer ProGuard 规则随包（设计 §7）。
        consumerProguardFiles("consumer-rules.pro")
    }

    // 不开 BuildConfig：版本号硬编码在 `common/Config.kt`（对照 RC `Config.frameworkVersion`），
    // 由 `ConfigVersionTest` 与 gradle.properties / 版本目录对账。
    // 顺带把生成的 `BuildConfig` 挡在 metalava 公开面之外。
    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    // 单一变体（考古 §9.2：不要 productFlavors）。
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// ---- Maven 发布（ADR 0072：自托管静态仓库 `https://maven.revdog.org/releases`）----
//
// 坐标 `org.revdog:purchases:<VERSION_NAME>`，POM 元数据在 `gradle.properties` 的 POM_* 段。
// `afterEvaluate` 是必须的：`components["release"]` 由 AGP 在自己的 afterEvaluate 里才注册
// （`singleVariant("release")` 决定了只有这一个）。
//
// Gradle 这一侧**只发到本地 staging 目录**（`build/maven-staging`，标准 Maven 布局 + 校验和），
// 不持有任何凭据、不碰网络；上传 R2 由 `scripts/sdk-android-maven-publish.sh` 做，
// 门禁（版本不可变、与公开仓库 tag 同源、先拉回远端 maven-metadata.xml 再合并）也都在那里。
// 不引入第三方发布插件：手写 `maven-publish` 足够，少一个要跟 AGP 版本对齐的依赖。
//   ./gradlew :purchases:publishReleasePublicationToStagingRepository
//   ./gradlew :purchases:publishToMavenLocal        # 本地宿主联调用
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.property("GROUP") as String
                artifactId = project.property("POM_ARTIFACT_ID") as String
                version = project.property("VERSION_NAME") as String
                pom {
                    name.set(project.property("POM_NAME") as String)
                    description.set(project.property("POM_DESCRIPTION") as String)
                    url.set(project.property("POM_URL") as String)
                    licenses {
                        license {
                            name.set(project.property("POM_LICENCE_NAME") as String)
                            url.set(project.property("POM_LICENCE_URL") as String)
                            distribution.set(project.property("POM_LICENCE_DIST") as String)
                        }
                    }
                    developers {
                        developer {
                            id.set(project.property("POM_DEVELOPER_ID") as String)
                            name.set(project.property("POM_DEVELOPER_NAME") as String)
                        }
                    }
                    scm {
                        url.set(project.property("POM_SCM_URL") as String)
                        connection.set(project.property("POM_SCM_CONNECTION") as String)
                        developerConnection.set(project.property("POM_SCM_DEV_CONNECTION") as String)
                    }
                }
            }
        }
        repositories {
            maven {
                name = "staging"
                url = uri(layout.buildDirectory.dir("maven-staging"))
            }
        }
    }
}

// 宿主兼容（0.1.4，对照 RC `purchases/build.gradle.kts` 的 `kotlinLanguage`）：
// 编译器用版本目录的 `kotlin`（2.4.x），但产物按 `kotlinLanguage` 出 —— 类的 Kotlin metadata
// 版本 = languageVersion，宿主编译器最多只能读「自身 + 1」个小版本的 metadata
// （0.1.3 出的是 2.4，AGP 9.2 内置的 Kotlin 2.2.10 读不了，宿主直接编译失败）。
// apiVersion 同值：源码里用不到比它新的 stdlib API。coreLibrariesVersion 决定 POM /
// Gradle module 里声明的 kotlin-stdlib 版本（不设就是编译器版本 2.4.20，把宿主的 stdlib 顶到 2.4）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(KotlinVersion.fromVersion(libs.versions.kotlinLanguage.get()))
        apiVersion.set(KotlinVersion.fromVersion(libs.versions.kotlinLanguage.get()))
    }
    coreLibrariesVersion = libs.versions.kotlinStdlib.get()
}

// 同一件事的另一半：poko 插件把 `poko-annotations` 自动加成 `implementation`（发布成 runtime 依赖，
// RC 也是这样发的），而 poko-annotations 0.23.1 的 POM 要 kotlin-stdlib **2.4.0** —— 宿主的
// runtime classpath 于是被顶到 stdlib 2.4。Hilt 的聚合编译（`hiltJavaCompile*`）按 runtime classpath
// 编译、用自带的 kotlin-metadata-jvm（Hilt 2.59.2 = 2.2.20，最多读 2.3）读 `kotlin.Metadata`，直接失败。
// `@Poko` 是 SOURCE retention，产物字节码里零引用；这里只切掉它传递的 stdlib，不动它本身的版本。
configurations.configureEach {
    dependencies.withType<ExternalModuleDependency>().configureEach {
        if (group == "dev.drewhamilton.poko" && name == "poko-annotations") {
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        }
    }
}

// 公开面只进不出：显式 API 模式强制 public 声明写出可见性与返回类型。
// **只加在生产 source set 上** —— 测试里写 `class FooTest` 不该被逼着加 `public`。
tasks.withType<KotlinCompile>().configureEach {
    if (!name.contains("UnitTest") && !name.contains("AndroidTest")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// 版本号对账用（ConfigVersionTest）：把构建期的真相源喂给单测。
tasks.withType<Test>().configureEach {
    systemProperty("revdog.versionName", project.property("VERSION_NAME") as String)
    systemProperty("revdog.billingClientVersion", libs.versions.billingClient.get())
    // 错误码表的两端唯一真相源（sdk/error-codes.json）。ErrorCodesContractTest 读它对账。
    systemProperty("revdog.errorCodesJson", rootProject.file("../error-codes.json").absolutePath)
}

dependencies {
    // `api` 而不是 `implementation`：宿主要能直接引用 BillingClient 类型（对照 RC `api(libs.billing)`）。
    // 坐标是**纯 `billing`**（0.2.0 起，ADR 0099 决定 2；RC 10.22.1 同样是纯 billing）：0.1.x 用的
    // `billing-ktx` 带 Kotlin metadata 2.3.0，经这条 `api` 传给宿主编译器，宿主 Kotlin 下限因此被抬到 2.2；
    // SDK 源码全部是回调形态，从没用过 ktx 的挂起扩展。换掉之后下限由 coroutines 1.11（metadata 2.2）决定 → Kotlin 2.1。
    api(libs.billing)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common)
    api(libs.coroutines.core)
    implementation(libs.coroutines.android)

    detektPlugins(project(":detekt-rules"))

    testImplementation(libs.junit)
    testImplementation(libs.assertJ)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    // Android 在单测里把 org.json 打成 stub；塞一个真实实现，让解析路径真的被跑到
    // （对照 RC `purchases/build.gradle.kts:222`）。
    testImplementation(libs.json)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    // **故意不配 baseline**：从第一天就零遗留（RC 的 baseline 有 241 条，
    // 其中 24 条就是它自己的 ForbiddenPublicEnum）。要么改代码，要么最小范围 @Suppress。
    source.setFrom(files("src/main/kotlin"))
}

// metalava：公开 API 基线（考古 §8.4 三件套之一）。
// 生成的 BuildConfig 不进基线 —— 它随构建类型变化，不是我们承诺的公开面。
metalava {
    filename.set("api/purchases.api")
    hiddenAnnotations.add("org.revdog.purchases.InternalRevenueDogAPI")
    arguments.addAll(listOf("--hide", "ReferencesHidden"))
}
