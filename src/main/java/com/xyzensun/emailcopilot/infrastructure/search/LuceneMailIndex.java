package com.xyzensun.emailcopilot.infrastructure.search;

import com.xyzensun.emailcopilot.domain.enums.MessageDirection;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单实例 Embedded Lucene 投影；PostgreSQL 负责提供当前邮件事实。 */
@Component
public class LuceneMailIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneMailIndex.class);

    private static final String ID_FIELD = "_id";
    private static final String ID_SORT_FIELD = "_id_sort";
    private static final String SUBJECT_FIELD = "subject";
    private static final String BODY_FIELD = "body";
    private static final String FROM_DISPLAY_FIELD = "from_display";
    private static final String ACCOUNT_FIELD = "mail_account_id";
    private static final String DIRECTION_FIELD = "direction";
    private static final String CATEGORY_FIELD = "category";
    private static final String TAG_FIELD = "tag_id";
    private static final String RECEIVED_AT_FIELD = "received_at";
    private static final String RECEIVED_AT_SORT_FIELD = "received_at_sort";
    private static final String ATTACHMENT_FIELD = "has_attachment";
    private static final String ANALYZER_METADATA_KEY = "search.analyzer";
    private static final String SCHEMA_METADATA_KEY = "search.schema_version";
    private static final String ANALYZER_METADATA_VALUE = "smartcn";
    private static final String SCHEMA_METADATA_VALUE = "1";
    private static final int SEARCH_BATCH_SIZE = 256;

    private final SearchIndexProperties properties;
    private Directory directory;
    private Analyzer analyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    public LuceneMailIndex(SearchIndexProperties properties) {
        this.properties = properties;
    }

    public synchronized boolean isOpen() {
        return writer != null;
    }

    public synchronized void open() {
        if (writer != null) {
            return;
        }
        try {
            openWriter(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            if (!hasCommittedIndex(directory)) {
                setMetadata();
                writer.commit();
            }
            searcherManager = new SearcherManager(writer, new org.apache.lucene.search.SearcherFactory());
        } catch (IOException exception) {
            closeQuietly();
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized void rebuild(Collection<SearchIndexDocument> documents) {
        try {
            closeQuietly();
            Files.createDirectories(properties.getIndexPath());
            directory = FSDirectory.open(properties.getIndexPath());
            analyzer = new SmartChineseAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            writer = new IndexWriter(directory, config);
            setMetadata();
            for (SearchIndexDocument document : documents) {
                writer.updateDocument(new Term(ID_FIELD, Long.toString(document.messageId())), toLuceneDocument(document));
            }
            writer.commit();
            searcherManager = new SearcherManager(writer, new org.apache.lucene.search.SearcherFactory());
        } catch (IOException exception) {
            closeQuietly();
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized void updateDocument(SearchIndexDocument document) {
        ensureOpen();
        try {
            writer.updateDocument(
                    new Term(ID_FIELD, Long.toString(document.messageId())),
                    toLuceneDocument(document));
            searcherManager.maybeRefreshBlocking();
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized void deleteMessage(long messageId) {
        ensureOpen();
        try {
            writer.deleteDocuments(new Term(ID_FIELD, Long.toString(messageId)));
            searcherManager.maybeRefreshBlocking();
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized void deleteMessages(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        ensureOpen();
        try {
            Term[] ids = messageIds.stream()
                    .map(messageId -> new Term(ID_FIELD, Long.toString(messageId)))
                    .toArray(Term[]::new);
            writer.deleteDocuments(ids);
            searcherManager.maybeRefreshBlocking();
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized void deleteMailAccount(long mailAccountId) {
        ensureOpen();
        try {
            writer.deleteDocuments(LongPoint.newExactQuery(ACCOUNT_FIELD, mailAccountId));
            searcherManager.maybeRefreshBlocking();
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized void commit() {
        if (writer == null || !writer.hasUncommittedChanges()) {
            return;
        }
        try {
            setMetadata();
            writer.commit();
            searcherManager.maybeRefreshBlocking();
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized boolean hasExpectedMetadata() {
        try (Directory candidateDirectory = FSDirectory.open(properties.getIndexPath())) {
            if (!hasCommittedIndex(candidateDirectory)) {
                return false;
            }
            try (DirectoryReader reader = DirectoryReader.open(candidateDirectory)) {
                Map<String, String> userData = reader.getIndexCommit().getUserData();
                String storedAnalyzer = userData.get(ANALYZER_METADATA_KEY);
                String storedSchema = userData.get(SCHEMA_METADATA_KEY);
                if (!ANALYZER_METADATA_VALUE.equals(storedAnalyzer)
                        || !SCHEMA_METADATA_VALUE.equals(storedSchema)) {
                    // 分词器/schema version 不匹配触发重建：必须留下索引里记的值与当前代码的值，
                    // 否则一次静默重建后无从排查为什么投影被丢弃重写（logging-guidelines 硬约束）。
                    log.warn("Lucene 索引 metadata 不匹配，将触发重建：stored analyzer={}, schema={}; expected analyzer={}, schema={}",
                            storedAnalyzer, storedSchema, ANALYZER_METADATA_VALUE, SCHEMA_METADATA_VALUE);
                    return false;
                }
                return true;
            }
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }

    public synchronized List<Long> literalSearch(
            String queryText,
            boolean searchSubject,
            boolean searchBody,
            boolean descending,
            Long accountId,
            MessageDirection direction,
            OffsetDateTime receivedAfter,
            OffsetDateTime receivedBefore) {
        return searchAll(
                buildLiteralQuery(
                        queryText,
                        searchSubject,
                        searchBody,
                        accountId,
                        direction,
                        receivedAfter,
                        receivedBefore),
                timeSort(descending));
    }

    public synchronized List<Long> relevanceSearch(
            String queryText,
            Long accountId,
            MessageDirection direction,
            OffsetDateTime receivedAfter,
            OffsetDateTime receivedBefore) {
        return searchAll(
                buildRelevanceQuery(
                        queryText,
                        accountId,
                        direction,
                        receivedAfter,
                        receivedBefore),
                Sort.RELEVANCE);
    }

    public synchronized Set<Long> documentIds() {
        return new LinkedHashSet<>(searchAll(new MatchAllDocsQuery(), idSort()));
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void ensureOpen() {
        if (writer == null) {
            open();
        }
    }

    private void openWriter(IndexWriterConfig.OpenMode openMode) throws IOException {
        Files.createDirectories(properties.getIndexPath());
        directory = FSDirectory.open(properties.getIndexPath());
        analyzer = new SmartChineseAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer).setOpenMode(openMode);
        writer = new IndexWriter(directory, config);
    }

    private List<Long> searchAll(Query query, Sort sort) {
        ensureOpen();
        try {
            searcherManager.maybeRefreshBlocking();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                int totalDocuments = searcher.getIndexReader().numDocs();
                if (totalDocuments == 0) {
                    return List.of();
                }
                List<Long> result = new ArrayList<>();
                org.apache.lucene.search.ScoreDoc after = null;
                while (true) {
                    TopDocs hits;
                    if (sort == Sort.RELEVANCE) {
                        hits = after == null
                                ? searcher.search(query, SEARCH_BATCH_SIZE)
                                : searcher.searchAfter(after, query, SEARCH_BATCH_SIZE);
                    } else {
                        hits = after == null
                                ? searcher.search(query, SEARCH_BATCH_SIZE, sort)
                                : searcher.searchAfter(after, query, SEARCH_BATCH_SIZE, sort);
                    }
                    if (hits.scoreDocs.length == 0) {
                        break;
                    }
                    for (org.apache.lucene.search.ScoreDoc hit : hits.scoreDocs) {
                        String id = searcher.storedFields().document(hit.doc).get(ID_FIELD);
                        if (id != null) {
                            result.add(Long.parseLong(id));
                        }
                    }
                    after = hits.scoreDocs[hits.scoreDocs.length - 1];
                    if (hits.scoreDocs.length < SEARCH_BATCH_SIZE) {
                        break;
                    }
                }
                return result;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }

    private Query buildLiteralQuery(
            String queryText,
            boolean searchSubject,
            boolean searchBody,
            Long accountId,
            MessageDirection direction,
            OffsetDateTime receivedAfter,
            OffsetDateTime receivedBefore) {
        List<String> terms = analyze(queryText);
        if (terms.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String term : terms) {
            BooleanQuery.Builder fields = new BooleanQuery.Builder();
            if (searchSubject) {
                fields.add(new TermQuery(new Term(SUBJECT_FIELD, term)), BooleanClause.Occur.SHOULD);
            }
            if (searchBody) {
                fields.add(new TermQuery(new Term(BODY_FIELD, term)), BooleanClause.Occur.SHOULD);
            }
            query.add(fields.build(), BooleanClause.Occur.MUST);
        }
        addStableFilters(query, accountId, direction, receivedAfter, receivedBefore);
        return query.build();
    }

    private Query buildRelevanceQuery(
            String queryText,
            Long accountId,
            MessageDirection direction,
            OffsetDateTime receivedAfter,
            OffsetDateTime receivedBefore) {
        List<String> terms = analyze(queryText);
        if (terms.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String term : terms) {
            BooleanQuery.Builder fields = new BooleanQuery.Builder();
            fields.add(new BoostQuery(new TermQuery(new Term(SUBJECT_FIELD, term)), 3.0f), BooleanClause.Occur.SHOULD);
            fields.add(new BoostQuery(new TermQuery(new Term(FROM_DISPLAY_FIELD, term)), 2.0f), BooleanClause.Occur.SHOULD);
            fields.add(new BoostQuery(new TermQuery(new Term(BODY_FIELD, term)), 1.0f), BooleanClause.Occur.SHOULD);
            query.add(fields.build(), BooleanClause.Occur.MUST);
        }
        addStableFilters(query, accountId, direction, receivedAfter, receivedBefore);
        return query.build();
    }

    private static void addStableFilters(
            BooleanQuery.Builder query,
            Long accountId,
            MessageDirection direction,
            OffsetDateTime receivedAfter,
            OffsetDateTime receivedBefore) {
        if (accountId != null) {
            query.add(LongPoint.newExactQuery(ACCOUNT_FIELD, accountId), BooleanClause.Occur.FILTER);
        }
        if (direction != null) {
            query.add(new TermQuery(new Term(DIRECTION_FIELD, direction.getValue())), BooleanClause.Occur.FILTER);
        }
        if (receivedAfter != null || receivedBefore != null) {
            long lower = receivedAfter == null ? Long.MIN_VALUE : receivedAfter.toInstant().toEpochMilli();
            long upper = receivedBefore == null ? Long.MAX_VALUE : receivedBefore.toInstant().toEpochMilli();
            query.add(LongPoint.newRangeQuery(RECEIVED_AT_FIELD, lower, upper), BooleanClause.Occur.FILTER);
        }
    }

    private List<String> analyze(String text) {
        List<String> terms = new ArrayList<>();
        try (var stream = analyzer.tokenStream("query", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException(exception);
        }
        return terms;
    }

    private static Sort timeSort(boolean descending) {
        return new Sort(
                new SortField(RECEIVED_AT_SORT_FIELD, SortField.Type.LONG, descending),
                new SortField(ID_SORT_FIELD, SortField.Type.LONG, descending));
    }

    private static Sort idSort() {
        return new Sort(new SortField(ID_SORT_FIELD, SortField.Type.LONG));
    }

    private static Document toLuceneDocument(SearchIndexDocument source) {
        Document document = new Document();
        document.add(new StringField(ID_FIELD, Long.toString(source.messageId()), Field.Store.YES));
        document.add(new NumericDocValuesField(ID_SORT_FIELD, source.messageId()));
        document.add(new LongPoint(ACCOUNT_FIELD, source.mailAccountId()));
        document.add(new StringField(DIRECTION_FIELD, source.direction().getValue(), Field.Store.NO));
        document.add(new LongPoint(RECEIVED_AT_FIELD, source.receivedAt().toInstant().toEpochMilli()));
        document.add(new NumericDocValuesField(
                RECEIVED_AT_SORT_FIELD, source.receivedAt().toInstant().toEpochMilli()));
        if (source.category() != null) {
            document.add(new StringField(CATEGORY_FIELD, source.category().getValue(), Field.Store.NO));
        }
        for (Long tagId : source.tags()) {
            document.add(new LongPoint(TAG_FIELD, tagId));
        }
        document.add(new StringField(ATTACHMENT_FIELD, Boolean.toString(source.hasAttachment()), Field.Store.NO));
        addText(document, SUBJECT_FIELD, source.subject());
        addText(document, BODY_FIELD, source.bodyText());
        addText(document, FROM_DISPLAY_FIELD, source.fromDisplay());
        return document;
    }

    private static void addText(Document document, String field, String value) {
        if (value != null && !value.isBlank()) {
            document.add(new TextField(field, value, Field.Store.NO));
        }
    }

    private void setMetadata() {
        writer.setLiveCommitData(List.of(
                Map.entry(ANALYZER_METADATA_KEY, ANALYZER_METADATA_VALUE),
                Map.entry(SCHEMA_METADATA_KEY, SCHEMA_METADATA_VALUE)));
    }

    private static boolean hasCommittedIndex(Directory directory) throws IOException {
        return DirectoryReader.indexExists(directory);
    }

    private void closeQuietly() {
        closeResource(searcherManager);
        searcherManager = null;
        closeResource(writer);
        writer = null;
        closeResource(analyzer);
        analyzer = null;
        closeResource(directory);
        directory = null;
    }

    private static void closeResource(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception exception) {
            throw new SearchIndexUnavailableException(exception);
        }
    }
}
