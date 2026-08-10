package com.billy65536.infrastructure.core.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 归档镜像只读抽象基类（模板方法）。
 *
 * <p>持有 ZIP 路径与已解析的框架元数据，提供与业务无关的通用能力：</p>
 * <ul>
 *   <li>{@link #readMetadata(Path)} 经 ZIP 注释定位随机命名的元数据 entry；</li>
 *   <li>{@link #validate()} 模板方法：业务字段校验 + 通用完整性校验合流；</li>
 *   <li>{@link #extractTo(Path)} 解压全部负载（跳过框架元数据，含穿越防护）；</li>
 *   <li>{@link #openEntry(String)} / {@link #copyEntryTo(String, Path)} / {@link #entrySha256(String)} 单 entry 访问。</li>
 * </ul>
 *
 * <p>子类只需实现两个钩子：{@link #validateBusinessFields(List, List)} 与
 * {@link #requiredEntries()}，把「哪些字段算错、哪些 entry 必需」交给业务层，
 * 框架负责全部 I/O 与 SHA-256 比对。</p>
 *
 * <h2>旧包兼容</h2>
 * {@link #readMetadata(Path)} 在 ZIP 注释缺失或元数据 entry 不存在时抛
 * {@link IOException}，由子类捕获后自行决定是否走兼容回落
 * （如 chunkscanner 历史导出包无注释、元数据内嵌于负载的 {@code metadata.json}）。
 *
 * @see ArchiveWriter
 * @see ArchiveMetadata
 * @see ValidationResult
 */
public abstract class ArchiveImage {

    /** 框架元数据 entry 名前缀，用于识别并跳过它。 */
    protected static final String META_ENTRY_PREFIX = "archive.";

    private final Path zipPath;
    private final ArchiveMetadata metadata;

    /**
     * 构造归档镜像。
     *
     * @param zipPath   归档 ZIP 路径
     * @param metadata  已解析的框架元数据（不可为 null）
     */
    protected ArchiveImage(Path zipPath, ArchiveMetadata metadata) {
        this.zipPath = zipPath;
        this.metadata = metadata;
    }

    /** 归档 ZIP 路径。 */
    protected Path zipPath() {
        return zipPath;
    }

    /** 框架元数据。 */
    protected ArchiveMetadata metadata() {
        return metadata;
    }

    // ==================== 元数据定位 ====================

    /**
     * 经 ZIP 注释定位并解析框架元数据。
     *
     * <p>先 {@code ZipFile.getComment()} 取 hex，再拼出
     * {@code archive.<hex>.metadata.json} 定位 entry 并解析。</p>
     *
     * @param zipPath 归档 ZIP 路径
     * @return 解析出的框架元数据
     * @throws IOException 如果注释缺失、元数据 entry 不存在或内容非法
     */
    protected static ArchiveMetadata readMetadata(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new IOException("Archive not found: " + zipPath);
        }
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            String hex = zf.getComment();
            if (hex == null || hex.isEmpty()) {
                throw new IOException("Archive has no comment (cannot locate framework metadata): " + zipPath);
            }
            String entryName = ArchiveMetadata.metadataEntryName(hex);
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Framework metadata entry missing: " + entryName
                        + " (comment hex=" + hex + ")");
            }
            try (InputStream in = zf.getInputStream(entry)) {
                return ArchiveMetadata.parse(in);
            }
        }
    }

    // ==================== 校验（模板方法） ====================

    /**
     * 校验归档：业务字段 + 通用完整性，合并为一个 {@link ValidationResult}。
     *
     * <p>单次打开 ZIP 完成全部检查：</p>
     * <ol>
     *   <li>回调 {@link #validateBusinessFields(List, List)} 收集业务错误/警告；</li>
     *   <li>通用完整性：必需 entry 必须实际存在；逐 {@code FileEntry} 比对 SHA-256；
     *       ZIP 内未声明的文件告警（框架元数据自身除外）。</li>
     * </ol>
     *
     * @return 校验结果
     */
    public final ValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateBusinessFields(errors, warnings);

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Set<String> declaredNames = new HashSet<>();
            for (ArchiveMetadata.FileEntry fe : metadata.files()) {
                declaredNames.add(fe.name());
            }

            // 必需 entry 只要求实际存在；是否被声明由下方的未声明告警统一覆盖，
            // 以便兼容摘要清单不完整的历史归档
            for (String required : requiredEntries()) {
                if (zf.getEntry(required) == null) {
                    errors.add("Required entry missing from archive: " + required);
                }
            }

            for (ArchiveMetadata.FileEntry fe : metadata.files()) {
                ZipEntry ze = zf.getEntry(fe.name());
                if (ze == null) {
                    errors.add("Declared file missing from archive: " + fe.name());
                    continue;
                }
                try (InputStream in = zf.getInputStream(ze)) {
                    String actual = ArchiveIO.sha256Hex(in);
                    if (!actual.equalsIgnoreCase(fe.sha256())) {
                        errors.add("SHA-256 mismatch for file '" + fe.name()
                                + "': expected " + fe.sha256() + " but got " + actual);
                    }
                }
            }

            // 未声明但存在的多余文件（框架元数据自身除外）→ 警告
            zf.stream().forEach(ze -> {
                String name = ze.getName();
                if (!name.startsWith(META_ENTRY_PREFIX) && !declaredNames.contains(name)) {
                    warnings.add("Undeclared file present in archive: " + name);
                }
            });
        } catch (IOException e) {
            errors.add("Failed to read archive for integrity check: " + e.getMessage());
        }

        return ValidationResult.of(errors, warnings);
    }

    /**
     * 业务字段校验钩子：子类填充错误与警告列表。
     *
     * @param errors   错误列表（致命，导致无法安全加载）
     * @param warnings 警告列表（不影响加载但值得注意）
     */
    protected abstract void validateBusinessFields(List<String> errors, List<String> warnings);

    /**
     * 必需的负载 entry 集合（必须实际存在于归档中，否则视为错误）。
     *
     * @return 必需 entry 名集合
     */
    protected abstract Set<String> requiredEntries();

    // ==================== 解包与访问 ====================

    /**
     * 解压全部负载 entry 到目标目录（跳过框架元数据 entry）。
     *
     * <p>每个 entry 都经 {@link ArchiveIO#resolveSafely(Path, String)} 做路径穿越防护；
     * 含 {@code ".."} 或绝对路径的 entry 直接抛 {@link IOException}。</p>
     *
     * @param targetDir 解包根目录
     * @throws IOException 如果解压或穿越防护失败
     */
    public void extractTo(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            for (ZipEntry ze : zf.stream().toList()) {
                String name = ze.getName();
                if (name.startsWith(META_ENTRY_PREFIX)) {
                    continue; // 框架元数据不落盘
                }
                Path out = ArchiveIO.resolveSafely(targetDir, name);
                if (ze.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(ze)) {
                        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * 打开某个 entry 的输入流（调用方负责关闭）。
     *
     * @param entryName entry 名
     * @return 输入流
     * @throws IOException 如果 entry 不存在或读取失败
     */
    public InputStream openEntry(String entryName) throws IOException {
        ZipFile zf = new ZipFile(zipPath.toFile());
        ZipEntry entry = zf.getEntry(entryName);
        if (entry == null) {
            zf.close();
            throw new IOException("Archive entry not found: " + entryName);
        }
        return new WrappedInputStream(zf, zf.getInputStream(entry));
    }

    /**
     * 把某个 entry 复制到目标文件。
     *
     * @param entryName entry 名
     * @param dest      目标路径
     * @throws IOException 如果 entry 不存在或写入失败
     */
    public void copyEntryTo(String entryName, Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (InputStream in = openEntry(entryName)) {
            Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 计算某个 entry 的 SHA-256 十六进制串。
     *
     * @param entryName entry 名
     * @return 小写十六进制摘要
     * @throws IOException 如果 entry 不存在或读取失败
     */
    public String entrySha256(String entryName) throws IOException {
        try (InputStream in = openEntry(entryName)) {
            return ArchiveIO.sha256Hex(in);
        }
    }

    /** 关闭 ZipFile 的输入流包装，确保读取完成后底层 ZIP 被关闭。 */
    private static final class WrappedInputStream extends InputStream {
        private final ZipFile owner;
        private final InputStream delegate;

        WrappedInputStream(ZipFile owner, InputStream delegate) {
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                owner.close();
            }
        }
    }
}
