# linear mod 交接文档（LinearV2 + BufferedLinearV3 存档实现）

> 由 Cowork 会话生成，供后续会话继续开发。任务：在本 Fabric mod（MC 26.2）中实现
> LinearV2 与 BufferedLinearV3 两种区域存档格式（读+写），**其他格式不做**
> （linear v1 / bufferedlinear v2 / anvil 转换均不需要，v1 读到时报清晰错误即可）。
> 参考资料全部在 `linear-tools-rs/` 下。

## 一、已确认的设计决策（用户拍板）

1. **写入格式由配置文件选择**，`config/linear.properties`，默认 `bufferedlinearv3`；
   已有区域文件按魔数自动识别读取（两种格式都能读）。
2. **BufferedLinearV3 完整移植 swap 文件机制**（参考 `linear-tools-rs/BufferedLinearRegionFile.java`，
   Luminol 实现）：写入先落 `.swp`、后台 flusher 线程定期同步脏 bucket 进 master、自动 compact。
3. **生效范围：region + entities + poi**（全部走 `RegionFileStorage`，一个 mixin 覆盖三者）。
4. 文件扩展名跟随 linear-tools-rs CLI 约定：LinearV2 = `r.X.Z.linear`，BufferedLinearV3 = `r.X.Z.b_linear`。
5. 混合世界安全兜底：某区域两种 linear 文件都不存在但 `.mca` 存在时，回落原版 `RegionFile`
   读写该区域并 log 提示转换（避免静默丢档）；都不存在则按配置格式新建。
6. swap 文件的 sector 压缩：参考实现用 LZ4，这里改用 **zstd level 1**（swap 是纯进程内部文件，
   打开时删除、DELETE_ON_CLOSE，从不跨进程读取，格式自由；省掉 lz4-java 依赖）。
   master/linear 文件格式与参考完全一致，不受影响。

## 二、已完成并已写入项目的文件

```
src/main/java/org/linear/
├── LinearConfig.java                    # config/linear.properties 加载器（含默认文件生成）
└── storage/
    ├── IRegionFile.java                 # 区域文件抽象接口（与原版 RegionFile 公开方法一一对应）
    └── util/
        ├── XXHash32.java                # 纯 Java xxhash32（规范实现，⚠ 尚未跑测试向量验证）
        ├── XXHash64.java                # 纯 Java xxhash64（同上）
        ├── ZstdUtil.java                # zstd-jni 薄封装（compress/decompress/decompressStream）
        └── ByteBufferInputStream.java   # ByteBuffer → InputStream
```

配置项：`format`（linearv2|bufferedlinearv3|anvil，别名 mca —— anvil 表示新区域用原版 .mca，
已有 linear 文件仍按魔数识别读写）、`compression-level`（1-22，默认 3）、
`grid-size`（1/2/4/8/16/32，默认 8，仅 v2）、`v2-flush-interval-seconds`（默认 60，0=禁用，
周期落盘兜底）、`v3-flush-delay-seconds`（默认 5）、`v3-flush-threads`。

`ZstdUtil` 用到的 zstd-jni API（桩/依赖需覆盖这些）：
`ZstdCompressCtx.setLevel/setChecksum/compress(byte[])`、`Zstd.decompress(byte[], int)`、
`ZstdInputStream(InputStream).readAllBytes()`。

## 三、剩余工作（按顺序）

### 1. `org.linear.storage.LinearV2RegionFile implements IRegionFile`

内存态：`byte[1024][] chunks`（null=空）、`long[1024] timestamps`（**秒**）、
`Map<String,Integer> nbtFeatures`（load 时保留、新文件为空）、`dirty` 标志、单锁。

- 构造 `(Path, regionX, regionZ, LinearConfig)`：文件存在且非空 → 整文件读入内存解析。
- `getChunkDataInputStream` → `DataInputStream(ByteArrayInputStream(chunks[idx]))`，空返回 null。
- `getChunkDataOutputStream` → ByteArrayOutputStream 子类，close() 时提交：
  `chunks[idx]=bytes; timestamps[idx]=epochSeconds; dirty=true`。
- `clear` → `chunks[idx]=null; timestamps[idx]=0; dirty=true`。
- `flush()`/`close()`：dirty 时整文件保存（写 `path+".tmp"` → `force(true)` → ATOMIC_MOVE，
  失败回退普通 move）。
