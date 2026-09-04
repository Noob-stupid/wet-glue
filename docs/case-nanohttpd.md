# 真实案例：在 NanoHTTPD 上验证 wet-glue

> 这是 wet-glue 第一次在**别人写的真实代码**上跑完整流程。不是玩具 demo，是 5k+ star 的真实开源项目。
> 日期：2026-09-04 ｜ 对象：NanoHttpd/nanohttpd @ efb2ebf

## 场景

NanoHTTPD 是单文件嵌入式 HTTP 服务器。它的默认调度模型写在 `DefaultAsyncRunner.exec()` 里：**每个连接新建一个线程**。作者自己在注释里标注了这个取舍——"简单但可能撑不住高并发"。

这就是一个典型的"拿不准的代码"：该不该换成线程池？直觉说换，但没数据就是猜。

```java
@Override
public void exec(ClientHandler clientHandler) {
    ++this.requestCount;
    this.running.add(clientHandler);
    // glue:begin spawn
    createThread(clientHandler).start();      // ← 拿不准：每连接一线程？
    // glue:end
}
```

## 过程

```bash
glue scan                        # 识别真实项目里的胶水区域
glue add spawn thread-per-conn   # 登记原版
# （手写线程池版 + PoolHolder 配套结构）
glue add spawn thread-pool       # 登记线程池版
glue verify spawn "javac ... && java Bench"   # 一键编译+压测
```

压测：50→300 并发，HTTP/1.0 短连接（强制每请求一个 ClientHandler，正好压在 exec 决策点上）。

## 真实数据（反直觉）

| 负载 | thread-per-conn | thread-pool | 赢家 |
|---|---|---|---|
| 50 并发 / 2000 请求 | **316 ms / 峰值 66 线程** | 358 ms / 峰值 258 线程 | 原版 |
| 300 并发 / 6000 请求 | **853 ms / 峰值 316 线程** | 942 ms / 峰值 508 线程 | 原版 |

**每连接一线程在两个负载下都更快、峰值线程更少。** 固定池的 200 个线程在 50 并发时大半空转，300 并发时调度开销反超。直觉错了——NanoHTTPD 的默认选择在这些负载下并不差。

## 结论与固化

数据说原版够用。`glue seal spawn` 把原版焊死，标记移除，代码回到它本来的样子——但这次**决定是有数据支撑的，不是默认继承的**：

```bash
glue seal spawn
#   已登记验证命令，固化前先跑一遍…
#   ✓ 验证通过。  （压测再跑一遍确认）
#   确认固化？ yes
#   已固化。标记已移除。
```

`swappable.lock` 里留下了完整审计轨迹，每条都挂着 NanoHTTPD 的 git commit（`git=efb2ebf`）——什么时候试了哪个候选、跑了什么验证、最后定了哪个，全可查。

## 这次验证暴露了什么（诚实复盘）

1. **范式在真实代码上成立**：标记、登记、切换、验证、固化全流程在陌生代码上零适配跑通。
2. **"拿不准"是真的**：连"线程池一定更好"这种看似显然的直觉都被数据推翻了——这正是胶水存在的意义。
3. **一个真实边界**：线程池版需要类级配套结构（`PoolHolder`），不能纯靠一行替换。这是 demo 里碰不到、真实代码一定会遇到的情况——候选片段要能携带"配套改动"，或者胶水区要框得更大。这是下一版要回答的问题。
4. **契约对比起了作用**：`use` 时正确亮出 `- 不再读取: PoolHolder`，提醒两版的外部依赖不同。
