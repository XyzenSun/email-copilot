package com.xyzensun.emailcopilot.infrastructure.mail;

import com.xyzensun.emailcopilot.domain.AttachmentMeta;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.domain.mail.BaseSubject;
import com.xyzensun.emailcopilot.domain.mail.ParsedInboundMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.dom.address.AddressList;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.dom.field.AddressListField;
import org.apache.james.mime4j.dom.field.DateTimeField;
import org.apache.james.mime4j.dom.field.MailboxListField;
import org.apache.james.mime4j.dom.field.ParsedField;
import org.apache.james.mime4j.dom.field.UnstructuredField;
import org.apache.james.mime4j.field.LenientFieldParser;
import org.apache.james.mime4j.parser.AbstractContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 使用 MIME4J token stream 解析原始邮件，并在读取解码正文/附件时执行固定资源上限。
 *
 * <p>正文只输出纯文本；HTML 通过 jsoup 离线解析，绝不调用 {@code Jsoup.connect}。
 * 附件字节只流式计数、不保留。结构无法可靠确定时整封确定性拒绝，调用方可把 UID 视为终态。
 */
@Component
public class MimeMessageParser {

    public static final int MAX_HEADER_COUNT = 1_000;
    public static final int MAX_HEADER_LENGTH = 64 * 1024;
    public static final int MAX_LINE_LENGTH = 1024 * 1024;
    public static final int MAX_NESTING_DEPTH = 30;
    public static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ATTACHMENT_COUNT = 200;
    public static final int MAX_FILENAME_LENGTH = 1_024;
    private static final int MAX_REFERENCES = 10_000;
    private static final int MAX_MESSAGE_ID_LENGTH = 998;

    private static final MimeConfig MIME_CONFIG = MimeConfig.custom()
            .setStrictParsing(true)
            .setMaxLineLen(MAX_LINE_LENGTH)
            .setMaxHeaderCount(MAX_HEADER_COUNT)
            .setMaxHeaderLen(MAX_HEADER_LENGTH)
            .setMaxContentLen(BoundedRawMessageSpool.MAX_RAW_MIME_BYTES)
            .build();

    public ParsedInboundMessage parse(InputStream rawMessage)
            throws MessageContentRejectedException {
        return parse(rawMessage, null);
    }

    /**
     * authenticatedFromDomain 仅供已完成独立 DKIM 校验的调用方合并结果；通常先调用
     * {@link #parse(InputStream)} 得到 From 域，再由同一 spool 进行 DNS 校验。
     */
    public ParsedInboundMessage parse(
            InputStream rawMessage,
            String authenticatedFromDomain)
            throws MessageContentRejectedException {
        ParsingContentHandler handler = new ParsingContentHandler();
        MimeStreamParser parser = new MimeStreamParser(MIME_CONFIG);
        parser.setContentDecoding(true);
        parser.setRecurse();
        parser.setContentHandler(handler);
        try {
            parser.parse(rawMessage);
            return handler.toParsedMessage(authenticatedFromDomain);
        } catch (MessageContentRuntimeException ex) {
            throw ex.rejection();
        } catch (MimeException | IOException | RuntimeException ex) {
            throw rejection("MIME_MALFORMED", "MIME 结构或传输编码无法安全解析");
        }
    }

    private static final class ParsingContentHandler extends AbstractContentHandler {

        private final Deque<PartState> partStack = new ArrayDeque<>();
        private final List<AttachmentMeta> attachments = new ArrayList<>();
        private final List<String> plainTextParts = new ArrayList<>();
        private final List<String> htmlTextParts = new ArrayList<>();

        private HeaderState rootHeaders;
        private HeaderState currentHeaders;
        private int nestingDepth;
        private long accumulatedTextBytes;

        @Override
        public void startMessage() {
            // MIME4J 会紧接着单独回调 startHeader；此处只维护结构深度，不能提前创建空 header。
            enterNestedEntity();
        }

        @Override
        public void endMessage() {
            leaveNestedEntity();
        }

        @Override
        public void startBodyPart() {
            // 与 startMessage 相同，header 生命周期由 parser 自己发出的事件驱动。
            enterNestedEntity();
        }

        @Override
        public void endBodyPart() {
            if (!partStack.isEmpty()) {
                partStack.pop();
            }
            leaveNestedEntity();
        }