- idx 公式：`idx = (x & 31) + ((z & 31) << 5)`。

### 2. `org.linear.storage.BufferedLinearV3RegionFile implements IRegionFile`

完整移植 `BufferedLinearRegionFile.java`，删掉 Moonrise/Paper 依赖
（IRegionFile 换成我们的接口、`MoonriseRegionFileIO`/`getOversizedData`/`recalculateHeader`
等 MCC 相关方法全部不要、`ConcurrentUtil` VarHandle 换成 AtomicBoolean/AtomicLong）。

要点：
- 构造 `(Path master, LinearConfig, BufferedLinearFlusher)`：删除残留 `.swp` 与 `.tmp` →
  开 swap channel（CREATE/WRITE/READ/DELETE_ON_CLOSE）→ 初始化 1024 个 Sector
  （offset=headerSize, len=0）→ 若 master 存在：只校验 14 字节头
  （魔数+version==3，MASTER 魔数 + version 2 → 报"bufferedlinear v2 不支持"；
  LINEAR 魔数 → 报"这是 linear 文件放错了扩展名"），**不预读数据**（懒加载）→ 注册进 flusher。
- 懒加载 `ensureBucketLoaded(chunkIndex)`：bucket.lock 内检查 loaded；从 master 读
  posTable[bucket]，0 → 空；否则读 rawLen+cmpLen+压缩数据 → `Zstd.decompress(data, rawLen)` →
  64 条 entry（u32 size，0=空；否则 size 字节 section 数据）→
  `writeChunkDataRaw(idx, section, skipSync=true)` 写入 swap。
- 写路径 `write(pos, data)`：`ensureBucketLoaded` → 构造 chunk section
  （u32 nbtLen + u64 **毫秒**时间戳 + u32 xxh32(nbt, seed) + nbt）→ zstd-1 压缩
  （锁外压缩，存 swap 格式 `[u32 rawLen][zstd bytes]`）→ 写锁内 `sector.store` →
  `markBucketDirty` → `markAsToSync`。`getChunkDataOutputStream().close()` 里写完后调
  `flushInternal()`（与参考一致）。
- `Sector.store` 语义照抄参考（新数据 ≤ 旧长度原地写，否则 append 到
  `currentAcquiredIndex`）；注意参考里先 `this.length=newDataLength` 再用暂存的
  oldLength 判断，移植时别弄错顺序。
- `flushInternal()`：写锁内算 spare（=acquiredIndex-headerSize-Σ活sector长度）、
  live；`spare > 1MiB && spare > 0.6*live` → `compactSwapFile()`（完整移植：写头→
  转移活 sector 到 .tmp→原子替换→重开 channel→更新 sectors/acquiredIndex→再写头）；
  锁外：master 不存在且未 compact → `syncToMasterFile()`。
- `syncToMasterFile()` = `writeMainFileBucketed` 移植：masterFileLock 写锁；旧 master 有效
  （魔数+ver3）则解析旧 posTable；写 `.tmp`：14B 头（魔数+3+level+seed 0x0721）→ 128B
  全零占位 → 从 142 起每 bucket：脏 → 从 swap 逐 chunk 解压 sector 拼 64 条
  `u32 size + section`（空写 0），有数据则 zstd（配置等级，**不带** frame checksum）写
  `rawLen+cmpLen+data`，全空 → posTable 置 0；不脏 → 从旧 master `transferTo` 原样复制
  → 回填 posTable → `force(true)` → ATOMIC_MOVE 替换 → 更新 syncedEpoch。
  CAS `synced` 防并发，失败还原并抛 IOException。
  不变式：脏 bucket 必然已 loaded（write/clear 前都 ensureBucketLoaded），sync 时 swap 里
  即该 bucket 完整内容。
- `close()`：syncIfNeeded（最后一次同步）→ 写锁 markClosed（flusher.removeFile）→ 关 swap。
- 读路径校验 xxh32 失败 → 抛 IOException（阻止加载坏数据）。
- 单 chunk 大小上限自定（建议 256 MiB，超出抛 IOException；原版 Paper 的
  `RegionFile.MAX_CHUNK_SIZE` 在 26.2 原版里**不存在**）。

### 3. `org.linear.storage.BufferedLinearFlusher`

