package com.xyzensun.emailcopilot.infrastructure.search;

/** Lucene 文件系统边界暂时不可用时交给全局 500 处理器的运行时异常。 */
public class SearchIndexUnavailableException extends RuntimeException {

    public SearchIndexUnavailableException(Throwable cause) {
        super(cause);
    }
}