        @Override
        public void startHeader() {
            currentHeaders = new HeaderState();
            if (rootHeaders == null) {
                rootHeaders = currentHeaders;
            }
        }

        @Override
        public void field(Field field) {
            if (field.getSafeRaw().length() > MAX_HEADER_LENGTH) {
                fail("MIME_HEADER_TOO_LONG", "单个 MIME header 超过 64 KiB 安全上限");
            }
            currentHeaders.add(field);
        }

        @Override
        public void endHeader() {
            partStack.push(new PartState(currentHeaders));
        }

        @Override
        public void startMultipart(BodyDescriptor bodyDescriptor) {
            // multipart 自身不产生正文，嵌套深度由 body part/message 事件统一计算。
        }

        @Override
        public void body(BodyDescriptor descriptor, InputStream inputStream) throws IOException {
            PartState part = partStack.peek();
            HeaderState headers = part == null ? new HeaderState() : part.headers();
            String mimeType = descriptor.getMimeType() == null
                    ? "application/octet-stream"
                    : descriptor.getMimeType().toLowerCase(Locale.ROOT);
            String filename = headers.filename();
            boolean attachment = filename != null || headers.attachmentDisposition();
            if (attachment) {
                readAttachment(inputStream, filename, mimeType);
                return;
            }

            if ("text/plain".equals(mimeType) || "text/html".equals(mimeType)) {
                String decodedText = decodeText(inputStream, descriptor.getCharset());
                if ("text/plain".equals(mimeType)) {
                    plainTextParts.add(decodedText);
                } else {
                    htmlTextParts.add(htmlToPlainText(decodedText));
                }
            } else {
                drain(inputStream);
            }
        }

        private void readAttachment(InputStream input, String filename, String mimeType)
                throws IOException {
            if (attachments.size() >= MAX_ATTACHMENT_COUNT) {
                fail("MIME_TOO_MANY_ATTACHMENTS", "附件数量超过 200 个安全上限");
            }
            String safeFilename = filename == null || filename.isBlank() ? "unnamed" : filename;
            if (safeFilename.length() > MAX_FILENAME_LENGTH) {
                fail("MIME_FILENAME_TOO_LONG", "附件 filename 超过 1024 字符安全上限");
            }
            long size = drain(input);
            if (size > Integer.MAX_VALUE) {
                fail("MIME_ATTACHMENT_TOO_LARGE", "附件解码后大小超出可记录范围");
            }
            attachments.add(new AttachmentMeta(safeFilename, mimeType, size));
        }

