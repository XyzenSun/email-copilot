/**
 * 基础设施层：外部世界的实现细节。
 *
 * <p>子包与职责：
 * <ul>
 *   <li>{@code persistence}——MyBatis-Plus 实体、Mapper 接口、{@code TypeHandler}
 *       （PostgreSQL 原生数组列 {@code bigint[]} 需要自定义 handler）</li>
 *   <li>{@code mail}——IMAP 拉取、SMTP 提交、MIME 流式解析、DKIM 校验</li>
 *   <li>{@code ai}——{@code ChatModelHolder}、提示词、structured output 契约、工具注册表</li>
 *   <li>{@code search}——Lucene 索引读写与从 PostgreSQL 全量重建</li>
 *   <li>{@code security}——AES-GCM 加解密、口令哈希、会话存储</li>
 * </ul>
 *
 * <p><b>{@code ChatModel} 不用 starter 自动装配的单例</b>（{@code ARCHITECTURE.md} §8.5）：
 * provider、端点、型号、超时、API key 五项全部可在界面上热改，因此由
 * {@code ChatModelHolder} 持有一个 {@code volatile} 引用，配置变更时重建。
 * 正在跑的调用持旧引用跑完，替换只对此后的新调用生效。
 *
 * <p><b>PostgreSQL 是唯一业务事实源，Lucene 是可重建投影</b>（{@code DATABASE.md} §9）。
 * 分词器名与索引 schema version 写入 Lucene 索引自身的 commit data，不建元数据表——
 * 索引是磁盘上一个可被直接删除的目录，元数据存数据库就会出现“库里记着 smartcn、
 * 目录已不存在”的状态。启动时读出比对，不一致即从 PostgreSQL 全量重建。
 * 必须自动检测而非依赖手动重建，因为失效模式无声：分词方式变了而索引未重建时，
 * 查询词被切成对不上的词项，返回“没找到”而不报错，用户只会以为系统没收到那封邮件。
 *
 * <p><b>绝不直接访问邮件中出现的任何地址</b>（{@code ARCHITECTURE.md} §7.3）。
 * 取证一律经固定的第三方检索服务，不接受模型指定任意网络客户端或任意 URL 抓取。
 */
package com.xyzensun.emailcopilot.infrastructure;
