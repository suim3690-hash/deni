import assert from 'node:assert/strict'
import test from 'node:test'
import { powerOnSafetyNotice } from '../src/lib/hazardRisk.ts'

test('전원 ON 뒤 안전 정지면 현재 프로필의 미처리 삼킴 위험을 안내한다', () => {
  const message = powerOnSafetyNotice('HAZARD_PAUSED', [
    { objectName: '동전' }, { objectName: '전선' }, { objectName: '구슬' }, { objectName: '동전' },
  ])
  assert.match(message, /전원은 켜졌지만/)
  assert.match(message, /동전·구슬/)
  assert.match(message, /스마트 안심 케어 맵에서 남은 위험물을 처리/)
})

test('다른 프로필에서 생긴 차단과 정상 주행을 구분한다', () => {
  assert.match(powerOnSafetyNotice('HAZARD_PAUSED', [{ objectName: '전선' }]), /위험을 감지했던 아이 프로필/)
  assert.equal(powerOnSafetyNotice('RUNNING', [{ objectName: '동전' }]), '')
})
