# 女仆管理终端（Maid Manager）

一个 **Minecraft 1.21.1 NeoForge** 的《车万女仆》（Touhou Little Maid）伴生模组。

按一个键呼出全屏面板，集中管理你的所有女仆：战斗中**一键召唤**，战斗结束后**一键收回**。

> 📌 **本分支是 1.21 版。** 1.20 / 1.20.1（Forge）请看 [`1.20` 分支](../../tree/1.20)。

## 支持版本

| | 版本 |
| --- | --- |
| **Minecraft** | **1.21.1** |
| 加载器 | **NeoForge 21.1.x** |
| 车万女仆 | 1.5.0+（**NeoForge** 版） |
| JDK（自行构建时） | 21 |

> ⚠️ **1.21 只有 NeoForge，没有 Forge。** Forge 从未发布 1.21 版本，车万女仆在 1.21.1 上
> 也**只提供 NeoForge 构建**（`touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar`）。
> 因此本分支的加载器是 NeoForge，与 1.20 分支的 Forge 版本**不通用**，jar 也**不能混装**。

> 本模组**不包含任何 TLM 的代码或美术素材**，仅通过其公开 API 交互。TLM 代码部分为 MIT，
> 素材部分为 CC BY-NC-SA 4.0，本项目只调用代码 API，因此不受素材条款约束。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 全屏管理面板 | `.` 呼出，列出你的全部女仆：名字、血量、维度、坐标、状态 |
| 一键召唤 / 收回 | 单个快捷键，**默认不绑键**，由你自己设置（见下） |
| 三态显示 | `在列` / `已收起` / `未加载`，各自可执行的操作不同 |
| 逐女仆强加载开关 | 每行独立开关，控制该女仆在区块未加载时能否被召唤（**默认关**） |
| 搜索 / 全选 / 反选 | 女仆多了也好找 |
| 多人安全 | 所有操作服务端权威校验，只能操作自己的女仆 |

按键可在「选项 → 控制 → 女仆管理终端」中自行修改。

---

## 快捷键

| 默认键 | 功能 |
| --- | --- |
| **`.`**（句号） | 打开女仆终端 |
| **未绑定** | 一键召唤 / 收回 |

### 关于那个「召唤 / 收回」键

它**默认是空的**，请自己去「选项 → 控制」里设置一个顺手的键（比如 `V`、`R`、鼠标侧键）。

因为它只有一个键却要做两件事，所以**按下时自动判断方向**：

| 勾选女仆的状态 | 按键效果 |
| --- | --- |
| 有「**已收起**」的 | **放出**她们（从数据还原到身边） |
| 全都在世界里 | **收回**她们（收成数据） |
| 都在身边、没什么可做 | 提示「已在身边，无需操作」 |

> 实际用起来就是：战斗中按一下把人拉过来，战后按一下把她们收起来，不需要切模式。

### ⚠️ 只对「勾选的」女仆生效

这个键**只作用于你在终端里勾选的女仆**：

| 勾选情况 | 按键效果 |
| --- | --- |
| 勾了若干 | 只操作这些 |
| **一个都没勾** | **什么都不做**，屏幕提示「请先勾选女仆」 |

这是刻意的设计：避免你只想叫 2 个女仆，结果手一抖把全家都拉过来。

**勾选状态会保留**——关掉面板、甚至打死怪再开，勾选都还在。所以战斗中你先勾一次，之后就能一直盲按快捷键。用 `Ctrl+A` 全选、或者面板右上角的「全选 / 反选 / 清空」来快速改选。



---

## 收藏（星标）与置顶

每行**最左边有个星标**，点一下即可收藏。收藏的女仆**永远排在最上面**——凌驾于状态、时间、名字之上。

| 操作 | 效果 |
| --- | --- |
| 点击星标 | 收藏 / 取消收藏 |
| 点「★ 只看收藏」 | 只显示收藏的女仆（再点一次恢复显示全部） |

星标显示为 `*`（金色）表示已收藏，`-`（暗色）表示未收藏。

收藏状态**按玩家保存**，重进世界、重启服务器都还在。

> 典型用法：把你最常带出门的几只星标起来，平时点「只看收藏」，列表就只剩这几只，
> 战斗中勾选和召唤都不会误伤到别的女仆。

---

## 列表是怎么排序的

排序结果**完全确定**（刷新多少次顺序都一样）：

