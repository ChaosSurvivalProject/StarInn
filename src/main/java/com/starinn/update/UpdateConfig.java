package com.starinn.update;

/**
 * 更新模块配置（不可变）。
 *
 * <p>字段语义见 references/contract.md。此处为 Paper 插件侧的最小实现，
 * 全部注释使用简体中文，标识符使用英文。
 */
public final class UpdateConfig {
    /** 更新清单固定地址（gh-pages 或 raw 直连），元数据直连，不走代理池。 */
    public final String manifestUrl;
    /** 本地插件版本（来自 plugin.yml，由构建脚本注入）。 */
    public final String currentVersion;
    /** 服务端实际 Paper/Purpur 的 Minecraft API 版本（如 1.21.11）。 */
    public final String serverApiVersion;
    /** 目标通道 stable/beta。 */
    public final String channel;
    /** 是否开机自检，默认 true。 */
    public final boolean checkOnBoot;
    /** 是否启用定时（每周四 12:00），默认 true。 */
    public final boolean scheduleEnabled;

    public UpdateConfig(String manifestUrl, String currentVersion, String serverApiVersion,
                        String channel, boolean checkOnBoot, boolean scheduleEnabled) {
        this.manifestUrl = manifestUrl;
        this.currentVersion = currentVersion;
        this.serverApiVersion = serverApiVersion;
        this.channel = channel;
        this.checkOnBoot = checkOnBoot;
        this.scheduleEnabled = scheduleEnabled;
    }
}
