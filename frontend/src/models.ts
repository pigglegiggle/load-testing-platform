export interface Check {
  type: string;
  path: string;
  expected: string;
}
export interface Step {
  name: string;
  method: string;
  url: string;
  headers: Record<string, string>;
  body: string;
  timeoutMs: number;
  expectedStatus: number;
  checks: Check[];
  extract: Record<string, string>;
}
export interface Scenario {
  name: string;
  variables: Record<string, string>;
  steps: Step[];
}
export interface SavedScenario {
  id: string;
  definition: Scenario;
  updatedAt: string;
}
export interface Reply {
  status: number;
  headers: Record<string, string[]>;
  body: string;
  bytes: number;
  latencyMs: number;
  queueMs: number;
  passed: boolean;
  outcome: string;
  errors: string[];
  extracted: Record<string, string>;
}
export interface StartRun {
  scenario: Scenario;
  mode: string;
  concurrency: number;
  iterations: number;
  durationSeconds: number;
}
export interface Sample {
  sequence: number;
  worker: number;
  step: string;
  status: number;
  outcome: string;
  latencyMs: number;
  queueMs: number;
  bytes: number;
  error: string;
}
export interface Bucket {
  second: number;
  requests: number;
  failed: number;
  meanMs: number;
}
export interface Snapshot {
  id: string;
  name: string;
  state: string;
  startedAt: string;
  finishedAt: string | null;
  configuration: StartRun;
  attempted: number;
  passed: number;
  failed: number;
  cancelled: number;
  iterationsCompleted: number;
  activeRequests: number;
  elapsedSeconds: number;
  requestsPerSecond: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  queueP95Ms: number;
  responseBytes: number;
  outcomes: Record<string, number>;
  steps: {
    name: string;
    requests: number;
    passed: number;
    failed: number;
    cancelled: number;
    p95Ms: number;
  }[];
  series: Bucket[];
  samples: Sample[];
  message: string;
}
export interface History {
  id: string;
  name: string;
  state: string;
  startedAt: string;
  attempted: number;
  failed: number;
  p95Ms: number;
  requestsPerSecond: number;
}
export interface Configuration {
  allowedOrigins: string[];
  demoOrigin: string;
  demoEnabled: boolean;
}
export function newStep(name = "Request"): Step {
  return {
    name,
    method: "GET",
    url: "",
    headers: {},
    body: "",
    timeoutMs: 3000,
    expectedStatus: 200,
    checks: [],
    extract: {},
  };
}