1. **收藏优先**：星标的女仆永远在最上面（含未加载、已收起的）
2. **再按状态**：`在列` → `已收起` → `未加载`
3. **同状态内**：最近收起的排前面 → 再按名字（忽略大小写）→ 最后按 UUID

最后一步按 UUID 是必要的：**女仆可以重名**（比如两只都叫「雾雨魔理沙」）。少了这一步，
同名女仆的顺序会在每次刷新时变动，你刚勾选的那一行可能下次就跑到别处了。

为了让你能区分重名女仆，已收起的行会显示**收起时间**和**短 ID**：

```
雾雨魔理沙   已收起 3m 前 · #a1b2
雾雨魔理沙   已收起 2h 前 · #7f3c
```

---

## 三种状态的含义

理解这三个状态是用好本模组的关键：

| 状态 | 含义 | 能召唤吗 | 能收回吗 |
| --- | --- | --- | --- |
| **在列** | 女仆实体已经加载，就在世界里 | ✅ 直接传送 | ✅ |
| **已收起** | 女仆已被收成存档数据，实体不存在 | ✅ 从数据直接放出 | ❌（本来就是收起态） |
| **未加载** | 女仆在 TLM 的世界记录里，但她的区块没加载（走远了，或**在别的维度**） | ⚠️ **取决于该女仆的强加载开关** | ❌ |

---

## 「强加载」开关（重要）

### 为什么需要它

TLM 自己有一个机制：**区块卸载前**，如果女仆是同维度、且没开「家模式」、且能移动，
就会把她**传送回主人身边**。

但它的判定里有硬性的一条：**要求主人和女仆在同一维度**。所以：

- 你**走路走远** → 女仆会被自动带回你身边 ✅
- 你**地图传送 / 下界传送门 / 跨维度指令** → 女仆**留在原维度**，变成「未加载」状态 ❌

这正是需要「强加载」的场景。

### 开关做什么

| 开关 | 行为 |
| --- | --- |
| **开** | 该女仆所在区块**持续保持加载**（即使你走远、甚至去了别的维度），因此随时可被一键召唤 |
| **关**（默认） | 保持 TLM 原生行为，本模组完全不介入 |

开着的时候她会一直待在那儿活动、干活，代价是**该区块会持续占用服务器资源**——所以别给太多女仆开。

### 实现方式（为什么是安全的）

用的是 NeoForge 的**区块票据（Ticket）**，**不是**原版的 `setChunkForced`：

| | `setChunkForced`（不用） | NeoForge 区块票据（在用） |
| --- | --- | --- |
| 存储 | 写进存档 `forcedchunks.dat` | 运行时票据，不写存档 |
| 归属 | 无归属，谁都清不掉 | **按女仆 UUID + 控制器 ID** 记账 |
| 女仆死亡/删除 | 区块**永久残留在加载** | 票据随实体自动失效，**不会泄漏** |
| 关闭开关 | 无效 | 立即释放 |

> 🔧 **1.21 的实现变化**：1.20 分支用的是 Forge 的 `ForgeChunkManager.forceChunk`。
> 1.21 的 NeoForge 删掉了整个 `ForgeChunkManager`，替代品是
> `net.neoforged.neoforge.common.world.chunk.TicketController`——方法签名几乎一样，
> 但它必须先在 `RegisterTicketControllersEvent` 里注册才能用（本模组已在模组主类里注册）。
> 注意这不是原版的 `setChunkForced`，两者在存档写入和归属记账上完全不同。

另外还有一层保险：**每 20 tick 重新确认一次**，女仆走到新区块会自动改跟；服务器重启后会按开关状态**自动重新加载**，不会因为重启就失效。

### 跨维度召唤

顺带说明：如果你在**别的维度**，跨维度召唤走的是「存 NBT → 目标维度重建」这条路，**不需要加载区块**。
因为 TLM 的女仆记录表是整个存档唯一一份（存在主世界），跨维度查询天然可用。

### 首次开启的提示

第一次给某个女仆打开强加载时，会弹一个说明窗口，讲清楚它会临时加载区块、可能增加服务器负担，
建议只给常用的女仆开启。确认后不再重复提示。

**本模组不设数量上限**——由你自己权衡。

服主可以在配置文件里把 `allowForceLoad` 设为 `false`，彻底禁用所有区块加载行为
（此时开关会显示但不可点击）。

