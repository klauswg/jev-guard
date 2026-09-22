package com.jevguard.client;

import com.jevguard.domain.JevAnswers;

import java.util.Map;

public interface JevClient {
    /**
     * 调 Jev /v1/systemone。
     * @param state     渲染后的英文特征摘要
     * @param questions risk-rules.yml 里的 4 个问题 schema
     */
    JevAnswers evaluate(String state, Map<String, Object> questions);

    boolean isMock();
}