单例（lazy，按 LinearConfig 建）：1 个 daemon 调度线程每 1s 扫描注册文件集
（ConcurrentHashMap.newKeySet）；条件 `shouldSync() && (nanoTime-lastWritten) ≥ delay
&& markAsBeingSynced()` → 提交到 `v3-flush-threads` 个 daemon worker 执行
`syncIfNeeded()`（其 finally 会复位 beingSynced，照参考）。异常只 log 不杀线程。

### 4. Mixin（包 `org.linear.mixin`，注册进 `src/main/resources/linear.mixins.json` 的 "mixins"）

- `RegionFileMixin`：`@Mixin(RegionFile.class) implements IRegionFile` —— 方法签名完全同名，
  自动满足接口，零方法体（用于 .mca 兜底时把原版 RegionFile 当 IRegionFile 用，
  `(IRegionFile)(Object) new RegionFile(info, mcaPath, folder, sync)`）。
- `RegionFileStorageMixin`：`@Mixin(RegionFileStorage.class)`
  - `@Shadow @Final` info / folder / sync；
  - `@Unique` 自己的缓存 `LinkedHashMap<Long, IRegionFile>`（**mixin 的字段初始化器不会执行**，
    必须在统一入口方法里 lazy new；不要 @Shadow 原版 fastutil regionCache，省依赖）；
  - `@Inject(at=HEAD, cancellable=true)` 拦截 5 个公开方法：
    - `read`：`getFile(pos).getChunkDataInputStream(pos)` → null → setReturnValue(null)，
      否则 `NbtIo.read(in)`（try-with 关流）；
    - `scanChunk`：in!=null 时 `NbtIo.parse(in, visitor, NbtAccounter.unlimitedHeap())`；
    - `write`：`SharedConstants.DEBUG_DONT_SAVE_WORLD` 直接 return；tag==null → `clear(pos)`，
      否则 `NbtIo.write(tag, getChunkDataOutputStream(pos))`（try-with）；
    - `flush`：遍历缓存逐个 flush（收集异常后抛）；
    - `close`：遍历缓存逐个 close（用 `ExceptionCollector`），清空缓存；
    - 全部 `ci.cancel()` / `cir.setReturnValue`。handler 可以声明 `throws IOException`
      （目标方法都带 throws）。
  - `getFile(pos)`：key=`ChunkPos.pack(getRegionX(), getRegionZ())`；LRU 上限 256，逐出时
    close；`FileUtil.createDirectoriesSafe(folder)`；解析顺序 = 配置格式的文件 → 另一种
    linear 文件 → `.mca`（原版兜底 + warn）→ 新建配置格式。
- mixins.json 已有 `"package": "org.linear.mixin"`、`compatibilityLevel: JAVA_25`、
  `overwrites.requireAnnotations`（我们不用 @Overwrite，全用 @Inject）。

### 5. 其他接线

- `Linear.onInitialize()`：调 `LinearConfig.get()` 触发加载+打日志即可。
- `build.gradle` dependencies 加：`include(implementation("com.github.luben:zstd-jni:1.5.7-3"))`
  （版本号在有网环境核实最新）。
- `fabric.mod.json` 的 description 还是模板残留 "Friend EMI Plugin"，顺手改掉。

## 四、格式规格关键事实（文档没写清/有坑的部分）

### LinearV2（`.linear`，魔数 `0xc3ff13183cca9d9a`，全部大端）

- **磁盘上的 version 字节是 3**（LINEARv2.md 写 2 是错的；1/2 = linear v1 布局 → 直接报错
  "linear v1 不支持，请用 linear-tools-rs 转换"。Rust `to_linear_v2` 写 3、Luminol
  `version==3 → parseLinearV2`，一致）。读取接受 3 即可。
- 布局：SuperBlock 26B（魔数8 + ver1 + newestTimestamp u64 + gridSize i8 + regionX i32 +
  regionZ i32）→ 存在位图 128B → NBT features 字典（u8 keyLen + key + u32 value，重复，
  0x00 结束；空字典就一个 0x00）→ bucket 头 grid²×13B（u32 压缩后大小 + i8 压缩等级 +
  u64 xxh64）→ 压缩 bucket 顺序拼接 → 尾部魔数 8B。
- **位图只写不信**：读取时跳过 128B、按 entry size>0 判断存在（文档明说 Java 实现的位图
  是坏的；Luminol 也这么做）。写入时按 `chunks[idx]!=null` 正确生成（bit MSB-first：
  `out[i] |= 1 << (7 - j)`，i*8+j = chunk 索引）。
