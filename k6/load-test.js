/**
 * k6 load/smoke test (read-only, non-mutating).
 *
 * Purpose: exercise login + authenticated read path to measure latency/throughput/errors under load.
 * This script MUST NOT mutate account or application state (no password changes, no writes).
 *
 * Required env vars (no defaults; never hardcode secrets):
 *   BASE_URL      - target backend URL (e.g. https://<app>.onrender.com or http://localhost:8080)
 *   TEST_EMAIL    - email of a valid account in the target environment's DB
 *   TEST_PASSWORD - password for that account
 *
 * The test account must exist and be able to log in in the target environment.
 * For GitHub Actions (workflow_dispatch), pass via secrets/env (e.g. K6_TEST_EMAIL, K6_TEST_PASSWORD, RENDER_PROD_URL).
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL;
const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

if (!BASE_URL || !TEST_EMAIL || !TEST_PASSWORD) {
  throw new Error(
    'Missing required env vars: BASE_URL, TEST_EMAIL, TEST_PASSWORD. ' +
      'Pass with: k6 run -e BASE_URL=<url> -e TEST_EMAIL=<email> -e TEST_PASSWORD=<secret> k6/load-test.js'
  );
}

const errorRate = new Counter('errors');
const loginDuration = new Trend('login_duration');
const meDuration = new Trend('me_duration');

// Conservative stages for single-account, read-only smoke/perf checks.
// Thresholds are environment-tolerant (prod/cold starts can skew p95).
export const options = {
  stages: [
    { duration: '30s', target: 5 },
    { duration: '2m', target: 5 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    login_duration: ['p(95)<3000'],
    me_duration: ['p(95)<2000'],
    errors: ['count<20'],
    http_req_failed: ['rate<0.05'],
  },
};

function getAccessTokenCookie(setCookieHeader) {
  if (!setCookieHeader) return '';
  const cookies = Array.isArray(setCookieHeader) ? setCookieHeader : [setCookieHeader];
  for (const c of cookies) {
    if (c.includes('accessToken=')) {
      return c.split('accessToken=')[1].split(';')[0];
    }
  }
  return '';
}

export default function () {
  // 1) Login (read of auth state only; no account mutation)
  const loginResp = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: TEST_EMAIL, password: TEST_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  loginDuration.add(loginResp.timings.duration);

  const loginOk = check(loginResp, {
    'login status is 200': (r) => r.status === 200,
  });
  if (!loginOk) {
    errorRate.add(1);
    sleep(1);
    return;
  }

  const accessToken = getAccessTokenCookie(loginResp.headers['Set-Cookie']);
  if (!accessToken) {
    errorRate.add(1);
    sleep(1);
    return;
  }

  // 2) Authenticated read-only request (no writes)
  const meResp = http.get(`${BASE_URL}/api/auth/me`, {
    headers: { Cookie: `accessToken=${accessToken}` },
  });

  meDuration.add(meResp.timings.duration);

  const meOk = check(meResp, {
    'me status is 200': (r) => r.status === 200,
    'me has email': (r) => {
      try {
        return !!r.json('email');
      } catch (e) {
        return false;
      }
    },
  });
  if (!meOk) errorRate.add(1);

  sleep(1);
}