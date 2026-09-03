package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Tool> tools = new HashMap<>();

    public ToolRegistry() {
        // 注册内置工具
        registerFileTools();
        registerShellTools();
        registerCodeTools();
    }

    /**
     * 注册文件操作工具
     */
    private void registerFileTools() {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容",
                createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    String path = args.get("path");
                    try {
                        Path target = SandboxGuard.resolve(path);
                        if (!SandboxGuard.isInside(target)
                                && !SandboxGuard.askUser("read_file:" + target,
                                        "读取沙箱外的文件: " + target, false)) {
                            return SandboxGuard.deniedMessage("读取沙箱外的文件: " + target);
                        }
                        String content = Files.readString(target);
                        return "文件内容:\n" + content;
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    try {
                        Path target = SandboxGuard.resolve(path);
                        if (!SandboxGuard.isInside(target)
                                && !SandboxGuard.askUser("write_file:" + target,
                                        "写入沙箱外的文件: " + target, false)) {
                            return SandboxGuard.deniedMessage("写入沙箱外的文件: " + target);
                        }
                        // 确保父目录存在
                        Path parent = target.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(target, content);
                        return "文件已写入: " + target;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    String path = args.get("path");
                    try {
                        Path target = SandboxGuard.resolve(path);
                        if (!SandboxGuard.isInside(target)
                                && !SandboxGuard.askUser("list_dir:" + target,
                                        "列出沙箱外的目录: " + target, false)) {
                            return SandboxGuard.deniedMessage("列出沙箱外的目录: " + target);
                        }
                        if (!Files.isDirectory(target)) {
                            return "目录不存在: " + target;
                        }
                        StringBuilder sb = new StringBuilder("目录内容 (" + target + "):\n");
                        try (var stream = Files.list(target)) {
                            stream.sorted().forEach(p -> sb
                                    .append(Files.isDirectory(p) ? "[D] " : "[F] ")
                                    .append(p.getFileName())
                                    .append("\n"));
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册Shell命令工具
     *
     * 防护策略:
     * 1) 命令在沙箱根目录下执行 (pb.directory);
     * 2) 命中只读白名单 -> 直接放行; 其余一律先征求用户同意;
     * 3) 超时强杀 + 输出上限兜底, 防止死循环/刷屏拖垮 CLI。
     */
    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "执行Shell命令",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> {
                    String command = args.get("command");
                    try {
                        // 白名单命令静默放行, 其余请求用户确认
                        if (!SandboxGuard.isWhitelistedCommand(command)
                                && !SandboxGuard.askUser("execute_command:" + command,
                                        "执行命令: " + command, SandboxGuard.looksDangerous(command))) {
                            return SandboxGuard.deniedMessage("执行命令: " + command);
                        }

                        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                        pb.directory(SandboxGuard.root().toFile());
                        pb.redirectErrorStream(true);
                        Process process = pb.start();

                        // 独立线程持续读取输出, 避免管道写满导致死锁; 超上限即截断
                        StringBuilder output = new StringBuilder();
                        AtomicBoolean truncated = new AtomicBoolean(false);
                        Thread drain = new Thread(() -> {
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(process.getInputStream()))) {
                                char[] buf = new char[4096];
                                int n;
                                long total = 0;
                                while ((n = reader.read(buf)) != -1) {
                                    total += n;
                                    if (total > SandboxGuard.outputLimitBytes()) {
                                        truncated.set(true);
                                        break;
                                    }
                                    output.append(buf, 0, n);
                                }
                            } catch (IOException ignored) {
                                // 进程被强杀时管道中断属预期
                            }
                        });
                        drain.setDaemon(true);
                        drain.start();

                        boolean finished = process.waitFor(SandboxGuard.timeoutSeconds(), TimeUnit.SECONDS);
                        if (!finished) {
                            process.destroyForcibly();
                            process.waitFor();
                        }
                        drain.join(2000);
                        int exitCode = process.exitValue();

                        String head = finished ? "命令执行完成" : "命令执行超时, 已被强制终止";
                        String tail = truncated.get()
                                ? "\n[输出超过上限(" + SandboxGuard.outputLimitBytes() + " 字节), 已截断]"
                                : "";
                        return String.format("%s (exit code: %d)\n%s%s", head, exitCode, output, tail);
                    } catch (Exception e) {
                        return "执行命令失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册代码相关工具
     */
    private void registerCodeTools() {
        tools.put("create_project", new Tool(
                "create_project",
                "创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");
                    try {
                        Path projectPath = Paths.get(name);
                        Files.createDirectories(projectPath);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectPath.resolve("src/main/java"));
                                Files.createDirectories(projectPath.resolve("src/main/resources"));
                                Files.writeString(projectPath.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectPath.resolve(name));
                                Files.writeString(projectPath.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectPath.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectPath.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }
                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 创建参数定义
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于LLM）
     */
    public List<com.paicli.llm.GLMClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new com.paicli.llm.GLMClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 执行工具调用
     */
    public String executeTool(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name;
        }

        try {
            JsonNode args = mapper.readTree(argumentsJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(entry ->
                    argMap.put(entry.getKey(), entry.getValue().asText()));
            return tool.executor().execute(argMap);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required) {}

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
