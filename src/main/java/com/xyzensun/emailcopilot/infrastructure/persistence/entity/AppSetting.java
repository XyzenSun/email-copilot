package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.AiProvider;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * <b>单行表</b>，装用户可在界面上调的 26 个参数（{@code DATABASE.md} §8.5）。
 * 护栏 13 项 + AI 连接 5 项 + 垃圾评分策略 2 项 + 流水线开关 5 项 + 独立会话摘要开关 1 项。
 *
 * <p><b>为什么一列一个参数而不是 key/value 两列</b>：key-value 表里所有值都是 text，
 * {@code searchResultLimit = -5} 这种非法值数据库拦不住（它只看到两个字符），
 * 要等到真去检索时才炸；而 typed column + check 约束当场拒绝。
 * 代价是加参数要改表结构——但这套参数是封闭集合、变化极慢。
 *
 * <p>{@code check (id = 1)} 是单行守卫：没有它，一次 INSERT 失误就会出现两行配置，
 * <b>而代码不会报错，只会随机读到一行</b>。行由 migration 插入，
 * 接口只有 UPDATE，没有 POST/DELETE。
 *
 * <p><b>改动的生效时机不统一</b>：
 * <table border="1">
 *   <caption>各参数生效范围</caption>
 *   <tr><th>参数</th><th>生效范围</th></tr>
 *   <tr><td>{@link #pendingActionTtlHours}</td><td>只影响新建的提案，已存在的行 expiresAt 不重算</td></tr>
 *   <tr><td>{@link #turnTimeoutSeconds}</td><td>立即作用于正在跑的 Turn（不存 deadlineAt）</td></tr>
 *   <tr><td>{@link #threadSizeLimit}</td><td>下一次归并生效，不回溯拆分已超限的会话</td></tr>
 *   <tr><td>{@link #initialSyncDays}</td><td>只影响此后新加入的账号的首次同步</td></tr>
 *   <tr><td>{@link #aiContextWindowK}</td><td>下一轮提问时生效，不回溯重压已有对话</td></tr>
 *   <tr><td>其余</td><td>下一次读取时生效</td></tr>
 * </table>
 *
 * <p>不放进本表的配置：加密主密钥（环境变量）、数据库连接、AI API key
 * （凭据，归 {@link ExternalAccountSecret}）。
 */
@Data
@TableName("app_setting")
public class AppSetting {

    /** 单行守卫，恒为 1。由 migration 插入，接口只 UPDATE。 */
    @TableId(type = IdType.INPUT)
    private Short id;

    // ── 护栏参数 9 项 ───────────────────────────────────────

    /** 首次接入的回溯天数，1..365，默认 7。 */
    private Integer initialSyncDays;

    /** 单会话规模上限，10..10000，默认 100。库本身无上限，这是归并前的产品守卫。 */
    private Integer threadSizeLimit;

    /** 流水线阶段重试上限，0..20，默认 3。 */
    private Integer processingRetryLimit;

    /** 单次检索返回上限，1..200，默认 20。 */
    private Integer searchResultLimit;

    /**
     * 单个 Turn 的模型调用上限，1..50，默认 10。
     *
     * <p>Spring AI 的工具循环<b>无内置硬上限</b>（spike 结论），全靠这个参数兜住。
     */
    private Integer turnModelCallLimit;

    /** 整轮超时秒数，10..1800，默认 120。 */
    private Integer turnTimeoutSeconds;

    /** 提案有效期小时数，1..720，默认 24。 */
    private Integer pendingActionTtlHours;

    /**
     * 单次工具执行超时，<b>5</b>..300，默认 20。
     *
     * <p>调太小会让 IMAP 检索一类的慢工具永远超时，
     * 表现为"AI 什么都查不到"而不是报错。界面必须给出警告。
     */
    private Integer toolTimeoutSeconds;

    /**
     * SMTP 提交超时，<b>5</b>..300，默认 20。
     *
     * <p><b>这是用户能给自己造出的最坑的一种配置</b>：调太小会让正常发信被判
     * indeterminate，而结果不确定的邮件<b>不入库</b>——于是信实际发出去了、
     * 系统里没有记录、用户以为没发又发一遍。界面必须给出警告。
     */
    private Integer smtpTimeoutSeconds;

    // ── 邮件生命周期自动化 4 项（阶段15）────────────────────
    // 定时同步与自动清理的护栏；关 auto_sync_enabled / auto_delete_enabled 即停全部自动化。
    // interval 下限 30 秒防打爆 IMAP；retention 下限 1 天防误删近期（真删整行不可逆）。

    /** 定时 IMAP 同步总开关；关闭后仅手动同步触发，默认开。 */
    private Boolean autoSyncEnabled;

    /** 定时 IMAP 同步最小间隔（秒），30..3600，默认 60。 */
    private Integer imapSyncIntervalSeconds;

    /** 过期 inbound 邮件自动删除开关；outbound 与草稿不删，默认开。 */
    private Boolean autoDeleteEnabled;

    /** inbound 邮件保留天数，1..3650，默认 30；真删整行不可逆。 */
    private Integer messageRetentionDays;

    // ── AI 连接 5 项（API key 是凭据，不在本表）──────────────

    private AiProvider aiProvider;

    /** 自定义端点；null 表示用该 provider 的官方地址。 */
    private String aiBaseUrl;

    /**
     * AI 型号名；<b>null 表示未配置</b>，AI 功能整体不可用。
     *
     * <p>未配置<b>不是错误状态</b>，是首次部署时的正常状态：
     * 此时流水线跳过全部 AI 阶段照常入库（AI 不可用不阻塞收信），
     * 对话接口返回 {@code AI_NOT_CONFIGURED}。
     */
    private String aiModel;

    /**
     * 模型上下文窗口，<b>单位 k</b>（128 = 128000 token），4..2000，默认 128。
     *
     * <p>跟着型号走，换型号要改它。<b>系统不会根据型号名自动填这个数</b>：
     * 型号名是一个可以随便填的字符串（用户可能连的是自建服务或代理），
     * 系统无从知道它对应多大窗口。
     *
     * <p>单位取 k 是因为用户查到的窗口大小本来就是"128k""200k"这种写法，
     * 让他填 131072 只会平白多一次换算出错的机会。
     */
    private Integer aiContextWindowK;

    /** 单次 AI HTTP 调用超时，5..600，默认 60。作用在 HTTP 客户端层，随 reload 生效。 */
    private Integer aiTimeoutSeconds;

    // ── 垃圾评分策略 2 项 ───────────────────────────────────

    /** spamScore 大于或等于此值时由代码写入 spam 分类，0..1，默认 0.800。 */
    private BigDecimal spamClassificationThreshold;

    /** 用户可编辑的评分政策；固定安全 wrapper 与 JSON schema 不存入该字段。 */
    private String spamJudgmentPrompt;

    // ── 五个 AI 流水线开关 + 一个独立会话摘要开关 ─────────────
    // 五个流水线开关都关掉 = 只收信入库、不花任何流水线模型调用。
    // 自动分类与自动标签分别控制；语言判断与翻译共用一个开关。
    // 会话摘要不是流水线阶段，点开会话时现算，关闭时对应接口返回 409。
    // 开关不回溯：改开关不触发任何补跑，已打好的标签、已写好的摘要都留在库里。

    /** 关掉后只有屏蔽规则命中的才判 spam，AI 不再做垃圾判断，spamScore 为 null。 */
    private Boolean aiSpamCheckEnabled;

    /** 关掉后 category 保持 null，列表的分类筛选选什么都是空。 */
    private Boolean aiClassifyEnabled;

    /** 关掉后标签只能手动打。 */
    private Boolean aiTaggingEnabled;

    /** 关掉后确定性语言判断与非中文正文翻译都跳过，只显示原文。 */
    private Boolean aiLanguageTranslationEnabled;

    /** 关掉后 message.summary 为 null，列表 snippet 回退到正文前 120 字。 */
    private Boolean aiSummaryEnabled;

    /** 关掉后会话页不再显示 AI 摘要块，对应接口返回 409。 */
    private Boolean aiThreadSummaryEnabled;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @Override
    public String toString() {
        return "AppSetting[id=" + id
                + ", aiProvider=" + aiProvider
                + ", aiModel=" + aiModel
                + ", spamClassificationThreshold=" + spamClassificationThreshold
                + ", spamJudgmentPrompt=<已隐藏>"
                + ", updatedAt=" + updatedAt + "]";
    }
}
