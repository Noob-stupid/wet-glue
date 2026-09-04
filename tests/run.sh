#!/usr/bin/env bash
# 胶水 glue —— 全链路回归测试
# 在临时目录从零搭建环境，不污染主工作区。任何断言失败 → 退出码 1。
# 用法: bash tests/run.sh
set -u

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✗ $1"; }
# assert_contains <描述> <期望子串> <实际输出>
assert_contains() {
  if echo "$3" | grep -qF "$2"; then ok "$1"; else bad "$1 —— 期望包含「$2」，实际:\n$3"; fi
}
# assert_not_contains <描述> <不应出现的子串> <实际输出>
assert_not_contains() {
  if echo "$3" | grep -qF "$2"; then bad "$1 —— 不应包含「$2」"; else ok "$1"; fi
}
# assert_exit <描述> <期望退出码> <实际退出码>
assert_exit() {
  if [ "$2" = "$3" ]; then ok "$1 (exit=$3)"; else bad "$1 —— 期望 exit=$2，实际 exit=$3"; fi
}
# assert_file_contains <描述> <文件> <期望子串>
assert_file_contains() {
  if grep -qF "$3" "$2"; then ok "$1"; else bad "$1 —— 文件 $2 期望包含「$3」"; fi
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "== 临时环境: $TMP =="

cp -r "$ROOT/src" "$ROOT/examples" "$TMP/"
cd "$TMP" || exit 1

echo "== 0. 编译 =="
javac -encoding UTF-8 -d build src/Glue.java 2>&1
assert_exit "javac 编译" 0 $?
GLUE="java -Dfile.encoding=UTF-8 -cp build Glue"

echo "== 1. scan / list =="
OUT=$($GLUE scan 2>&1)
assert_contains "scan 发现 Java 区域" "lookup" "$OUT"
assert_contains "scan 发现 C# 区域" "cs_lookup" "$OUT"
OUT=$($GLUE list 2>&1)
assert_contains "list 显示 lookup" "lookup" "$OUT"

echo "== 2. add / use =="
OUT=$($GLUE add lookup rbtree 2>&1)
assert_contains "add 登记候选" "rbtree" "$OUT"
# 冷启动环境手工造出第二个候选：把区域体改成 bplus 版再登记
#（sed 写法兼容 GNU 与 BSD/macOS：-i.bak + $'...' 真实换行）
NEW_BLOCK=$'        int found = Collections.binarySearch(bpKeys, key);\n        return (found < 0) ? null : bpVals.get(found);'
sed -i.bak "s|        return rbtree.get(key);|${NEW_BLOCK}|" examples/demo/IndexStore.java && rm -f examples/demo/IndexStore.java.bak
OUT=$($GLUE add lookup bplus 2>&1)
assert_contains "add 登记第二候选" "bplus" "$OUT"
OUT=$($GLUE use lookup rbtree 2>&1)   # 先切回 rbtree 再测 bplus 切换
assert_contains "切回 rbtree" "已切换到: rbtree" "$OUT"
OUT=$($GLUE use lookup bplus 2>&1)
assert_contains "use 切换成功" "已切换到: bplus" "$OUT"
assert_contains "use 触发契约对比" "新增读取" "$OUT"
assert_file_contains "源码已替换为 bplus" examples/demo/IndexStore.java "binarySearch"
OUT=$($GLUE use lookup rbtree 2>&1)
assert_contains "切回 rbtree" "已切换到: rbtree" "$OUT"
OUT=$($GLUE use lookup nosuch 2>&1)
assert_contains "不存在候选报错" "没有这个候选实现" "$OUT"

echo "== 3. refs =="
OUT=$($GLUE refs lookup 2>&1)
assert_contains "refs 显示读取外部" "读取外部" "$OUT"

echo "== 4. verify =="
OUT=$($GLUE verify lookup "javac -encoding UTF-8 -d out examples/demo/IndexStore.java && java -Dfile.encoding=UTF-8 -cp out IndexStore" 2>&1)
assert_contains "verify 登记命令" "登记验证命令" "$OUT"
OUT=$($GLUE verify lookup 2>&1); CODE=$?
assert_contains "verify 执行通过" "验证通过" "$OUT"
assert_exit "verify 退出码 0" 0 $CODE
$GLUE verify lookup "exit 3" >/dev/null 2>&1
OUT=$($GLUE verify lookup 2>&1); CODE=$?
assert_contains "verify 失败被报告" "验证失败" "$OUT"
assert_exit "verify 失败退出码透传" 3 $CODE
$GLUE verify lookup "javac -encoding UTF-8 -d out examples/demo/IndexStore.java && java -Dfile.encoding=UTF-8 -cp out IndexStore" >/dev/null 2>&1

echo "== 5. seal（含验证门槛与二次确认） =="
# CI 环境里 GitHub Actions 自带 CI=true，而本测试是人工触发的功能验证，显式放行
OUT=$(echo yes | GLUE_ALLOW_SEAL=1 $GLUE seal lookup 2>&1); CODE=$?
assert_contains "seal 前自动跑验证" "验证通过" "$OUT"
assert_contains "seal 完成" "已固化" "$OUT"
assert_exit "seal 退出码 0" 0 $CODE
assert_not_contains "固化后标记已移除" "// glue:begin" "$(cat examples/demo/IndexStore.java)"
OUT=$($GLUE use lookup bplus 2>&1)
assert_contains "固化后 use 被拒" "已固化" "$OUT"

echo "== 6. seal 验证失败阻止固化 =="
$GLUE unseal lookup >/dev/null 2>&1
$GLUE verify lookup "exit 3" >/dev/null 2>&1
OUT=$(echo yes | GLUE_ALLOW_SEAL=1 $GLUE seal lookup 2>&1); CODE=$?
assert_contains "验证失败禁止固化" "禁止固化" "$OUT"
assert_exit "被阻止的 seal 退出码 1" 1 $CODE
$GLUE verify lookup "javac -encoding UTF-8 -d out examples/demo/IndexStore.java && java -Dfile.encoding=UTF-8 -cp out IndexStore" >/dev/null 2>&1

echo "== 7. CI 禁止固化（GLUE_ALLOW_SEAL 为空时） =="
OUT=$(CI=true GLUE_ALLOW_SEAL= $GLUE seal lookup 2>&1); CODE=$?
assert_contains "CI 环境拒绝固化" "CI 环境" "$OUT"
assert_exit "CI seal 退出码 2" 2 $CODE

echo "== 8. unseal / check =="
OUT=$($GLUE check 2>&1); CODE=$?
assert_contains "check 通过" "检查通过" "$OUT"
assert_exit "check 退出码 0" 0 $CODE

echo "== 9. 漂移检测 =="
cp examples/demo/IndexStore.java "$TMP/IndexStore.bak"
sed -i.bak 's/return rbtree.get(key);/return "drifted";/' examples/demo/IndexStore.java && rm -f examples/demo/IndexStore.java.bak
OUT=$($GLUE check 2>&1); CODE=$?
assert_contains "check 报漂移" "漂移" "$OUT"
assert_exit "漂移时 check 退出码 1" 1 $CODE
cp "$TMP/IndexStore.bak" examples/demo/IndexStore.java
OUT=$($GLUE check 2>&1); CODE=$?
assert_exit "还原后 check 通过" 0 $CODE

echo "== 10. 泄漏检测 =="
cat > examples/leaktest.java << 'EOF'
public class Leaktest {
    int helper() {
        // glue:begin leakcase
        int secret = 1;
        return secret;
        // glue:end
    }
    int leak() { return secret; }
}
EOF
$GLUE scan >/dev/null 2>&1
OUT=$($GLUE check 2>&1); CODE=$?
assert_contains "check 报胶水外泄" "胶水外泄" "$OUT"
assert_exit "泄漏时 check 退出码 1" 1 $CODE
rm -f examples/leaktest.java
rm -rf .glue/leakcase

echo "== 11. C# 生命周期（有 dotnet 才测） =="
if command -v dotnet >/dev/null 2>&1; then
  # 冷启动环境：先登记候选与验证命令，再走 use/seal/unseal
  OUT=$($GLUE add cs_lookup rbtree 2>&1)
  assert_contains "C# add 登记" "已把区域 cs_lookup" "$OUT"
  OUT=$($GLUE use cs_lookup rbtree 2>&1)
  assert_contains "C# use 切换" "已切换到: rbtree" "$OUT"
  $GLUE verify cs_lookup "cd examples/csharp && dotnet run -c Release" >/dev/null 2>&1
  OUT=$(echo yes | GLUE_ALLOW_SEAL=1 $GLUE seal cs_lookup 2>&1)
  assert_contains "C# seal 含自动验证" "验证通过" "$OUT"
  assert_contains "C# seal 完成" "已固化" "$OUT"
  OUT=$($GLUE unseal cs_lookup 2>&1)
  assert_contains "C# unseal 还原" "已解固化" "$OUT"
else
  echo "  - 跳过（未检测到 dotnet）"
fi

echo ""
echo "=============================="
echo "  通过 $PASS 项，失败 $FAIL 项"
echo "=============================="
[ "$FAIL" = 0 ] || exit 1
