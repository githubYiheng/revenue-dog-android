# RevenueDog Android SDK

Revenue Dog 的 Android 客户端 SDK：自建 RevenueCat 式内购后端的 Google Play Billing SDK，
接口命名与 RevenueCat 兼容以便迁移。MIT 许可。

- **minSdk 24**（Android 7.0）· compileSdk 36 · JVM 17 · Kotlin 2.4
- **Play Billing Library 9.1.0**（PBL 8 的强制期限已到；9 改了错误码语义，我方直接以 9 为基线）
- 公开 API 与 iOS SDK 同名同形，三套形态：callback / Kotlin lambda（`…With`）/ suspend + Flow

> **这个仓库是发布产物，不接受 PR。** 开发在私有 monorepo 的 `sdk/android` 目录进行，
> 每次发布用 `git subtree split` 推到这里并打 `vX.Y.Z` tag（历史保留）。
> 下文提到的 `docs/…` 路径都指 monorepo 内的设计文档，本仓库不含。版本纪律见 `CHANGELOG.md`。

## 安装

```kotlin
// settings.gradle.kts —— 仓库由用户定（见下文「发布」）
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// build.gradle.kts
dependencies {
    implementation("org.revdog:purchases:0.1.0")
}
```

> **0.1.0 还没发布到任何 Maven 仓库**（渠道与凭据待用户裁定）。在那之前用源码依赖：
> `includeBuild("…/revenue-dog-android")` 或 `implementation(project(":purchases"))`。

`minifyEnabled true` 的宿主**不需要抄任何 ProGuard 规则** —— 规则随 aar 分发
（`purchases/consumer-rules.pro`），仓库内有端到端门禁 `scripts/r8-check.sh` 守着。

## 接线

### 1. `configure`：放在 `Application.onCreate`

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Purchases.configure(
            PurchasesConfiguration.Builder(this, "pk_…")   // public key，**绝不写死在仓库里**
                .logLevel(if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO)
                .build(),
        )
    }
}
```

**必须这么早**：SDK 在 `configure` 里挂 `onPurchasesUpdated` 监听并发起 BillingClient 连接。
应用外购买（用户在 Play 商店里重订）与「上次没上报成功的交易」都只能在连接成功之后补回来。
挂在某个 Activity 里 = 用户没走到那个页面就丢单。

`Builder` 的其余开关：

| 方法 | 默认 | 说明 |
|---|---|---|
| `appUserID(String?)` | `null` | 宿主是身份源时在这里传；`null` = SDK 生成匿名身份，之后用 `logIn` 切 |
| `baseURL(String)` | `https://api.revdog.org` | 指 staging 时才改 |
| `logLevel(LogLevel)` | `INFO` | 商店构建用 `INFO` 及以上 |
| `diagnosticsEnabled(Boolean)` | `true` | 关掉时 SDK 不记不发，并清空本地队列目录 |
| `purchasesCompletedBy(…)` | `REVENUE_DOG` | `MY_APP` = 宿主自己 ack / consume，SDK 只上报 |
| `pendingTransactionsForPrepaidPlansEnabled(Boolean)` | `false` | 预付费套餐的 pending 交易 |

### 2. 身份：`logIn` / `logOut`

```kotlin
Purchases.sharedInstance.logInWith(firebaseUid) { customerInfo, created -> /* … */ }
```

- 宿主是身份源：**启动后先 `logIn(uid)` 再展示付费墙**，不要用 `$RCAnonymousID` 形态的 id。
- 新旧 id 相同时 SDK **不打后端**（宿主每次启动都调同一个 uid 是常态）。
- `logOut` **先服务端确认再切本地身份**：离线时它会失败，且本地身份、缓存、事件流
  **一个字节都不动**（偏离 RC 的纯本地 logOut —— 那样一旦离线，设备就被留在一个后端从没见过的
  匿名 ID 上，身份分裂）。
- `appAccountToken` / `obfuscatedAccountId` 由 SDK 内部处理，宿主不管。

### 3. offerings 与付费墙文案

```kotlin
Purchases.sharedInstance.getOfferingsWith(onError = { /* 别展示空白付费墙 */ }) { offerings ->
    val pkg = offerings.current?.monthly ?: return@getOfferingsWith
    val product = pkg.product ?: return@getOfferingsWith   // null = Play 上查不到这个商品
    priceLabel.text = product.price.formatted
    trialLabel.isVisible = product.defaultOption?.freePhase != null
}
```

- 一个 productId 在 Play 上会炸成 N 个 `StoreProduct`（N = base plan 数），
  `Package` 用 `platform_product_plan_identifier` 精确匹配到具体那一个。
- Play 上查不到的商品**保留 package、`product` 为 null、进 `offerings.notFoundProductIds`**
  （偏离 RC：RC 丢掉整个 offering，那会让付费墙整块空白）。
- 后端失败时回落磁盘上的原始响应（stale 付费墙优于空白付费墙），并记一条
  `sdk_warning{offerings_cache_fallback}`。

### 4. 购买

