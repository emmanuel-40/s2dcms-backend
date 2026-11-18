import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL;
const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

if (!BASE_URL || !TEST_EMAIL || !TEST_PASSWORD) {
  throw new Error('Missing required env vars: BASE_URL, TEST_EMAIL, TEST_PASSWORD.');
}

const errorRate = new Counter('errors');
const loginDuration = new Trend('login_duration'); // only used by login_check
const meDuration = new Trend('me_duration');       // only used by me_load

export const options = {
  scenarios: {
    // Runs first, alone, so login is measured without contention
    login_check: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 20,
      maxDuration: '2m',
      exec: 'loginTest',
    },
    // Starts after login_check finishes
    me_load: {
      executor: 'constant-vus',
      vus: 5,
      duration: '2m',
      startTime: '90s',
      exec: 'meTest',
    },
  },
  thresholds: {
    login_duration: ['p(95)<6000'],
    me_duration: ['p(95)<2000'],
    errors: ['count<20'],
    http_req_failed: ['rate<0.05'],
  },
};

const jsonHeaders = { 'Content-Type': 'application/json' };
const creds = JSON.stringify({ email: TEST_EMAIL, password: TEST_PASSWORD });

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

// One login shared by all me_load VUs (the access token lasts 15 min, the test is shorter)
export function setup() {
  const resp = http.post(`${BASE_URL}/api/auth/login`, creds, { headers: jsonHeaders });
  return { accessToken: getAccessTokenCookie(resp.headers['Set-Cookie']) };
}

export function loginTest() {
  const resp = http.post(`${BASE_URL}/api/auth/login`, creds, { headers: jsonHeaders });
  loginDuration.add(resp.timings.duration);
  const ok = check(resp, { 'login status is 200': (r) => r.status === 200 });
  if (!ok) {
    console.error(`login failed: status=${resp.status}`);
    errorRate.add(1);
  }
  sleep(1);
}

export function meTest(data) {
  const resp = http.get(`${BASE_URL}/api/auth/me`, {
    headers: { Cookie: `accessToken=${data.accessToken}` },
  });
  meDuration.add(resp.timings.duration);
  const ok = check(resp, {
    'me status is 200': (r) => r.status === 200,
    'me has email': (r) => {
      try { return !!r.json('email'); } catch (e) { return false; }
    },
  });
  if (!ok) {
    console.error(`me failed: status=${resp.status}`);
    errorRate.add(1);
  }
  sleep(1);
}