- bucket 顺序：外层 `for bx { for bz }`，`bucketIndex = bx*grid + bz`；bucket 内
  `for ix { for iz }`（局部序 `ix*cpb+iz`），`chunkX = bx*cpb+ix`, `chunkZ = bz*cpb+iz`,
  `idx = chunkX + chunkZ*32`，`cpb = 32/grid`。
- entry：`u32 size`（空=0；非空=**数据长+8**，即含时间戳 8 字节）+ `u64 timestamp`
  （**秒**；空 chunk 也写时间戳）+ 数据。读取时 `size<8` 视为空（Rust 同款防御）。
- bucket 的 xxh64 = 对**压缩后**字节、种子 0 计算；不匹配 → 抛异常（Rust/Luminol 都抛）。
  尾部魔数校验失败也抛（Luminol 行为）。
- zstd：写入时带 frame checksum（对齐 Rust `include_checksum(true)`）；读取必须**流式解压**
  （格式不存原始长度，Rust 编码器也没写 pledged size）→ `ZstdUtil.decompressStream`。
  bucket 压缩后大小为 0 → 整桶为空（合法情况）。
- 读取容错：bucket 解压后 entry 不足 cpb² 条 → 缺的当空 chunk（对齐 Rust 逐条读到 EOF 的
  宽容行为）。
- **不要复刻 Rust 写入端 `data.len()==64` 就写空桶的 quirk**——那是上游 python 抄来的
  丢数据 bug。
- 保存时 superblock 的 region 坐标用构造参数（来自 ChunkPos），不是文件里读到的。

### BufferedLinearV3（`.b_linear`，master 魔数 `0xFFFFDFF7EDDAFD97`，全部大端）

- Header 14B：魔数8 + version(=3) + zstd等级 i8 + xxh32种子 i32（写 0x0721；**读 chunk 时
  用文件头里的种子**）。PosTable：偏移 14 起 16×u64（bucket 绝对偏移，0=空桶）。数据区从
  142 起。
- bucket 外层：`u32 rawLen + u32 cmpLen + zstd数据`（**不带** frame checksum，对齐 Luminol
  `Zstd.compress`）；内层 64 条 `u32 size + section`（0=空）。
- section：`u32 nbtLen + u64 timestamp(毫秒) + u32 xxh32(nbt, seed) + nbt`，size = nbtLen+16。
  读时校验 xxh32（对 section 头之后的剩余字节算）。
- 索引：`idx = (x&31) + ((z&31)<<5)`；`bucket = idx >> 6`；`inBucket = idx & 63`。
- swap 文件（`master + ".swp"`）：魔数 `0x1145141919810L`、version 2、种子、acquiredIndex、
  1024×Sector(u64 offset + u64 len + u8 hasData)。打开时先删旧 swap（**从不跨进程恢复**，
  崩溃安全性完全由 master 承担）；头部只在 compact 时落盘。
- compact 阈值：垃圾空间 > 1MiB 且 > 活数据的 60%。

### 两种格式共同点

- chunk 存的都是**未压缩的原始 NBT 序列化字节**（原版 .mca 是每 chunk zlib，linear 是
  桶/文件级 zstd）。`NbtIo.read/write` 直接对接我们的流，无需再压缩。

## 五、MC 26.2 API 事实（从 loom-cache 的 jar javap 得到，Mojang 官方名，无混淆）

- jar 位置：`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-043a8b3edf/26.2/minecraft-common-043a8b3edf-26.2.jar`
  （23.9MB；客户端专有类在旁边的 clientOnly jar，本任务用不到）。
- `RegionFileStorage`（final）：**所有** IO 走 5 个公开方法 `read/scanChunk/write/flush/close`
  （+`info()`）；`IOWorker` 只调这 6 个；region/entities/poi（`EntityStorage`、
  `SectionStorage`、`SimpleRegionStorage`）全部经 `IOWorker` → 一个 mixin 全覆盖。
- 私有 `getRegionFile(ChunkPos)`：`ChunkPos.pack(getRegionX(),getRegionZ())` 作 key；
  缓存 `Long2ObjectLinkedOpenHashMap`，≥256 时 `removeLast().close()`；
  `FileUtil.createDirectoriesSafe(folder)`；文件名 `"r." + x + "." + z + ".mca"`；
  `new RegionFile(info, path, folder, sync)`。
