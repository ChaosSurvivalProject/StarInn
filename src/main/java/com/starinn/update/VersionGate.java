package com.starinn.update;

/**
 * 版本门禁：按 contract.md 第 2 节逐条判定，命中即返回。
 * 语义版本比较：major->minor->patch，preRelease 低于正式版，build metadata 忽略。
 */
public final class VersionGate {

    public enum Code {
        UP_TO_DATE,
        BLOCKED_UPGRADE_REQUIRED,
        BLOCKED_API_TOO_NEW,
        BLOCKED_CLIENT_TOO_OLD,
        UPDATE_REQUIRED,
        UPDATE_OPTIONAL,
        BLOCKED_DOWNGRADE_FORBIDDEN
    }

    /** 门禁决策：返回码 + 中英双语文案 + 下载地址（仅 UPDATE_* 时非空）。 */
    public record Decision(Code code, String messageZh, String messageEn, String downloadUrl) {
        /** 是否允许进入下载流程：仅 UPDATE_* 允许，BLOCKED_* 与 UP_TO_DATE 一律禁止。 */
        public boolean allowsDownload() {
            return code == Code.UPDATE_REQUIRED || code == Code.UPDATE_OPTIONAL;
        }
    }

    /** 解析语义版本核心段，忽略 build metadata 与 preRelease 尾。 */
    private static int[] parse(String v) {
        String core = v.replaceAll("\\+.*", "").split("-")[0];
        String[] p = core.split("\\.");
        int[] r = new int[3];
        for (int i = 0; i < 3 && i < p.length; i++) {
            String digits = p[i].replaceAll("\\D", "");
            r[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return r;
    }

    /** 比较两个语义版本：a < b 返回 -1，相等返回 0，a > b 返回 1。 */
    private static int cmp(String a, String b) {
        if (a.equals(b)) return 0;
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        }
        // 核心相同但字符串不同：带 preRelease 者更低。
        boolean ap = a.contains("-"), bp = b.contains("-");
        if (ap != bp) return ap ? -1 : 1;
        return a.compareTo(b);
    }

    /**
     * 逐条判定，命中即返回。
     *
     * @param mode OPTIONAL / REQUIRED
     * @param manifestApi target.apiVersion；为空表示不检查 API
     */
    public Decision evaluate(String current, String target, String minSupported, String minClient,
                             String clientVersion, String serverApiVersion, String manifestApi,
                             String mode, String downloadUrl) {
        String api = (manifestApi == null || manifestApi.isBlank()) ? serverApiVersion : manifestApi;

        // 1) target == current
        if (cmp(target, current) == 0) {
            return new Decision(Code.UP_TO_DATE, "已是最新版本", "You are up to date", null);
        }
        // 2) current < minSupported
        if (cmp(current, minSupported) < 0) {
            return new Decision(Code.BLOCKED_UPGRADE_REQUIRED,
                    "当前版本过旧，必须先升级", "Your version is too old; upgrade first", downloadUrl);
        }
        // 3) target > current
        if (cmp(target, current) > 0) {
            // 服务端：清单要求更高 API 版本时，只提示升级服务端，绝不下载、不降级。
            if (serverApiVersion != null && api != null && cmp(api, serverApiVersion) > 0) {
                return new Decision(Code.BLOCKED_API_TOO_NEW,
                        "当前插件要求 Paper " + api + "，你的服务器为 " + serverApiVersion
                                + "，请先升级服务端，不会自动跨版本更新",
                        "This plugin requires Paper " + api + " but the server is " + serverApiVersion
                                + "; upgrade the server first. No cross-version auto update.",
                        null);
            }
            // 客户端最低版本不足（Paper 侧一般不触发，保留以符合契约）。
            if (minClient != null && !minClient.isBlank()
                    && clientVersion != null && cmp(minClient, clientVersion) > 0) {
                return new Decision(Code.BLOCKED_CLIENT_TOO_OLD,
                        "客户端版本过低，请先更新客户端", "Client is too old; please update the client first",
                        downloadUrl);
            }
            Code c = "REQUIRED".equalsIgnoreCase(mode) ? Code.UPDATE_REQUIRED : Code.UPDATE_OPTIONAL;
            String zh = c == Code.UPDATE_REQUIRED ? "有新版本，必须更新" : "有新版本，可选更新";
            String en = c == Code.UPDATE_REQUIRED
                    ? "A new version is available and required"
                    : "A new version is available (optional)";
            return new Decision(c, zh, en, downloadUrl);
        }
        // 4) target < current
        return new Decision(Code.BLOCKED_DOWNGRADE_FORBIDDEN, "禁止降级", "Downgrade is forbidden", null);
    }
}
