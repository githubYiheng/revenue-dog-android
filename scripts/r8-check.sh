#!/usr/bin/env bash
# consumer ProGuard / R8 门禁（设计 §7 最后一行，M4 建）。
#
#   scripts/r8-check.sh            # 跑 :example:assembleRelease 再断言
#   SKIP_BUILD=1 scripts/r8-check.sh   # 直接断言上一次的产物
#
# 为什么需要它：aar 带出去的 `purchases/consumer-rules.pro` 是**宿主 release 构建**才生效的东西，
# 单测一条都覆盖不到。规则漏了的症状是「debug 一切正常，商店版本第一次解析响应就炸」。
# 这个脚本把「规则有没有生效」变成四份可 diff 的产物上的断言：
#
#   configuration.txt —— R8 实际吃进去的全部规则（必须含 aar 带来的那几条）
#   seeds.txt         —— 被 keep 命中的符号（SDK 公开面 / Billing / org.json）
#   usage.txt         —— 被删掉的符号（SDK 的**类**一个都不许出现）
#   mapping.txt       —— 改名表（SDK 公开类必须原名 → 原名）
#
# `:example` 的 `proguard-rules.pro` 里**没有任何 keep**，所以过关只可能是 SDK 自己的规则在起作用。
set -u

cd "$(dirname "$0")/.." || exit 1

MAPPING_DIR="example/build/outputs/mapping/release"
APK="example/build/outputs/apk/release/example-release-unsigned.apk"
exit_code=0

fail() { echo "❌ $*" >&2; exit_code=1; }
ok() { echo "✅ $*"; }

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    echo "==> ./gradlew :example:assembleRelease（minifyEnabled true）"
    ./gradlew :example:assembleRelease --console=plain -q || { echo "❌ assembleRelease 失败" >&2; exit 1; }
fi

for f in configuration.txt seeds.txt usage.txt mapping.txt; do
    [[ -s "$MAPPING_DIR/$f" ]] || fail "缺少 R8 产物 $MAPPING_DIR/$f（example/proguard-rules.pro 的 -print* 指令没生效？）"
done
[[ -s "$APK" ]] || fail "缺少 $APK"
[[ $exit_code -eq 0 ]] || exit $exit_code

echo
echo "==> 1/6 aar 的 consumer 规则进了 R8 配置"
CONSUMER_RULES=(
    '-keep class org.revdog.purchases.** { public protected *; }'
    '-keep class com.android.billingclient.api.** { *; }'
    '-keep class org.json.* { *; }'
    '-keep class androidx.lifecycle.DefaultLifecycleObserver'
)
for rule in "${CONSUMER_RULES[@]}"; do
    if grep -qF -- "$rule" "$MAPPING_DIR/configuration.txt"; then
        ok "规则已生效：$rule"
    else
        fail "consumer 规则没进 R8 配置：$rule"
    fi
done

echo
echo "==> 2/6 SDK 没有任何类被 R8 删掉"
# usage.txt 的约定：`类名:` = 只删了它下面列出的成员；`类名`（无冒号）= 整个类被删。
removed_classes="$(grep '^org\.revdog\.purchases' "$MAPPING_DIR/usage.txt" | grep -v ':$' || true)"
if [[ -z "$removed_classes" ]]; then
    ok "0 个 SDK 类被删（被删的只有 private 常量，那是内联，不是裁剪）"
else
    fail "以下 SDK 类被 R8 删掉了：\n$removed_classes"
fi

echo
echo "==> 3/6 SDK 公开类没有被改名（堆栈要能直接读）"
renamed="$(awk -F' -> ' '/^org\.revdog\.purchases/ {
    cls=$1; new=$2; sub(/:$/,"",new);
    if (cls != new && cls !~ /\$\$/ && cls !~ /\$[0-9]/) print cls" -> "new
}' "$MAPPING_DIR/mapping.txt" || true)"
if [[ -z "$renamed" ]]; then
    ok "公开类全部原名保留（改名的只有 \$\$ExternalSyntheticLambda / 匿名类）"
else
    fail "以下 SDK 类被改名：\n$renamed"
fi

