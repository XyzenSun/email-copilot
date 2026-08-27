/**
 * MyBatis-Plus Mapper 接口，20 个，与 20 张表一一对应。
 *
 * <p>本阶段只继承 {@code BaseMapper<T>}，<b>不加自定义查询方法</b>——
 * 各阶段用到什么再加什么，现在预写一堆没有调用方的方法只会在后续阶段被改掉。
 *
 * <p>由启动类的 {@code @MapperScan} 统一注册，接口上不再标 {@code @Mapper}
 * （约定优于配置，避免同一件事表达两遍）。
 *
 * <p><b>三类操作不能用 BaseMapper 的现成方法</b>，后续阶段须写自定义 SQL：
 * <ul>
 *   <li><b>原子消费</b>（批准提案、领取租约）—— 必须是条件更新 + 判受影响行数，
 *       不是"先 select 看状态再 update"。后者在并发下会让同一个批准被消费两次</li>
 *   <li><b>去重插入</b>（邮件入库、读取证据）—— 直接 insert 并捕获唯一约束冲突，
 *       不是"先查再插"。并发下拦不住</li>
 *   <li><b>数组列操作</b>（删标签时清理 message.tags 残留）—— 需要
 *       {@code array_remove} 与 {@code tags @> ARRAY[?]} 这类 PostgreSQL 原生表达</li>
 * </ul>
 */
package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;
