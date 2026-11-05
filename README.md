# 星穹旅驿 · 新手引导插件（StarInn）

> English documentation: [README_EN.md](README_EN.md)

单插件融合版新手引导：Java 玩家（BossBar 逐字台词）与基岩玩家（Geyser 接入，ActionBar +
表单/聊天选项）行为一致；进度 SQLite 持久化；资源包**彩色立绘固化为真实 PNG 打进 jar**，
运行时只做「释放 -> 覆盖 -> 打包 -> 回填 SHA-256」四步，由**内置 HTTP 服务**下发
（端口完全可配，代码零硬编码端口）。

- 服务端：**Purpur 1.21.11**（Paper/Spigot 同版本亦可运行）
- Java：**21（必须）**
- 前置：**无**。不依赖 LuckPerms / PlaceholderAPI / ProtocolLib / Adventure / Vault；
  Geyser 只是可选探测组件，未安装也能完整运行（全部按 Java 端渲染）。
  **Velocity 侧不装任何组件。**
- 网络：**零对外请求**。内置 HTTP 服务只“被动发放本地文件”，不向任何外部地址发起下载。
- 架构：唯一主类 `com.starinn.StarInn`，内部嵌套类组织
  （Config / Database / Dialogue / Node / Script / Session / Renderer / BossBarRenderer /
  ActionBarRenderer / PackBuilder / PackServer / SelfCheck），一个 jar 搞定全部功能。
- 健壮性：启动逐项自检并自动降级——任何一项失败只关闭该功能，
  **绝不影响服务器其他部分、绝不导致插件启动失败、绝不卡住玩家**。

---

## 一、安装

1. 把 `StarInn-1.0.0.jar` 放进 `plugins/`，重启服务器；
2. 首次启动自动生成 `plugins/StarInn/`：`config.yml`、`dialogues/`（三个对话文件）、
   `textures/`（空目录，专放你的正式立绘）、`StarInnResourcePack.zip`、`guides.db`；
3. 日志会打印 `[星穹旅驿] 资源包下发地址：http://xxx:端口/StarInnResourcePack.zip`
   与 `[星穹旅驿] 自检完成：正常 X 项 / 降级 Y 项`；
4. 在防火墙/安全组放行你配置的端口；游戏内 `/starinn reload`，进服即可看到引导。

分发包（本仓库 `dist/`）里额外提供：

| 文件 | 用途 |
|---|---|
| `StarInn-1.0.0.jar` | 插件本体，放 `plugins/` |
| `resource-pack/StarInnResourcePack.zip` | Java 资源包预成品（与插件运行时生成的同构，pack_format 75，支持 1.21.9~1.21.11） |
| `bedrock/StarInnResourcePack.mcpack` | 基岩版资源包（带 manifest，可直接双击导入；Geyser 玩家也可由服务器自动转换下发） |
| `textures/*.png` | 已固化进 jar 的立绘/背景源图（全部 2 的幂、标准 PNG） |

## 二、资源包与端口说明（重点）

### 2.1 彩色立绘已固化进 jar，运行时不再现画

立绘 PNG 存放在工程 `src/main/resources/resourcepack/`，编译后打进 jar：

```
resourcepack/
  pack.mcmeta                                   pack_format 75（1.21.11），支持 70~75
  pack.png                                      64x64 包图标
  assets/starinn/textures/gui/dialogue_bg.png   512x256 对话框底图
  assets/starinn/textures/gui/wu|chen|an.png    64x64 三张彩色立绘
  assets/starinn/textures/gui/bg_*.png          64x64 三张场景背景
```

`PackBuilder` 只做四步：① 从 jar 释放 `resourcepack/` 到临时目录；
② `plugins/StarInn/textures/` 下有服主同名 PNG 则覆盖；③ 打包为
`plugins/StarInn/StarInnResourcePack.zip`；④ 计算 SHA-256 并原位回填
`config.yml` 的 `resource-pack.sha256`（中文注释全保留）。
后续启动：zip 存在且 hash 与配置一致 -> 跳过生成；不一致 -> 自动重建。

**服主日后换正式立绘：把同名 PNG 丢进 `plugins/StarInn/textures/`，执行
`/starinn pack regen` 即可，不用重新编译插件。**
贴图必须是标准 PNG、宽高 2 的幂（64/128/256/512…），不合规会被日志提示并忽略
（保基岩端 Geyser 转码）；资源包总大小控制在 5MB 以内；包内不含字体目录 /
shader / optifine 目录。资源包生成失败只打日志并继续启动，引导照常（仅无立绘）。

### 2.2 端口完全可配，代码零硬编码

Java 源码中不存在任何端口数字字面量；下发 URL 全部由配置拼装：

```
http://<public-host>:<builtin-port><serve-path>
```

`config.yml` 四项严格生效：

| 配置项 | 说明 |
|---|---|
| `resource-pack.builtin-port` | 内置 HTTP 服务监听端口。**服主端口申请下来后改这里**，并在防火墙/安全组放行 |
| `resource-pack.builtin-host` | 监听地址，默认 `0.0.0.0`（所有网卡） |
| `resource-pack.serve-path` | 下载路径，默认 `/StarInnResourcePack.zip` |
| `resource-pack.public-host` | 对外 IP 或域名，**留空自动取服务器 IP**；**基岩玩家需能访问到此地址** |

- 改端口后 `/starinn reload` 即时生效（自动关停旧监听、按新配置重开）；
- 端口被占用：捕获 `BindException`，日志提示
  「端口 X 被占用，请在 config.yml 修改 resource-pack.builtin-port」，
  插件继续启动、引导正常，仅资源包下发关闭；