echo
echo "==> 4/6 关键入口在 seeds 里（真被 keep 住，不是恰好没被删）"
for seed in \
    'org.revdog.purchases.Purchases: void purchase(org.revdog.purchases.PurchaseParams,org.revdog.purchases.PurchaseCallback)' \
    'org.revdog.purchases.Purchases: void restorePurchases(org.revdog.purchases.ReceiveCustomerInfoCallback)' \
    'org.revdog.purchases.Purchases: void syncPurchases(org.revdog.purchases.ReceiveCustomerInfoCallback)' \
    'org.revdog.purchases.Purchases: void logIn(java.lang.String,org.revdog.purchases.LogInCallback)' \
    'org.revdog.purchases.Purchases: void logOut(org.revdog.purchases.ReceiveCustomerInfoCallback)' \
    'org.revdog.purchases.Purchases: void getOfferings(org.revdog.purchases.ReceiveOfferingsCallback)' \
    'org.revdog.purchases.Purchases: void setAttributes(java.util.Map)' \
    'org.revdog.purchases.Purchases: void syncAttributes()'
do
    grep -qF "$seed" "$MAPPING_DIR/seeds.txt" && ok "keep 命中：${seed#*: }" || fail "公开入口没被 keep：$seed"
done

echo
echo "==> 5/6 org.json 与 Billing 没被裁（SDK 的解析层与 IPC 回调都靠它们）"
json_seeds="$(grep -c '^org\.json' "$MAPPING_DIR/seeds.txt" || true)"
json_removed="$(grep -c '^org\.json' "$MAPPING_DIR/usage.txt" || true)"
billing_seeds="$(grep -c '^com\.android\.billingclient' "$MAPPING_DIR/seeds.txt" || true)"
[[ "$json_seeds" -gt 0 ]] && ok "org.json 被 keep 的符号 $json_seeds 个" \
    || fail "org.json 一条都没被 keep（:example 的 org.json 依赖还在吗？）"
[[ "$json_removed" -eq 0 ]] && ok "org.json 0 个符号被删" || fail "org.json 有 $json_removed 处被删"
[[ "$billing_seeds" -gt 0 ]] && ok "com.android.billingclient 被 keep 的符号 $billing_seeds 个" \
    || fail "Billing 类一条都没被 keep"
grep -qF 'org.json.JSONObject -> org.json.JSONObject' "$MAPPING_DIR/mapping.txt" \
    && ok "org.json.JSONObject 原名保留" || fail "org.json.JSONObject 被改名了"

echo
echo "==> 6/6 apk 的 dex 里真的有这些类（产物级复核，不只看 R8 的自述）"
APKANALYZER="${APKANALYZER:-$(command -v apkanalyzer || true)}"
if [[ -z "$APKANALYZER" && -n "${ANDROID_HOME:-}" ]]; then
    APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
fi
if [[ -x "$APKANALYZER" ]]; then
    dex_classes="$("$APKANALYZER" dex packages --defined-only "$APK" 2>/dev/null | awk '$1=="C"{print $NF}')"
    for cls in \
        org.revdog.purchases.Purchases \
        org.revdog.purchases.PurchasesConfiguration \
        org.revdog.purchases.offerings.Offerings \
        org.revdog.purchases.customerinfo.CustomerInfo \
        org.json.JSONObject \
        com.android.billingclient.api.BillingClient
    do
        grep -qxF "$cls" <<<"$dex_classes" && ok "dex 内含 $cls" || fail "dex 里找不到 $cls"
    done
    echo "   apk：${APK}，$(du -h "$APK" | cut -f1)"
else
    echo "   ⚠️ 找不到 apkanalyzer（设 APKANALYZER 或 ANDROID_HOME），跳过 dex 复核 —— 跳过 ≠ 通过"
fi

echo
if [[ $exit_code -eq 0 ]]; then
    echo "R8 / consumer ProGuard 门禁全过。"
else
    echo "R8 / consumer ProGuard 门禁失败：缺的规则要加进 purchases/consumer-rules.pro，" >&2
    echo "**不要**加到 example/proguard-rules.pro（那等于把 SDK 的缺陷藏起来）。" >&2
fi
exit $exit_code
