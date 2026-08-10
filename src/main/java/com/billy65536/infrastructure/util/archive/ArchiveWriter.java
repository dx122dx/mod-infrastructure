package com.billy65536.infrastructure.util.archive;

import com.billy65536.infrastructure.util.io.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 归档写入器（AutoCloseable）。
 *
 * <p>统一负责「写负载 entry + 收集 SHA-256 + 写出框架元数据 + 原子落盘」四件事，
 * 把 ZIP 读写的重复代码从各业务仓收编到基础设施。</p>
 *
 * <h2>使用流程</h2>
 * <pre>{@code
 * try (ArchiveWriter w = new ArchiveWriter(outFile)) {
 *     w.addFile("database.zip", dbZip);
 *     w.addBytes("regions.json", json.getBytes(UTF_8));
 *     w.finish(businessJson);   // 写 archive.<hex>.metadata.json、设 ZIP 注释、原子落盘
 * }
 * }</pre>
 *
 * <p>未调用 {@link #finish(JsonObject)} 就关闭（含异常）时，临时文件会被清理，
 * 不会留下半截归档，保证 try-with-resources 安全。</p>
 *
 * @see ArchiveImage
 * @see ArchiveMetadata
 */
public final class ArchiveWriter implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Path destFile;
    private final String hex;
    private final Path tmpFile;
    private final ZipOutputStream zos;
    private final List<ArchiveMetadata.FileEntry> declared = new ArrayList<>();
    private boolean finished = false;

    /**
     * 构造写入器，立即开临时输出流。
     *
     * @param destFile 最终落盘路径（临时文件与其同目录，随机后缀避免并发冲突）
     * @throws IOException 如果创建临时文件失败
     */
    public ArchiveWriter(Path destFile) throws IOException {
        this.destFile = destFile;
        this.hex = ArchiveIO.randomHex();
        Path parent = destFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.tmpFile = (parent != null)
                ? parent.resolve(destFile.getFileName() + "." + hex + ".tmp")
                : destFile.resolveSibling(destFile.getFileName() + "." + hex + ".tmp");
        this.zos = new ZipOutputStream(Files.newOutputStream(tmpFile));
    }

    // ==================== 添加负载 ====================

    /**
     * 以默认（DEFLATED）方式添加一个负载 entry。
     *
     * @param entryName 包内 entry 名（不可与已添加的重名）
     * @param src       源文件路径
     * @throws IOException           如果读取或写入失败
     * @throws IllegalArgumentException 如果 entry 名重复
     */
    public void addFile(String entryName, Path src) throws IOException {
        requireUnique(entryName);
        byte[] data = Files.readAllBytes(src);
        writeEntry(entryName, data, false, null);
    }

    /**
     * 以 STORED 方式添加一个负载 entry（用于内嵌的已压缩数据，免二次 deflate）。
     *
     * <p>会预先经 {@link ArchiveIO#sizeAndCrc(Path)} 填好 size / compressedSize / crc，
     * 否则 {@link ZipOutputStream} 会抛异常。</p>
     *
     * @param entryName 包内 entry 名（不可与已添加的重名）
     * @param src       源文件路径（已压缩数据，如内嵌 ZIP）
     * @throws IOException           如果读取或写入失败
     * @throws IllegalArgumentException 如果 entry 名重复
     */
    public void addStored(String entryName, Path src) throws IOException {
        requireUnique(entryName);
        byte[] data = Files.readAllBytes(src);
        ArchiveIO.SizeAndCrc sc = ArchiveIO.sizeAndCrc(src);
        writeEntry(entryName, data, true, sc);
    }

    /**
     * 以默认（DEFLATED）方式添加一段内存中的字节作为负载 entry。
     *
     * @param entryName 包内 entry 名（不可与已添加的重名）
     * @param data      负载字节
     * @throws IOException           如果写入失败
     * @throws IllegalArgumentException 如果 entry 名重复
     */
    public void addBytes(String entryName, byte[] data) throws IOException {
        requireUnique(entryName);
        writeEntry(entryName, data, false, null);
    }

    // ==================== 收尾 ====================

    /**
     * 写框架元数据、设 ZIP 注释、关流并原子落盘。
     *
     * @param business 业务方自定义段（原样透传，可为 {@code null}）
     * @return 最终落盘的文件路径
     * @throws IOException 如果写入或落盘失败
     */
    public Path finish(JsonObject business) throws IOException {
        if (finished) {
            throw new IllegalStateException("ArchiveWriter already finished");
        }
        ArchiveMetadata meta = new ArchiveMetadata(
                ArchiveMetadata.FORMAT_VERSION,
                ArchiveMetadata.nowTime(),
                declared,
                business);
        byte[] metaBytes = GSON.toJson(meta.toJson()).getBytes(StandardCharsets.UTF_8);

        ZipEntry metaEntry = new ZipEntry(ArchiveMetadata.metadataEntryName(hex));
        zos.putNextEntry(metaEntry);
        zos.write(metaBytes);
        zos.closeEntry();

        zos.setComment(hex);
        zos.close();
        finished = true;

        AtomicFiles.moveAtomically(tmpFile, destFile);
        return destFile;
    }

    /**
     * 关闭写入器；未 {@link #finish(JsonObject)} 时清理临时文件。
     *
     * @throws IOException 如果关闭底层流失败
     */
    @Override
    public void close() throws IOException {
        try {
            zos.close();
        } finally {
            if (!finished) {
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (IOException ignored) {
                    // 临时文件残留不影响正确性
                }
            }
        }
    }

    // ==================== 内部实现 ====================

    private void requireUnique(String entryName) {
        for (ArchiveMetadata.FileEntry fe : declared) {
            if (fe.name().equals(entryName)) {
                throw new IllegalArgumentException("Duplicate archive entry: " + entryName);
            }
        }
    }

    private void writeEntry(String entryName, byte[] data, boolean stored, ArchiveIO.SizeAndCrc sc)
            throws IOException {
        ZipEntry ze = new ZipEntry(entryName);
        if (stored && sc != null) {
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(sc.size());
            ze.setCompressedSize(sc.size());
            ze.setCrc(sc.crc32());
        }
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();

        // 记录摘要，供 finish 写入框架元数据
        String sha;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data);
            sha = HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
        declared.add(new ArchiveMetadata.FileEntry(entryName, sha));
    }
}
