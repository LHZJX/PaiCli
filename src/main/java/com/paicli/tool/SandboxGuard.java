package com.paicli.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 沙箱守卫 - 沙箱边界判定 + 越界操作的用户确认。
 *
 * <p>规则:
 * <ol>
 *   <li>沙箱根目录: 默认 CLI 启动目录, 可用环境变量 {@code PAI_SANDBOX_DIR} 覆盖。</li>
 *   <li>路径类工具 (read_file / write_file / list_dir): 解析并规范化后的路径落在沙箱内
 *       -> 自动放行; 落在沙箱外 -> 请求用户确认。</li>
 *   <li>execute_command: 命令在沙箱根目录下执行; 命中"只读检查白名单"(无 shell 元字符、
 *       路径不出沙箱)才静默放行, 其余一律请求用户确认(命中危险特征时给出警告)。</li>
 *   <li>确认交互: {@code y} 仅同意这一次; {@code a} 本会话同类操作不再询问; 回车/其他为拒绝。
 *       拒绝结果以工具错误的形式返回给 LLM, 让它调整方案。</li>
 * </ol>
 */
public final class SandboxGuard {

    private static final String ENV_ROOT = "PAI_SANDBOX_DIR";
    private static final String ENV_TIMEOUT = "PAI_CMD_TIMEOUT_SEC";
    private static final String ENV_OUTPUT = "PAI_CMD_OUTPUT_LIMIT";

    /** 沙箱根目录(规范化为真实路径, 用于识别符号链接逃逸) */
    private static final Path ROOT = initRoot();

    /** execute_command 默认超时(秒), 可用 PAI_CMD_TIMEOUT_SEC 覆盖 */
    private static final long TIMEOUT_SECONDS = envLong(ENV_TIMEOUT, 600L);
    /** execute_command 输出上限(字节), 可用 PAI_CMD_OUTPUT_LIMIT 覆盖 */
    private static final long OUTPUT_LIMIT_BYTES = envLong(ENV_OUTPUT, 512 * 1024L);

    /** 本会话内用户选择过 "a(始终同意)" 的操作 key 集合(static: 切换模式重建 Agent 也不丢失) */
    private static final Set<String> ALWAYS_ALLOWED = new HashSet<>();

    /** 白名单 - 无需参数即可放行的只读/无副作用命令 */
    private static final Set<String> SAFE_SINGLE_WORD = Set.of(
            "pwd", "echo", "printf", "ls", "which", "type", "true", "false");

    /** 白名单 - git 只读子命令(进程 cwd 已锁定在沙箱根, 操作对象即沙箱内仓库) */
    private static final Set<String> SAFE_GIT_SUB = Set.of(
            "status", "log", "diff", "branch", "remote");

    /** 白名单 - 仅查版本的无副作用命令(必须整条命令完全一致) */
    private static final Set<String> SAFE_VERSION_CMD = Set.of(
            "java -version", "mvn -v", "mvn -version", "mvn --version",
            "node -v", "node --version", "npm -v", "npm --version",
            "python -V", "python --version", "python3 -V", "python3 --version");

    /** shell 元字符/重定向/管道/引号/变量展开: 出现即视为无法静态判定安全, 需要确认 */
    private static final String META_CHARS = "<>|&;$`'\"()*?{}[]~\\";

    /** 危险特征(仅用于确认提示, 不直接拦截) */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(rm|mv|dd|mkfs|sudo|su|shutdown|reboot|halt|poweroff|kill|pkill|killall|"
                    + "chmod|chown|curl|wget|nc|ncat|netcat|telnet|ssh|scp|rsync|fdisk|parted|"
                    + "mount|umount|systemctl)\\b"
                    + "|/etc|/usr|/root|/home|/var|/dev/sd|\\.\\.|~");

    private SandboxGuard() {
    }

    // ------------------------------------------------------------------
    // 沙箱根目录与路径边界判定
    // ------------------------------------------------------------------

