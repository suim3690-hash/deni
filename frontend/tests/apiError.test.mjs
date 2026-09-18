import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiRequestError, apiErrorFromResponse, apiErrorMessage } from '../src/services/apiError.ts'

test('shows backend validation details and request ID', async () => {
  const response = new Response(JSON.stringify({
    error: {
      code: 'VALIDATION_ERROR',
      message: '입력값을 확인해 주세요.',
      requestId: 'req-123',
      fieldErrors: { name: '이름을 입력해 주세요.', birthDate: '올바른 날짜를 입력해 주세요.' },
    },
  }), { status: 400, headers: { 'Content-Type': 'application/json' } })

  const error = await apiErrorFromResponse(response, '기본 안내')
  assert.ok(error instanceof ApiRequestError)
  assert.equal(error.status, 400)
  assert.equal(error.code, 'VALIDATION_ERROR')
  assert.deepEqual(error.fieldErrors, {
    name: '이름을 입력해 주세요.',
    birthDate: '올바른 날짜를 입력해 주세요.',
  })
  assert.equal(apiErrorMessage(error, '기본 안내'), '입력값을 확인해 주세요. (요청 ID: req-123)')
})

test('falls back safely when server returns non-JSON content', async () => {
  const response = new Response('<html>Bad gateway</html>', {
    status: 502,
    headers: { 'X-Request-Id': 'proxy-9' },
  })

  const error = await apiErrorFromResponse(response, '서버 응답을 확인하지 못했어요.')
  assert.equal(error.status, 502)
  assert.equal(error.message, '서버 응답을 확인하지 못했어요.')
  assert.equal(apiErrorMessage(error, '기본 안내'), '서버 응답을 확인하지 못했어요. (요청 ID: proxy-9)')
})

test('distinguishes reused registration requests and connection failures', () => {
  const conflict = new ApiRequestError(409, 'server text', 'IDEMPOTENCY_KEY_REUSED', null, {})
  assert.match(apiErrorMessage(conflict, '기본 안내'), /이전 등록 요청/)
  assert.match(apiErrorMessage(new TypeError('Failed to fetch'), '기본 안내'), /서버에 연결할 수 없어요/)
  assert.equal(apiErrorMessage(new Error('기기에서 명령을 거부했어요.'), '기본 안내'), '기기에서 명령을 거부했어요.')
})
