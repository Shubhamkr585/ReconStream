import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Custom metrics
const stormConflictRate = new Rate('storm_conflict_rate');
const stormSuccessRate = new Rate('storm_success_rate');
const steadyDuration = new Trend('steady_req_duration');

export const options = {
  scenarios: {
    duplicate_storm: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 1500,
      maxDuration: '45s',
      exec: 'duplicateStorm',
    },
    steady_throughput: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 60,
      stages: [
        { target: 40, duration: '10s' },
        { target: 60, duration: '15s' },
        { target: 0, duration: '5s' },
      ],
      startTime: '30s', // Starts after duplicate_storm
      exec: 'steadyThroughput',
    },
  },
  thresholds: {
    // All checks must pass (status is 201 or 409, 0 5xx errors)
    'checks': ['rate==1.0'],
    'http_req_failed': ['rate<0.001'],
    // Steady throughput p95 and p99 constraints
    'steady_req_duration': ['p(95)<300', 'p(99)<600'],
  },
};

/**
 * Scenario 1: Duplicate Storm
 * 1,500 total requests across 50 VUs reusing only 300 Idempotency-Keys (5x duplicate retry storm).
 * Assert all responses are 201 or 409 (0 5xx errors).
 */
export function duplicateStorm() {
  const keyIndex = exec.scenario.iterationInTest % 300;
  const idempotencyKey = `storm-key-${keyIndex}`;
  const externalOrderId = `order-storm-${keyIndex}`;

  const payload = JSON.stringify({
    externalOrderId: externalOrderId,
    amountPaise: 50000, // 500.00 INR
    expectedMdrBps: 150,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    responseCallback: http.expectedStatuses(201, 409),
  };

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  const is201 = res.status === 201;
  const is409 = res.status === 409;
  const isNo5xx = res.status < 500;

  check(res, {
    'storm response is 201 or 409': () => (is201 || is409),
    'storm zero 5xx errors': () => isNo5xx,
  });

  stormSuccessRate.add(is201);
  stormConflictRate.add(is409);
}

/**
 * Scenario 2: Steady Throughput
 * Ramping arrival rate measuring real TPS, p95, and p99 latency with unique keys.
 */
export function steadyThroughput() {
  const uniqueId = `${Date.now()}-${__VU}-${exec.scenario.iterationInTest}`;
  const idempotencyKey = `steady-key-${uniqueId}`;
  const externalOrderId = `order-steady-${uniqueId}`;

  const payload = JSON.stringify({
    externalOrderId: externalOrderId,
    amountPaise: 25000, // 250.00 INR
    expectedMdrBps: 120,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    responseCallback: http.expectedStatuses(201),
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);
  const duration = Date.now() - start;

  steadyDuration.add(duration);

  check(res, {
    'steady payment succeeded (201)': (r) => r.status === 201,
  });
}