- `write` 开头检查 `SharedConstants.DEBUG_DONT_SAVE_WORLD`；tag==null → `clear`。
- NBT：`NbtIo.read(DataInput)`、`NbtIo.write(CompoundTag, DataOutput)`、
  `NbtIo.parse(DataInput, StreamTagVisitor, NbtAccounter)`、`NbtAccounter.unlimitedHeap()`。
- `close()` 用 `net.minecraft.util.ExceptionCollector`（add/throwIfPresent）。
- `ChunkPos` 是 record：`x()`/`z()`（不是字段访问）、`getRegionX()/getRegionZ()`、
  静态 `pack(int,int)`。
- 原版 `RegionFile` 公开方法：`getPath/getChunkDataInputStream/getChunkDataOutputStream/
  doesChunkExist/hasChunk/clear/flush/close`；其中 `doesChunkExist`/`hasChunk` **没有**
  throws 子句（IRegionFile 接口声明了 throws IOException，实现收窄合法，mixin 合并不校验
  throws，没问题）；构造器 `(RegionStorageInfo, Path, Path, boolean)`。
- 26.2 原版没有 `RegionFile.MAX_CHUNK_SIZE`（那是 Paper 加的）。

## 六、构建与验证环境事实

- 项目：Fabric mod，loom 1.17-SNAPSHOT，MC 26.2（无混淆、无 mappings 依赖行），loader
  0.19.3，fabric-api 0.155.2+26.2，Java 25 target，split source sets（client 空壳不用动）。
- Cowork 云容器：Java 21 + gradle + cargo 在位，但出站白名单默认**全堵**
  （maven central / fabricmc / mojang / crates.io / apt 都 403 或 000）。报错提示
  "Add this host to your network egress settings"——用户可在 Claude 网络出站设置里加：
  `index.crates.io static.crates.io repo.maven.apache.org maven.fabricmc.net
  libraries.minecraft.net piston-meta.mojang.com piston-data.mojang.com
  services.gradle.org plugins.gradle.org`。加了之后容器内才能 cargo 构建参考工具/跑 gradle。
  用户本机网络正常（有代理），**最终 `gradlew build` 在用户机器上跑即可**。
- 无网情况下的编译检查方案：javac 21 + 上述缓存 mc jar + 手写桩
  （mixin 注解 org.spongepowered.asm.mixin.*、net.fabricmc.api.ModInitializer、
  net.fabricmc.loader.api.FabricLoader#getConfigDir、org.slf4j、com.github.luben.zstd 三类）。
  代码保持 Java 21 语法兼容（release 25 只在用户机上生效）。
- 交叉验证方案（有网后）：cargo 构建 `linear-tools-rs`（workspace = 根 + mclinear，纯
  crates.io 依赖）；Java 写出的文件用 CLI 转换回来逐字节比对 chunk，反向同理。
  ⚠ CLI 收集输入只认 `.mca`/`.linear` 扩展名（`get_dir_file`），`.b_linear` 文件要**改名成
  `.linear`** 再喂给它（文件类型按魔数识别，改名无害）。CLI 用法：
  `mc-linear-tool to-linear-v2|to-b-linear-v3 <in_dir> <out_dir> --compress-level N --grid-size N`。
- xxhash 两个类是我按规范手写的，**必须先验证**再信任：有网后与 `xxhash-rust` 或 pip
  `xxhash` 对拍；至少覆盖空输入、<16B、<32B、跨块长输入、带种子（0x0721）各档。
- `linear-tools-rs/mca/data/` 下有各版本真实 .mca（1.21.11.mca 等）可当测试数据；
  `test/` 下还有现成的 region/world/world_linear 测试目录。

## 七、验收清单（2026-07-26 全部通过）

- [x] javac 全量编译零错误（用户机 `gradlew build` 通过，产出 linear-v1.0.0.jar）
- [x] xxhash32/64 对拍通过（vs Python xxhash 3.6.0，18 种长度 × 种子 0/0x0721/0x9E3779B1/-1
      共 73 组 + 带偏移用例，全部一致）
- [x] Java 写 LinearV2（grid 1/8/16/32）→ Rust CLI 能读且转换后数据逐 chunk 摘要一致
      （基准：test/world_linear/region/r.0.0.linear，727 chunks / 4.4MB）
