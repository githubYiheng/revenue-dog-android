# 手测 app 自己的 R8 规则。
#
# **这里故意只有诊断指令，没有任何 keep**：SDK 需要的 keep 必须全部来自
# aar 里带出来的 `purchases/consumer-rules.pro` —— 宿主不该为了用 SDK 而抄规则。
# 在这里补一条 keep 就等于把 SDK 的缺陷藏起来，门禁就白跑了。

# R8 的四份产物，`scripts/r8-check.sh` 拿它们做断言：
#   mapping.txt       —— 谁被改名了（公开面必须原名原样）
#   seeds.txt         —— 哪些符号被 keep 规则命中（org.json / Billing / SDK 公开面）
#   usage.txt         —— 哪些符号被删了（SDK 公开面一条都不该出现在这里）
#   configuration.txt —— R8 实际吃进去的全部规则（含 aar 带来的 consumer 规则）
-printmapping build/outputs/mapping/release/mapping.txt
-printseeds build/outputs/mapping/release/seeds.txt
-printusage build/outputs/mapping/release/usage.txt
-printconfiguration build/outputs/mapping/release/configuration.txt

# 手测 app 的入口由 manifest 引用，AGP 生成的规则已经 keep 了它们，这里不重复。