```kotlin
Purchases.sharedInstance.purchaseWith(
    purchaseParams = PurchaseParams.Builder(activity, pkg)
        // 升降级：只传旧 productId，旧 purchaseToken 由 SDK 现查
        // .oldProductId("sub_basic").replacementMode(ReplacementMode.CHARGE_PRORATED_PRICE)
        .build(),
    onError = { error, userCancelled ->
        when {
            userCancelled -> Unit
            error.code == PurchasesErrorCode.PurchasePendingServerConfirmation ->
                showToast("支付已完成，正在确认，稍后到账")   // 901：**绝不引导用户重买**
            error.code == PurchasesErrorCode.PurchaseRejectedByServer ->
                showSupportEntry()                            // 902：走客服 / 退款
            else -> showToast(error.message)
        }
    },
) { result ->
    if (result.isPending) {
        // 现金支付 / 待家长批准：Play 受理了但**没扣款**。既不发权益、也不报错，
        // 等 Play 转成 PURCHASED 后 SDK 自动补报。
        showToast("等待支付确认")
    } else {
        grant(result.customerInfo)
    }
}
```

**权益一律以 `CustomerInfo` 为准，端上不自己判**。购买前先订阅 `customerInfoFlow`
（或挂 `updatedCustomerInfoListener`）：补报、续订、退款都从那条通道出。

### 5. restore / sync

```kotlin
Purchases.sharedInstance.restorePurchasesWith { customerInfo -> grant(customerInfo) }  // 用户点「恢复购买」
Purchases.sharedInstance.syncPurchasesWith { customerInfo -> /* 只上报，不碰 Billing */ }
```

PBL 8 起 `queryPurchaseHistoryAsync` 已被删除，端上只看得到**活跃订阅 + 未消耗的一次性商品**。
权威历史在后端（Play Developer API + RTDN），**不要**拿 restore 的结果当历史清单。

### 6. 订阅者属性

```kotlin
val rejected = Purchases.sharedInstance.setAttributes(mapOf("tier" to "gold", "old_key" to null))
Purchases.sharedInstance.setDisplayName("…")      // 保留键有专用 setter
Purchases.sharedInstance.collectDeviceIdentifiers()
Purchases.sharedInstance.syncAttributes()          // 可选：立刻发；否则搭车 / 生命周期钩子自动发
```

fire-and-forget：返回值是**端上就被拒**的键（键名非法 / value > 500 / 触及 50 个自定义属性上限）。
`null` 与空串都是墓碑（上行编码成空串，服务端存 NULL）。

### 7. 诊断

默认**开启**。SDK 在关键节点记结构化事件 → 本地 JSONL 队列 → 攒批
`POST /v1/diagnostics/events`。**不上传任何日志文本、不上传 token / 密钥 / 邮箱姓名**，
错误只带 SDK 自己的 `error_code` / `error_class`。

```kotlin
PurchasesConfiguration.Builder(context, key).diagnosticsEnabled(false).build()
```

关掉时不记不发，并清空本地队列目录。它是 configure 期决定的，改完要重启进程。

> **宿主侧还有两件事**（与 iOS 同）：Play Console 的 Data Safety 表单要自己勾
> User ID / Device ID / Diagnostics；`appUserID` **不得**是邮箱等个人信息。

### 8. ProGuard / R8

宿主什么都不用加。SDK 随 aar 带出 `consumer-rules.pro`，keep 住公开面、Play Billing 的 IPC 回调、
`org.json`（SDK 的整个解析层建在它上面）与 `androidx.lifecycle.DefaultLifecycleObserver`。
仓库内的门禁：`:example:assembleRelease`（`minifyEnabled true`）+ `scripts/r8-check.sh`。

崩溃堆栈里 SDK 的类名是**原名**（consumer 规则不允许改名），排障不需要 mapping 文件。

## 开发（monorepo 内）

```bash
cd sdk/android
./gradlew :purchases:testDebugUnitTest      # 378 条 Robolectric 单测
./gradlew :purchases:lintDebug detekt       # lint + detekt（零 baseline）
./gradlew :api-tester:compileDebugKotlin :api-tester:compileDebugJavaWithJavac
bash scripts/api-check.sh                   # 公开 API 基线门禁
bash scripts/r8-check.sh                    # consumer ProGuard 门禁（跑 :example:assembleRelease）
./gradlew :example:installDebug              # 最小手测 app（key 从 local.properties 注入）
```

改了公开面之后跑 `bash scripts/api-dump.sh` 重新生成基线，**review diff 再提交** ——
`purchases/api/purchases.api` 是「我们承诺了什么」的唯一记录。

## 发布

```bash
bash scripts/sdk-android-release.sh 0.1.0            # dry-run：八道门禁 + 打印将执行的动作
bash scripts/sdk-android-release.sh 0.1.0 --apply    # 真推：subtree split → main → tag v0.1.0
```

八道门禁（任何一条不过就退出，不允许跳过）：语义化版本 + CHANGELOG 条目 · 工作区干净 ·
单测全绿 · 公开 API 基线一致 · `Config.FRAMEWORK_VERSION == gradle.properties VERSION_NAME == 版本号` ·
tag 远端不存在 · 远端 main fast-forward · **R8 / consumer ProGuard**（第八道，Android 专有，
iOS 那边没有对应物）。

**待用户裁定（脚本 dry-run 会把它们打印出来）**：

1. 公开仓库 `github.com/githubYiheng/revenue-dog-android` 需用户创建（公开，MIT）；
2. Maven 发布渠道（GitHub Packages 还是 Maven Central Portal）、账号与凭据；
   Central 还需要 GPG 签名密钥。Gradle 侧配置已就位，凭据走
   `~/.gradle/gradle.properties` 的 `revdogMavenUrl` / `revdogMavenUser` / `revdogMavenPassword`，
   **绝不入库**。

版本纪律：公开 API 基线 diff 有「减」或「改」= 主版本；只「增」= 次版本；无 diff = 修订。
破坏性变更必须在 CHANGELOG 写迁移说明。**tag 不可移动。**