- [x] Rust CLI 转出的 LinearV2 → Java 读取一致（含 v3→v2 反向转换产物）
- [x] Java 写 BufferedLinearV3 → Rust CLI（改名 .linear 后）能读且数据一致；反向同理
- [x] V3 单元场景：懒加载重开、覆盖写变大/变小、clear、compact（实测触发，swap
      4.1MB→345KB 数据完好）、脏桶+净桶混合 sync、全清空缩回 142B 头，6144 项校验全过
- [x] 进服实测（runClient）：默认配置生成 `.b_linear`（region/entities/poi 全覆盖），
      切 `linearv2` 后新区域生成 `.linear`、旧区域按魔数继续 V3 读写，混合世界重进正常，
      退出无 .swp/.tmp 残留，全程日志零 linear 相关错误；真实世界文件用独立 harness
      复读通过（r.19.19.linear 满 1024 chunks / 49MB）
- 另有三路 agent 规格审查（V2 / V3+Flusher / mixin，对照 Rust 实现与 Luminol 参考）：零问题

### 验证时发现的注意事项

1. **linear-tools-rs CLI（HEAD）有两个读取端 bug**，不修则读不了任何 linear 文件：
   `mclinear/src/utils.rs` get_file_type 先 seek 回开头才读版本字节（永远读到魔数首字节），
   且 `version == 1 && version == 2` 应为 `||`；`mclinear/src/models/linear_v2.rs`
   deserialize_bucket_header 的 `grid_size * grid_size` 在 i8 上溢出（grid≥16 回绕成 0，
   读出 0 个 bucket）。交叉验证是在打了修复补丁的 CLI 上做的（**补丁未提交**，
   linear-tools-rs 保持原样；补丁内容见上述两处描述，很容易重打）。
2. **LinearV2 落盘时机**（已解决）：全内存实现只在 storage flush/close 时写盘，游戏内
   Esc 暂停存档不触发。已加 `LinearV2Flusher` 周期落盘兜底（`v2-flush-interval-seconds`，
   默认 60s，0=禁用回旧行为），端到端验证通过（写入不 close，6.2s 后台落盘且内容一致）。
   后续对抗审查修了三处并发/健壮性问题：`closed` 标志防 close 失败后陈旧引用与新实例
   并发写盘；save 改锁内快照+锁外压缩写盘（不再阻塞游戏 IO）；失败恢复 dirty 改 catch
   Throwable（zstd-jni 抛 RuntimeException，只 catch IOException 会静默丢数据）。
3. `gradle.properties` 的 `mod_version=v1.0.0` 非 SemVer（带 v 前缀），Fabric Loader 会 WARN。

## 八、C2ME 兼容性分析结论（2026-07-26，对照本地源码 C2ME 0.4.2-alpha.0 / MC 26.2）

**默认配置下不兼容，且是双向静默破坏**（启动不报错、日志无异常，属最危险形态）：

- C2ME 的 `c2me-rewrites-chunkio`（config `ioSystem.replaceImpl`，**默认 true**）用 @Redirect
  把 region 的 StorageIoWorker 换成自建 C2MEStorageThread，经 @Invoker 直呼**私有**
  `getRegionFile` + `RegionFile.getChunkInputStream/invokeWriteChunk` 读写 .mca——完全绕过
  我们拦截的 5 个公开方法。后果：已有 linear 世界的区域被视为空 → **地形静默重生成**、
  新数据全写 .mca；而 entities/poi 的 StorageIoWorker 构造不在其 @Redirect 范围内仍走我们
  → **同一世界 region=.mca、entities/poi=linear 的裂脑存档**。
- 反向破坏：C2MEStorageThread 仅有的公开 API 调用是 `storage.sync()`/`storage.close()`，
  被我们 linear$flush/linear$close 无条件 HEAD-cancel（其实例的 linear$cache 恒为 null）
  → C2ME 的 RegionFile **永不 fsync/close**（句柄泄漏、崩溃时 .mca 损坏风险升高）。
- 次级路径：`c2me-base` 的 IDirectStorage 原始字节直写同样经私有 getRegionFile 绕过 write，
  但仅 `ioSystem.gcFreeChunkSerializer=true` 时激活（**默认 false**）。

