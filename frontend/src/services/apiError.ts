export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string | null
  readonly requestId: string | null
  readonly fieldErrors: Record<string, string>

  constructor(
    status: number,
    message: string,
    code: string | null,
    requestId: string | null,
    fieldErrors: Record<string, string>,
  ) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
    this.requestId = requestId
    this.fieldErrors = fieldErrors
  }
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function nonemptyString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

export async function apiErrorFromResponse(response: Response, fallbackMessage: string): Promise<ApiRequestError> {
  let body: unknown = null
  try {
    body = await response.json()
  } catch {
    // A proxy or failed server can return an empty or non-JSON error body.
  }

  const error = record(record(body)?.error)
  const fieldErrors: Record<string, string> = {}
  for (const [field, message] of Object.entries(record(error?.fieldErrors) ?? {})) {
    const value = nonemptyString(message)
    if (value) fieldErrors[field] = value
  }

  return new ApiRequestError(
    response.status,
    nonemptyString(error?.message) ?? fallbackMessage,
    nonemptyString(error?.code),
    nonemptyString(error?.requestId) ?? nonemptyString(response.headers.get('X-Request-Id')),
    fieldErrors,
  )
}

export function apiErrorMessage(error: unknown, fallbackMessage: string): string {
  if (error instanceof ApiRequestError) {
    const message = error.code === 'IDEMPOTENCY_KEY_REUSED'
      ? '이전 등록 요청과 입력 내용이 달라요. 내용을 수정한 뒤 다시 시도해 주세요.'
      : error.message
    return error.requestId ? `${message} (요청 ID: ${error.requestId})` : message
  }
  if (error instanceof TypeError) return '서버에 연결할 수 없어요. 연결 상태를 확인하고 다시 시도해 주세요.'
  if (error instanceof SyntaxError) return '서버 응답을 읽을 수 없어요. 잠시 후 다시 시도해 주세요.'
  if (error instanceof Error && error.message) return error.message
  return fallbackMessage
}
