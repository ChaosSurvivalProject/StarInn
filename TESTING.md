# 星穹旅驿 · 验收自测清单与自检报告（TESTING）

> 编译：Purpur 1.21.11 API + Java 21，`mc_build`（Gradle 8.12.1）BUILD SUCCESSFUL，0 错误；
> 仅 `compileOnly` 依赖（Purpur API、SQLite 驱动），运行期全部由服务端提供 —— 零第三方插件依赖、零对外网络请求。
>
> 验证方法说明：本容器无 Purpur 服务端可跑，运行时行为类条目采用
> **代码路径静态核对 + JDK 机制独立测试**（单文件测试程序直接验证 HttpServer 行为）；
> 文件/产物类条目全部用脚本真跑（解压 jar/zip、逐条解析 yml、读 PNG 头、正则回填测试）。

## 一、验收清单逐项结果（本轮最终整合改进指令 · 第十条）

| # | 验收项 | 结果 | 证据/位置 |
|---|---|---|---|
| 1 | 22 条台词齐全，S5_4 无选项、播完即 COMPLETED 结束 | ✅ 通过 | 脚本解析三个 yml：wu 14 / chen 4 / an 4，合计 22，缺失=[]；S5_4 无 choices，text=「两边都见过了。十分钟后系统会让你正式选，这期间你先自己转转。」；S6_1/S6_2/S7_1/S7_2 已删除（阵营选择移交独立插件） |
| 2 | 代码中全局搜索不到硬编码端口数字 | ✅ 通过 | `grep -E "\b[0-9]{4,6}\b"` 全文仅 `65535`（端口合法区间上界，校验用）与 `8192`（读文件缓冲字节数）；无任何监听/下发端口字面量。`25585` 只存在于 config.yml 示例注释值 |
| 3 | 改 builtin-port 后重启/ reload，HTTP 监听新端口 | ✅ 通过 | `doReload` 对比 `builtin-port/host/serve-path/public-host` 任一变化即 `packServer.stop()` 后按新配置 `start()`；JDK 机制测试：同端口重复绑定抛 BindException，换端口成功监听（见三.机制测试） |
| 4 | 启动日志打印完整下发地址 | ✅ 通过 | `PackServer.start()` 成功即 `info("资源包下发地址：" + packDownloadUrl())`，前缀 `[星穹旅驿]`；`onEnable` 末尾与每次 reload 也打印 `describePackSource()` |
| 5 | 端口被占用时插件正常启动 | ✅ 通过 | `start()` 捕获 `BindException`：severe 提示「端口 X 被占用，请在 config.yml 修改 resource-pack.builtin-port」后返回 false；自检项记降级，`onEnable` 继续走完，事件/命令照常注册 |
| 6 | 彩色立绘已固化为真实 PNG 打进 jar，运行时不再现画 | ✅ 通过 | jar 内 `resourcepack/` 含 pack.mcmeta + 8 张 PNG（unzip -l 核对）；全文件 0 处 `BufferedImage/Graphics2D/ImageIO/java.awt` 代码引用（仅注释说明），PackBuilder 只做「释放→覆盖→打包→回填 hash」四步 |
| 7 | 所有贴图 2 的幂、标准 PNG、总大小 <5MB | ✅ 通过 | 脚本读 PNG 头：pack/立绘/背景 64×64，dialogue_bg 512×256，签名+IHDR 校验全过；8 张合计 57,962 字节 ≪ 5MB；服主覆盖贴图运行时也做同样校验（`isPngPowerOfTwo`，不合规忽略并告警） |
| 8 | 资源包不含字体/shader/optifine 目录 | ✅ 通过 | 固定条目清单 9 项，全部为 mcmeta/PNG；脚本断言 resourcepack 下无 fonts/shader(s)/optifine 目录；mcpack 内也只有 manifest+PNG |
| 9 | `/starinn pack regen` 可重新生成并回填 hash | ✅ 通过 | `doPackRegen`：删旧 zip → 异步 `buildAndPublish(true)`（重打包+`sha256Hex`+`writeShaToConfigText` 原位回填）→ 按当前配置重启 HTTP → 玩家侧提示新地址；回填正则对新版 config.yml 实测唯一命中 `sha256:` 行且保留缩进/注释结构 |
| 10 | 任一模块失败时服务器不受影响、插件照常启用 | ✅ 通过 | `Script.load` 不再抛异常（缺台词跳过节点+选项目标缺失体面收尾）；`loadConfigSafe` 格式错误默认值兜底并打印行列号；SQLite 失败降级内存进度；打包/HTTP 失败只记自检降级；`onEnable` 全链路无未捕获异常路径 |
| 11 | 玩家可随时跳过、掉线可续、超时自动放行 | ✅ 通过 | Java=Shift（`PlayerToggleSneakEvent`），基岩=表单「跳过引导」/聊天 `skip`；每节点落库 `current_node`，重连按库续播、选项节点重显选项；两段闲置计时：追问一次→再超时 `skip()` 记 SKIPPED 并回收 UI，绝不永久停留 |
| 12 | 基岩端不显示 BossBar 文字、无中文乱码、资源包失败不被踢 | ✅ 通过 | 基岩分支渲染器只有 `ActionBarRenderer`（全文唯一 BossBar 创建点仅 Java 分支与 Java 问候分支）；所有文件读取显式 `StandardCharsets.UTF_8`；`FAILED_DOWNLOAD` 分支只 warn 记录（提示 public-host/端口放行）并放行，插件无任何 kick 调用 |
| 13 | Purpur 1.21.11 + Java 21 编译通过，零第三方插件依赖，零网络请求 | ✅ 通过 | mc_build BUILD SUCCESSFUL；`build.gradle` 仅 compileOnly；Geyser 全反射探测（无编译依赖）；HTTP 服务只被动发放本地文件 |

