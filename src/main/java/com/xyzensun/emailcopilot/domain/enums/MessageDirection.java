package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 邮件方向：收到的与自己发出的都在 {@code message} 表，由本列区分（{@code DATABASE.md} §3.2.1）。
 *
 * <p>同表而非另建发信表的理由：<b>会话视图必须把用户的回复插回时间线</b>，
 * 否则整段往来只剩对方发言、看起来像自言自语；另建一张表则要在每个会话查询里 union 两张表。
 *
 * <p>{@code OUTBOUND} 的邮件<b>不进判定流水线</b>（不建 {@code processing_progress} 行，
 * 不分类、不判垃圾、不摘要、不翻译——那是用户自己写的内容），
 * <b>但必须进 Lucene 索引</b>，否则用户搜不到自己说过的话。
 */
public enum MessageDirection implements IEnum<String> {

    INBOUND("inbound"),
    OUTBOUND("outbound");

    private final String value;

    MessageDirection(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
