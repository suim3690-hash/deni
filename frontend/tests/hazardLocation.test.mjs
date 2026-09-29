import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let server
let HazardLocation
const living = { hazardId: 'living', objectName: '전선', riskLevel: 'HIGH', detectedAt: '2026-09-23T01:00:00Z' }
const swallow = { ...living, hazardId: 'swallow', objectName: '동전' }
const readyRobot = { stale: false, powerEnabled: true, operationState: 'PAUSED', movementState: 'STOPPED', taskState: 'HAZARD_PAUSED' }

before(async () => {
  globalThis.window = { location: { search: '' } }
  server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  HazardLocation = (await server.ssrLoadModule('/src/pages/HazardLocation.tsx')).default
})
after(async () => {
  await server?.close()
  delete globalThis.window
})

function render(hazard, status = 'ACTIVE', redetected = false, robotState = readyRobot, hazards = [swallow, living]) {
  return renderToStaticMarkup(createElement(HazardLocation, {
    hazard, hazards, deviceId: 'robot-test', stage: 'TODDLER',
    operationState: 'PAUSED', detail: { ...hazard, status, captureImageUrl: `/images/${hazard.hazardId}.jpg` },
    error: '', errorStatus: null, isMock: false, redetected, robotState,
    onBack() {}, onRetry() {}, onSelect() {}, onLivingResolved() {}, onRemovalCompleted() {},
  }))
}

test('같은 라벨 두 물체도 별도 선택 항목과 각자의 사진으로 표시한다', () => {
  const batteries = ['battery-a', 'battery-b'].map(hazardId => ({ ...swallow, hazardId, objectName: '배터리' }))
  for (const selected of batteries) {
    const html = render(selected, 'ACTIVE', false, readyRobot, batteries)
    assert.match(html, /감지된 위험물 2건/)
    assert.match(html, /1\. 배터리/)
    assert.match(html, /2\. 배터리/)
    assert.ok(html.includes(`/images/${selected.hazardId}.jpg`))
  }
})

test('모터가 정지했어도 탐지 준비 중이면 처리 버튼 대신 대기 이유를 표시한다', () => {
  const html = render(swallow, 'ACTIVE', false, { ...readyRobot, taskState: 'RUNNING' })
  assert.match(html, /로봇의 정지 확인을 기다리고 있어요/)
  assert.doesNotMatch(html, /사용자 직접 제거|위험 물체 안전 이송/)
})

test('전원 OFF와 오래된 상태에서는 처리 요청을 열지 않는다', () => {
  for (const state of [{ ...readyRobot, powerEnabled: false }, { ...readyRobot, stale: true }, null]) {
    assert.doesNotMatch(render(swallow, 'ACTIVE', false, state), /사용자 직접 제거|위험 물체 안전 이송/)
  }
})

test('로봇이 준비되지 않아도 생활공간 위험 확인은 가능하다', () => {
  assert.match(render(living, 'ACTIVE', false, null), /위험 요소 확인 완료/)
})

test('생활공간 위험은 삼킴 위험과 함께 있어도 해당 사진과 확인 버튼을 표시한다', () => {
  const html = render(living)
  assert.match(html, /src="\/images\/living.jpg"/)
  assert.match(html, /위험 요소 확인 완료/)
  assert.match(html, /동전/)
  assert.match(html, /<section aria-label="스마트 안심 케어 맵"/)
  assert.doesNotMatch(html, /감지된 위험 물체가 없습니다|삼킴 위험물을 먼저 처리/)
})

test('같은 화면에서 삼킴 위험을 선택하면 해당 사진과 이송·제거 조치를 표시한다', () => {
  const html = render(swallow)
  assert.match(html, /src="\/images\/swallow.jpg"/)
  assert.match(html, /위험 물체 안전 이송/)
  assert.match(html, /사용자 직접 제거/)
  assert.doesNotMatch(html, /위험 요소 확인 완료/)
})

test('생활공간 위험 완료 후에는 남은 삼킴 위험 확인으로 이어진다', () => {
  const html = render(living, 'RESOLVED')
  assert.match(html, /남은 위험물 1건 확인/)
  assert.doesNotMatch(html, /위험 요소 확인 완료|src="\/images\/living.jpg"/)
})

test('직접 제거 후 새 위험으로 재감지되면 다시 치우라는 알림을 표시한다', () => {
  const html = render(swallow, 'ACTIVE', true)
  assert.match(html, /재감지/)
  assert.match(html, /위험 물체가 다시 감지되었어요/)
  assert.match(html, /제거했던 위험 물체가 다시 보여요. 다시 치워 주세요./)
  assert.doesNotMatch(html, /영유아 삼킴 위험 물체\(동전\) 발견/)
})
