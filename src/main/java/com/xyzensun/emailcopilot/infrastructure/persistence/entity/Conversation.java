package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 多轮上下文容器，<b>无运行状态机</b>（运行状态属于各 {@link Turn}）。
 * {@code DATABASE.md} §5.1。
 *
 * <p>系统不保存"给模型看的聊天记录"，每轮现场把已完成的 turn 拼出来。
 * 对话长了，拼出来的串迟早超过模型上限，到那时是<b>直接报错，不是优雅降级</b>——
 * 三个 {@code context} 前缀的列就是为此存在的。
 *
 * <p><b>清除上下文是记书签，不是删数据</b>：{@link #contextBaseTurnId} 记下当时最后一轮的 id，
 * 重建只从它之后开始，{@code turn} 一行不动——用户还要往回翻聊天记录，删掉就没了。
 * 所以"AI 记不记得"和"界面上看不看得到"是两回事：界面显示全部 turn，
 * 模型只拿到书签之后的，前端按 {@code inContext} 布尔把被排除的轮次灰显。
 *
 * <p>完整重建规则（{@code DATABASE.md} §5.2）：
 * <pre>
 * contextSummary（若非 null）
 *   + 该对话下 status=COMPLETED 且 id &gt; max(contextBaseTurnId,
 *                                          contextSummarizedUptoTurnId) 的 turn
 *     按 startedAt 升序拼 (userMessage, finalAnswer)
 *   + 本次的新问题
 * </pre>
 * <b>两个书签取较大者</b>：清除之后又发生过压缩、或压缩之后又被清除，
 * 都不该让更早的内容漏回来。
 */
@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 创建首轮时由代码截断 {@code userMessage} 填入（确定性，不额外消耗模型调用），
     * 用户可在前端重命名。not null 因此前端无需处理空标题回退。
     *
     * <p>对话行在<b>首轮提问的同一次请求里创建</b>——没有独立的"新建对话"接口，
     * 否则点了新对话又不提问会留下一堆无标题的空行。
     */
    private String title;

    /** 只是显示位，不是状态机。 */
    private Boolean archived;

    /** 上下文起点书签，逻辑引用 turn；null 表示从头取。用户点"清除上下文"时写。 */
    private Long contextBaseTurnId;

    /**
     * 已压缩轮次缩成的一段话，拼在重建结果最前面。
     *
     * <p>压缩产出的摘要<b>存下来不重算</b>：每次重新压一遍要多花一次模型调用，
     * 而且同一段内容压两次得到的话可能不一样，上下文会莫名其妙地漂。
     */
    private String contextSummary;

    /**
     * {@link #contextSummary} 覆盖到哪一轮为止，逻辑引用 turn。
     * 与 {@link #contextSummary} 同为 null 或同非 null（check 约束保证）。
     */
    private Long contextSummarizedUptoTurnId;

    /**
     * 上一轮实际用掉多少 token，0 = 还没有过成功调用（{@code DATABASE.md} §5.1.2）。
     *
     * <p>首轮不计算不判断直接发（那时没有任何真实数据可依据，而首轮上下文最短、
     * 最不可能撑爆）；之后优先写 {@code usage.prompt_tokens}（服务端算出来的精确值，
     * 误差不累积），provider 不返回 usage 时退化为字符数 ÷ 4、不分语言。
     *
     * <p><b>÷ 4 的已知代价</b>：中文一个汉字差不多就是一个 token，
     * 等于把中文对话用量低估约四倍——窗口 200k 时界面显示 140k 该压缩了，
     * 实际可能早过 500k 撞墙。主流端点都返回 usage，故不为此另加机制。
     */
    private Integer contextTokens;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
