/** Keep an uncertain write retry attached to the same user intent. */
export function mutationIntent() {
  let signature = ''
  let key = ''
  return {
    key(payload: unknown): string {
      const next = JSON.stringify(payload)
      if (!key || signature !== next) { signature = next; key = crypto.randomUUID() }
      return key
    },
    reset() { signature = ''; key = '' },
  }
}
