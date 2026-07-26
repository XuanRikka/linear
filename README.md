# linear

[![build](https://github.com/XuanRikka/linear/actions/workflows/build.yml/badge.svg)](https://github.com/XuanRikka/linear/actions/workflows/build.yml)

Minecraft **26.2 / Fabric** 的区域存档格式 mod：用 zstd 压缩的 linear 系列格式替代原版
anvil（`.mca`），显著减小存档体积。生效范围覆盖 **region / entities / poi** 三类存储。

> ⚠️ 本 mod 直接改变世界数据的落盘方式。**首次使用或切换格式前请备份世界。**

## 支持的格式

| 格式 | 扩展名 | 出处 | 说明 |
|---|---|---|---|
| `bufferedlinearv3`（默认） | `.b_linear` | [Luminol](https://github.com/LuminolMC/Luminol) 服务端 | 16 个 bucket 独立 zstd 压缩，懒加载 + swap 缓冲 + 自动 compact。写入只落 swap，后台线程定期同步主文件，写放大小，适合日常游玩 |
| `linearv2` | `.linear` | [xymb](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools) | 整文件按 grid 分桶 zstd 压缩，压缩率最高。全内存实现，任何修改都会整文件重写 |
| `anvil`（别名 `mca`） | `.mca` | Mojang | 原版格式。装着本 mod 也可以继续用原版存储 |

两种 linear 格式都是既有格式的 Java 实现，磁盘布局与各自的原始实现兼容，可以用对应的
社区工具互相转换。

**已有的区域文件一律按文件内容（魔数）自动识别格式读取，与配置无关。** 配置项只决定
*新* 区域文件用什么格式创建，因此三种格式可以在同一个世界里共存：切到 `anvil` 之后，
之前生成的 `.linear` / `.b_linear` 区域照常读写，只有全新区域才写 `.mca`。

## 安装

把 [Releases](https://github.com/XuanRikka/linear/releases) 里的 `linear-*.jar` 放进 `mods` 目录。

- 依赖 Fabric Loader ≥ 0.19.3 和 Fabric API
- zstd 已经内嵌进 jar，无需额外安装
- 服务端 / 客户端均可

> **同时装了 C2ME？** 必须先在 `config/c2me.toml` 里把 `[ioSystem]` 的 `replaceImpl`
> 改成 `false`，否则本 mod 会拒绝启动。原因和影响见下面的
> [与 C2ME 共存](#与-c2me-共存)。

## 配置

首次启动会生成 `config/linear.properties`（带中文注释）。修改后需重启生效。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `format` | `bufferedlinearv3` | 新区域文件使用的格式：`bufferedlinearv3` / `linearv2` / `anvil` |
| `compression-level` | `3` | zstd 压缩等级，1–22 |
| `grid-size` | `8` | 仅 LinearV2：分桶网格，可选 1/2/4/8/16/32。1 压缩率最高，32 最低 |
| `v2-flush-interval-seconds` | `60` | 仅 LinearV2：有未落盘修改的区域文件多久写盘一次（5–3600）。崩溃时最多丢这么久的改动；`0` = 禁用，回到只在退出世界时落盘 |
| `v3-flush-delay-seconds` | `5` | 仅 BufferedLinearV3：写入静默多少秒后把脏 bucket 同步进主文件（1–600） |
| `v3-flush-threads` | CPU 核数 / 4 | 仅 BufferedLinearV3：后台同步线程数（1–16） |

非法值会打印警告并回落到默认值或范围边界，不会导致启动失败。

## 与 C2ME 共存

**需要做的事**：编辑 `config/c2me.toml`，把 `[ioSystem]` 段里的 `replaceImpl` 改成 `false`
（`gcFreeChunkSerializer` 保持默认的 `false`），然后重启。

```toml
[ioSystem]
	replaceImpl = false
```

C2ME 首次启动才会生成这个文件；如果还没有，先单独启动一次游戏让它生成。

**为什么必须这么做**：C2ME 的 chunk IO 重写模块（就是 `replaceImpl`，默认开启）会**绕过本
mod 直接读写 `.mca`**。已有 linear 世界的区域会被它当作空白，导致**地形被静默重新生成**，
而且 region 走 `.mca`、entities/poi 走 linear，存档会变成互相割裂的状态。

**代价很小**：关掉的只是 chunk IO 这一个模块，**C2ME 其余优化（并行世界生成、密度函数
编译器、区块系统改写等）全部照常工作**。

**不用担心忘记**：本 mod 启动时会读取 C2ME 的实际配置——配置安全就正常共存并记录一条日志，
配置危险则**直接拒绝启动**并在错误信息里给出上面这段指引，不会让你在不知情的情况下丢档。

## 已知限制

- **LinearV2 的写放大**：格式为单文件全局结构，改动任何一个 chunk 都需要整文件重写。区域改动
  频繁时 CPU 开销明显高于 BufferedLinearV3
- **不支持 linear v1**：读到 v1 文件会报明确的错误信息，请先用格式转换工具转成 v2
- 不提供存量世界的批量格式转换功能，格式迁移是随游玩渐进发生的

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。需要 JDK 25。`tools/xval/` 下有一套无头验证 harness（区域文件交叉
校验、周期落盘测试、xxhash 对拍等），用法见其 README。

## 致谢

本 mod 只是把两种既有格式实现到 Fabric 上，格式本身的设计与原始实现归功于：

- **LinearV2** —— 由 [xymb-endcrystalme/LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools)
  设计并实现
- **BufferedLinearV3** —— 由 [Luminol](https://github.com/LuminolMC/Luminol) 服务端开发，
  本 mod 的实现移植自其 `BufferedLinearRegionFile`
- 压缩使用 [zstd-jni](https://github.com/luben/zstd-jni)

## 关于本项目的代码

本项目的代码、测试与文档由 **Claude Fable 5**（Anthropic）在人类开发者的需求定义与决策下编写，
关键设计取舍（格式选择、并发与落盘策略、C2ME 兼容方案）均经过人工审阅确认。

存档格式实现的正确性经过以下验证：与参考实现（Rust CLI / Luminol）的双向交叉校验、
xxhash 测试向量对拍、并发与故障场景的多轮对抗审查，以及实际进入游戏的读写冒烟测试。
即便如此，**存档数据无价，请务必保留备份**。

## 许可证

[MIT](LICENSE.txt)