---

## 安装

1. 安装 **Minecraft 1.21.1**，以及 **NeoForge 21.1.x**
2. 安装 **车万女仆（Touhou Little Maid）1.5.0+** 的 **NeoForge** 版（`touhou_little_maid`）
3. 把 `maid_manager-1.0.0-neoforge+mc1.21.1.jar` 放进 `mods/`

三者缺一不可：本模组依赖车万女仆，没装会在启动时报缺失依赖。

> ⚠️ 第 2 步**必须**下 NeoForge 版（文件名带 `neoforge`）。下成 Forge 版（带 `forge`）
> 是 1.20.1 用的，在 1.21.1 上装不上，游戏也起不来。

---

## 配置

`config/maid_manager-common.toml`

| 项 | 默认 | 说明 |
| --- | --- | --- |
| `maxSummonPerAction` | `12` | 单次操作最多处理多少只女仆 |
| `summonIntervalTicks` | `2` | 批量处理时每只之间的间隔（tick） |
| `allowForceLoad` | `true` | 是否允许强加载（服主可关掉以禁止一切区块加载） |
| `forceLoadTimeoutTicks` | `100` | 召唤时等待区块加载的最长时间，超时则放弃并提示 |

---

## 从源码构建

需要 **JDK 21**（1.21 起 Minecraft 要求 Java 21；1.20 分支用的是 17）。

```bash
# Windows
gradlew.bat build

# 开发调试（会启动带 TLM 的客户端）
gradlew.bat runClient
```

产物：`build/libs/maid_manager-1.0.0-neoforge+mc1.21.1.jar`

文件名格式为 `<模组id>-<版本>-<加载器>+mc<MC版本>`，与车万女仆官方的
`touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar` 保持一致的风格。

> 本模组在 1.21 上是 **NeoForge 专用**的（用到了 NeoForge 的 `PayloadRegistrar`、
> `TicketController`、`ModConfigSpec` 等 API）。**Forge 没有 1.21 版本**，
> 所以文件名里的 `neoforge` 是准确的。
> 若日后移植到别的加载器，把 `gradle.properties` 里的 `mod_loader` 改掉即可，
> 文件名会自动跟着变。

### ⚠️ 构建前必须先放入 TLM 的 jar

TLM **没有**发布到任何公开 Maven 仓库，本项目用 `flatDir` 从 `libs/` 读取它。
**这个 jar 不在本仓库里**（它属于 TLM 作者，23 MB，不应被转载），所以克隆后需要自己放一份：