**兼容组合**（静态判定 compatible）：c2me.toml 设 `ioSystem.replaceImpl=false` 且保持
`gcFreeChunkSerializer=false`——此时 3 个 chunkio mixin 整体不应用，IO 回到原版
StorageIoWorker → 5 个公开方法 → 我们完整接管。建议再做运行时冒烟（加载 linear 世界、
跨区域移动、存盘重启、确认无 .mca 新增）。

**已实施（2026-07-27）**：精确守卫（`Linear.checkC2meCompat()`）——检测到 c2me 后读其
实际配置：两个危险开关都关（`ioSystem.replaceImpl=false` 且 `gcFreeChunkSerializer=false`）
→ 打 INFO 放行共存；任一开启 → 抛 IllegalStateException 拒绝启动，错误信息附 c2me.toml
修改指引与当前检测值。读取顺序：反射 `ModuleEntryPoint#enabled`（chunkio 与 chunk_serializer
两个模块，字段均名 enabled，类加载即含默认值语义；ClassNotFound = 模块未分发 = 安全）→
失败回落自带的极简 TOML 解析（严格小写布尔，非法值返回 null）→ 均失败 fail-closed 拒启。
解析器 12 项边界测试全过（scratchpad GuardTest）。曾短暂采用 breaks 一刀切（15f6441），
已按用户决定放宽。

**运行时冒烟已完成（2026-07-27，真 C2ME 0.4.2-alpha.0.27 jar 放 run/mods）**：
- 危险态（无 c2me.toml，全默认）→ mod 初始化即被守卫拦截崩溃，crash report 内含
  中文指引，窗口未打开；C2ME 首次加载会自动生成 c2me.toml ✅
- 兼容态（replaceImpl=false）→ INFO"正常共存"，游戏正常启动 ✅
- 共存实测：进入混合格式世界（.linear + .b_linear）游玩，C2ME 各模块正常工作
  （日志 51 处活动），全程零 .mca 生成、零存储错误、退出无 .swp/.tmp 残留，
  区域文件事后 harness 复读通过（930/1024 chunks）✅
- 反射失败回落路径无法用真 jar 触发（其字段必然存在），由 GuardTest 单测覆盖，可接受。
  注：其 mods 目录常见伴生 jar c2me-opts-accel-opencl 未参与测试（纯 worldgen 加速，
  不碰 chunk IO）。测试后 c2me.jar 已移出 run/mods 恢复干净开发环境。

**其余建议措施（未实施）**：
1. 启动守卫：检测 c2me 加载且 replaceImpl=true（反射 ModuleEntryPoint#enabled 或解析
   c2me.toml）→ 抛致命错误拒绝进世界，信息写明两条出路（关 replaceImpl / 纯 anvil 世界）。
2. 廉价加固（与 C2ME 无关也值得做）：linear$flush/linear$close 改为仅当 linear$cache != null
   时才 cancel——我们没服务过的 RegionFileStorage 实例（如 C2ME 私建的）回落原版行为，
   反向破坏即消失。
3. mixin priority 无用：双方注入的类/方法零重叠，不要浪费时间调。
4. ~~长期：RegionFile 层深度集成（replaceImpl=true 下 linear 仍生效）~~
   **已否决（2026-07-27，用户决定）**。理由：(a) 结构性双重压缩税——C2ME 经
   invokeWriteChunk 递交的是已 deflate 的 .mca 信封，linear 格式存裸 NBT，必须
   解开重压，接口层级错配无法绕开；(b) 自研 async-write（写路径 O(1) 入队 +
   pending map + 后台编码压缩）可从我们自己这层拿到 C2ME chunkio 优化的大部分
   收益，做完后集成的剩余收益趋零；(c) 绑死原版 RegionFile 私有 API，跨版本
   维护负债。兼容路线定格：守卫 + 用户在 c2me.toml 关 replaceImpl，
   性能优化走自研（async-write 列为 v1.1 主项）。
5. ~~`format=anvil` 时放行 replaceImpl=true~~ **已否决（2026-07-27，用户决定）**。
   技术上可行但需三件事（守卫加 format 分支、flush/close 加固、世界级 linear 文件
   扫描兜底），唯一受益场景是"多世界混用一套 mods"，收益配不上复杂度。
   规则统一为：装 linear 即必须 `ioSystem.replaceImpl=false`，不区分 format。
   随之建议 2（flush/close 仅 cache!=null 才 cancel 的加固）也维持未实施——
   兼容配置下 C2ME 不会私建存储实例，该风险仅存在于守卫已拦截的组合里。
