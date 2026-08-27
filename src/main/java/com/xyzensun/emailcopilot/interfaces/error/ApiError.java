package com.xyzensun.emailcopilot.interfaces.error;

import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Locale;

/**
 * 全部 API 错误码（{@code API.md} §4）。
 *
 * <p><b>这份清单是封闭的</b>：新增错误必须先在 {@code API.md} §4 登记，不允许在代码里临时拼字符串。
 * 因此这里一次登记全部 43 个，而不是各阶段实现到哪个就加哪个——后者会让实现某个接口时发现
 * 「这个码还没登记」而顺手拼一个，清单封闭的意义就没了，前端也没法照 {@code code} 分支。
 * 枚举常量不产生「功能已实现」的假象，它只是一张常量表。
 *
 * <p>三个字段的分工（{@code API.md} §3）：
 * <ul>
 *   <li>{@link #name()} 即 {@code code}，<b>前端唯一应据以分支的字段</b>；
 *   <li>{@link #title()} 是给人看的中文短句，<b>可改文案，前端不得据此判断</b>；
 *   <li>{@link #type()} 由 {@code code} 机械派生，不手写第二遍。
 * </ul>
 *
 * <p>派生规则：{@code code} 转小写 → 下划线转连字符 → 前缀 {@code /problems/}。
 * <pre>
 * PENDING_ACTION_EXPIRED  →  /problems/pending-action-expired
 * TURN_ALREADY_RUNNING    →  /problems/turn-already-running
 * </pre>
 * {@code type} 与 {@code code} 承载同一信息，但派生而非手写，<b>不存在两处写法不同步的风险</b>。
 * {@code /problems/*} 是标识符而非可访问文档页——RFC 9457 明确允许 {@code type} 不可解引用，
 * 本项目不托管这些路径。
 *
 * <p><b>409 与 422 的分界</b>：422 是请求本身不合法（改请求即可），
 * 409 是请求合法但与当前状态冲突（改状态或重新发起）。409 这一组基本是数据库唯一约束与
 * CAS 条件更新失败的映射。
 *
 * <p><b>执行失败不用错误码</b>（{@code API.md} §2.5）：批准审批、直接发信、测试连接三处返回
 * HTTP 200 + {@code status} 字段。4xx/5xx 只用于「这个动作根本没能进行」。
 * 若把执行失败也返回错误码，前端会以为没生效而重试——而批准是一次一用、已被消费。
 */
public enum ApiError {

