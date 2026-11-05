# 星穹旅驿 · 三句话上手

1. 把 `StarInn-1.0.0.jar` 放进 `plugins/` 目录，重启 Purpur 1.21.11（日志会打印自检结果与资源包下发地址）
2. 端口申请下来后改 `plugins/StarInn/config.yml` 的 `resource-pack.builtin-port`（并在防火墙/安全组放行）；
   服务器在 NAT 后就再填 `resource-pack.public-host`（基岩玩家必须能访问这个地址）
3. 游戏内输入 `/starinn reload`，进服即可看到引导；随时 Shift（Java）/ skip 或表单按钮（基岩）可跳过

换正式立绘：同名 PNG 丢进 `plugins/StarInn/textures/`（宽高 2 的幂），`/starinn pack regen` 一键重建，不用重编插件。

从旧版升级（阵营选择已移交独立阵营插件）：对话文件只在**首次安装**时释放，升级换 jar 不会覆盖
`plugins/StarInn/dialogues/` 里的旧台词。请删除旧的 `wu.yml / chen.yml / an.yml`
（或用分发包 `dialogues/` 里的新文件覆盖），再 `/starinn reload`，否则老服上 S5_4 仍会带旧的三选项。
