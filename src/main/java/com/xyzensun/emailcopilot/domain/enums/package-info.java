/**
 * 领域枚举：所有 {@code check in (...)} 约束的列都映射到这里的枚举，不用 String。
 *
 * <p><b>为什么不用 String</b>：这些值参与 SQL 过滤和 CAS 条件更新。写成 {@code "runing"}
 * 编译器不报错，数据库也不报错——CAS 更新只会返回零行，被代码当成"已被他人消费"，
 * 于是审批永远批不掉而没有任何错误信息。枚举把这类错误提前到编译期。
 *
 * <p><b>映射方式</b>：全部实现 MyBatis-Plus 的 {@code IEnum<String>}，
 * {@code getValue()} 返回数据库里的字面值（小写下划线）。常量名用大写下划线。
 * <b>不要依赖 {@code name().toLowerCase()}</b>——那会让改一个枚举常量名变成一次
 * 静默的数据库不兼容变更（旧数据读不出来，且不报错）。
 */
package com.xyzensun.emailcopilot.domain.enums;
