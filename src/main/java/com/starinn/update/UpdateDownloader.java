package com.starinn.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 下载到 .part，校验 sha256 后原子替换到 plugins/update/。
 * 禁止热加载：下载包不覆盖正在运行的 jar，由运维在停服后替换并重启生效。
 */
public final class UpdateDownloader {

    private static final int MAX_ATTEMPTS = 2;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 下载并校验；失败抛异常，由调用方按 result_code 上报。
     * sha256 不匹配时删除临时文件并重试，最多 MAX_ATTEMPTS 次。
     *
     * @return 校验通过后的新文件绝对路径
     */
    public Path downloadAndVerify(String url, String expectedSha256, Path target) throws Exception {
        Path part = Paths.get(target + ".part");
        SecurityException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "StarInn-UpdateGate/1.0")
                    .GET()
                    .build();
            HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(part));
            if (res.statusCode() / 100 != 2) {
                Files.deleteIfExists(part);
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            String actual = sha256(part);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(part);
                last = new SecurityException("SHA256_MISMATCH");
                continue;
            }
            Files.createDirectories(target.getParent());
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException atomicFailed) {
                // 文件系统不支持原子移动时退化处理。
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        }
        throw last;
    }

    private static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
