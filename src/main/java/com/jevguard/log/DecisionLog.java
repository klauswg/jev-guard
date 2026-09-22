package com.jevguard.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jevguard.domain.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 决策日志：JSONL 落盘 + 内存副本供 /v1/decisions 与 /v1/metrics 查询。 */
@Component
public class DecisionLog {

    private static final Logger log = LoggerFactory.getLogger(DecisionLog.class);
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path file;
    private final List<Decision> memory = new CopyOnWriteArrayList<>();

    public DecisionLog(@Value("${jevguard.decision-log}") String file) {
        this.file = Path.of(file);
    }

    public synchronized void append(Decision d) {
        memory.add(d);
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(om.writeValueAsString(d));
                w.newLine();
            }
        } catch (IOException e) {
            log.error("failed to write decision log", e);
        }
    }

    /** 最近 N 条，地址中段脱敏（PRD §4.6.2）。 */
    public List<Decision> recent(int limit) {
        int from = Math.max(0, memory.size() - limit);
        return new ArrayList<>(memory.subList(from, memory.size()));
    }

    public List<Decision> all() {
        return new ArrayList<>(memory);
    }

    public static String maskAddress(String addr) {
        if (addr == null || addr.length() <= 12) return addr;
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }
}
