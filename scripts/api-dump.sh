#!/usr/bin/env bash
# 重新生成公开 API 基线（metalava）。结构对照 RC `scripts/api-dump.sh`。
#
# 改了公开面之后跑它，然后 **review diff 再提交** —— 基线文件是「我们承诺了什么」的唯一记录。
set -u

cd "$(dirname "$0")/.." || exit 1

exit_code=0
./gradlew :purchases:metalavaGenerateSignatureRelease || exit_code=$?
exit $exit_code