    private static Path initRoot() {
        String configured = System.getenv(ENV_ROOT);
        Path root = (configured == null || configured.isBlank())
                ? Paths.get(System.getProperty("user.dir"))
                : Paths.get(configured);
        try {
            Files.createDirectories(root); // 根目录是可信工作区, 不存在则创建
            return root.toRealPath().normalize();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /**
     * 把用户给出的路径解析到沙箱坐标系下并规范化。
     * 相对路径相对沙箱根解析(与 execute_command 的 cwd 一致), 绝对路径按原样。
     */
    public static Path resolve(String rawPath) {
        return canonicalize(ROOT.resolve(rawPath));
    }

    /** 真实路径规范化: 已存在的部分走 toRealPath(识别符号链接), 尾部缺失部分拼回。 */
    private static Path canonicalize(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path cursor = abs;
        Deque<Path> missing = new ArrayDeque<>();
        while (cursor != null && !Files.exists(cursor)) {
            missing.push(cursor.getFileName());
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return abs;
        }
        try {
            Path real = cursor.toRealPath();
            for (Path part : missing) {
                real = real.resolve(part);
            }
            return real.normalize();
        } catch (IOException e) {
            return abs;
        }
    }

    /** 目标是否落在沙箱根目录内(含根目录本身) */
    public static boolean isInside(Path target) {
        return canonicalize(target).startsWith(ROOT);
    }

    // ------------------------------------------------------------------
    // 命令白名单判定
    // ------------------------------------------------------------------

    /**
     * 命令是否命中"只读检查"白名单 —— 命中则无需用户确认即可放行。
     * 要求: 无 shell 元字符、无多行、命令形态安全、且任何形似路径的参数都不指向沙箱外。
     */
    public static boolean isWhitelistedCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String cmd = command.trim();
        if (cmd.contains("\n") || containsMetaChar(cmd)) {
            return false;
        }
        String[] tokens = cmd.split("\\s+");
        if (tokens.length == 0) {
            return false;
        }

        boolean safeShape;
        if (SAFE_SINGLE_WORD.contains(tokens[0])) {
            safeShape = true;
        } else if (tokens.length >= 2 && tokens[0].equals("git")
                && SAFE_GIT_SUB.contains(tokens[1])) {
            safeShape = true;
        } else if (SAFE_VERSION_CMD.contains(cmd)) {
            safeShape = true;
        } else {
            return false;
        }

        // 路径类参数不允许逃出沙箱: 绝对路径、~、..、或相对路径解析后落在根之外
        for (String token : tokens) {
            if (tokenEscapesSandbox(token)) {
                return false;
            }
        }
        return safeShape;
    }

    private static boolean containsMetaChar(String cmd) {
        for (int i = 0; i < META_CHARS.length(); i++) {
            if (cmd.indexOf(META_CHARS.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean tokenEscapesSandbox(String token) {
        if (token.startsWith("~") || token.equals("..") || token.startsWith("../")
                || token.contains("/../") || token.contains("/./")) {
            return true;
        }
        if (token.startsWith("/")) {
            // bash 的绝对路径(含 /c/... 等映射), 一律视为可能指向沙箱外
            return true;
        }
        if (token.contains("/")) {
            Path p = canonicalize(ROOT.resolve(token));
            return !p.startsWith(ROOT);
        }
        return false;
    }

    /** 是否存在危险/越界特征(仅用于提示) */
    public static boolean looksDangerous(String command) {
        return command != null && DANGEROUS.matcher(command).find();
    }

    // ------------------------------------------------------------------
    // 用户确认
    // ------------------------------------------------------------------

    /**
     * 请求用户确认一次越界/无法判定安全的操作。
     *
     * @param key         操作标识, "a" 选项按此 key 记忆(如 read_file:/etc/passwd、execute_command:rm -rf x)
     * @param description 展示给用户的操作描述
     * @param warn        是否附带危险特征警告
     * @return true = 放行, false = 拒绝
     */
    public static boolean askUser(String key, String description, boolean warn) {
        synchronized (ALWAYS_ALLOWED) {
            if (ALWAYS_ALLOWED.contains(key)) {
                return true;
            }
        }

        System.out.println();
        System.out.println("🛡️ 需要你的确认: 该操作不在沙箱内或无法判定为安全");
        System.out.println("   操作: " + description);
        if (warn) {
            System.out.println("   ⚠️ 检测到危险/越界特征, 请谨慎判断");
        }
        System.out.print("   请选择 [y] 同意一次 | [a] 本次会话同类不再询问 | [n] 拒绝(回车默认拒绝): ");
        System.out.flush();

        String answer;
        try (Scanner scanner = new Scanner(System.in)) {
            answer = scanner.hasNextLine() ? scanner.nextLine() : "";
        } catch (Exception e) {
            answer = "";
        }

        String choice = answer == null ? "" : answer.trim().toLowerCase();
        if (choice.equals("y") || choice.equals("yes")) {
            return true;
        }
        if (choice.equals("a") || choice.equals("always")) {
            synchronized (ALWAYS_ALLOWED) {
                ALWAYS_ALLOWED.add(key);
            }
            return true;
        }
        System.out.println("   ⛔ 已拒绝: " + description);
        return false;
    }

    /** 拒绝后返回给 LLM 的提示(让模型调整方案) */
    public static String deniedMessage(String description) {
        return "⛔ 操作被用户拒绝: " + description
                + "。请只在沙箱目录内(" + ROOT + ")操作, 或先向用户说明并获得同意。";
    }

    // ------------------------------------------------------------------
    // 其他访问器
    // ------------------------------------------------------------------

    public static Path root() {
        return ROOT;
    }

    public static long timeoutSeconds() {
        return TIMEOUT_SECONDS;
    }

    public static long outputLimitBytes() {
        return OUTPUT_LIMIT_BYTES;
    }

    public static void printStartupInfo() {
        System.out.println("📦 沙箱保护已启用");
        System.out.println("   沙箱根目录: " + ROOT);
        System.out.println("   规则: 目录内操作自动放行; 越界文件操作与白名单外的命令需要你确认");
        System.out.println("   环境变量: PAI_SANDBOX_DIR=沙箱目录 | PAI_CMD_TIMEOUT_SEC=命令超时秒(默认600) | "
                + "PAI_CMD_OUTPUT_LIMIT=输出字节上限(默认524288)");
        System.out.println();
    }

    private static long envLong(String name, long defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
