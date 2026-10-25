import { Buffer } from 'node:buffer';
import process from 'node:process';
import { setTimeout as delay } from 'node:timers/promises';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const START = '<!-- backdrop:performance:start -->';
export const END = '<!-- backdrop:performance:end -->';
export const PENDING = 'Publication and benchmark results are pending.';

function managedBlock(body) {
  const starts = body.split(START).length - 1;
  const ends = body.split(END).length - 1;
  if (starts === 0 && ends === 0) return null;
  if (starts !== 1 || ends !== 1 || body.indexOf(START) > body.indexOf(END)) {
    throw new Error('Release notes need one ordered performance marker pair.');
  }
  const start = body.indexOf(START);
  const end = body.indexOf(END) + END.length;
  return { start, end, text: body.slice(start, end) };
}

/** Keeps the managed section last, with GitHub's changelog footer after it. */
export function performanceNotes(body = '', content) {
  body ??= '';
  const existing = managedBlock(body);
  const block = content === undefined && existing
    ? existing.text
    : `${START}\n## Performance\n\n${content ?? PENDING}\n${END}`;
  const prose = existing ? body.slice(0, existing.start) + body.slice(existing.end) : body;
  const footer = /^\*\*Full Changelog\*\*:.*$/m.exec(prose);
  if (footer && /^##\s/m.test(prose.slice(footer.index + footer[0].length))) {
    throw new Error('Keep the generated Full Changelog footer after all release sections.');
  }
  const at = footer ? footer.index : prose.length;
  return `${prose.slice(0, at).trimEnd()}\n\n${block}\n\n${prose.slice(at).trimStart()}`.trim() + '\n';
}

export function checkVersion(tag, properties) {
  const version = /^version\s*=\s*(\S+)\s*$/m.exec(properties)?.[1];
  if (!version || tag.replace(/^v/, '') !== version) {
    throw new Error('The release tag must match the version in gradle.properties.');
  }
  return version;
}

/** Expands the workload matrix printed by JMH's own parameter discovery. */
export function workloadKeys(listing) {
  const methods = new Map();
  let current;
  let listed = false;
  for (const line of listing.split(/\r?\n/)) {
    if (/^Benchmarks:\s*$/.test(line)) { listed = true; continue; }
    if (!listed) continue;
    if (/^[\w$]+(?:\.[\w$]+)+$/.test(line)) {
      if (methods.has(line)) throw new Error('Duplicate JMH workload declaration.');
      current = new Map();
      methods.set(line, current);
      continue;
    }
    const param = /^\s+param "([^"]+)" = \{([^}]+)}\s*$/.exec(line);
    if (param) {
      if (!current || current.has(param[1])) throw new Error('Invalid JMH parameter declaration.');
      current.set(param[1], param[2].split(', ').map(value => value.trim()));
    }
  }
  if (methods.size === 0) throw new Error('JMH did not list any workloads.');
  const keys = new Set();
  for (const [method, params] of methods) {
    let combinations = [{}];
    for (const [name, values] of params) {
      combinations = combinations.flatMap(row => values.map(value => ({ ...row, [name]: value })));
    }
    for (const params of combinations) keys.add(resultKey({ benchmark: method, params }));
  }
  return keys;
}

function resultKey(row) {
  return JSON.stringify([row.benchmark, Object.entries(row.params ?? {}).sort(([a], [b]) => a.localeCompare(b))]);
}

export function validateResults(results, listing) {
  if (!Array.isArray(results) || results.length === 0) throw new Error('Benchmark results are empty.');
  const expected = workloadKeys(listing);
  const actual = new Set();
  for (const row of results) {
    const key = resultKey(row);
    if (actual.has(key) || !expected.has(key)) throw new Error('Duplicate or unexpected benchmark result.');
    actual.add(key);
    for (const [metric, unit] of [[row.primaryMetric, 'us/op'], [row.secondaryMetrics?.['gc.alloc.rate.norm'], 'B/op']]) {
      if (!metric || !Number.isFinite(metric.score) || metric.score < 0 || metric.scoreUnit !== unit) {
        throw new Error('A benchmark metric is missing, non-finite or uses an unexpected unit.');
      }
    }
    if (row.mode !== 'avgt') throw new Error('Benchmark results must measure average time.');
  }
  if (actual.size !== expected.size) throw new Error('Benchmark results omit discovered workloads.');
  return results;
}

function cell(value) {
  return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('|', '&#124;').replaceAll('`', '&#96;').replace(/[\r\n]+/g, ' ');
}

export function benchmarkTable(results, listing) {
  validateResults(results, listing);
  const rows = [...results].sort((a, b) => resultKey(a).localeCompare(resultKey(b)));
  const table = [
    '| Benchmark | Parameters | Time (µs/op) | Error (µs/op) | Managed allocation (B/op) |',
    '| --- | --- | ---: | ---: | ---: |',
  ];
  for (const row of rows) {
    const params = Object.entries(row.params ?? {}).sort(([a], [b]) => a.localeCompare(b))
      .map(([name, value]) => `${cell(name)}=${cell(value)}`).join(', ') || '—';
    const error = Number.isFinite(row.primaryMetric.scoreError) ? row.primaryMetric.scoreError.toFixed(3) : 'n/a';
    table.push(`| ${cell(row.benchmark)} | ${params} | ${row.primaryMetric.score.toFixed(3)} | ${error} | ${row.secondaryMetrics['gc.alloc.rate.norm'].score.toFixed(3)} |`);
  }
  return table.join('\n');
}

