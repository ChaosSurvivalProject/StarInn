package com.starinn.update;

import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 更新编排：拉取 manifest -> 版本门禁 -> （仅允许时）下载校验并落到 plugins/update/ -> 调度。
 *
 * <p>安全约束：客户端不内置 Token；只走 HTTPS；元数据直连固定地址，不走代理池；
 * 不热加载运行中的 jar，下载包落到 plugins/update/，由运维停服替换后重启生效。
 */
public final class UpdateManager {

    /** result_code 枚举，见 contract.md 第 3 节。 */
    public enum ResultCode {
        SUCCESS, VERSION_GATE_BLOCKED, NETWORK_ALL_MIRRORS_FAILED, SHA256_MISMATCH,
        SIGNATURE_INVALID, DISK_FULL, INSTALL_REJECTED, ROLLED_BACK
    }

    private final JavaPlugin plugin;
    private final UpdateConfig cfg;
    private final VersionGate gate = new VersionGate();
    private final UpdateDownloader downloader = new UpdateDownloader();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 匿名实例标识，不上报 IP / 账号。 */
    private final String instanceId = UUID.randomUUID().toString();

    /** 最近一次门禁结果，供命令查询；未检查时为 null。 */
    private volatile VersionGate.Decision lastDecision;

    public UpdateManager(JavaPlugin plugin, UpdateConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    /** 供主类 onEnable 调用：开机自检一次 + 注册每周四 12:00 定时任务。 */
    public void checkAndSchedule(Runnable scheduleTask) {
        if (cfg.checkOnBoot) {
            check(false);
        }
        if (cfg.scheduleEnabled) {
            UpdateScheduler.scheduleWeekly(plugin, scheduleTask, "Asia/Shanghai");
        }
    }

    /** 执行检查。apply=true 时在门禁允许后下载新包到 plugins/update/。 */
    public VersionGate.Decision check(boolean apply) {
        long t0 = System.currentTimeMillis();
        try {
            // 1) 拉取 manifest（元数据直连固定地址，仅 HTTPS）。
            if (!cfg.manifestUrl.startsWith("https://")) {
                plugin.getLogger().warning("[更新] manifest 地址非 HTTPS，已拒绝。");
                report(null, ResultCode.NETWORK_ALL_MIRRORS_FAILED, t0, 0, "non-https manifest");
                return null;
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.manifestUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "StarInn-UpdateGate/1.0")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                report(null, ResultCode.NETWORK_ALL_MIRRORS_FAILED, t0, 0, "HTTP " + res.statusCode());
                return null;
            }

            // 2) 解析 manifest 并定位目标通道。
            Map<String, Object> root = Json.asMap(Json.parse(res.body()));
            List<Object> channels = Json.asList(root.get("channels"));
            Map<String, Object> ch = null;
            for (Object o : channels) {
                Map<String, Object> c = Json.asMap(o);
                if (cfg.channel.equals(Json.str(c, "id"))) { ch = c; break; }
            }
            if (ch == null) {
                report(null, ResultCode.VERSION_GATE_BLOCKED, t0, 0, "channel not found: " + cfg.channel);
                return null;
            }

            Map<String, Object> target = Json.asMap(ch.get("target"));
            String version = Json.str(target, "version");
            String api = Json.str(target, "apiVersion");
            Map<String, Object> minSupported = Json.asMap(ch.get("minSupported"));
            String minVersion = Json.str(minSupported, "version");
            String minClient = Json.str(ch, "minClientVersion");
            String mode = Json.str(ch, "mode");

            List<Object> assets = Json.asList(ch.get("assets"));
            Map<String, Object> asset = Json.asMap(assets.get(0));
            String fileName = Json.str(asset, "fileName");
            String downloadUrl = Json.str(asset, "downloadUrl");
            String sha256 = Json.str(asset, "sha256");

            // 3) 版本门禁。
            VersionGate.Decision d = gate.evaluate(
                    cfg.currentVersion, version, minVersion, minClient, null,
                    cfg.serverApiVersion, api, mode, downloadUrl);
            this.lastDecision = d;
            plugin.getLogger().info("[更新] " + d.code() + "：" + d.messageZh());

            if (!d.allowsDownload()) {
                report(d, ResultCode.VERSION_GATE_BLOCKED, t0, 0, d.code().name());
                return d;
            }

            // 4) 仅允许时下载校验，落到 plugins/update/，不覆盖运行中的 jar。
            if (apply) {
                Path targetFile = Paths.get("plugins", "update", fileName);
                try {
                    Path done = downloader.downloadAndVerify(downloadUrl, sha256, targetFile);
                    plugin.getLogger().info("[更新] 新包已下载并校验：" + done
                            + "。请在停服后替换 jar 并重启生效（不会热加载）。");
                    report(d, ResultCode.SUCCESS, t0, done.toFile().length(), "downloaded");
                } catch (SecurityException se) {
                    plugin.getLogger().warning("[更新] SHA256 校验失败，已删除临时文件：" + se.getMessage());
                    report(d, ResultCode.SHA256_MISMATCH, t0, 0, se.getMessage());
                } catch (java.io.IOException ioe) {
                    plugin.getLogger().warning("[更新] 写入失败（可能磁盘已满）：" + ioe.getMessage());
                    report(d, ResultCode.DISK_FULL, t0, 0, ioe.getMessage());
                } catch (Exception ex) {
                    plugin.getLogger().warning("[更新] 下载失败：" + ex.getMessage());
                    report(d, ResultCode.NETWORK_ALL_MIRRORS_FAILED, t0, 0, ex.getMessage());
                }
            }
            return d;
        } catch (Exception e) {
            plugin.getLogger().warning("[更新] 检查异常：" + e.getMessage());
            report(null, ResultCode.NETWORK_ALL_MIRRORS_FAILED, t0, 0, e.getMessage());
            return null;
        }
    }

    /** 供命令查询最近一次门禁结果。 */
    public VersionGate.Decision lastDecision() {
        return lastDecision;
    }

    /** 可观测上报：匿名 instanceId，不含 IP / 账号，不打印签名参数。此处仅落日志。 */
    private void report(VersionGate.Decision d, ResultCode rc, long t0, long bytes, String failReason) {
        String fromTo = d == null ? "-" : cfg.currentVersion + "->" + "?";
        plugin.getLogger().info("[更新上报] instanceId=" + instanceId
                + " result=" + rc + " fromTo=" + fromTo
                + " duration_ms=" + (System.currentTimeMillis() - t0)
                + " bytes=" + bytes + " failReason=" + failReason);
    }

    public UpdateConfig config() {
        return cfg;
    }

    public JavaPlugin plugin() {
        return plugin;
    }
}