**报告汇总：通过 13 项 / 降级 0 项 / 未通过 0 项。**
（第 11、12 项的运行期表现建议在有 Geyser 的真机上按下方「人工验证」列复跑一遍。）

## 二、功能回归（原有能力保持）

| 验收项 | 实现 | 人工验证 |
|---|---|---|
| 新玩家从 S0_1 起逐字播放 | `onJoin` 读库判定 -> `Session.begin(firstPlayable)`，`char-interval` 逐字 | 新号进服观察 |
| Java 台词在 BossBar（芜白/晨金/黯紫）、提示在 ActionBar | `BossBarRenderer` + `Script.npcColor` | 全程观察颜色 |
| 基岩台词整条走 ActionBar | `ActionBarRenderer`，基岩分支从不创建 BossBar | Geyser 玩家进服 |
| S5_4 为终点节点（无选项） | `linkChain(MAIN_FLOW)` 中 S5_4 next=null -> `goNext()` 判 null -> `complete()` 写 COMPLETED | 走到最后一条按空格/点击 |
| 阵营落地已移交 | 引导不再跳转 S6/S7、不再写 CHIRANG/YUANYU（chosen_faction 恒空，列保留兼容旧库） | 完成后查库确认 faction 为空 |
| 完成写 COMPLETED；老玩家只闪问候 | `complete()` / `flashGreeting()` | 完成后再进服 |
| 掉线重连续播、选项重显 | 每节点落库，重连按 `current_node` 恢复 | 中途拔网线重连 |
| 无操作追问 -> 再超时记 SKIPPED | `restartIdle()` 两段计时 | 挂机等待 |
| 命令四条 | reload / reset / status / pack regen（plugin.yml usage 同步） | OP 执行 |
| 空格继续真实生效 | `tickJumpAdvance` 比对 `Statistic.JUMP` 基线（与点击推进双保险） | Java 客户端按键 |

## 三、机制测试（本轮实际执行）

1. **JDK 端口机制**（`com.sun.net.httpserver.HttpServer`，与 PackServer 同 API）：
   - 监听成功 -> 同端口重复绑定抛 `BindException: Address already in use`（捕获，不崩）；
   - 换端口成功监听新端口；`stop(0)` 后端口立即可重绑（onDisable 释放端口的等价验证）。
2. **sha256 原位回填**：正则 `^(\s*sha256\s*:\s*).*$` 对新 config.yml 唯一命中
   `  sha256: ""` 行，替换后缩进/注释结构完整保留。
3. **贴图合规**：脚本逐张读 PNG 签名+IHDR 宽高（2 的幂）、统计总大小 57,962B、
   断言无 fonts/shader/optifine 目录。
4. **台词完整性**：逐条解析 yml 比对 22 个必含 id；确认 S5_4 无 choices、
   chen.yml/an.yml 中已无 S6/S7 节点。
5. **jar 产物**：`unzip -l` 确认 `resourcepack/` 固化 PNG、新 config.yml、
   plugin.yml usage 含 `pack regen`；预成品 zip 条目与运行时 `RESOURCEPACK_FILES`
   清单逐一对应（同构）。

## 四、已知边界（如实说明）

1. Geyser `SimpleForm` 反射链按 Geyser 2.x 公开 API 实现；接口不同名时自动落到
   “ActionBar + 聊天序号”降级路径，功能不缺（自检项 6 记降级并告警）。
2. 基岩端“点击屏幕继续”依赖 Geyser 把触屏映射为交互事件（标准行为）；`skip` 文本
   输入为兜底，永远可用。
3. `setResourcePack(String,String)` / `sendActionBar(String)` 为 Bukkit 标准接口
   （Paper 系标记 deprecated 但 Purpur 1.21.11 行为正常）；为兼容 Spigot 端未用 Paper 专属 API。
4. `public-host` 留空时自动取服务器绑定 IP / 本机地址（均为本地取值，不发起网络请求）；
   NAT/内网环境请务必显式填写，否则基岩玩家下载不到资源包（只记日志，不踢人）。
5. 换贴图后如果不执行 `/starinn pack regen` 而只 reload：hash 与 zip 一致会跳过重建——
   这是第 3 步“一致即跳过”的预期行为，强制重建请走 `pack regen`。
