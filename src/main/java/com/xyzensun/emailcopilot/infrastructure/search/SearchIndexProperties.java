package com.xyzensun.emailcopilot.infrastructure.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Lucene 投影的部署位置；索引内容本身不进入 PostgreSQL。 */
@ConfigurationProperties("email-copilot.search")
public class SearchIndexProperties {

    private Path indexPath = Path.of("data/lucene");

    public Path getIndexPath() {
        return indexPath;
    }

    public void setIndexPath(Path indexPath) {
        this.indexPath = indexPath;
    }
}
