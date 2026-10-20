# HMCL RegionPatch

一个独立的 Java Agent，用来在**运行时**把 HMCL 的

```java
org.jackhuang.hmcl.util.RegionUtils.isIsChinaMainlandMachine()
```

改成恒定返回 `false`。

- 不修改 HMCL 源码，不修改 HMCL 的 JAR，不替换文件。
- 只改这一个方法：`IS_CHINA_MAINLAND_MACHINE` 字段和 `isIsChinaMainlandNetwork()` 都保持原样。
- 通过 `-javaagent:` 在 HMCL 自己的 JVM 启动时注入，退出 HMCL 后自动失效。

> 适用版本：**HMCL 3.17+（nightly / main）**。`RegionUtils` 是 2026-10-20 才进入主干的类，
> 稳定版 **3.16.4 的 JAR 中根本没有这个类**。把 Agent 加载到 3.16.4 上不会报错，也不会生效，
> 只会在退出时打印一条 “exiting without patching …” 的说明（见下文诊断表）。

---

## 1. 目录结构

```
.
├── build.gradle.kts                  # Kotlin DSL 构建脚本（Shadow 打包、smokeTest）
├── settings.gradle.kts               # 仓库与插件版本
├── gradle.properties                 # Kotlin / Byte Buddy / Shadow 版本
├── gradlew, gradlew.bat, gradle/     # Gradle Wrapper 8.10
├── src/main/kotlin/org/jackhuang/hmcl/patch/RegionPatchAgent.kt
│                                     # Agent 主体（premain + Byte Buddy）
├── src/fixture/java/...              # 与 HMCL 同 FQN 的测试桩
├── src/harness/java/...              # 独立 JVM 行为断言
├── src/preload/java/...              # 仅测试用：先加载目标类，覆盖 retransform 路径
├── start-hmcl-with-patch.bat         # 一键启动脚本（临时设置 HMCL_JAVA_OPTS）
└── tools/RegionProbe.java            # 可选：对真实 HMCL JAR 做反射探针
```

构建产物：

```
build/libs/RegionPatch.jar            # 最终 Agent（含 Byte Buddy，可直接使用）
build/libs/RegionPatch-1.0.0-thin.jar # 瘦 JAR，仅调试用，不要拿去 -javaagent
```

---

## 2. 构建

要求：

- **JDK 17 或 21**（Gradle 8.10 不支持 JDK 25；本仓库用 JDK 17 验证）。
- 首次构建需要联网下载 Gradle 依赖。

```powershell
# 任选一个 17/21 的 JDK
$env:JAVA_HOME = "C:\Users\<you>\.jdks\ms-17.0.16"

.\gradlew.bat build
```

只打包：

```powershell
.\gradlew.bat shadowJar
```

JAR 的 `META-INF/MANIFEST.MF` 关键属性：

```
Premain-Class: org.jackhuang.hmcl.patch.RegionPatchAgent
Can-Retransform-Classes: true
```

---

## 3. 测试

```powershell
.\gradlew.bat smokeTest
```

该任务在**独立的 JVM 进程**里跑 4 个场景，全部按行为/字节码断言，而不是看日志：

| 场景 | 内容 | 断言 |
| --- | --- | --- |
| `no-agent` | 不加载 Agent | `isIsChinaMainlandMachine()` 与 `isIsChinaMainlandNetwork()` 均为 `true`，字段为 `true` |
| `with-agent` | 目标类在 Agent 之后才被定义 | 目标方法返回 `false`；邻近方法、字段、方法/字段数量不变 |
| `with-agent-retransform` | 辅助 Agent 先加载目标类，RegionPatch 之后才安装 | 同上，证明已加载类通过 `RETRANSFORMATION` 被成功改写 |
| `target-absent` | 类路径上没有 `RegionUtils`（等价于 HMCL 3.16.4） | 退出码 0、无 “PATCH APPLIED”，且必须出现 “exiting without patching” |

另外还会检查：

- Manifest 中 `Premain-Class`、`Can-Retransform-Classes` 正确。
- Agent 导出的重写后字节码（`build/smoke/dump/{load-time,retransform}/…class`）存在，且
  `javap -p -c` 显示目标方法为 `iconst_0; ireturn`、不再读字段；邻近方法仍读字段。

