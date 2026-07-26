# 验证 harness（无头，不需要启动游戏）

绕过 FabricLoader 直接驱动存储实现的验证工具，配合 `linear-tools-rs` CLI
做交叉验证。所有工具只依赖 `gradlew build` 产出的类和 gradle 缓存里的 jar。

| 文件 | 用途 |
|---|---|
| `Xval.java` | 区域文件读写交叉验证 + LinearV2 周期落盘端到端测试 |
| `GuardTest.java` | C2ME 守卫的 c2me.toml 解析器 12 项边界测试 |
| `XXTest.java` + `check.py` | XXHash32/64 与 Python `xxhash` 库对拍（73 组向量） |

## 编译与运行（Git Bash，Windows 路径按机器调整）

先 `gradlew build` 一次（harness 直接用 `build/classes/java/main` 和 gradle 缓存里的 jar）。

```bash
cd tools/xval
mkdir -p out

# 三个必须遵守的点，否则 classpath 会静默失效（jar 悄悄丢失、报 NoClassDefFoundError）：
#   1. 全部用绝对路径 —— Git Bash 对含分号的参数做路径转换，混入相对路径会坏掉
#   2. gradle 缓存路径用 cygpath 转成 C:/ 形式 —— java 认不了 /c/Users/... 这种 POSIX 路径
#   3. 选版本用 sort -V（版本序）—— 字典序会把 2.0.9 排在 2.0.13 之后、8.0.16 排在 10.0.21 之后
R=$(cd ../.. && pwd -W)                       # 仓库根，Windows 形式
M2="$(cygpath -m ~/.gradle)/caches/modules-2/files-2.1"
pick() { find "$M2/$1" -name "$2" 2>/dev/null | grep -vE "sources|javadoc" | sort -V | tail -1; }

MC_JAR=$(ls "$R"/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/26.2/*-26.2.jar | head -1)
CP="$R/tools/xval/out;$R/build/classes/java/main;$MC_JAR"
CP="$CP;$(pick com.github.luben 'zstd-jni-*.jar')"
CP="$CP;$(pick org.slf4j/slf4j-api '*.jar')"
CP="$CP;$(pick net.fabricmc/fabric-loader 'fabric-loader-*.jar')"
CP="$CP;$(pick com.mojang/datafixerupper '*.jar')"
CP="$CP;$(pick it.unimi.dsi/fastutil '*.jar')"

javac -cp "$CP" -d out Xval.java
javac -cp "$CP" -d out GuardTest.java   # 注意：属 org.linear 包（访问包私有方法）
javac -d out "$R"/src/main/java/org/linear/storage/util/XXHash32.java \
             "$R"/src/main/java/org/linear/storage/util/XXHash64.java XXTest.java
```

排查提示：跑起来没有任何输出（连 TOTAL 行都没有）基本就是 classpath 问题——
去掉 `2>/dev/null` 看真实异常。

### Xval 命令

```bash
java -cp "$CP" Xval dump <文件> <rx> <rz>          # 每个非空 chunk 输出 "idx len xxh64"，按魔数自动识别 V2/V3
java -cp "$CP" Xval copy <src> <rx> <rz> <v2|v3> <dst> <grid> <level>   # 全 chunk 复制转格式
java -cp "$CP" Xval expectv1error <v1文件>          # 验证 linear v1 报清晰错误
java -cp "$CP" Xval flushtest <目标文件>            # V2 周期落盘端到端（约 6~15s）
```

交叉验证套路：Java `copy` 写出 → 用格式转换工具转换 → Java `dump` 比对摘要，双向都做。
（`linear-tools-rs` 是独立的参考项目，不属于本仓库。）

### xxhash 对拍

```bash
java -cp out XXTest > java_hashes.txt && python check.py   # 需 pip install xxhash
```

### 守卫解析器测试

```bash
java -cp "$CP" org.linear.GuardTest
```
