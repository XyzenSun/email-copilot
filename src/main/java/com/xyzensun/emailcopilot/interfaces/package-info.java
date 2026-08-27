/**
 * 对外接口层：REST Controller、SSE 端点、请求/响应 DTO、全局异常处理。
 *
 * <p>放什么：参数绑定与校验、调用应用服务、组装响应、把业务异常映射成
 * RFC 9457 Problem Details（{@code API.md} §3）。
 *
 * <p>不放什么：业务逻辑、{@code @Transactional}（事务边界属于
 * {@link com.xyzensun.emailcopilot.application}）、直接注入 Mapper。
 *
 * <p>DTO 必须与 {@code openapi.yaml} 一致——它是前后端契约的唯一来源，
 * 前端类型由 {@code openapi-typescript} 生成而非手写，两边对不上时前端会拿到编译期
 * 看不出来的运行期错误。改接口时 {@code API.md} 与 {@code openapi.yaml} 两边都要动。
 *
 * <p>子包按 {@code API.md} 的六组接口划分：{@code auth}（§7）、{@code mail}（§8 §9）、
 * {@code conversation}（§10）、{@code draft}（§11）、{@code setting}（§12）、
 * {@code error}（{@code ApiError} 枚举与
 * {@code @RestControllerAdvice}）。
 */
package com.xyzensun.emailcopilot.interfaces;
