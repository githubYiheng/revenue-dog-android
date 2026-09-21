# Changelog

语义化版本。公开 API 基线（`purchases/api/purchases.api`，metalava 生成）有「减」或「改」= 主版本；
只「增」= 次版本；无差异 = 修订号。破坏性变更必须在对应条目里写迁移说明。**tag 一经发布不可移动。**

基线的唯一门禁是 `scripts/api-check.sh`（跑 `api-dump.sh` 再看 git diff）。
发布走 `scripts/sdk-android-release.sh <version>`（默认 dry-run，八道门禁全过才允许 `--apply`）。

## [Unreleased]

## [0.1.1] - 2026-09-21

**只增不改**：公开 API 基线只有新增行（39 行），现有签名与行为一字未动。与 RevenueCat
purchases-android **10.22.1** 同语义（口径逐字对照其源码与单测，见下）。

### 新增：按周期折算价格

`StoreProduct.pricePerDay/pricePerWeek/pricePerMonth/pricePerYear(locale)`、
`StoreProduct.formattedPricePerMonth(locale)`、
`PricingPhase.pricePerDay/pricePerWeek/pricePerMonth/pricePerYear(locale)`、
`Period.valueInMonths`。`locale` 默认取系统 locale（Java 侧有 `@JvmOverloads` 无参重载）。

折算口径（**改一个字就与 RC 对不上**）：

- **按 period 常量换算，不是日历**：周 = 7 天、月 = 30 天、年 = 365 天。
  所以 **1 年 = 365 / 7 ≈ 52.142857 周**（不是 52 周），**1 月 = 365 / 12 / 7 ≈ 4.345238 周**
  （不是 4 周），1 年 = 12 月。
- 金额 = `amountMicros ÷ 目标周期数`，结果**向零截断**（不是四舍五入）；返回的
  `Price.amountMicros` 就是这个未舍入值，`currencyCode` 原样带过来。
- `formatted` 在 `amountMicros` 基础上再按币种的 `defaultFractionDigits` 做一次 **FLOOR**
  （只向下），然后用 `NumberFormat.getCurrencyInstance(locale)` 渲染。
- **都是近似值**，只用于展示，不要拿来算钱。
- `StoreProduct` 上算的是 **base plan**（用 `price` + `period`），**不是 `defaultOption`** ——
  免费试用 / 折扣阶段一概不参与；要按某个 offer 的某个阶段折算，用 `PricingPhase.pricePerX`。
- **无法折算返回 `null`**：一次性商品（`period == null`）、周期 ISO 8601 解析不了
  （`Period.Unit.UNKNOWN`）、币种代码不是合法 ISO 4217。

两处**有意偏离 RC**（都是 fail-loud 方向，签名上表现为 `PricingPhase.pricePerX` 返回可空）：

- 周期单位 `UNKNOWN` 时 RC 把周期数取 `0.0`，`amountMicros / 0.0 = Infinity`，
  `toLong()` = `Long.MAX_VALUE` —— 屏幕上会出现天文数字的「周价」。我方返回 `null`。
- 币种代码非法时 RC 让 `Currency.getInstance` 抛 `IllegalArgumentException`（会炸掉付费墙）。
  我方记一条 error 日志并返回 `null`。

未实现 RC 的 `PricingPhase.formattedPriceInMonths(locale)`：它在 RC 那边一出生就是
`@Deprecated`（替代品是 `pricePerMonth(locale).formatted`），不值得新增一个已弃用的符号。

### 新增：`PricingPhase.offerPaymentMode` 与 `OfferPaymentMode`

付费墙区分「免费试用 / 预付一期 / 折扣连扣」三种文案的判据。判定逐字对照 RC
`PricingPhase.offerPaymentMode`：`recurrenceMode != FINITE_RECURRING` → `null`；
`price.amountMicros == 0` → `FREE_TRIAL`；`billingCycleCount == 1` → `SINGLE_PAYMENT`；
`> 1` → `DISCOUNTED_RECURRING_PAYMENT`；其余 → `null`。

**偏离 RC**：RC 的 `OfferPaymentMode` 是 public enum，我方守 `ForbiddenPublicEnum`，
改成 `@Poko class` + companion 常量（`name` 与 RC 的枚举常量名逐字一致，`ALL` 列全三个值）。
它是本地推导出来的、不从后端下行解析，所以**不带 `UNKNOWN`**，推不出来就是 `null`。

### 修复

- 日志脱敏：`Backend` 合并在飞请求时的 debug 日志原样打印了去重键，而去重键里装着
  **purchaseToken 原文 + app_user_id + 整个 receiptInfo JSON**。改成只打 `sha1` 前 8 位
  （与 `BillingWrapper` 打 token 的做法同款）。行为不变，只影响日志。

## [0.1.0] - 2026-09-21

首个版本。真机清单 D1–D17 已在 license tester 真机上跑完（含 R8 release 包）。分发：公开仓库
`github.com/githubYiheng/revenue-dog-android` + 自托管 Maven 仓库
`https://maven.revdog.org/releases`（见 `README.md`「安装」与「发布」两节）。

Play Billing Library **9.1.0** 基线，`minSdk 24` / `compileSdk 36` / JVM 17。
公开 API 与 iOS SDK 同名同形（三套形态：callback / Kotlin lambda / suspend + Flow）。

### 能力

- **配置与身份**：`Purchases.configure(PurchasesConfiguration)`、`logIn` / `logOut`、
  匿名身份 `$RDAnonymousID:`（**同时识别** `$RCAnonymousID:`，影子期宿主会注入它）。
  `logOut` **先服务端确认再切本地身份，失败零副作用**（偏离 RC 的纯本地 logOut，ADR 0046–0048）。
