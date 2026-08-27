package com.xyzensun.emailcopilot.infrastructure.mail;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 把原始 RFC 5322 流有界地写入 owner-only 临时文件，供 MIME4J 与 DKIM 各自重新读取。
 *
 * <p>限制在读取完整消息前执行，避免无界 {@code byte[]}；临时文件不是业务事实源，
 * {@link Spool#close()} 总会删除。文件名、路径和内容都不得写入日志。
 */
@Component
public class BoundedRawMessageSpool {

    public static final long MAX_RAW_MIME_BYTES = 50L * 1024L * 1024L;
    private static final String FILE_PREFIX = "email-copilot-raw-";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    public Spool copyFrom(InputStream source)
            throws IOException, MessageContentRejectedException {
        if (source == null) {
            throw new IllegalArgumentException("原始 MIME 流不能为空");
        }
        Path path = createOwnerOnlyTempFile();
        boolean completed = false;
        long bytesWritten = 0;
        try (OutputStream output = Files.newOutputStream(path)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (bytesWritten + read > MAX_RAW_MIME_BYTES) {
                    throw new MessageContentRejectedException(
                            "RAW_MIME_TOO_LARGE", "原始 MIME 超过 50 MiB 安全上限");
                }
                output.write(buffer, 0, read);
                bytesWritten += read;
            }
            completed = true;
            return new Spool(path, bytesWritten);
        } finally {
            if (!completed) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path createOwnerOnlyTempFile() throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            return Files.createTempFile(
                    FILE_PREFIX, ".eml",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly));
        } catch (UnsupportedOperationException ex) {
            Path path = Files.createTempFile(FILE_PREFIX, ".eml");
            // 非 POSIX 文件系统只作为开发 fallback；Java API 尽量收紧到 owner 读写。
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
            return path;
        }
    }

    public static final class Spool implements AutoCloseable {

        private final Path path;
        private final long sizeBytes;
        private boolean closed;

        private Spool(Path path, long sizeBytes) {
            this.path = path;
            this.sizeBytes = sizeBytes;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        public InputStream openStream() throws IOException {
            if (closed) {
                throw new IllegalStateException("原始 MIME spool 已关闭");
            }
            return Files.newInputStream(path);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            Files.deleteIfExists(path);
        }

        @Override
        public String toString() {
            return "BoundedRawMessageSpool.Spool[sizeBytes=" + sizeBytes + ", path=<已隐藏>]";
        }
    }
}
