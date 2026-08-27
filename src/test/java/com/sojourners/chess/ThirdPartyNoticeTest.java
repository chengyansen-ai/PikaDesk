package com.sojourners.chess;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ThirdPartyNoticeTest {

    private static final Set<String> BINARY_RESOURCE_EXTENSIONS =
            Set.of("png", "jpg", "ico", "wav", "onnx", "ttf");

    private final Path projectRoot = Path.of(System.getProperty("user.dir"));

    @Test
    void noticePreservesUpstreamAttributionAndReleaseGate() throws IOException {
        String notice = Files.readString(projectRoot.resolve("NOTICE.md"), StandardCharsets.UTF_8);
        String thirdParty = Files.readString(projectRoot.resolve("docs/third-party.md"), StandardCharsets.UTF_8);

        assertTrue(notice.contains(ProductInfo.UPSTREAM_URL));
        assertTrue(notice.contains(ProductInfo.LICENSE_ID));
        assertTrue(thirdParty.contains("发布阻断"));
        assertTrue(thirdParty.contains("yolov11.onnx"));
    }

    @Test
    void recordsEveryBundledBinaryResourceBySha256() throws IOException, NoSuchAlgorithmException {
        Map<String, String> manifest = readManifest(projectRoot.resolve("docs/bundled-resources.sha256"));
        Map<String, String> actual = hashBinaryResources(projectRoot.resolve("src/main/resources"));

        assertEquals(actual.keySet(), manifest.keySet(), "资源清单必须与仓库中的二进制资源一一对应");
        assertEquals(actual, manifest, "资源内容变化时必须同步更新 SHA-256 和来源审计");
    }

    private Map<String, String> readManifest(Path manifestPath) throws IOException {
        Map<String, String> entries = new HashMap<>();
        for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("  ", 2);
            assertEquals(2, fields.length, "清单行必须为 <sha256><两个空格><路径>: " + line);
            assertTrue(fields[0].matches("[0-9a-f]{64}"), "无效 SHA-256: " + line);
            assertNull(entries.put(fields[1], fields[0]), "清单路径重复: " + fields[1]);
        }
        return entries;
    }

    private Map<String, String> hashBinaryResources(Path resourcesRoot)
            throws IOException, NoSuchAlgorithmException {
        try (Stream<Path> files = Files.walk(resourcesRoot)) {
            Set<Path> binaryFiles = files
                    .filter(Files::isRegularFile)
                    .filter(this::isAuditedBinaryResource)
                    .collect(Collectors.toSet());
            Map<String, String> hashes = new HashMap<>();
            for (Path file : binaryFiles) {
                String relativePath = projectRoot.relativize(file).toString().replace('\\', '/');
                hashes.put(relativePath, sha256(file));
            }
            return hashes;
        }
    }

    private boolean isAuditedBinaryResource(Path path) {
        String fileName = path.getFileName().toString();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart < 0) {
            return false;
        }
        String extension = fileName.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
        return BINARY_RESOURCE_EXTENSIONS.contains(extension);
    }

    private String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
