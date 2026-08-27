package com.xyzensun.emailcopilot.domain.mail;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 一次 mailbox 同步的不可变 UID 快照计划。
 *
 * <p>{@code UIDNEXT} 只界定本轮开始时已经存在的 UID 上界，不会直接成为完成水位。
 * 只有候选消息逐封达到终态后，应用服务才可把 cursor 推到 {@link #snapshotUpperUid}。
 * UID 空洞天然可越过，因为计划只对服务器实际返回的 UID 排序。
 */
public record ImapUidSyncPlan(
        boolean uidValidityResetRequired,
        boolean bootstrap,
        long firstUid,
        long snapshotUpperUid,
        OffsetDateTime initialSyncSince) {

    public static final long MAX_UID = 4_294_967_295L;
    public static final long UNKNOWN_UID_NEXT = -1L;

    public ImapUidSyncPlan {
        if (firstUid < 1 || firstUid > MAX_UID + 1) {
            throw new IllegalArgumentException("firstUid 超出 IMAP UID 范围");
        }
        if (snapshotUpperUid < 0 || snapshotUpperUid > MAX_UID) {
            throw new IllegalArgumentException("snapshotUpperUid 超出 IMAP UID 范围");
        }
        if (initialSyncSince == null) {
            throw new IllegalArgumentException("initialSyncSince 不能为空");
        }
    }

    /**
     * @param persistedUidValidity cursor 当前 UIDVALIDITY
     * @param persistedLastSeenUid 已完成水位
     * @param currentUidValidity mailbox 当前 UIDVALIDITY
     * @param uidNext 打开 mailbox 时读取的 UIDNEXT；未知传 {@link #UNKNOWN_UID_NEXT}
     * @param lastExistingUid UIDNEXT 未知时，打开瞬间最后一封消息的 UID；空 mailbox 传 0
     */
    public static ImapUidSyncPlan create(
            long persistedUidValidity,
            long persistedLastSeenUid,
            OffsetDateTime initialSyncSince,
            long currentUidValidity,
            long uidNext,
            long lastExistingUid) {
        requireUidValidity(persistedUidValidity, "persistedUidValidity");
        requireUidValidity(currentUidValidity, "currentUidValidity");
        requireUid(persistedLastSeenUid, true, "persistedLastSeenUid");
        requireUid(lastExistingUid, true, "lastExistingUid");

        boolean resetRequired = persistedUidValidity != currentUidValidity;
        long effectiveLastSeenUid = resetRequired ? 0 : persistedLastSeenUid;
        long upperUid = snapshotUpperUid(uidNext, lastExistingUid);
        long firstUid = Math.min(MAX_UID + 1, effectiveLastSeenUid + 1);
        return new ImapUidSyncPlan(
                resetRequired,
                effectiveLastSeenUid == 0,
                firstUid,
                upperUid,
                initialSyncSince);
    }

    /** UIDNEXT 未知时只使用打开 mailbox 同一快照里的最后实际 UID。 */
    public static long snapshotUpperUid(long uidNext, long lastExistingUid) {
        requireUid(lastExistingUid, true, "lastExistingUid");
        if (uidNext == UNKNOWN_UID_NEXT) {
            return lastExistingUid;
        }
        if (uidNext < 1 || uidNext > MAX_UID + 1) {
            throw new IllegalArgumentException("uidNext 超出 IMAP UID 范围");
        }
        return Math.min(MAX_UID, uidNext - 1);
    }

    /** 空 mailbox 或本轮没有新 UID 时无需 FETCH，也无需伪造一次推进。 */
    public boolean hasUidRangeToInspect() {
        return firstUid <= snapshotUpperUid;
    }

    /**
     * 对服务器实际返回的 metadata 做精确窗口过滤。IMAP SINCE 只有日期粒度，
     * {@code INTERNALDATE >= initialSyncSince} 的最终边界必须由代码再次判断。
     * INTERNALDATE 缺失是确定性内容拒绝，但仍保留在结果中交由调用方记录终态。
     */
    public List<MessageCandidate> selectCandidates(Collection<MessageCandidate> serverMessages) {
        if (serverMessages == null || serverMessages.isEmpty()) {
            return List.of();
        }
        return serverMessages.stream()
                .filter(candidate -> candidate.uid() >= firstUid)
                .filter(candidate -> candidate.uid() <= snapshotUpperUid)
                .filter(candidate -> !bootstrap
                        || candidate.internalDate() == null
                        || !candidate.internalDate().isBefore(initialSyncSince))
                .sorted(Comparator.comparingLong(MessageCandidate::uid))
                .toList();
    }

    /** 当前计划中的一个服务器实际 UID；internalDate 为 null 时由同步器确定性拒绝。 */
    public record MessageCandidate(long uid, OffsetDateTime internalDate) {

        public MessageCandidate {
            requireUid(uid, false, "uid");
        }
    }

    private static void requireUidValidity(long value, String field) {
        if (value < 1 || value > MAX_UID) {
            throw new IllegalArgumentException(field + " 超出 IMAP UIDVALIDITY 范围");
        }
    }

    private static void requireUid(long value, boolean allowZero, String field) {
        long minimum = allowZero ? 0 : 1;
        if (value < minimum || value > MAX_UID) {
            throw new IllegalArgumentException(field + " 超出 IMAP UID 范围");
        }
    }
}
