package com.starinn.update;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 更新命令：/sinnupdate check|apply|status。
 * check 打印门禁中英双语文案；apply 触发下载（仅门禁允许时）；status 显示当前版本。
 */
public final class UpdateCommand implements CommandExecutor, TabCompleter {

    private final UpdateManager manager;
    private static final String PREFIX = "§8[§bStarInn更新§8] §r";

    public UpdateCommand(UpdateManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !"update".equalsIgnoreCase(args[0])) {
            sender.sendMessage(PREFIX + "用法 / Usage: /sinnupdate <check|apply|status>");
            return true;
        }
        if (!sender.hasPermission("starinn.update")) {
            sender.sendMessage(PREFIX + "§c你没有权限执行此命令 / You lack permission.");
            return true;
        }
        String sub = args.length > 1 ? args[1] : "check";
        switch (sub.toLowerCase()) {
            case "check": {
                sender.sendMessage(PREFIX + "§7正在检查更新... / Checking for updates...");
                // 在异步线程检查，避免阻塞主线程网络请求。
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(
                        manager.plugin(), () -> {
                            VersionGate.Decision d = manager.check(false);
                            final VersionGate.Decision fd = d;
                            org.bukkit.Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                                if (fd == null) {
                                    sender.sendMessage(PREFIX + "§c检查失败，请稍后重试 / Check failed, retry later.");
                                } else {
                                    sender.sendMessage(PREFIX + fd.messageZh());
                                    sender.sendMessage(PREFIX + fd.messageEn());
                                }
                            });
                        });
                break;
            }
            case "apply": {
                sender.sendMessage(PREFIX + "§7正在下载更新包... / Downloading update...");
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(
                        manager.plugin(), () -> {
                            VersionGate.Decision d = manager.check(true);
                            final VersionGate.Decision fd = d;
                            org.bukkit.Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                                if (fd == null || !fd.allowsDownload()) {
                                    sender.sendMessage(PREFIX + "§c没有可应用的更新 / No update to apply.");
                                } else {
                                    sender.sendMessage(PREFIX + "§a更新包已下载，请停服后替换 jar 并重启生效 / "
                                            + "Update downloaded; replace the jar while stopped and restart to apply.");
                                }
                            });
                        });
                break;
            }
            case "status": {
                sender.sendMessage(PREFIX + "当前版本 / Current: " + manager.config().currentVersion
                        + " §8| 通道 / Channel: " + manager.config().channel);
                VersionGate.Decision d = manager.lastDecision();
                if (d != null) {
                    sender.sendMessage(PREFIX + "上次检查 / Last check: " + d.code() + " - " + d.messageZh());
                }
                break;
            }
            default:
                sender.sendMessage(PREFIX + "用法 / Usage: /sinnupdate <check|apply|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Collections.singletonList("update");
        if (args.length == 2 && "update".equalsIgnoreCase(args[0])) {
            return Arrays.asList("check", "apply", "status");
        }
        return Collections.emptyList();
    }
}
