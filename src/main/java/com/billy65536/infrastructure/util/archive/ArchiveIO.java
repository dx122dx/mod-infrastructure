package com.billy65536.infrastructure.util.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 归档相关的静态 I/O 工具。
 *
 * <p>本类只提供与具体业务无关的纯能力：摘要计算、随机命名、路径穿越防护、
 * 递归删除、以及给 STORED 模式预先填充 {@code ZipEntry} 的尺寸信息。
 * 没有任何「业务字段」「包类型」等概念，可被任意归档实现复用。</p>
 */
public final class ArchiveIO {

    /** SHA-256 流式摘要的缓冲大小。 */
    private static final int DIGEST_BUFFER = 8192;

    /** 随机命名所用的字节数（hex 后为 32 个字符）。 */
    private static final int RANDOM_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ArchiveIO() {
    }

    // ==================== 摘要与命名 ====================

    /**
     * 计算输入流的 SHA-256 十六进制串（单遍流式，8192 字节缓冲）。
     *
     * @param in 待摘要的流（调用方负责关闭）
     * @return 小写十六进制摘要串
     * @throws IOException 如果读取失败
     */
    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
        byte[] buf = new byte[DIGEST_BUFFER];
        int len;
        while ((len = in.read(buf)) > 0) {
            md.update(buf, 0, len);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 生成随机十六进制串，用作元数据文件名与 ZIP 注释，避免与负载重名。
     *
     * @return {@code RANDOM_BYTES} 个安全随机字节的十六进制串
     */
    public static String randomHex() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 计算路径文件的尺寸与 CRC32，供 STORED 模式预先填充 {@link java.util.zip.ZipEntry}。
     *
     * <p>已压缩数据（如内嵌的 ZIP）用 STORED 可避免无效二次 deflate，
     * 但 {@link java.util.zip.ZipOutputStream} 要求显式给出 size、compressedSize 与 crc。</p>
     *
     * @param file 待测量文件
     * @return 包含 size（未压缩尺寸）与 crc（CRC32）的结果
     * @throws IOException 如果读取失败
     */
    public static SizeAndCrc sizeAndCrc(Path file) throws IOException {
        byte[] buf = new byte[DIGEST_BUFFER];
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        long size = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int len;
            while ((len = in.read(buf)) > 0) {
                crc.update(buf, 0, len);
                size += len;
            }
        }
        return new SizeAndCrc(size, crc.getValue());
    }

    /**
     * STORED 模式所需的尺寸与校验值。
     *
     * @param size          未压缩字节数
     * @param crc32         已计算的 CRC32 值
     */
    public record SizeAndCrc(long size, long crc32) {
    }

    // ==================== 路径穿越防护 ====================

    /**
     * 把 entry 名解析为绝对目标路径，并校验不逃逸目标目录。
     *
     * <p>拒绝三类危险 entry：绝对路径、含 {@code ".."} 段、规范化后逃逸目标目录。
     * 这是解包的安全底线，任何归档实现都必须经过本方法，不得丢失防护。</p>
     *
     * @param targetDir 解包根目录
     * @param entryName ZIP 内 entry 名
     * @return 已规范化、限定在 targetDir 内的绝对路径
     * @throws IOException 如果 entry 名企图逃逸目标目录
     */
    public static Path resolveSafely(Path targetDir, String entryName) throws IOException {
        // 先拒绝本身非法的 entry 名：绝对路径或含父目录逃逸段
        if (entryName.isEmpty()) {
            throw new IOException("Illegal archive entry (empty name)");
        }
        if (Path.of(entryName).isAbsolute()) {
            throw new IOException("Illegal archive entry (absolute path): " + entryName);
        }
        if (hasParentEscape(entryName)) {
            throw new IOException("Illegal archive entry escapes target dir: " + entryName);
        }
        Path normalizedTarget = targetDir.normalize().toAbsolutePath();
        Path resolved = normalizedTarget.resolve(entryName).normalize();
        if (!resolved.startsWith(normalizedTarget)) {
            throw new IOException("Illegal archive entry escapes target dir: " + entryName);
        }
        return resolved;
    }

    /**
     * 判断 entry 名是否含父目录逃逸段（{@code ".."}）。
     *
     * @param entryName ZIP 内 entry 名
     * @return 含 {@code ".."} 段时返回 true
     */
    public static boolean hasParentEscape(String entryName) {
        for (String part : entryName.split("/")) {
            if (part.equals("..")) return true;
        }
        return false;
    }

    // ==================== 递归删除 ====================

    /**
     * 递归删除目录或文件（收编各仓私有实现）。
     *
     * @param path 待删除的路径
     * @throws IOException 如果删除失败
     */
    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                // 先删最深的，故逆序
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
