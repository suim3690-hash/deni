import assert from 'node:assert/strict'
import test from 'node:test'
import { classifyHazard, orderHazardsForAttention, riskByStage } from '../src/lib/hazardRisk.ts'

test('삼킴 위험도는 영아기 높음, 걸음마 매우 높음, 유아 활동기 보통이다', () => {
  assert.deepEqual(['INFANT', 'TODDLER', 'ACTIVE_CHILD'].map((stage) => riskByStage[stage].SWALLOW),
    ['HIGH', 'VERY_HIGH', 'MEDIUM'])
  assert.deepEqual(['INFANT', 'TODDLER', 'ACTIVE_CHILD'].map((stage) => riskByStage[stage].LIVING),
    ['HIGH', 'HIGH', 'VERY_HIGH'])
})

test('주사위도 삼킴 위험물로 분류한다', () => {
  assert.equal(classifyHazard('주사위'), 'SWALLOW')
})

test('동시 감지 시 삼킴 위험을 먼저, 확인한 생활 위험은 마지막에 둔다', () => {
  const items = [
    { objectName: '전선', detectedAt: '2026-09-22T10:03:00Z', acknowledgedAt: null },
    { objectName: '콘센트', detectedAt: '2026-09-22T10:04:00Z', acknowledgedAt: '2026-09-22T10:05:00Z' },
    { objectName: '동전', detectedAt: '2026-09-22T10:01:00Z', acknowledgedAt: null },
    { objectName: '배터리', detectedAt: '2026-09-22T10:02:00Z', acknowledgedAt: null },
  ]
  assert.deepEqual(orderHazardsForAttention(items).map((item) => item.objectName),
    ['배터리', '동전', '전선', '콘센트'])
  assert.equal(items[0].objectName, '전선')
})