export function benchmarkReport(results, listing, { commit, runUrl }) {
  const table = benchmarkTable(results, listing);
  if (!/^[a-f0-9]{40,64}$/.test(commit)) throw new Error('A benchmark report needs its exact commit.');
  const sample = results[0];
  for (const name of ['forks', 'warmupIterations', 'measurementIterations']) {
    if (!Number.isInteger(sample[name]) || sample[name] < 1) throw new Error('Benchmark execution metadata is incomplete.');
  }
  for (const name of ['jdkVersion', 'warmupTime', 'measurementTime']) {
    if (typeof sample[name] !== 'string' || !sample[name]) throw new Error('Benchmark runtime metadata is incomplete.');
  }
  const markdown = [
    `Commit: \`${commit}\`. [Raw results and environment](${runUrl}).`,
    'Warmed JVM throughput and managed allocation on a shared Linux runner. Motion workloads use unpaced CPU raster rendering.',
    `JDK ${cell(sample.jdkVersion)}; ${sample.forks} forks; ${sample.warmupIterations} warm-ups (${cell(sample.warmupTime)}); ${sample.measurementIterations} measurements (${cell(sample.measurementTime)}).`,
    table,
  ].join('\n\n');
  if (Buffer.byteLength(markdown, 'utf8') > 50000) throw new Error('The performance table exceeds the release-note budget; retain the full artifact and revise the reporting layout.');
  return markdown;
}

/** Reads the current body immediately before replacing only the managed section. */
export async function updatePerformance(github, repo, releaseId, content, expectedTag) {
  const { data } = await github.rest.repos.getRelease({ ...repo, release_id: releaseId });
  if (expectedTag && data.tag_name !== expectedTag) throw new Error("The release tag changed during the workflow.");
  const body = performanceNotes(data.body, content);
  if (body !== data.body) await github.rest.repos.updateRelease({ ...repo, release_id: releaseId, body });
  return data;
}

/** Requires the latest push CI for this commit; pending runs are observed without dispatching tests. */
export async function requirePassingTests(github, repo, sha, {
  timeoutMs = 20 * 60 * 1000, pollMs = 20000, now = Date.now, wait = delay,
} = {}) {
  const deadline = now() + timeoutMs;
  for (;;) {
    const { data } = await github.rest.actions.listWorkflowRuns({
      ...repo, workflow_id: 'test.yml', head_sha: sha, event: 'push', per_page: 100,
    });
    const run = data.workflow_runs.filter(run => run.head_sha === sha && run.event === 'push')
      .sort((a, b) => b.id - a.id)[0];
    if (!run) throw new Error('No push Test CI exists for the release commit; let normal CI run before publishing.');
    if (run.status === 'completed') {
      if (run.conclusion !== 'success') throw new Error(`Test CI has not passed: ${run.conclusion}.`);
      return run;
    }
    const remaining = deadline - now();
    if (remaining <= 0) throw new Error('Timed out waiting for the release commit to pass Test CI.');
    await wait(Math.min(pollMs, remaining));
  }
}

export async function prepareDraft(github, repo, tag, previousTag) {
  const version = checkVersion(tag, readFileSync('gradle.properties', 'utf8'));
  await github.rest.git.getRef({ ...repo, ref: `tags/${tag}` });
  const releases = await github.paginate(github.rest.repos.listReleases, { ...repo, per_page: 100 });
  if (releases.some(release => release.tag_name.replace(/^v/, '') === version && release.tag_name !== tag)) {
    throw new Error('This artifact version already has a release under another tag.');
  }
  const existing = releases.find(release => release.tag_name === tag);
  if (existing) {
    if (!existing.draft) throw new Error('Only a draft can be resumed.');
    await updatePerformance(github, repo, existing.id, undefined, tag);
    return existing;
  }
  const { data: notes } = await github.rest.repos.generateReleaseNotes({
    ...repo, tag_name: tag, ...(previousTag ? { previous_tag_name: previousTag } : {}),
  });
  const { data } = await github.rest.repos.createRelease({
    ...repo, tag_name: tag, name: notes.name, draft: true, prerelease: version.includes('-'),
    body: performanceNotes(notes.body),
  });
  return data;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [command, report, listing, output] = process.argv.slice(2);
  if (command !== 'report' || !output) throw new Error('Usage: release-notes.mjs report <results.json> <workloads.txt> <output.md>');
  writeFileSync(output, benchmarkTable(JSON.parse(readFileSync(report, 'utf8')), readFileSync(listing, 'utf8')) + '\n');
}
