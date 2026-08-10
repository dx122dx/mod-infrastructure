package com.billy65536.infrastructure.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {

    @TempDir
    Path temp;

    @Test
    void write_succeeds_and_leaves_no_tmp() throws IOException {
        Path target = temp.resolve("out.txt");
        AtomicFiles.writeString(target, "hello");

        assertEquals("hello", Files.readString(target, StandardCharsets.UTF_8));
        // 临时文件应已被原子改名消费掉，目录内不应残留 .tmp
        try (var stream = Files.list(temp)) {
            List<Path> tmp = stream.filter(p -> p.toString().endsWith(".tmp")).toList();
            assertTrue(tmp.isEmpty(), "残留临时文件: " + tmp);
        }
    }

    @Test
    void write_overwrites_existing_file() throws IOException {
        Path target = temp.resolve("out.txt");
        Files.writeString(target, "old");
        AtomicFiles.writeString(target, "new");

        assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void write_creates_missing_parent_dirs() throws IOException {
        Path target = temp.resolve("a/b/c/out.txt");
        AtomicFiles.writeString(target, "deep");

        assertTrue(Files.exists(target));
        assertEquals("deep", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void write_rolls_back_when_callback_throws() throws IOException {
        Path target = temp.resolve("out.txt");
        Files.writeString(target, "original");

        IOException boom = new IOException("boom");
        IOException thrown = assertThrows(IOException.class, () ->
                AtomicFiles.write(target, out -> {
                    throw boom;
                }));

        assertEquals(boom, thrown);
        // 原内容保持不变
        assertEquals("original", Files.readString(target, StandardCharsets.UTF_8));
        // 临时文件被清理
        try (var stream = Files.list(temp)) {
            assertTrue(stream.filter(p -> p.toString().endsWith(".tmp")).findAny().isEmpty());
        }
    }

    @Test
    void writeBytes_and_writeString_utf8() throws IOException {
        Path target = temp.resolve("bin.bin");
        byte[] data = {1, 2, 3, (byte) 0xFF};
        AtomicFiles.writeBytes(target, data);
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "数组长度不一致");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "索引 " + i + " 不一致");
        }
    }
}
