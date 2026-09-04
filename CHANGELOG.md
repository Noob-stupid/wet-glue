# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [0.1.0] - 2026-09-04

首个公开迭代。

### 新增
- 头尾标记范式：`// glue:begin <name>` / `// glue:end` 框住拿不准的代码，切换 = 源码替换，固化 = 焊死、运行时开销为 0
- 全部命令：`scan / list / add / use / refs / seal / unseal / verify / check`
- `use` 切换前对外契约对比（机械差异可见）
- `seal` 固化前强制验证（登记过 verifyCmd 则自动执行，失败禁止固化）+ 二次确认 + CI 默认拒绝（`GLUE_ALLOW_SEAL=1` 显式放行）
- 状态机 experimental / candidate / sealed + `swappable.lock` 审计（附 git HEAD）
- 多语言开箱：凡 `//` 行注释语言（Java / C# / Kotlin / Go / TS / JS / C / C++ / Rust / Swift）
- `tests/run.sh`：39 项全链路断言，临时目录隔离
- 示例：Java（红黑树 vs B+ 树）、C#（同一工具管理 .NET 代码）
- 真实案例：[在 NanoHTTPD 上验证](docs/case-nanohttpd.md)（反直觉数据：两负载下原版更快）

### 已知边界
- 契约分析为文本启发式，无类型信息；跨作用域同名变量可能误报泄漏
- 换实现如需类级配套结构（如线程池字段），当前需手工配合（见案例复盘）