1. 从 [CurseForge](https://www.curseforge.com/minecraft/mc-mods/touhou-little-maid) 或
   [Modrinth](https://modrinth.com/mod/touhou-little-maid) 下载 **TLM 1.21.1 NeoForge** 版
2. **原样**放进 `libs/`，文件名不要改。`flatDir` 是按 `名字-版本.jar` 查找的，
   带额外的前缀会导致找不到

即：

```
libs/touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar
```

> ⚠️ 注意：`gradle.properties` 里坐标写的是 `tlm:touhoulittlemaid:...`，但 `flatDir`
> **会忽略 group 部分**，实际只按文件名 `touhoulittlemaid-<版本>.jar` 查找。
> 所以文件名必须是 `touhoulittlemaid-` 开头，写成 `tlm-touhoulittlemaid-...` 会构建失败。

换 TLM 版本时，改文件名并同步修改 `build.gradle` 里的 `compileOnly` / `runtimeOnly` 坐标。

这个 jar **不会**被打包进产物（用的是 `compileOnly` + `runtimeOnly`）。

> 💡 1.20 和 1.21 两个分支的 `libs/` 可以各放一份 TLM，互不干扰。

### 可选：代理与 JDK 路径

这两项**默认全部关闭**，克隆下来直接 `gradlew build` 即可，无需任何配置。

**代理**——只有你的网络无法直连 `maven.neoforged.net` 时才需要。典型症状是构建报
`Connection reset` 或 `SSL peer shut down incorrectly`：

```bash
# 环境变量（推荐，不进版本库）
set MAID_MANAGER_PROXY=127.0.0.1:7897        # Windows
export MAID_MANAGER_PROXY=127.0.0.1:7897     # Linux/macOS

# 或命令行临时覆盖
gradlew.bat build -Pproxy=127.0.0.1:7897
```

也可以写进 `gradle.properties` 的 `proxy=`（自带注释，取消注释即可），但那样会把你的
代理地址提交到仓库，**不建议**。

> 为什么不能简单地设一个全局代理？因为实测这里 **Mojang 的 CDN 反而是直连更稳**，
> 走代理会握手失败。所以脚本会自动把 Mojang / Gradle / Maven Central / Parchment
> 加进 `nonProxyHosts`，**只有 NeoForged 走代理**。格式写错会明确报错而不是静默忽略。

**JDK 路径**——通常不用填，Gradle 会自己找（`JAVA_HOME`、系统、或自动下载）。
只有自动探测挑错了 JDK 时才需要：

```bash
set MAID_MANAGER_JDK_PATHS=/path/to/jdk-21
gradlew.bat build -Pjdk_paths=/path/to/jdk-21
```

---

## 调试命令

```
/maidmanager list
```

在服务端打印当前玩家的全部女仆及其状态，便于排查（不需要开客户端界面）。

---

## 已知限制

- 只管理**自己的**女仆（`isOwnedBy` 校验），不能操作他人的。
- 女仆**死亡留下的墓碑**不在此面板管理，请用 TLM 自己的方式处理。
- 跨维度召唤会让女仆**离开她原本的工作点**。如果你希望她长期驻守某地，请不要对她开启强加载，
  或召唤后用 TLM 的原生方式重新安排她的家/工作点。
- 若 TLM 未来大改内部 API，本模组会在启动时自检并**降级为「只显示在线女仆」**，而不是崩溃。

---

## 从 1.20 移植到 1.21 改了什么

功能、界面、交互**完全一致**，改的都是 1.21 平台层的 API。记录在此便于日后升级参考：

| 方面 | 1.20 / Forge | 1.21.1 / NeoForge |
| --- | --- | --- |
| 元数据文件 | `META-INF/mods.toml` | **`META-INF/neoforge.mods.toml`** |
| 依赖声明字段 | `mandatory = true` | 仍可用，但 NeoForge 推荐 `type = "required"` |
| 事件总线 | `MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS` |
| 网络 | `SimpleChannel` + 数字 id + `NetworkEvent.Context` | **`CustomPacketPayload` + `PayloadRegistrar`**，用 `ResourceLocation` 当 id |
| 发消息 | `PacketDistributor.PLAYER.with(() -> p)` | `PacketDistributor.sendToPlayer(p, msg)` |
| 配置 | `ForgeConfigSpec`（返回 commons-lang `Pair`） | `ModConfigSpec`（**仍然**返回 commons-lang `Pair`） |
| 区块加载 | `ForgeChunkManager.forceChunk(...)` | `TicketController.forceChunk(...)`，**需先注册控制器** |
| `SavedData` | `load(tag)` / `save(tag)` | 加 `HolderLookup.Provider` 参数；`computeIfAbsent` 改收 `SavedData.Factory` |
| 按键事件 | `InputEvent.Key` | `ClientTickEvent.Post` + `consumeClick()` |
| 滚动事件 | `mouseScrolled(x, y, delta)` | `mouseScrolled(x, y, scrollX, scrollY)` |
| 背景渲染 | `Screen.renderBackground(GuiGraphics)` | 只能由 `super.render()` 内部触发 |
| 组件序列化 | `buf.writeComponent(c)` | `ComponentSerialization.STREAM_CODEC.encode(buf, c)` |
| `ItemStack` NBT | `stack.getTag()` | `stack.get(DataComponents.CUSTOM_DATA)` |
| 资源包版本 | `pack_format: 15` | `pack_format: 34` |
| 构建插件 | ForgeGradle 6 + Parchment | **NeoGradle 7** + Parchment |
| Gradle / JDK | 8.8 / 17 | **8.13 / 21** |

> 逻辑层（`MaidManagerService`、`MaidEntry`、`MaidRegistry`、`MaidStorage`）与客户端层
> 几乎原样复用，改动集中在框架对接处。

> ⚠️ 构建注意：若所在网络无法直连 `maven.neoforged.net`，需要按上文「可选：代理与 JDK 路径」
> 配置代理，否则会报 `SSL peer shut down incorrectly` 之类的握手错误。
> 代理是**可选且默认关闭**的，脚本里没有任何硬编码的机器相关配置。

---

## 许可

MIT。
