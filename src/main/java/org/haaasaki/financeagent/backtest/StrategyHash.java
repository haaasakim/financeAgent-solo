package org.haaasaki.financeagent.backtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;

/**
 * 策略定义 hash：条件树规范化 JSON 的 SHA-256，作为回测复现依据。
 */
public final class StrategyHash {

    private StrategyHash() {
    }

    public static String of(ScreenerDsl dsl, ObjectMapper objectMapper) {
        try {
            String json = objectMapper.writeValueAsString(dsl);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("策略序列化失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
