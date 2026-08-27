/**
 * 跨层的 Spring 装配：不属于任何单一分层、被多层共用的基础 bean。
 *
 * <p>目前只有 {@link com.xyzensun.emailcopilot.config.ClockConfig}。
 *
 * <p>放什么：像 {@code Clock} 这种被 interfaces / application / infrastructure 共同依赖、
 * 又不属于其中任何一层的东西。
 *
 * <p>不放什么：任何业务逻辑，以及只服务于单一分层的配置——
 * 那些放到对应层里（如安全配置在 {@code infrastructure.security}）。
 */
package com.xyzensun.emailcopilot.config;
