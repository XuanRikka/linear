# 验证 harness（无头，不需要启动游戏）

绕过 FabricLoader 直接驱动存储实现的验证工具，配合 `linear-tools-rs` CLI
做交叉验证。所有工具只依赖 `gradlew build` 产出的类和 gradle 缓存里的 jar。

| 文件 | 用途 |
|---|---|
| `Xval.java` | 区域文件读写交叉验证 + LinearV2 周期落盘端到端测试 |
| `GuardTest.java` | C2ME 守卫的 c2me.toml 解析器 12 项边界测试 |
| `XXTest.java` + `check.py` | XXHash32/64 与 Python `xxhash` 库对拍（73 组向量） |

## 编译与运行（Git Bash，Windows 路径按机器调整）

```bash
cd tools/xval
MC_JAR=$(ls ../../.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/26.2/*-26.2.jar | head -1)
ZSTD_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.github.luben -name "zstd-jni-*.jar" | grep -v sources | head -1)
SLF4J_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api -name "*.jar" | grep -v sources | sort | tail -1)
LOADER_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loader -name "fabric-loader-*.jar" | grep -v sources | head -1)
DFU_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.mojang/datafixerupper -name "*.jar" | sort | tail -1)
FASTUTIL_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/it.unimi.dsi/fastutil -name "*.jar" | sort | tail -1)
CP="out;../../build/classes/java/main;$MC_JAR;$ZSTD_JAR;$SLF4J_JAR;$LOADER_JAR;$DFU_JAR;$FASTUTIL_JAR"

javac -cp "$CP" -d out Xval.java
javac -cp "$CP" -d out GuardTest.java   # 注意：属 org.linear 包（访问包私有方法）
javac -d out ../../src/main/java/org/linear/storage/util/XXHash32.java \
             ../../src/main/java/org/linear/storage/util/XXHash64.java XXTest.java
```

### Xval 命令

```bash
java -cp "$CP" Xval dump <文件> <rx> <rz>          # 每个非空 chunk 输出 "idx len xxh64"，按魔数自动识别 V2/V3
java -cp "$CP" Xval copy <src> <rx> <rz> <v2|v3> <dst> <grid> <level>   # 全 chunk 复制转格式
java -cp "$CP" Xval expectv1error <v1文件>          # 验证 linear v1 报清晰错误
java -cp "$CP" Xval flushtest <目标文件>            # V2 周期落盘端到端（约 6~15s）
```

交叉验证套路（基准数据在 `linear-tools-rs/test/world_linear/region/`）：
Java `copy` 写出 → Rust CLI 转换 → Java `dump` 比对摘要，双向都做。
⚠ CLI 输入只认 `.mca`/`.linear` 扩展名，`.b_linear` 要改名成 `.linear` 再喂
（按魔数识别，改名无害）。⚠ linear-tools-rs HEAD 有两个读取端 bug
（见 HANDOFF.md 第七节），需打补丁后使用；该仓库为只读参考，补丁不入库。

### xxhash 对拍

```bash
java -cp out XXTest > java_hashes.txt && python check.py   # 需 pip install xxhash
```

### 守卫解析器测试

```bash
java -cp "$CP" org.linear.GuardTest
```
