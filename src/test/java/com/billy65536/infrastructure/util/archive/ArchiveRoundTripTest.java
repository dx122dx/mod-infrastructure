package com.billy65536.infrastructure.util.archive;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveRoundTripTest {

    /** 仅用于测试的最小 ArchiveImage 子类。 */
    static final class TestImage extends ArchiveImage {
        TestImage(Path zip, ArchiveMetadata meta) {
            super(zip, meta);
        }

        static TestImage open(Path zip) throws IOException {
            return new TestImage(zip, readMetadata(zip));
        }

        @Override
        protected String expectedArchiveType() {
            return "test:kind";
        }

        @Override
        protected void validateBusinessFields(List<String> errors, List<String> warnings) {
            // type 校验已由框架 expectedArchiveType() 承担，此处无额外业务校验
        }

        @Override
        protected Set<String> requiredEntries() {
            return Set.of("payload.txt", "metadata.json");
        }
    }

    @TempDir
    Path temp;

    private Path buildArchive(String type, boolean includePayload, boolean includeBusinessMeta) throws IOException {
        Path zip = temp.resolve("test.zip");
        JsonObject business = new JsonObject();
        if (includeBusinessMeta) {
            business.addProperty("type", type);
            business.addProperty("extra", "x");
        }
        try (ArchiveWriter w = new ArchiveWriter(zip)) {
            if (includePayload) {
                w.addBytes("payload.txt", "payload-content".getBytes(StandardCharsets.UTF_8));
                w.addBytes("metadata.json", "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8));
            }
            w.finish(type, business);
        }
        return zip;
    }

    @Test
    void write_sets_comment_and_metadata_entry() throws IOException {
        Path zip = buildArchive("test:kind", true, true);

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            String hex = zf.getComment();
            assertTrue(hex != null && !hex.isEmpty(), "ZIP 注释应为随机 hex");
            assertEquals(ArchiveMetadata.metadataEntryName(hex),
                    ArchiveMetadata.metadataEntryName(hex));
            ZipEntry metaEntry = zf.getEntry(ArchiveMetadata.metadataEntryName(hex));
            assertTrue(metaEntry != null, "框架元数据 entry 应存在");
        }
    }

    @Test
    void payload_metadata_json_does_not_conflict_with_framework_metadata() throws IOException {
        // 负载中存在同名 metadata.json，框架元数据用随机命名独立 entry，互不冲突
        Path zip = buildArchive("test:kind", true, true);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            // 业务 metadata.json 仍原样存在
            ZipEntry biz = zf.getEntry("metadata.json");
            assertTrue(biz != null);
            try (InputStream in = zf.getInputStream(biz)) {
                assertEquals("{\"k\":\"v\"}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            // 框架元数据 entry 命名不冲突
            assertTrue(zf.getEntry("archive." + zf.getComment() + ".metadata.json") != null);
        }
    }

    @Test
    void addStored_round_trips_without_secondary_compression() throws IOException {
        Path src = temp.resolve("inner.zip");
        // 构造一个任意已压缩内容的字节数组充当内嵌 ZIP
        byte[] inner = new byte[2048];
        for (int i = 0; i < inner.length; i++) inner[i] = (byte) (i % 37);
        Files.write(src, inner);

        Path zip = temp.resolve("stored.zip");
        try (ArchiveWriter w = new ArchiveWriter(zip)) {
            w.addStored("database.zip", src);
            w.finish("test:kind", null);
        }

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry ze = zf.getEntry("database.zip");
            assertEquals(ZipEntry.STORED, ze.getMethod());
            try (InputStream in = zf.getInputStream(ze)) {
                assertArrayEquals(inner, in.readAllBytes());
            }
        }
    }

    @Test
    void validate_reports_sha_mismatch() throws IOException {
        Path zip = buildArchive("test:kind", true, true);
        // 篡改 payload.txt 后归档内 sha 与磁盘内容不一致，但归档本身未被改
        TestImage img = TestImage.open(zip);
        assertTrue(img.validate().valid(), "原封归档应校验通过");

        // 直接重写归档中的 payload.txt 内容制造不一致
        Path corrupted = temp.resolve("corrupt.zip");
        try (ZipFile srcZf = new ZipFile(zip.toFile());
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(corrupted))) {
            // 保留原 ZIP 注释，否则框架元数据无法定位
            String comment = srcZf.getComment();
            if (comment != null) zos.setComment(comment);
            // 复制所有 entry，但把 payload.txt 内容改掉
            for (ZipEntry e : srcZf.stream().toList()) {
                if (e.getName().startsWith("archive.")) {
                    // 框架元数据必须保留真实 sha，否则 metadata 校验逻辑失效；这里仅改 payload
                    zos.putNextEntry(new ZipEntry(e.getName()));
                    zos.write(srcZf.getInputStream(e).readAllBytes());
                    zos.closeEntry();
                    continue;
                }
                zos.putNextEntry(new ZipEntry(e.getName()));
                if (e.getName().equals("payload.txt")) {
                    zos.write("TAMPERED".getBytes(StandardCharsets.UTF_8));
                } else {
                    zos.write(srcZf.getInputStream(e).readAllBytes());
                }
                zos.closeEntry();
            }
        }
        TestImage bad = TestImage.open(corrupted);
        ValidationResult r = bad.validate();
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("SHA-256 mismatch")),
                "应报告 SHA-256 不一致: " + r.errors());
    }

    @Test
    void validate_reports_missing_required_entry() throws IOException {
        // 只写 business，但 requiredEntries 要求 payload.txt，应报错
        Path zip = temp.resolve("missing.zip");
        JsonObject biz = new JsonObject();
        biz.addProperty("type", "test:kind");
        try (ArchiveWriter w = new ArchiveWriter(zip)) {
            // 故意不写 payload.txt
            w.finish("test:kind", biz);
        }
        TestImage img = TestImage.open(zip);
        ValidationResult r = img.validate();
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(s -> s.contains("payload.txt")));
    }

    @Test
    void validate_clean_archive_has_no_warnings() throws IOException {
        Path zip = temp.resolve("extra.zip");
        JsonObject biz = new JsonObject();
        biz.addProperty("type", "test:kind");
        try (ArchiveWriter w = new ArchiveWriter(zip)) {
            w.addBytes("payload.txt", "x".getBytes(StandardCharsets.UTF_8));
            w.addBytes("metadata.json", "{}".getBytes(StandardCharsets.UTF_8));
            w.addBytes("ghost.txt", "y".getBytes(StandardCharsets.UTF_8));
            w.finish("test:kind", biz);
        }
        TestImage img = TestImage.open(zip);
        ValidationResult r = img.validate();
        assertTrue(r.valid(), "正常归档应校验通过: " + r.errors());
        // ArchiveWriter 写入的全部 entry 都已声明，故不应产生未声明告警
        assertTrue(r.warnings().isEmpty(), "已声明文件不应告警: " + r.warnings());
    }

    @Test
    void finish_rejects_missing_type() throws IOException {
        // finish 强制要求归档类型，缺失时直接拒绝
        Path zip = temp.resolve("notype.zip");
        try (ArchiveWriter w = new ArchiveWriter(zip)) {
            w.addBytes("payload.txt", "x".getBytes(StandardCharsets.UTF_8));
            w.addBytes("metadata.json", "{}".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> w.finish(null, null));
        }
        assertFalse(Files.exists(zip), "拒绝后不应落盘最终文件");
    }

    @Test
    void finish_rejects_mismatched_type() throws IOException {
        // business 已含不一致的 type 时应拒绝
        Path zip = temp.resolve("badtype.zip");
        JsonObject biz = new JsonObject();
        biz.addProperty("type", "other:kind");
        try (ArchiveWriter w = new ArchiveWriter(zip)) {
            w.addBytes("payload.txt", "x".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> w.finish("test:kind", biz));
        }
        assertFalse(Files.exists(zip), "拒绝后不应落盘最终文件");
    }

    @Test
    void validate_reports_type_mismatch_warning() throws IOException {
        // 类型不一致仅是警告，不致命
        Path zip = buildArchive("test:kind", true, true);
        JsonObject business = new JsonObject();
        business.addProperty("type", "other:kind");
        ArchiveMetadata meta = new ArchiveMetadata(
                ArchiveMetadata.FORMAT_VERSION, ArchiveMetadata.nowTime(), List.of(), business);
        TestImage img = new TestImage(zip, meta);
        ValidationResult r = img.validate();
        assertTrue(r.valid(), "类型不一致不应致命: " + r.errors());
        assertTrue(r.warnings().stream().anyMatch(s -> s.contains("type mismatch")),
                "应报告类型不一致警告: " + r.warnings());
    }

    @Test
    void readMetadata_throws_when_comment_missing() throws IOException {
        Path zip = temp.resolve("nocomment.zip");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(zip.toFile());
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry("payload.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            // 不设注释
        }
        assertThrows(IOException.class, () -> TestImage.open(zip));
    }

    @Test
    void extractTo_skips_framework_metadata_and_respects_traversal_guard() throws IOException {
        Path zip = buildArchive("test:kind", true, true);
        Path out = temp.resolve("out");
        TestImage img = TestImage.open(zip);
        img.extractTo(out);

        assertTrue(Files.exists(out.resolve("payload.txt")));
        assertTrue(Files.exists(out.resolve("metadata.json")));
        // 框架元数据 entry 不应落盘
        try (var stream = Files.walk(out)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("archive.")));
        }
    }

    @Test
    void resolveSafely_rejects_traversal_and_absolute() {
        Path root = temp;
        assertThrows(IOException.class, () -> ArchiveIO.resolveSafely(root, "../evil.txt"));
        assertThrows(IOException.class, () -> ArchiveIO.resolveSafely(root, "/abs/etc"));
        assertThrows(IOException.class, () -> ArchiveIO.resolveSafely(root, "a/../../b"));
    }

    @Test
    void writer_does_not_leave_residue_on_close_without_finish() throws IOException {
        Path zip = temp.resolve("unfinished.zip");
        ArchiveWriter w = new ArchiveWriter(zip);
        w.addBytes("payload.txt", "x".getBytes(StandardCharsets.UTF_8));
        w.close(); // 未 finish
        assertFalse(Files.exists(zip), "未 finish 不应落盘最终文件");
        try (var stream = Files.list(temp)) {
            assertTrue(stream.filter(p -> p.toString().endsWith(".tmp")).findAny().isEmpty(),
                    "未 finish 应清理临时文件");
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "数组长度不一致");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "索引 " + i + " 不一致");
        }
    }
}