- 插件 `onDisable` 必定关闭 HttpServer 释放端口；
- 启动日志打印完整下发地址（见安装第 3 步）。

## 三、启动自检与自动降级

启动时逐项自检并在日志输出汇总 `[星穹旅驿] 自检完成：正常 X 项 / 降级 Y 项`，
降级项逐条打印原因。六项自检与对应降级行为：

| 自检项 | 失败时 |
|---|---|
| 配置文件（config.yml） | 用内置默认值兜底，日志打印 SnakeYAML 具体行列号 |
| 对话台词（22 条齐全） | 跳过缺失节点，其余引导照常（选项指向缺失节点时体面收尾，不卡人） |
| 数据库持久化 | 降级为内存进度（不持久化），日志告警 |
| 资源包生成 | 关闭资源包下发，引导照常（仅无立绘） |
| HTTP 端口下发 | 端口无效/被占用 -> 关闭下发，引导照常 |
| Geyser 基岩桥接 | 未检测到/接口不符 -> 全按 Java 端渲染，正常运作 |

任何一项失败**只关闭该功能**，绝不导致插件启动失败，绝不影响服务器其他部分。

## 四、命令

| 命令 | 权限 | 功能 |
|---|---|---|
| `/starinn reload` | `starinn.reload` | 热加载配置与对话，不踢人；端口变化自动重启内置服务并打印新地址；进行中的会话重绑新脚本 |
| `/starinn reset <玩家>` | `starinn.reset` | 清除该玩家进度，下次进服重新引导 |
| `/starinn status` | `starinn.reload` | 插件状态：监听端口、public-host、实际下发地址、sha256、数据库、会话统计、台词计数 |
| `/starinn pack regen` | `starinn.reload` | 强制删除旧 zip -> 重新组装 -> 重算并回填 sha256 -> 按当前配置重启 HTTP 服务，执行后提示新地址（全程异步，不掉 TPS） |

## 五、双端行为（都不会卡住玩家）

- **Java 端**：`Bukkit.createBossBar()` 逐字显示台词（`char-interval` tick/字），
  颜色按 NPC：芜=白、晨=金、黯=紫；ActionBar 提示 `[左键地面继续] [Shift 跳过]`，
  播完变 `[空格继续]`（点击与跳跃均可推进）。选项节点机制保留（左键=选项1、右键=选项2、按 F=选项3），
  当前主线已无选项节点——阵营选择移交独立阵营插件，S5_4 播完点继续即结束引导。
  **随时按 Shift 跳过**。
- **基岩端（Geyser）**：**绝不用 BossBar 显示文字**（基岩 BossBar 只有血条）；
  台词整条走 ActionBar。选项优先 Geyser `SimpleForm` 按钮组（全反射调用，
  任何版本差异自动降级），降级为 ActionBar 提示 + 聊天输入 `1/2/3`；
  **跳过 = 表单按钮「跳过引导」或聊天输入 `skip`**。
- 掉线重连从库里的 `current_node` 继续；停在选项节点则重显该选项。
- 无操作超时：先由芜追问一句；再超时自动关闭引导并标记“已跳过”，绝不永久停留。
- 引导结束/跳过后不再触发任何 UI，玩家自由活动。
- 资源包下载/转码失败（含 `FAILED_DOWNLOAD`）只记日志并放行，**绝不因资源包踢玩家**。
- 所有耗时操作（数据库、打包、HTTP、表单）都在异步线程，不掉 TPS。
- 中文全链路 UTF-8（文件读取显式 `StandardCharsets.UTF_8`），防基岩端乱码。

## 六、常见问题

- **Geyser 未安装怎么办？** 不需要装。插件只反射探测 Geyser，探测失败一律按 Java 端渲染。
- **基岩玩家下载不到资源包？** `/starinn status` 看下发地址是否公网可达（NAT 后请填
  `public-host`）；防火墙/安全组放行 `builtin-port`；失败不会踢人，只没有立绘。
- **端口被占用怎么办？** 日志提示后改 `builtin-port` -> `/starinn reload`；插件不崩，仅下发关闭。
- **数据库被锁？** 异步重试 3 次，仍失败自动降级内存进度并告警，引导不中断。
- **想换立绘？** 同名 PNG 丢进 `plugins/StarInn/textures/`（2 的幂尺寸），`/starinn pack regen`。
- **台词文件被改坏？** 日志打印文件与行列号，缺的台词节点自动跳过，其余照常。

## 七、内容与验收红线

- 22 条台词（wu 14 + chen 4 + an 4）与设定原文逐字一致；阵营选择已移交独立阵营插件
  （玩家进服 10 分钟后由该插件弹窗选择）；S5_4 无选项，播完即标记 COMPLETED 结束；
  S6_1/S6_2/S7_1/S7_2 四条落地台词已删除，引导不再跳转 S6/S7；
- 代码中零硬编码端口；零第三方插件依赖；零对外网络请求；
- Purpur 1.21.11 + Java 21 编译通过（见 `build.gradle`：仅 `compileOnly` 的
  Purpur API 与 SQLite 驱动，运行期由服务端提供）。

## 八、开发构建

```bash
gradle build               # 产物 build/libs/StarInn-1.0.0.jar（立绘 PNG 已随 resourcepack/ 打进 jar）
python3 packs/build_packs.py   # 校验贴图合规 + 重打 dist/ 双端资源包与分发包
```

自测清单与本轮自检报告见 `TESTING.md`。
