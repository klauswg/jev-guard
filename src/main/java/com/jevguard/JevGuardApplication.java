package com.jevguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class JevGuardApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(JevGuardApplication.class, args);
    }

    /** 读取工作目录 .env（不存在则跳过），注入为 system properties，不覆盖已有环境变量。 */
    private static void loadDotEnv() {
        Path env = Path.of(".env");
        if (!Files.exists(env)) return;
        try {
            for (String line : Files.readAllLines(env)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int i = line.indexOf('=');
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if (System.getenv(k) == null && System.getProperty(k) == null) {
                    System.setProperty(k, v);
                }
            }
        } catch (IOException ignored) {
        }
    }
}
