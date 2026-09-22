package com.jevguard.eval;

import com.jevguard.domain.Action;
import com.jevguard.domain.TransferEvent;

import java.util.List;

/**
 * 一条标注样本（PRD §4.7.1）。
 * setupEvents：先于被标注事件进入特征窗口的历史事件（不标注、不评分）。
 * event：被标注事件，三列（规则基线 / Jev / 组合）都对它出动作。
 * expectedAction：作者标注的基准动作（映射规则见 SampleGenerator 类注释，
 * 生成器与标签同源——这是报告免责声明的一部分）。
 */
public record LabeledSample(
        String id,
        String category,        // pattern / boundary / injection
        String pattern,         // normal / structuring / rapid_in_out / mixer_linked / new_address_burst / dust_probe
        String note,
        List<TransferEvent> setupEvents,
        TransferEvent event,
        Action expectedAction
) {}