        private String decodeText(InputStream input, String declaredCharset) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                accumulatedTextBytes += read;
                if (accumulatedTextBytes > MAX_TEXT_BYTES) {
                    fail("MIME_TEXT_TOO_LARGE", "解码后正文累计超过 5 MiB 安全上限");
                }
                output.write(buffer, 0, read);
            }
            Charset charset;
            try {
                charset = declaredCharset == null || declaredCharset.isBlank()
                        ? StandardCharsets.UTF_8
                        : Charset.forName(declaredCharset);
            } catch (RuntimeException ex) {
                fail("MIME_UNKNOWN_CHARSET", "正文声明了不支持的字符集");
                return "";
            }
            return output.toString(charset);
        }

        private ParsedInboundMessage toParsedMessage(String authenticatedFromDomain)
                throws MessageContentRejectedException {
            if (rootHeaders == null) {
                throw rejection("MIME_HEADER_MISSING", "邮件缺少可解析 header");
            }
            Mailbox from = rootHeaders.fromMailbox();
            if (from == null || from.getAddress() == null || from.getAddress().isBlank()) {
                throw rejection("MIME_FROM_MISSING", "邮件缺少可解析的单一 From 地址");
            }
            String fromAddress = normalizeAddress(from.getAddress());
            String fromDomain = domainOf(fromAddress);
            String rawMessageId = normalizeMessageId(rootHeaders.firstBody("Message-ID"));
            List<String> references = rootHeaders.references();
            String bodyText = selectBodyText();
            String subject = rootHeaders.subject();
            OffsetDateTime sentAt = rootHeaders.sentAt();
            Recipients recipients = new Recipients(
                    rootHeaders.addresses("To"),
                    rootHeaders.addresses("Cc"),
                    rootHeaders.addresses("Bcc"));

            String fingerprint = null;
            String messageId = rawMessageId;
            boolean synthetic = false;
            if (messageId == null) {
                fingerprint = sha256Hex(String.join("\u001F",
                        fromAddress,
                        String.join(",", recipients.to()),
                        String.join(",", recipients.cc()),
                        sentAt == null ? "" : sentAt.toInstant().toString(),
                        subject == null ? "" : subject,
                        bodyText == null ? "" : bodyText,
                        attachments.stream()
                                .map(meta -> meta.filename() + ":" + meta.contentType() + ":" + meta.sizeBytes())
                                .reduce((left, right) -> left + "|" + right)
                                .orElse("")));
                messageId = "<synthetic-" + fingerprint + "@email-copilot.local>";
                synthetic = true;
            }

            boolean aligned = authenticatedFromDomain != null
                    && authenticatedFromDomain.equalsIgnoreCase(fromDomain);
            return new ParsedInboundMessage(
                    messageId,
                    synthetic,
                    fingerprint,
                    from.getName(),
                    fromAddress,
                    fromDomain,
                    aligned ? fromDomain : null,
                    recipients,
                    subject,
                    BaseSubject.extract(subject),
                    sentAt,
                    bodyText,
                    references,
                    attachments,
                    aligned);
        }

        private String selectBodyText() {
            List<String> selected = plainTextParts.stream()
                    .filter(text -> !text.isBlank())
                    .toList();
            if (selected.isEmpty()) {
                selected = htmlTextParts.stream().filter(text -> !text.isBlank()).toList();
            }
            if (selected.isEmpty()) {
                return null;
            }
            return String.join("\n\n", selected).strip();
        }

        private void enterNestedEntity() {
            nestingDepth++;
            if (nestingDepth > MAX_NESTING_DEPTH) {
                fail("MIME_NESTING_TOO_DEEP", "MIME 嵌套超过 30 层安全上限");
            }
        }

        private void leaveNestedEntity() {
            nestingDepth--;
        }
    }

    private record PartState(HeaderState headers) {
    }

    private static final class HeaderState {

        private final List<Field> fields = new ArrayList<>();

        void add(Field field) {
            fields.add(field);
        }

        String firstBody(String name) {
            return fields.stream()
                    .filter(field -> name.equalsIgnoreCase(field.getName()))
                    .map(Field::getBody)
                    .findFirst()
                    .orElse(null);
        }

        String subject() {
            Field field = firstField("Subject");
            if (field == null) {
                return null;
            }
            ParsedField parsed = parseField(field);
            return parsed instanceof UnstructuredField unstructured
                    ? unstructured.getValue()
                    : field.getBody();
        }

        OffsetDateTime sentAt() {
            Field field = firstField("Date");
            if (field == null) {
                return null;
            }
            ParsedField parsed = parseField(field);
            if (parsed instanceof DateTimeField dateTimeField && dateTimeField.getDate() != null) {
                return OffsetDateTime.ofInstant(dateTimeField.getDate().toInstant(), ZoneOffset.UTC);
            }
            return null;
        }

        Mailbox fromMailbox() {
            Field field = firstField("From");
            if (field == null) {
                return null;
            }
            ParsedField parsed = parseField(field);
            if (parsed instanceof MailboxListField mailboxListField) {
                MailboxList list = mailboxListField.getMailboxList();
                return list == null || list.size() != 1 ? null : list.get(0);
            }
            return null;
        }

        List<String> addresses(String headerName) throws MessageContentRejectedException {
            Field field = firstField(headerName);
            if (field == null) {
                return List.of();
            }
            ParsedField parsed = parseField(field);
            if (!(parsed instanceof AddressListField addressListField)) {
                return List.of();
            }
            AddressList addresses = addressListField.getAddressList();
            if (addresses == null) {
                return List.of();
            }
            return addresses.flatten().stream()
                    .map(Mailbox::getAddress)
                    .filter(java.util.Objects::nonNull)
                    .map(MimeMessageParser::normalizeAddress)
                    .toList();
        }

        List<String> references() throws MessageContentRejectedException {
            Set<String> references = new LinkedHashSet<>();
            // RFC References 承载从根到直接父节点的有序主链，应先完整保留；
            // In-Reply-To 仅在链缺失最后一跳时补充，不能反过来把父节点放到根之前。
            fields.stream()
                    .filter(field -> "References".equalsIgnoreCase(field.getName()))
                    .forEach(field -> extractMessageIds(field.getBody(), references));
            fields.stream()
                    .filter(field -> "In-Reply-To".equalsIgnoreCase(field.getName()))
                    .forEach(field -> extractMessageIds(field.getBody(), references));
            if (references.size() > MAX_REFERENCES) {
                throw rejection("MIME_TOO_MANY_REFERENCES", "References 数量超过安全上限");
            }
            return List.copyOf(references);
        }

        String filename() {
            String disposition = firstBody("Content-Disposition");
            String contentType = firstBody("Content-Type");
            String filename = extractParameter(disposition, "filename");
            return filename == null ? extractParameter(contentType, "name") : filename;
        }

        boolean attachmentDisposition() {
            String disposition = firstBody("Content-Disposition");
            return disposition != null
                    && disposition.stripLeading().toLowerCase(Locale.ROOT).startsWith("attachment");
        }

        private Field firstField(String name) {
            return fields.stream()
                    .filter(field -> name.equalsIgnoreCase(field.getName()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static ParsedField parseField(Field field) {
        return LenientFieldParser.getParser().parse(field, DecodeMonitor.SILENT);
    }

    private static String extractParameter(String headerBody, String parameterName) {
        if (headerBody == null) {
            return null;
        }
        String lower = headerBody.toLowerCase(Locale.ROOT);
        int start = lower.indexOf(parameterName.toLowerCase(Locale.ROOT) + "=");
        if (start < 0) {
            return null;
        }
        int valueStart = start + parameterName.length() + 1;
        if (valueStart >= headerBody.length()) {
            return null;
        }
        if (headerBody.charAt(valueStart) == '"') {
            int close = headerBody.indexOf('"', valueStart + 1);
            return close < 0 ? null : headerBody.substring(valueStart + 1, close);
        }
        int end = headerBody.indexOf(';', valueStart);
        return headerBody.substring(valueStart, end < 0 ? headerBody.length() : end).strip();
    }

    private static void extractMessageIds(String value, Set<String> destination) {
        if (value == null) {
            return;
        }
        int cursor = 0;
        while (cursor < value.length()) {
            int start = value.indexOf('<', cursor);
            if (start < 0) {
                break;
            }
            int end = value.indexOf('>', start + 1);
            if (end < 0) {
                break;
            }
            String messageId = normalizeMessageId(value.substring(start, end + 1));
            if (messageId != null) {
                destination.add(messageId);
            }
            cursor = end + 1;
        }
    }

    private static String normalizeMessageId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        int start = trimmed.indexOf('<');
        int end = trimmed.indexOf('>', start + 1);
        if (start < 0 || end < 0 || end - start + 1 > MAX_MESSAGE_ID_LENGTH) {
            return null;
        }
        String candidate = trimmed.substring(start, end + 1);
        return candidate.chars().anyMatch(Character::isWhitespace) ? null : candidate;
    }

    private static String normalizeAddress(String address) {
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
            return parsed.getAddress().toLowerCase(Locale.ROOT);
        } catch (AddressException ex) {
            throw new MessageContentRuntimeException(rejection(
                    "MIME_ADDRESS_INVALID", "邮件地址格式无法安全解析"));
        }
    }

    private static String domainOf(String address) {
        int separator = address.lastIndexOf('@');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new MessageContentRuntimeException(rejection(
                    "MIME_FROM_INVALID", "From 地址缺少有效域名"));
        }
        return address.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static String htmlToPlainText(String html) {
        org.jsoup.nodes.Document document = Jsoup.parse(html);
        document.select("script,style,noscript,template").remove();
        return document.text();
    }

    private static long drain(InputStream input) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
        }
        return total;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 SHA-256", ex);
        }
    }

    private static void fail(String code, String safeMessage) {
        throw new MessageContentRuntimeException(rejection(code, safeMessage));
    }

    private static MessageContentRejectedException rejection(String code, String safeMessage) {
        return new MessageContentRejectedException(code, safeMessage);
    }

    /** MIME4J callback 只能抛 MimeException/IOException；用私有 runtime 把确定性错误穿出。 */
    private static final class MessageContentRuntimeException extends RuntimeException {

        private final MessageContentRejectedException rejection;

        private MessageContentRuntimeException(MessageContentRejectedException rejection) {
            super(null, null, false, false);
            this.rejection = rejection;
        }

        private MessageContentRejectedException rejection() {
            return rejection;
        }
    }
}
