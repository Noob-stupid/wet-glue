#!/usr/bin/env bash
# wet-glue —— 30 秒演示脚本（供录 GIF / 短视频用）
#
# 用法：
#   bash scripts/demo.sh            # 完整跑一遍（每步停顿 2 秒，方便录屏）
#   bash scripts/demo.sh fast       # 不停顿，快速验证脚本本身能跑通
#
# 录制建议（任选其一）：
#   - asciinema:  asciinema rec demo.cast -c "bash scripts/demo.sh"
#   - 手机/屏幕录制软件：先开录，再跑这个脚本，结束即素材
#   - Windows Terminal + 自带录屏：同样先开录再跑
#
# 录完把 GIF 放到 docs/demo.gif，并在 README 顶部加一行：
#   ![demo](docs/demo.gif)
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

PAUSE=2
[ "${1:-}" = "fast" ] && PAUSE=0

GLUE="java -Dfile.encoding=UTF-8 -cp build Glue"
step() { echo; echo "───── $1 ─────"; sleep "$PAUSE"; }

# 0. 编译（首次）
if [ ! -f build/Glue.class ]; then
  step "首次运行，自动编译"
  javac -encoding UTF-8 -d build src/Glue.java
fi

step "1/6 看一眼拿不准的那块代码"
echo "（examples/demo/IndexStore.java —— 查找该用红黑树还是 B+ 树？）"
sed -n '29,33p' examples/demo/IndexStore.java
sleep "$PAUSE"

step "2/6 扫描：发现所有胶水区域"
$GLUE scan
sleep "$PAUSE"

step "3/6 登记第二个候选（当前写法另存为 bplus）"
$GLUE add lookup rbtree
sleep "$PAUSE"

step "4/6 切换实现：改的是源码文本，运行时零开销"
$GLUE use lookup bplus
sleep "$PAUSE"
echo "→ 看源码，那块代码已经换了："
sed -n '29,35p' examples/demo/IndexStore.java
sleep "$PAUSE"

step "5/6 一键验证：编译 + 跑基准，两版数字摆出来"
$GLUE verify lookup "javac -encoding UTF-8 -d out examples/demo/IndexStore.java && java -Dfile.encoding=UTF-8 -cp out IndexStore"
sleep "$PAUSE"

step "6/6 选定后固化：焊死成普通代码，标记消失"
echo yes | $GLUE seal lookup
sleep "$PAUSE"
sed -n '29,32p' examples/demo/IndexStore.java

echo
echo "───── 演示结束 ─────"
echo "（固化后标记已移除，代码和手写的一模一样，运行时开销为 0）"
echo
echo "还原示例到可玩状态："
echo "  $GLUE unseal lookup && rm -rf out"
