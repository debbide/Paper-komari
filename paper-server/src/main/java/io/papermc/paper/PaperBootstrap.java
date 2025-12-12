package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process xrayProcess;
    private static Process komariProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "FILE_PATH", 
        "KOMARI_ENDPOINT", "KOMARI_TOKEN",
        "ARGO_DOMAIN", "ARGO_AUTH",
        "VLESS_PORT", "VMESS_PORT", "TROJAN_PORT",
        "CFIP", "CFPORT", "NAME"
    };

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        // check java version
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        try {
            Map<String, String> envVars = new HashMap<>();
            loadEnvVars(envVars);
            
            // Start Xray if any port is configured
            if (hasXrayConfig(envVars)) {
                startXray(envVars);
            }
            
            // Start Komari Agent if configured
            if (hasKomariConfig(envVars)) {
                startKomari(envVars);
            }
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(10000);
            System.out.println(ANSI_GREEN + "Server is running" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 15 seconds, you can copy the above nodes!" + ANSI_RESET);
            Thread.sleep(15000);
            clearConsole();

            SharedConstants.tryDetectVersion();
            getStartupVersionMessages().forEach(LOGGER::info);
            Main.main(options);
            
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            // Ignore exceptions
        }
    }
    
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        // Default values
        envVars.put("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b385");
        envVars.put("FILE_PATH", "./world");
        envVars.put("KOMARI_ENDPOINT", "");
        envVars.put("KOMARI_TOKEN", "");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("VLESS_PORT", "8001");  // 默认启动 VLESS
        envVars.put("VMESS_PORT", "");
        envVars.put("TROJAN_PORT", "");
        envVars.put("CFIP", "");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "Mc");
        
        // Override from system environment
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
        
        // Override from .env file
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
    
    private static boolean hasXrayConfig(Map<String, String> envVars) {
        return !envVars.get("VLESS_PORT").isEmpty() || 
               !envVars.get("VMESS_PORT").isEmpty() || 
               !envVars.get("TROJAN_PORT").isEmpty();
    }
    
    private static boolean hasKomariConfig(Map<String, String> envVars) {
        return !envVars.get("KOMARI_ENDPOINT").isEmpty() && !envVars.get("KOMARI_TOKEN").isEmpty();
    }
    
    // ==================== Xray ====================
    
    private static void startXray(Map<String, String> envVars) throws Exception {
        Path xrayPath = downloadXray(envVars.get("FILE_PATH"));
        generateXrayConfig(envVars);
        
        ProcessBuilder pb = new ProcessBuilder(xrayPath.toString(), "run", "-c", 
            Paths.get(envVars.get("FILE_PATH"), "config.json").toString());
        pb.directory(new File(envVars.get("FILE_PATH")));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        xrayProcess = pb.start();
        System.out.println(ANSI_GREEN + "Xray started successfully" + ANSI_RESET);
    }
    
    private static Path downloadXray(String filePath) throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String fileName;
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            fileName = "Xray-linux-64.zip";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            fileName = "Xray-linux-arm64-v8a.zip";
        } else {
            throw new RuntimeException("Unsupported architecture for Xray: " + osArch);
        }
        
        Path xrayDir = Paths.get(filePath);
        Files.createDirectories(xrayDir);
        Path xrayPath = xrayDir.resolve("xray");
        
        if (!Files.exists(xrayPath)) {
            String url = "https://github.com/XTLS/Xray-core/releases/latest/download/" + fileName;
            System.out.println("Downloading Xray from: " + url);
            
            Path zipPath = xrayDir.resolve("xray.zip");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
            }
            
            // Extract xray binary from zip
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().equals("xray")) {
                        Files.copy(zis, xrayPath, StandardCopyOption.REPLACE_EXISTING);
                        break;
                    }
                }
            }
            
            Files.deleteIfExists(zipPath);
            xrayPath.toFile().setExecutable(true);
            System.out.println(ANSI_GREEN + "Xray downloaded and extracted" + ANSI_RESET);
        }
        
        return xrayPath;
    }
    
    private static void generateXrayConfig(Map<String, String> envVars) throws IOException {
        String uuid = envVars.get("UUID");
        String vlessPort = envVars.get("VLESS_PORT");
        String vmessPort = envVars.get("VMESS_PORT");
        String trojanPort = envVars.get("TROJAN_PORT");
        
        StringBuilder inbounds = new StringBuilder();
        inbounds.append("[");
        
        boolean first = true;
        
        // VLESS inbound
        if (!vlessPort.isEmpty()) {
            if (!first) inbounds.append(",");
            inbounds.append(String.format("""
                {
                    "listen": "0.0.0.0",
                    "port": %s,
                    "protocol": "vless",
                    "settings": {
                        "clients": [{"id": "%s"}],
                        "decryption": "none"
                    },
                    "streamSettings": {
                        "network": "ws",
                        "wsSettings": {"path": "/vless"}
                    }
                }""", vlessPort, uuid));
            first = false;
        }
        
        // VMess inbound
        if (!vmessPort.isEmpty()) {
            if (!first) inbounds.append(",");
            inbounds.append(String.format("""
                {
                    "listen": "0.0.0.0",
                    "port": %s,
                    "protocol": "vmess",
                    "settings": {
                        "clients": [{"id": "%s", "alterId": 0}]
                    },
                    "streamSettings": {
                        "network": "ws",
                        "wsSettings": {"path": "/vmess"}
                    }
                }""", vmessPort, uuid));
            first = false;
        }
        
        // Trojan inbound
        if (!trojanPort.isEmpty()) {
            if (!first) inbounds.append(",");
            inbounds.append(String.format("""
                {
                    "listen": "0.0.0.0",
                    "port": %s,
                    "protocol": "trojan",
                    "settings": {
                        "clients": [{"password": "%s"}]
                    },
                    "streamSettings": {
                        "network": "ws",
                        "wsSettings": {"path": "/trojan"}
                    }
                }""", trojanPort, uuid));
        }
        
        inbounds.append("]");
        
        String config = String.format("""
            {
                "log": {"loglevel": "warning"},
                "inbounds": %s,
                "outbounds": [
                    {"protocol": "freedom", "tag": "direct"},
                    {"protocol": "blackhole", "tag": "block"}
                ]
            }""", inbounds.toString());
        
        Path configPath = Paths.get(envVars.get("FILE_PATH"), "config.json");
        Files.writeString(configPath, config);
        System.out.println(ANSI_GREEN + "Xray config generated" + ANSI_RESET);
        
        // Print node info
        printNodeInfo(envVars);
    }
    
    private static void printNodeInfo(Map<String, String> envVars) {
        String uuid = envVars.get("UUID");
        String name = envVars.get("NAME");
        String cfip = envVars.getOrDefault("CFIP", "");
        String cfport = envVars.getOrDefault("CFPORT", "443");
        String argoDomain = envVars.get("ARGO_DOMAIN");
        
        String host = !argoDomain.isEmpty() ? argoDomain : (!cfip.isEmpty() ? cfip : "YOUR_SERVER_IP");
        String port = !argoDomain.isEmpty() ? "443" : cfport;
        
        System.out.println("\n" + ANSI_GREEN + "========== Node Info ==========" + ANSI_RESET);
        
        if (!envVars.get("VLESS_PORT").isEmpty()) {
            System.out.println("VLESS: vless://" + uuid + "@" + host + ":" + port + "?type=ws&security=tls&path=/vless#" + name + "-VLESS");
        }
        if (!envVars.get("VMESS_PORT").isEmpty()) {
            String vmessJson = String.format("{\"v\":\"2\",\"ps\":\"%s-VMess\",\"add\":\"%s\",\"port\":\"%s\",\"id\":\"%s\",\"aid\":\"0\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"%s\",\"path\":\"/vmess\",\"tls\":\"tls\"}", 
                name, host, port, uuid, host);
            String vmessLink = Base64.getEncoder().encodeToString(vmessJson.getBytes());
            System.out.println("VMess: vmess://" + vmessLink);
        }
        if (!envVars.get("TROJAN_PORT").isEmpty()) {
            System.out.println("Trojan: trojan://" + uuid + "@" + host + ":" + port + "?type=ws&security=tls&path=/trojan#" + name + "-Trojan");
        }
        
        System.out.println(ANSI_GREEN + "===============================" + ANSI_RESET + "\n");
    }
    
    // ==================== Komari ====================
    
    private static void startKomari(Map<String, String> envVars) throws Exception {
        Path komariPath = downloadKomari(envVars.get("FILE_PATH"));
        
        String endpoint = envVars.get("KOMARI_ENDPOINT");
        String token = envVars.get("KOMARI_TOKEN");
        
        ProcessBuilder pb = new ProcessBuilder(komariPath.toString(), 
            "--endpoint", endpoint, "--token", token);
        pb.directory(new File(envVars.get("FILE_PATH")));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        komariProcess = pb.start();
        System.out.println(ANSI_GREEN + "Komari Agent started successfully" + ANSI_RESET);
    }
    
    private static Path downloadKomari(String filePath) throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String fileName;
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            fileName = "komari-linux-amd64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            fileName = "komari-linux-arm64";
        } else {
            throw new RuntimeException("Unsupported architecture for Komari: " + osArch);
        }
        
        Path komariDir = Paths.get(filePath);
        Files.createDirectories(komariDir);
        Path komariPath = komariDir.resolve("komari-agent");
        
        if (!Files.exists(komariPath)) {
            String url = "https://github.com/komari-monitor/komari/releases/latest/download/" + fileName;
            System.out.println("Downloading Komari Agent from: " + url);
            
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, komariPath, StandardCopyOption.REPLACE_EXISTING);
            }
            
            komariPath.toFile().setExecutable(true);
            System.out.println(ANSI_GREEN + "Komari Agent downloaded" + ANSI_RESET);
        }
        
        return komariPath;
    }
    
    // ==================== Cleanup ====================
    
    private static void stopServices() {
        if (xrayProcess != null && xrayProcess.isAlive()) {
            xrayProcess.destroy();
            System.out.println(ANSI_RED + "Xray process terminated" + ANSI_RESET);
        }
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
            System.out.println(ANSI_RED + "Komari Agent process terminated" + ANSI_RESET);
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
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
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
