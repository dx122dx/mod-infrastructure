package com.billy65536.infrastructure.core.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 原子文件写入工具。
 *
 * <p>所有写入都遵循「先写同目录临时文件，成功后原子改名到目标」的流程，
 * 保证目标文件要么是改动前的完整旧内容，要么是改动后的完整新内容，
 * 绝不会出现写到一半崩溃留下的半截文件。</p>
 *
 * <h2>使用方式</h2>
 * 本类只提供回调式 API，不向外暴露临时文件或流对象，
 * 调用方无需（也无法）关心临时文件的创建、关闭与清理：
 * <pre>{@code
 * AtomicFiles.write(target, out -> {
 *     out.write(header);
 *     out.write(payload);
 * });
 * }</pre>
 * 回调正常返回即提交（改名生效）；回调抛出异常则回滚（清理临时文件、
 * 目标文件保持原样）并把异常原样上抛。
 *
 * @see IoConsumer
 */
public final class AtomicFiles {

    /** 临时文件名随机后缀的字节数，用于隔离并发写同一目标的场景。 */
    private static final int TEMP_SUFFIX_BYTES = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AtomicFiles() {
    }

    // ==================== 写入入口 ====================

    /**
     * 以原子方式写入文件：临时文件 → 执行回调 → 原子改名。
     *
     * <p>目标文件的父目录不存在时会自动创建。回调收到的流由本方法负责关闭，
     * 回调内不应自行关闭它。</p>
     *
     * @param dest 目标文件路径
     * @param body 写入回调，向给定输出流写出全部内容
     * @throws IOException          如果创建目录、写入或改名失败；
     *                              也包括回调自身抛出的异常（此时目标文件未被改动）
     * @throws NullPointerException 如果 {@code dest} 或 {@code body} 为 {@code null}
     */
    public static void write(Path dest, IoConsumer<OutputStream> body) throws IOException {
        if (dest == null) throw new NullPointerException("dest");
        if (body == null) throw new NullPointerException("body");

        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tmp = tempPathFor(dest);
        try {
            try (OutputStream out = Files.newOutputStream(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                body.accept(out);
            }
            moveAtomically(tmp, dest);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    /**
     * 以原子方式写入字节内容。
     *
     * @param dest 目标文件路径
     * @param data 待写入的字节
     * @throws IOException 如果写入或改名失败
     */
    public static void writeBytes(Path dest, byte[] data) throws IOException {
        write(dest, out -> out.write(data));
    }

    /**
     * 以原子方式写入文本内容。
     *
     * @param dest    目标文件路径
     * @param content 待写入的文本
     * @param charset 字符集
     * @throws IOException 如果写入或改名失败
     */
    public static void writeString(Path dest, String content, Charset charset) throws IOException {
        writeBytes(dest, content.getBytes(charset));
    }

    /**
     * 以 UTF-8 原子写入文本内容。
     *
     * @param dest    目标文件路径
     * @param content 待写入的文本
     * @throws IOException 如果写入或改名失败
     */
    public static void writeString(Path dest, String content) throws IOException {
        writeString(dest, content, StandardCharsets.UTF_8);
    }

    // ==================== 改名 ====================

    /**
     * 原子改名，不支持原子语义的文件系统上退化为普通替换。
     *
     * <p>部分网络盘或跨设备场景不支持 {@link StandardCopyOption#ATOMIC_MOVE}，
     * 此时退化为 {@link StandardCopyOption#REPLACE_EXISTING}：仍能保证结果正确，
     * 只是失去崩溃瞬间的原子性保障。</p>
     *
     * @param from 源路径
     * @param to   目标路径
     * @throws IOException 如果改名失败
     */
    public static void moveAtomically(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ==================== 内部工具 ====================

    /** 生成与目标同目录的临时文件路径，随机后缀避免并发写入互相覆盖。 */
    private static Path tempPathFor(Path dest) {
        byte[] bytes = new byte[TEMP_SUFFIX_BYTES];
        RANDOM.nextBytes(bytes);
        String suffix = HexFormat.of().formatHex(bytes);
        return dest.resolveSibling(dest.getFileName() + "." + suffix + ".tmp");
    }

    /** 删除临时文件，失败时静默忽略（残留临时文件不影响正确性）。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 残留 .tmp 不影响正确性，下次写入会使用新的随机后缀
        }
    }
}
