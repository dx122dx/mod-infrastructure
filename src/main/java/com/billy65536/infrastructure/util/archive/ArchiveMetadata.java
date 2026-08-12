package com.billy65536.infrastructure.util.archive;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 归档包的框架元数据，与包内负载数据<b>完全分离</b>。
 *
 * <h2>分离原则</h2>
 * 本元数据只描述「归档」这件事本身——归档时间与各负载文件的摘要清单，
 * 外加一段由业务方自定义、框架原样透传的 {@code business}。
 * 框架<b>不解析、不改写</b>任何负载文件：即便负载中存在同名的
 * {@code metadata.json}，那也是负载所有者自己的文件，与本元数据互不干涉。
 *
 * <h2>存放位置</h2>
 * 元数据以独立 entry 存于归档中，文件名为 {@code archive.<hex>.metadata.json}，
 * 其中 {@code <hex>} 为随机十六进制串，同时写入 ZIP 注释。
 * 读取时先取注释拿到 hex，再拼出文件名定位元数据 entry——
 * 随机命名彻底杜绝了与任何负载文件重名的可能。
 *
 * @param formatVersion 元数据格式版本
 * @param time          归档时间（ISO-8601 带时区偏移）
 * @param files         负载文件摘要清单
 * @param business      业务方自定义段，框架原样透传，可为 {@code null}
 * @see ArchiveWriter
 * @see ArchiveImage
 */
public record ArchiveMetadata(int formatVersion, String time,
                              List<FileEntry> files, JsonObject business) {

    /** 当前元数据格式版本。 */
    public static final int FORMAT_VERSION = 1;

    /**
     * {@link #business()} 内用于声明归档类型的 key。
     * 写入侧由 {@link ArchiveWriter#finish(String, JsonObject)} 强制提供；
     * 读取侧由 {@link ArchiveImage#expectedArchiveType()} 校验一致性。
     */
    public static final String BUSINESS_TYPE_KEY = "type";

    /** 元数据 entry 名的前缀。 */
    private static final String ENTRY_PREFIX = "archive.";

    /** 元数据 entry 名的后缀。 */
    private static final String ENTRY_SUFFIX = ".metadata.json";

    private static final Gson GSON = new Gson();

    /**
     * 拼出元数据 entry 名。
     *
     * @param hex 随机十六进制串（同时是 ZIP 注释内容）
     * @return {@code archive.<hex>.metadata.json}
     */
    public static String metadataEntryName(String hex) {
        return ENTRY_PREFIX + hex + ENTRY_SUFFIX;
    }

    /** 当前时刻的归档时间戳（ISO-8601 带时区偏移）。 */
    public static String nowTime() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * 从输入流解析元数据。
     *
     * @param in 元数据 entry 的输入流
     * @return 解析后的元数据
     * @throws IOException 如果内容为空或不是合法 JSON 对象
     */
    public static ArchiveMetadata parse(InputStream in) throws IOException {
        JsonObject obj;
        try {
            obj = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            throw new IOException("Malformed archive metadata", e);
        }
        if (obj == null) {
            throw new IOException("Archive metadata is empty or not valid JSON");
        }

        int version = obj.has("formatVersion") && obj.get("formatVersion").isJsonPrimitive()
                ? obj.get("formatVersion").getAsInt() : 0;
        String time = optString(obj, "time");

        List<FileEntry> files = new ArrayList<>();
        if (obj.has("files") && obj.get("files").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("files");
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) continue;
                JsonObject fo = arr.get(i).getAsJsonObject();
                files.add(new FileEntry(optString(fo, "name"), optString(fo, "sha256")));
            }
        }

        JsonObject business = obj.has("business") && obj.get("business").isJsonObject()
                ? obj.getAsJsonObject("business") : null;

        return new ArchiveMetadata(version, time, files, business);
    }

    /**
     * 序列化为 JSON 对象。
     *
     * @return 可直接写入元数据 entry 的 JSON
     */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", formatVersion);
        if (time != null) root.addProperty("time", time);

        JsonArray arr = new JsonArray();
        for (FileEntry fe : files) {
            JsonObject fo = new JsonObject();
            fo.addProperty("name", fe.name());
            fo.addProperty("sha256", fe.sha256());
            arr.add(fo);
        }
        root.add("files", arr);

        if (business != null) root.add("business", business);
        return root;
    }

    /**
     * 按名查找文件条目。
     *
     * @param name entry 名
     * @return 匹配的条目；不存在返回 {@code null}
     */
    public FileEntry findFile(String name) {
        for (FileEntry fe : files) {
            if (fe.name().equals(name)) return fe;
        }
        return null;
    }

    /** 业务段；从未写入时返回空对象，免去调用方判空。 */
    public JsonObject businessOrEmpty() {
        return business != null ? business : new JsonObject();
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /**
     * 单个负载文件的摘要声明。
     *
     * @param name   entry 名
     * @param sha256 内容的 SHA-256 十六进制串
     */
    public record FileEntry(String name, String sha256) {
    }
}