手动核对字节码：

```powershell
javap -p -c build\smoke\dump\load-time\org.jackhuang.hmcl.util.RegionUtils.class
```

### 对真实 HMCL JAR 验证（可选）

```powershell
javac -d build\probe tools\RegionProbe.java

# 1) 不加载 Agent
java -cp "build\probe;HMCL-nightly.jar" RegionProbe

# 2) 加载 Agent
java -javaagent:build\libs\RegionPatch.jar -cp "build\probe;HMCL-nightly.jar" RegionProbe
```

在本机用 `HMCL-nightly.jar`（3.17.0.362，class file major 61 / Java 17）实测结果：

```
              无 Agent                     有 Agent
isIsChinaMainlandMachine()   true                          false
IS_CHINA_MAINLAND_MACHINE    true                          true      （未改动）
declared methods / fields    5 / 2                         5 / 2     （结构未变）
```

---

## 4. 工作原理

- 入口是 `premain(String, Instrumentation)`，用 Byte Buddy 的 `AgentBuilder` 安装一个
  `ClassFileTransformer`，类型匹配器精确到 `org.jackhuang.hmcl.util.RegionUtils`。
- 改写前会校验方法确实是 `public static boolean isIsChinaMainlandMachine()` 且无参数；
  任何一项不符就**拒绝改写**并把状态记为 `TARGET_SIGNATURE_MISMATCH`。
- 方法体用 `FixedValue.value(false)` 替换，编译成 `iconst_0; ireturn`，不新增字段/方法。
- `RedefinitionStrategy.RETRANSFORMATION` + `Can-Retransform-Classes: true`：类已加载时也能生效。
- `disableClassFormatChanges()`：保证重转换时类结构不变（JVM 对 retransform 的硬性要求）。
- 只 relocate `net.bytebuddy` 到 `regionpatch.shaded.net.bytebuddy`，避免与 HMCL 的类路径冲突。

### 日志与状态

Agent 会区分“初始化完成”和“真的改写了”：

```
[RegionPatch] INFO: HMCL RegionPatch agent starting (args=none)
[RegionPatch] INFO: JVM 17.0.16 (Microsoft), retransform supported=true
[RegionPatch] INFO: Diagnostics: 'org.jackhuang.hmcl.util.RegionUtils' is not loaded yet ...
[RegionPatch] INFO: Target validated: public static boolean isIsChinaMainlandMachine() ...
[RegionPatch] INFO: PATCH APPLIED: 'org.jackhuang.hmcl.util.RegionUtils' rewritten ...
```

同时写入 JVM 系统属性 `regionpatch.agent.status`，可用 `jcmd` 读取（见 6.3）：

| status | 含义 |
| --- | --- |
| 属性不存在 | Agent 根本没被加载（`-javaagent` 没生效） |
| `STARTING` | Agent 已加载，但目标类始终没被这个 JVM 加载（如 HMCL 3.16.x） |
| `PATCHED` | 目标方法已成功改写 |
| `TARGET_METHOD_MISSING` | 类存在但没有该名字的无参方法（HMCL 改了方法名） |
| `TARGET_SIGNATURE_MISMATCH` | 方法存在但签名不是 `public static boolean ()` |
| `FAILED` | 转换或安装抛异常，日志里有堆栈 |

若 JVM 退出时状态仍不是 `PATCHED`，Agent 会直接向 stderr 打一条
`[RegionPatch] ERROR: exiting without patching …`，不会静默失败。

---

## 5. 接入 HMCL

### 5.1 `HMCL_JAVA_OPTS` 是否被 EXE 启动器支持？

