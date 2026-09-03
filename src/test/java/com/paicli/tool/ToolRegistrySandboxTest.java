package com.paicli.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证 ToolRegistry 的沙箱闸门:
 * - 沙箱内操作自动放行;
 * - 沙箱外操作在用户输入 "n" 时被拒绝, 且不会真正触达文件。
 */
class ToolRegistrySandboxTest {

    private final ToolRegistry registry = new ToolRegistry();
    private InputStream originalIn = System.in;

    @AfterEach
    void restoreStdin() {
        System.setIn(originalIn);
    }

    @Test
    void writeAndReadInsideSandboxNeedsNoConsent() throws Exception {
        Path target = SandboxGuard.root().resolve("target/sandbox_it_tmp.txt");
        Files.deleteIfExists(target);
        try {
            String write = registry.executeTool("write_file",
                    "{\"path\":\"" + escape(target.toString()) + "\",\"content\":\"hello\"}");
            assertTrue(write.contains("文件已写入"), "沙箱内写入应自动放行: " + write);

            String read = registry.executeTool("read_file", "{\"path\":\"" + escape(target.toString()) + "\"}");
            assertTrue(read.contains("hello"), "沙箱内读取应自动放行: " + read);
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void readOutsideSandboxIsRejectedWhenUserSaysNo() {
        // 指向沙箱外的路径(文件不必真实存在: 闸门应在触达文件前拦截)
        String outside = Path.of(System.getProperty("java.io.tmpdir"), "paicli_should_not_exist.txt")
                .toString();

        System.setIn(new ByteArrayInputStream("n\n".getBytes()));
        String result = registry.executeTool("read_file", "{\"path\":\"" + escape(outside) + "\"}");

        assertTrue(result.contains("操作被用户拒绝"), "越界读取在用户拒绝时应返回拒绝信息: " + result);
        assertTrue(!Files.exists(Path.of(outside)), "被拒绝的操作不应真的创建/读取任何文件");
    }

    @Test
    void readOutsideSandboxIsAllowedWhenUserSaysYes() throws Exception {
        // 造一个真实的沙箱外文件, 验证 y 放行后确实能读到
        Path outsideFile = Path.of(System.getProperty("java.io.tmpdir"), "paicli_it_consent.txt");
        Files.deleteIfExists(outsideFile);
        try {
            Files.writeString(outsideFile, "outside-data");
            System.setIn(new ByteArrayInputStream("y\n".getBytes()));
            String result = registry.executeTool("read_file",
                    "{\"path\":\"" + escape(outsideFile.toString()) + "\"}");
            assertTrue(result.contains("outside-data"), "用户同意后越界读取应放行: " + result);
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