    // ── 401 未认证（API.md §4.1）─────────────────────────────────────────
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "未登录或会话已失效"),
    /** 不区分「用户名不存在」与「口令错误」，避免枚举账号。 */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或口令错误"),

    // ── 403 禁止（API.md §4.2）───────────────────────────────────────────
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "CSRF 令牌缺失或不匹配"),

    // ── 404 不存在（API.md §4.3）─────────────────────────────────────────
    // 已软删除的邮件对读接口一律视为 404（deleted_at IS NOT NULL 不再出现在任何列表、检索与详情中）。
    MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "邮件不存在"),
    THREAD_NOT_FOUND(HttpStatus.NOT_FOUND, "会话不存在"),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "对话不存在"),
    TURN_NOT_FOUND(HttpStatus.NOT_FOUND, "对话轮次不存在"),
    PENDING_ACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "待审批操作不存在"),
    DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "草稿不存在"),
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "标签不存在"),
    MAIL_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "邮箱账号不存在"),
    SENDER_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "发件人规则不存在"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "维护任务不存在或已随重启丢失"),

    // ── 400 / 422 请求无效（API.md §4.4）────────────────────────────────
    /** 唯一带 {@code errors} 扩展成员的错误码，见 {@link ValidationErrorItem}。 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    INVALID_TAG_NAME(HttpStatus.UNPROCESSABLE_ENTITY, "标签标识只允许英文字母与数字"),
    INVALID_DOMAIN_PATTERN(HttpStatus.UNPROCESSABLE_ENTITY, "域名模式非法（支持精准域名、* 与 + 通配，不支持正则）"),
    INVALID_RECIPIENT_ADDRESS(HttpStatus.UNPROCESSABLE_ENTITY, "收件人地址格式非法"),
    TAG_NAME_IMMUTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "标签标识创建后不可修改"),
    DRAFT_ORIGIN_IMMUTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "草稿的来源关联（回复哪封、属于哪次对话）不可修改"),
    SMTP_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_ENTITY, "该邮箱账号未配置发信通道"),
    SECRET_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "启用该通道前必须先配置对应凭据"),
    GUARDRAIL_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "护栏参数超出允许范围"),
    AI_SETTINGS_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "AI 连接配置非法（端点不是合法 URL、超时越界等）"),
    PENDING_ACTION_CONTENT_IMMUTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "审批快照不可编辑"),
    MESSAGE_NOT_INBOUND(HttpStatus.UNPROCESSABLE_ENTITY, "目标邮件是已发送邮件，不可重新处理"),

    // ── 409 冲突（API.md §4.5）───────────────────────────────────────────
    PENDING_ACTION_ALREADY_DECIDED(HttpStatus.CONFLICT, "该操作已被决定"),
    PENDING_ACTION_EXPIRED(HttpStatus.CONFLICT, "待审批操作已过期"),
    TURN_ALREADY_RUNNING(HttpStatus.CONFLICT, "当前对话还有一轮进行中"),
    TURN_NOT_RUNNING(HttpStatus.CONFLICT, "该轮次已结束"),
    THREAD_SIZE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "会话规模超过上限"),
    MAIL_ACCOUNT_ADDRESS_TAKEN(HttpStatus.CONFLICT, "该邮箱地址已登记"),
    TAG_NAME_TAKEN(HttpStatus.CONFLICT, "标签标识已存在"),
    SENDER_RULE_DUPLICATE(HttpStatus.CONFLICT, "同类型同模式的规则已存在"),
    MESSAGE_ALREADY_DELETED(HttpStatus.CONFLICT, "邮件已删除"),
    MAIL_ACCOUNT_NOT_DISABLED(HttpStatus.CONFLICT, "请先停用该邮箱账号的全部通道"),
    SYNC_ALREADY_RUNNING(HttpStatus.CONFLICT, "该账号已有同步任务在进行"),
    MAINTENANCE_TASK_RUNNING(HttpStatus.CONFLICT, "已有维护任务在进行"),
    THREAD_SUMMARY_DISABLED(HttpStatus.CONFLICT, "会话摘要功能已关闭"),
    AI_NOT_CONFIGURED(HttpStatus.CONFLICT, "AI 尚未配置"),
    MESSAGE_REPROCESS_BUSY(HttpStatus.CONFLICT, "该邮件正在处理中"),
    MESSAGE_SPAM_RECLASSIFY_FORBIDDEN(HttpStatus.CONFLICT, "垃圾邮件不可重新分类，请改走垃圾评分"),

    // ── 429 限流（API.md §4.6）───────────────────────────────────────────
    /** 计数在内存中，重启清零（{@code DATABASE.md} §8.1：不为限流建表）。 */
    LOGIN_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试"),

    // ── 503 依赖不可用（API.md §4.7）────────────────────────────────────
    // AI 不可用只影响对话与流水线判定接口，绝不阻塞收信入库。
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂时不可用"),
    SMTP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "发信服务暂时不可用"),
    IMAP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "收信服务暂时不可用"),

    // ── 500（API.md §4.8）───────────────────────────────────────────────
    /** {@code detail} 只给可公开的摘要，堆栈只进服务端日志。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");

    private static final String TYPE_PREFIX = "/problems/";

    private final HttpStatus status;
    private final String title;
    private final URI type;

    ApiError(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
        this.type = URI.create(TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return type;
    }

    /** {@code code} 就是枚举名，前端唯一应据以分支的字段。 */
    public String code() {
        return name();
    }
}
