/**
 * 领域层：实体、值对象、枚举、领域规则与领域服务。
 *
 * <p>不依赖 Spring Web，除 MyBatis-Plus 的实体注解外不依赖框架。
 *
 * <p>放什么：
 * <ul>
 *   <li>状态转换规则——{@code PendingAction} 五态、{@code Turn} 四态、
 *       {@code ProcessingProgress} 阶段游标（{@code DATABASE.md} §5）</li>
 *   <li>纯逻辑——域名通配符匹配（{@code *.a.com} 恰好一层、{@code +.a.com} 任意层含裸域名）、
 *       base subject 剥离、token 估算、canonical payload hash</li>
 *   <li>{@code UntrustedContent}——邮件正文、工具结果、取证结果进模型前的包裹类型</li>
 * </ul>
 *
 * <p><b>命名必须与 {@code CONTEXT.md} 的词汇表一致</b>，它是术语的权威定义。
 * 词汇表里叫 {@code PendingAction} 就不要写 {@code PendingApproval}；
 * 叫 {@code LiteralSearch}/{@code RelevanceSearch} 就不要写
 * {@code KeywordSearch}/{@code SemanticSearch}。两套叫法并存时，
 * 读代码的人无法判断它们是同一个概念还是两个。
 */
package com.xyzensun.emailcopilot.domain;
