# wet-glue（胶水）

[![CI](https://github.com/Noob-stupid/wet-glue/actions/workflows/ci.yml/badge.svg)](https://github.com/Noob-stupid/wet-glue/actions/workflows/ci.yml)

> **两行注释，管理你拿不准的代码。**
> 框住 → 换实现 → 跑基准 → 挑最好的 → 焊死。切换是源码替换，运行时零开销；固化之后就是普通代码，好像它从来就该那样。

```java
public String get(int key) {
    // glue:begin lookup            ← 头
    return rbtree.get(key);          ← 拿不准的这块
    // glue:end                      ← 尾
}
```

```bash
glue scan                    # 发现所有胶水区域
glue add  lookup bplus       # 把当前写法登记为候选
glue use  lookup bplus       # 切换（源码文本替换，无间接层）
glue verify lookup           # 一键编译+跑基准，两版数字摆出来
glue seal lookup             # 选定最优，焊死（先自动验证，再二次确认）
```

## 为什么不是"注释掉旧代码"

90% 的人今天的做法：新实现写旁边，旧的注释掉，跑通了再删。胶水要赢的就是这个习惯：

| | 注释掉旧代码 | wet-glue |
|---|---|---|
| 有几个候选、当前用的是哪个 | 靠肉眼找注释 | `glue list` 一目了然 |
| 切换 | 手动改注释，易错 | `glue use` 一条命令 |
| 两版行为一致吗 | 祈祷 | `glue verify` 跑给你看 |
| 定稿 | 删掉注释块，无记录 | `glue seal` 焊死 + 审计留痕 |
| 手滑改坏了 | 不知道 | `glue check` 报漂移，CI 可拦截 |

也不是 Feature Flag（那是运行时切业务功能）或 DI 容器（要抽接口改调用方）。胶水是**编辑期**的：决策发生在写代码的时候，不是运行的时候。

## 它在 AI 时代的用处

AI 一天能给你三个实现。B+ 树还是红黑树？LLM 说的不算，基准说的才算。把候选都登记进胶水，跑一次基准，用数据选定，然后焊死——**决策从"感觉"变成"记录"**。

## 快速开始

需要 JDK 17+（首次运行自动编译工具自身）。

```bash
git clone https://github.com/Noob-stupid/wet-glue.git
cd wet-glue
./glue list            # 自带 Java + C# 两个示例区域
./glue use lookup bplus
./glue verify lookup   # 跑基准看两版差异（C# 示例需 dotnet）
```

Windows 用 `glue.cmd`，Git Bash / Linux / macOS 用 `./glue`。

## 命令速查

| 命令 | 作用 |
|---|---|
| `glue scan` | 扫描源码，登记所有胶水区域 |
| `glue list` | 区域、状态（experimental / candidate / sealed）、候选、"胶水干了没" |
| `glue add <区域> <别名>` | 把区域当前内容登记为一个候选实现 |
| `glue use <区域> <别名>` | 切换候选（源码替换）——切换前自动对比两版对外契约差异 |
| `glue refs <区域>` | 这块代码对外的隐式契约（读了什么、写了什么） |
| `glue seal <区域>` | **固化**：先自动跑验证（失败禁止固化），再二次确认，然后焊死 |
| `glue unseal <区域>` | 解固化：重新插入标记 |
| `glue verify <区域> [命令]` | 登记/执行验证命令（编译+测试一键跑，退出码透传可接 CI） |
| `glue check` | 校验漂移 / 胶水外泄（有问题退出码 1，可直接进 CI） |

## 机械差异怎么处理（签名不同、换依赖）

换上的实现若**对外依赖变了**（比如从 `rbtree.get()` 换成 `bpKeys` 二分查找），切换前工具先亮出差异：

```
⚠ 候选实现对外契约有差异（rbtree → bplus）：
  + 新增读取: bpKeys, bpVals
  - 不再读取: rbtree
```

- **符号级差异**（改名、换依赖）：切换前可见，扫一眼确认。
- **类型级差异**（方法签名、返回类型真不同）：`glue verify` 让编译器精确报差在哪，你在候选片段里补适配（适配就是普通代码，写进片段即可）。
- **固化前强制验证**：登记过验证命令的区域，`seal` 先自动跑一遍，失败**禁止固化**（对应「固化 = 测试通过 + 人工确认」）。

## 支持哪些语言

凡**行注释是 `//` 的语言**开箱即用——标记与引擎逻辑完全一致，按扩展名识别，加语言只需加一行：

`.java` `.cs` `.kt` `.go` `.ts` `.js` `.c` `.cpp` `.rs` `.swift` …（见 `src/Glue.java` 的 `SRC_EXT`）

`examples/csharp/` 有完整 C# 演示：同一套工具、同一套标记，管理 C# 代码全流程可用。
（`#` 注释语言如 Python 需改标记约定，属后续版本。）

## 设计要点

- **零运行时开销**：切换发生在编辑期，产物没有任何间接层。固化 = 普通代码，严格等价于手写。
- **状态自动流转**：experimental →（≥2 候选）→ candidate → sealed（↔ unseal）。
- **防误固化**：二次确认；检测到 CI 环境默认拒绝固化（防无人值守误固化）。自动化测试场景可显式放行：`GLUE_ALLOW_SEAL=1`。
- **全程留痕**：每次变更写入 `swappable.lock`（附 git HEAD），纳入版本控制。
- **胶水会"干"**：`list` 提示距上次切换的天数，提醒你该做决定了。
- **泄漏检测**：区域内部声明的名字被区域外引用 = 胶水漏了，整块无法替换，`check` 会报。

## 已知边界（诚实清单）

- **同一语言内替换**。调用方与实现方不同语言/运行时（Python 算法换 Rust）不适用——那是 IDL + 服务化的领域。
- **契约分析是启发式**：基于文本符号，没有类型信息。跨作用域的同名变量（如区域内的 `int i` 撞区域外循环的 `i`）可能误报泄漏——区域内部变量请起有区分度的名字。类型级正确性永远靠编译器（`verify`）把关。
- **固化选择没有编译器保护**：两个候选读写的外部名字不同，工具只能提示，不能替你保证。这是"未固化"的代价，也是你必须跑测试的原因。

## 测试

```bash
bash tests/run.sh    # 39 项全链路断言，临时目录隔离，零污染
```

CI：GitHub Actions 每次 push 自动跑（含 .NET 环境的 C# 示例测试）。

## 后续方向

- `#` 注释语言（Python / Shell）：标记约定的语言分支。
- 构建集成：切换接进 Maven/Gradle/dotnet，自动重编译。
- 风险分级：按引用广度 + 序列化耦合打分，高风险切换强制先 verify。

## License

待定。
