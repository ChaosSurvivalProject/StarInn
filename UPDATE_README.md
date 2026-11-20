# StarInn 内置自动更新模块 · 改动说明

> 依据 `ChaosSurvivalProject/update-gate` 的 `mc-autoupdate` skill（contract.md / paper.md）实现。

## 一、做了什么

为 StarInn（Paper 服务端插件）内置自动更新模块：开机自检 + 每周四 12:00（Asia/Shanghai）定时检查，GitHub 固定 manifest 直连（仅 HTTPS、无 Token），语义版本门禁，sha256 校验，重启生效（不热加载），并提供独立命令管理。

## 二、改动文件清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `src/main/java/**/update/`（6 个类 + Json 解析器，共 7 个文件） | 新增 | UpdateConfig / VersionGate / UpdateDownloader / UpdateScheduler / Json / UpdateManager / UpdateCommand |
| 主类 | 修改 | 加 import、onEnable 注入初始化（try-catch 隔离，失败不影响主功能）；**原有命令注册逻辑保持不动** |
| `plugin.yml` | 修改 | 新增 `sinnupdate` 命令与对应 update 权限 |
| `updates/latest.json` | 新增 | 更新清单（stable 通道，指向当前最新 Release，含真实 sha256/直链） |
| `gradle.properties` | 修改 | 版本号 1.0.0 → 1.0.1（patch +1） |

## 三、工作机制

1. **触发**：onEnable 自检一次；后台任务每周四 12:00 检查一次。
2. **来源**：`https://raw.githubusercontent.com/ChaosSurvivalProject/StarInn/main/updates/latest.json`，仅 HTTPS、无 Token。
3. **门禁**：目标==当前→UP_TO_DATE；当前过低→BLOCKED_UPGRADE_REQUIRED；目标更高且 API 兼容→UPDATE_*；目标 API 高于服务器→只提示升级服务端不下载；目标更低→禁止降级。
4. **下载**：仅门禁允许时下载到 `plugins/update/`，sha256 校验 + 原子替换，停服替换后重启生效。
5. **命令**：`/sinnupdate check|apply|status`（默认 op 权限）。

## 四、版本号与"何时真正更新"

本地版本 patch +1 到 1.0.1，而 manifest 的 `target.version` 仍指向当前已发布的最新 Release。当前状态下本地 ≥ 目标，门禁返回"已是最新/禁止降级"——这是正确且安全的。要触发一次真实更新，需未来发版时同步升级本版本号与 manifest 的 `target.version`、资产 `fileName`/`downloadUrl`/`sha256`。

## 五、安全红线核对

- [x] 开机自检 + 每周四 12:00（Asia/Shanghai）
- [x] 版本门禁逐条实现，不跨 minor/major、不降级
- [x] API 过高只提示升级服务端、不下载不降级
- [x] sha256 校验 + `.part` 临时文件 + 原子替换；重启生效不热加载
- [x] 无 Token、只走 HTTPS、文案中英双语、注释简体中文
- [x] 上报匿名 instanceId，不含 IP/账号
- [x] 7 个新类用 javac + `-Xlint:all` 编译：零错误零警告
- [ ] **构建验证**：本沙箱无 Java 21/Gradle 工具链且网络受限，需你本机 `./gradlew build`（或 `mvn package`）确认
