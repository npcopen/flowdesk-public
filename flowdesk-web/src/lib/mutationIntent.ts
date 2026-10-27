/** 为同一写入意图复用幂等键，避免请求结果不确定时重试产生重复记录。 */
export function mutationIntent() {
  let signature = ''
  let key = ''
  return {
    /**
     * 按业务载荷签名取得本次写入意图的幂等键。
     * 载荷不变时复用原键，载荷变化时生成新键，便于失败后安全重试。
     */
    key(payload: unknown): string {
      // 载荷不变时复用原键；用户改变输入后视为新的写入意图。
      const next = JSON.stringify(payload)
      if (!key || signature !== next) { signature = next; key = crypto.randomUUID() }
      return key
    },
    /**
     * 清除已保存的载荷签名及幂等键。
     * 调用方在写入成功或明确开始新意图时执行，使下一次提交生成新键。
     */
    reset() { signature = ''; key = '' },
  }
}