**支持。** 结论来自 [HMCLauncher-rs](https://github.com/HMCL-dev/HMCLauncher-rs) 的
`launcher.rs` / `java.rs`：启动器读取环境变量 `HMCL_JAVA_OPTS`，把它的内容**原样**拼接到
`java.exe` / `javaw.exe` 的命令行里、放在 `-jar HMCL.jar` 之前（Windows 负责解析其中的引号）。
在已发布的 `HMCL-3.16.4.exe` 二进制里也能直接搜到 `HMCL_JAVA_OPTS`、`HMCL_JAVA_HOME`、
`HMCL_LAUNCHER_VERBOSE_OUTPUT` 这些名字。

需要注意三个坑：

1. **`HMCL_JAVA_OPTS` 会替换启动器的默认参数**，而不是追加。启动器默认是
   `-Xmx1G -XX:MinHeapFreeRatio=5 -XX:MaxHeapFreeRatio=15`，设置该变量后这些默认值就没了。
   建议显式带上堆参数，例如 `-Xmx2G`。
2. **路径含空格必须加引号**：`-javaagent:"C:\Program Files\RegionPatch\RegionPatch.jar"`。
3. **双击 EXE 时用的是 `javaw.exe`，没有控制台**，Agent 的 stdout/stderr 看不到。
   验证方式见第 6 节（`jcmd` / dump 文件），不要靠“没看到报错”判断成功。

另外：

- `HMCL_JAVA_HOME` 用来指定 HMCL 使用的 JDK（不设置则用启动器自带的/系统默认的）。
  本 Agent 需要 **Java 17+**。
- 从已有控制台启动 `HMCL.exe` 时，HMCLauncher-rs 会检测到控制台并使用 `java.exe`，
  此时 Agent 日志可见（此点来自其源码，未在本机 EXE 上实测）。

### 5.2 临时设置（cmd，推荐先用这个）

`cmd` 的 `set` 只影响**当前窗口及其子进程**，关掉窗口就失效；`setx` 才会永久写入用户环境变量。

```bat
set "HMCL_JAVA_OPTS=-Xmx2G -javaagent:"C:\Tools\RegionPatch\RegionPatch.jar""
HMCL.exe
```

注意整行要用 `set "变量=值"` 的写法，这样值里的引号才会被原样保留
（路径含空格时必须这样写）。

### 5.3 一键启动脚本 `start-hmcl-with-patch.bat`

仓库根目录提供了脚本，双击即可，不需要改任何系统设置：

```bat
set "HMCL_EXE=%~dp0HMCL.exe"            rem HMCL.exe 路径
set "AGENT_JAR=%~dp0RegionPatch.jar"    rem Agent 路径
set "JAVA_HEAP=-Xmx2G"                  rem 堆参数（会替换启动器默认值）
set "DUMP_DIR="                         rem 可选：设为目录可 dump 改写后的 class
```

用法：

1. 把 `start-hmcl-with-patch.bat`、`build\libs\RegionPatch.jar` 与 `HMCL.exe` 放到同一目录；
2. 双击 `start-hmcl-with-patch.bat`；
3. 脚本会打印实际使用的 `HMCL_JAVA_OPTS`，然后启动 HMCL。

因为是从控制台启动，启动器会选用 `java.exe`，Agent 日志会直接显示在这个窗口里。
脚本里的 `set` 是进程级的，退出窗口后环境变量即失效。

### 5.4 永久设置（可选）

图形界面：`Win + R` → `sysdm.cpl` → 高级 → 环境变量 → 新建用户变量。

PowerShell（当前用户，永久）：

```powershell
setx HMCL_JAVA_OPTS '-Xmx2G -javaagent:"C:\Tools\RegionPatch\RegionPatch.jar"'
```

`setx` 之后需要重新登录（或重启资源管理器），双击启动的 EXE 才会读到新值。

用 JAR 直接启动（绕开 EXE，最容易看到日志）：

```powershell
java -Xmx2G -javaagent:"C:\Tools\RegionPatch\RegionPatch.jar" -jar HMCL.jar
```

---

## 6. 验证 Agent 是否真的生效

### 6.1 找到真正启动 HMCL 的 JVM

```powershell
jps -lvm | findstr /i hmcl
```

典型输出（`-v` 会列出 JVM 参数）：

```
12345 org.jackhuang.hmcl.MainLauncher -Xmx2G -javaagent:C:\Tools\RegionPatch\RegionPatch.jar ...
```

也可以：

```powershell
jcmd <pid> VM.command_line
```

> **不要和 Minecraft 游戏 JVM 搞混。** `HMCL_JAVA_OPTS` 只作用于 HMCL 启动器进程；
> 游戏进程是 HMCL 之后自己另起的 JVM，参数来自 HMCL 的“游戏设置 → Java 参数”，
> 不会继承 `HMCL_JAVA_OPTS`。`jps` 里同时出现两个 java 进程是正常的：带
> `org.jackhuang.hmcl.MainLauncher` 的那个才是本 Agent 的目标。

### 6.2 确认 Agent 被加载

上面 `jps -lvm` / `VM.command_line` 的输出里应出现
`-javaagent:...\RegionPatch.jar`。没有的话说明环境变量没被读到（常见原因：改完 `setx`
没有重启桌面、或者变量加到了系统变量但当前用户被覆盖、或路径引号写错导致参数被拆开）。

### 6.3 确认目标方法真的被改写（`javaw` 下也可用）

```powershell
jcmd <pid> VM.system_properties | findstr regionpatch
```

期望：

```
regionpatch.agent.loaded=true
regionpatch.agent.status=PATCHED
```

- 只看到 `loaded=true`、`status=STARTING` → Agent 起来了，但 HMCL 从没加载 `RegionUtils`
  （版本太老/类被改名），此时**没有生效**。
- 完全没有任何 `regionpatch` 行 → Agent 没加载。

### 6.4 字节码级证据

让 Agent 把改写后的类 dump 出来（可选，默认关闭）：

```powershell
$env:HMCL_JAVA_OPTS = '-Xmx2G -Dregionpatch.dumpDir=C:\Temp\regionpatch-dump -javaagent:"C:\Tools\RegionPatch\RegionPatch.jar"'
```

启动后目录里会出现 `org.jackhuang.hmcl.util.RegionUtils.class`：

```powershell
javap -p -c C:\Temp\regionpatch-dump\org.jackhuang.hmcl.util.RegionUtils.class
```

目标方法应为：

```
  public static boolean isIsChinaMainlandMachine();
    Code:
       0: iconst_0
       1: ireturn
```

### 6.5 行为验证

`isIsChinaMainlandMachine()` 返回 `false` 后，HMCL 里依赖它的逻辑（例如把“机器在中国大陆”
当作下载源/镜像选择的依据）会走非中国大陆分支。注意本补丁**不改字段**，所以
`isIsChinaMainlandNetwork()` 以及直接读 `IS_CHINA_MAINLAND_MACHINE` 的代码仍保持原行为。

---

## 7. 卸载 / 回滚

删除环境变量 `HMCL_JAVA_OPTS` 里的 `-javaagent:...` 片段（或删除该变量），重启 HMCL 即可。
Agent 不做任何持久化修改。

---

## 8. 已验证与未验证

**已验证（本机，JDK 17.0.16）**

- `.\gradlew.bat build` 成功，产物 `build/libs/RegionPatch.jar`，Manifest 正确。
- `smokeTest` 的 4 个独立 JVM 场景全部通过（含已加载类的 retransform、目标类缺失）。
- 对 `HMCL-nightly.jar` 3.17.0.362（class file major 61）实测：目标方法 `true → false`，
  字段仍为 `true`，方法/字段数量不变，dump 出的字节码为 `iconst_0; ireturn`。
- 对 `HMCL.jar` 3.16.4 实测：类不存在时 Agent 不崩溃，并在退出时明确报告未打补丁。
- 对 `HMCL-3.16.4.exe` 二进制确认其中包含 `HMCL_JAVA_OPTS` / `HMCL_JAVA_HOME` 字符串。

**未验证**

- 未在真实 HMCL 图形界面里完整跑一遍业务功能（只做了 JVM 级行为与字节码验证）。
- 未在本机用 `HMCL.exe` + `HMCL_JAVA_OPTS` 做端到端启动（结论来自 HMCLauncher-rs 源码与二进制字符串）。
- 未在 `javaw.exe`（双击 EXE、无控制台）场景下实测 `jcmd` 读取系统属性。
- 只在 Java 17 上运行过；Java 21/25 理论兼容（Byte Buddy 1.18.15 支持较新的 JVM），但未实测。
- Kotlin 标准库未 relocate（当前 HMCL 不含 Kotlin）。若未来 HMCL 自带 Kotlin，
  理论上存在类路径冲突的可能。

---

## 9. 免责声明

本项目仅用于个人学习与本地环境适配。修改 HMCL 的区域判断可能影响其下载源选择等行为，
请自行评估风险；HMCL 官方不对使用本补丁产生的任何问题负责。