- **offerings**：后端 offerings + `queryProductDetails` 按 type 分两次查并批量填充；
  磁盘只缓存原始响应（不冻价格），内存缓存成品；Play 上查不到的商品保留 package、`product` 为 null、
  进 `notFoundProductIds`（偏离 RC：RC 直接丢整个 offering）。
- **购买**：`purchase(PurchaseParams)`，支持 base plan / offer（`subscriptionOptionId`）、
  升降级四种 `ReplacementMode`（DEFERRED 的回调挂在**旧**商品上）、`isPersonalizedPrice`；
  `obfuscatedAccountId` = 服务端签发的 `account_token`（不是 appUserID 派生）。
- **可靠性**：先落盘上报上下文再 `launchBillingFlow`；**后端 200 之前不 ack 不 consume**；
  `consumeAndSave` 七分支由后端下发的 `purchased_products[].should_consume` 驱动，
  **该字段缺失 = fail-loud，不 ack 不 consume 不记台账**（偏离 RC 的缺省 false，ADR 0069 决策 2）；
  PENDING 交易完全跳过；补报三条链路（`queryPurchases` 差集 / `isAutoRenewing` diff / 本地上下文残留）。
- **ack 权威在服务端**（偏离 RC，设计 §8）：验证成功即由服务端调 Play 的 acknowledge；
  SDK 只在**首次上报满 24 小时仍未被确认**时自保 ack 一次（A8），并在后续上报带
  `acknowledged_by=sdk_timeout`。自保**按 token 单飞**（回前台并发几轮补报时同一笔只查一次、只记一条诊断）。
  **consume 只在 SDK 做。**
- **restore / sync**：`restorePurchases`（`initiation_source=restore`）与
  `syncPurchases`（`unsynced_active_purchases`，只上报不碰 Billing）。
- **Play in-app messages**：扣款失败时 Google 官方的挽回 snackbar。
  `showInAppMessagesAutomatically` **默认开**（与 RC 一致）—— SDK 注册
  `Application.ActivityLifecycleCallbacks`，每个 Activity 的 `onStart` 展示一次；
  关掉后由宿主调 `Purchases.showInAppMessagesIfNeeded(activity, types)`（类别默认 `InAppMessageType.ALL`）。
  用户在 snackbar 里修好扣款之后触发一次 `syncPurchases`，新权益走 CustomerInfo 通道。
  两处偏离 RC：`InAppMessageType` 是 `@Poko class` 而非 public enum（`ForbiddenPublicEnum`）；
  `close()` **会注销**那组 Activity 回调（RC 不注销，换配置重建后旧实例会一直被唤醒）。
- **订阅者属性**：LWW 本地缓存、保留键全集、50 个自定义属性上限、墓碑语义、
  随 `POST /v1/receipts` 搭车上行，`attributes_error_response` 里出错的键也标已同步。
- **客户端诊断**：JSONL 队列（500 条 / 256 KB）+ 攒批上传 `POST /v1/diagnostics/events`
  + info 级采样 + 指数退避 + 4xx 丢批 + 401 停 1 小时；`Purchases.diagnosticsEnabled` 默认开，
  关掉时不记不发并清空本地目录。事件名与 iOS 共用同一套契约。
- **错误面**：`PurchasesErrorCode` 与 iOS / RC 同位同名；Play 的 13 个响应码逐个映射，
  无一落到 `unknownError`。两个我方专有码：
  `purchasePendingServerConfirmation`（901，钱已扣、服务端未确认、SDK 会重放，**绝不引导用户重买**）、
  `purchaseRejectedByServer`（902，服务端确定性拒绝，走客服 / 退款）。
- **线程**：专用 `HandlerThread("revdog-billing")` 承载 BillingClient 调用，
  后台单线程执行器跑业务 HTTP，主线程 Handler 可空兜底（Flutter / RN 宿主）。

### 上行契约与 iOS / RC 的差异（每条都在 `docs/plan/android-sdk-design.md` §5 登记）

- 价格发 `price_amount_micros` + `currency`（Play 原生 micros；RC 发 Double，有精度问题）。
- `product_ids` 是**数组**（`product_id` 保留为 deprecated 别名）。
- `proration_mode` 发干净枚举名，不用 Google legacy 的 `IMMEDIATE_*`。
- **多发** `X-Client-Build-Version` 头（RC Android 不发；我方 `last_seen_app_build` 靠它）。
- 不发 `store_user_id`（Amazon 专用）、不发 `marketplace`。

### 工程

- 公开面三件套：metalava 基线 `purchases/api/purchases.api` + `scripts/api-check.sh` + `api-tester`
  模块（Java 与 Kotlin 各调一遍全部公开 API，只编译不运行）。
- detekt **零 baseline**（自定义规则 `ForbiddenPublicEnum`：公开面不许有 enum，
  后端加枚举值不能摔老宿主）；显式 API 模式 `-Xexplicit-api=strict`。
- 415 条 Robolectric 单测，含故障注入全矩阵（断网 / 超时 / 5xx / 401 / 403 / 404 / 408 / 429 /
  400 类确定性拒绝 / 断连 / 进程被杀 / 回调重复 / 时钟回拨 / ETag 损坏 / 诊断 4xx）。
- consumer ProGuard 规则随 aar 分发，门禁 = `:example:assembleRelease`（`minifyEnabled true`）
  + `scripts/r8-check.sh`（断言 R8 的 configuration / seeds / usage / mapping 四份产物）。
- `example` 模块 = 最小手测 app，**不含任何 key**（从 `local.properties` 注入）。
  真机清单见 monorepo 的 `docs/plan/android-sdk-device-checklist.md`。
