# RevenueDog Android SDK — 最小手测 app

形态照 RC `examples/purchase-tester` 裁剪。两个用途：

1. **真机 / license tester 手测的载体** —— 清单见 `docs/plan/android-sdk-device-checklist.md`；
2. **consumer ProGuard 规则的端到端门禁** —— `release` 构建开着 `minifyEnabled true`，
   SDK 随 aar 带出去的 `purchases/consumer-rules.pro` 漏了什么，R8 会在这里当场删掉；
   `scripts/r8-check.sh` 把结论变成断言。**这个模块自己的 `proguard-rules.pro` 里一条 keep 都没有**，
   所以门禁过关只可能是 SDK 的规则在起作用。

## 这个仓库里没有任何 key

`Purchases.configure` 需要一个 public key（`pk_…`）。它**从不入库**，由 `local.properties`
（已在 `.gitignore` 里）经 BuildConfig 注入。没配 key 时 app **不 configure**，
启动后在屏幕上写明缺什么，而不是拿空 key 去打后端。

在 `sdk/android/local.properties` 里加（三项都可选，只有第一项是必须的）：

```properties
# 必须：public key。生产 key 在用户机 ~/selah-keys/ 下，**不要**粘到任何入库文件里
REVENUEDOG_API_KEY=pk_xxxxxxxxxxxx

# 可选：默认就是 https://api.revdog.org。指 staging 时才填
REVENUEDOG_BASE_URL=https://api-staging.revdog.org

# 可选：启动时直接以这个身份 configure（留空 = SDK 生成匿名身份，用 UI 上的 logIn 按钮切）
REVENUEDOG_APP_USER_ID=
```

也可以走 Gradle 属性覆盖（CI 上别用环境里的真 key，手测 app 不需要 CI）：

```bash
./gradlew :example:installDebug -PREVENUEDOG_API_KEY=pk_xxx
```

## 装机与构建

```bash
# 手测（真机 / 模拟器）
./gradlew :example:installDebug

# consumer ProGuard 门禁（R8 真跑一遍；产出 unsigned apk，不用签名配置）
./gradlew :example:assembleRelease
bash scripts/r8-check.sh
```

`release` 构建**故意没有签名配置**：门禁要的是「R8 跑完、公开面还在」，不是一个能上架的包。
真机手测一律用 `installDebug`。要在真机上验 R8 后的行为（Play Billing 的 IPC 回调在 minify 之后
仍然打得通），按真机清单 §0 的说明自己给一个 debug 签名再装 release apk。

## UI 上的动作

| 按钮 | 调的 API | 真机清单里对应哪条 |
|---|---|---|
| offerings | `getOfferingsWith` | D1 商品与价格展示、`platform_product_plan_identifier` |
| purchase | `purchaseWith(PurchaseParams.Builder(activity, package))` | D2–D6 购买 / pending / 升降级 |
| 逐 package 购买（一个 package 一个按钮） | 同上，`package` 是点中的那个 | D3 一次性商品（`coins_100` / `$rc_lifetime`）、D6 换 base plan |
| 替换模式选择器 | `PurchaseParams.Builder.oldProductId(…).replacementMode(…)` | D6 升级 / 降级 / 换套餐 |
| restore | `restorePurchasesWith` | D8 重装恢复、D9 换账号 |
| sync | `syncPurchasesWith` | D10 只上报不碰 Billing |
| customerInfo | `getCustomerInfoWith(FETCH_CURRENT)` | D11 权益判定与 grace |
| logIn / logOut | `logInWith` / `logOutWith` | D9 身份切换不串号 |
| 设置订阅者属性 | `setAttributes` + `setDisplayName` + `collectDeviceIdentifiers` + `syncAttributes` | D12 属性 |
| 诊断开关 | `PurchasesConfiguration.Builder.diagnosticsEnabled` | D13 诊断队列与上传 |

诊断开关是 **configure 期**决定的，所以改完要杀进程重启才生效（UI 上也写了这句）。
关掉之后 SDK 会清空本地队列目录 —— 那正是 D13 要看的一条。

**逐 package 按钮**在每次 `offerings` 成功后整片重建（不累加），current offering 里有几个 package
就有几个按钮，文案是「package identifier / 商品 id / 价格」。Play 上查不到的 package
（`Offerings.notFoundProductIds` 里的那些）也画出来，但**置灰并写明「商店里查不到」**——
按钮凭空少一个远比一个灰按钮难排查。

**替换模式选择器**列的是 `ReplacementMode.ALL`（SDK 公开支持的全部五种），默认停在
「不替换（全新购买）」。选了某一种之后，购买参数才会带上 `oldProductId` + `replacementMode`；
**被替换的旧订阅只从最近一次 customerInfo 的活跃订阅里取**（`activeSubscriptions` ∩ `subscriptions`
里 `store == play_store` 的那笔），取不到就在日志区写明原因并按全新购买走 —— 不拿 offerings
里的某个订阅顶上，猜错的后果是给用户换错套餐。所以跑 D6 前先点一次 `customerInfo`。

## 不做的事

- 不接 navigation / viewBinding / Material / AppCompat：界面全部用代码搭。
  每多一个依赖就多一份「到底是谁的 ProGuard 规则救了场」的不确定性。
- 不做付费墙 UI、不做多 flavor（RC 那边有三个商店 flavor，我方只有 Play）。
- 不入库任何 key、keystore、`local.properties`。
