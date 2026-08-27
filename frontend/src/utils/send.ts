/**
 * 发信三态结果处理（design §6.3、quality-guidelines 约束 3）。
 *
 * POST /send 永远返回 HTTP 200，业务失败是 200+body.status。
 * 这里的纯函数把三态映射为 UI 动作（toast 类型 + 是否清空编辑框 + 是否跳转），
 * 集中一处避免散落实现把 failed/indeterminate 误放进 error 分支。
 *
 * 关键约束：
 *   - failed → 显示 resultMessage，**保留编辑框**（用户可修改后重发）
 *   - indeterminate → 提示可能已发出，**绝不清空编辑框**（那封信不入库，
 *     编辑框里的内容是它唯一的痕迹）
 *   - succeeded → 提示已发送，清空编辑框
 */
export interface SendOutcome {
  toastType: 'success' | 'error' | 'info'
  toastMessage: string
  /** 是否清空编辑框：只有 succeeded 才清空 */
  clearEditor: boolean
  /** 是否触发跳转 / 刷新：只有 succeeded 才跳 */
  shouldRefresh: boolean
}

/**
 * 把 SendResult.status 映射为 UI 动作。
 *
 * @param status 发信结果状态（succeeded / failed / indeterminate）
 * @param resultMessage SMTP 最终响应原文或错误描述（failed 时展示给用户）
 */
export function resolveSendResult(
  status: 'succeeded' | 'failed' | 'indeterminate',
  resultMessage: string,
): SendOutcome {
  switch (status) {
    case 'succeeded':
      return {
        toastType: 'success',
        toastMessage: '邮件已发送',
        clearEditor: true,
        shouldRefresh: true,
      }
    case 'failed':
      // 保留编辑框：用户可修改收件人/正文后重发
      return {
        toastType: 'error',
        toastMessage: resultMessage || '发送失败',
        clearEditor: false,
        shouldRefresh: false,
      }
    case 'indeterminate':
      // 绝不清空编辑框：这封信可能已发出但系统无记录，编辑框内容是唯一痕迹
      return {
        toastType: 'info',
        toastMessage: '结果不确定，邮件可能已发出。请去收件方或服务商 Sent 里确认，勿直接重发',
        clearEditor: false,
        shouldRefresh: false,
      }
  }
}
