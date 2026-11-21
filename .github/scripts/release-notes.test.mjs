import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { START, END, SKIP_BENCHMARKS, performanceNotes, releaseBaseline, skipsBenchmarks, workloadKeys, benchmarkTable, benchmarkReports, validateResults, checkVersion, updatePerformance, prepareDraft } from './release-notes.mjs';

const listing = 'Benchmarks:\nexample.Dynamic.frame\n  param "size" = {small, large}\n';
const results = ['small', 'large'].map(size => ({
  benchmark: 'example.Dynamic.frame', params: { size }, mode: 'avgt',
  primaryMetric: { score: 2.5, scoreError: 0.2, scoreUnit: 'us/op' },
  secondaryMetrics: { 'gc.alloc.rate.norm': { score: 64, scoreUnit: 'B/op' } },
}));

test('inserts before generated changelog, preserves edits and is idempotent', () => {
  const body = `## Changes\n\nOwner edits.\n\n**Full Changelog**: compare-link\n`;
  const pending = performanceNotes(body);
  assert.ok(pending.includes('Owner edits.'));
  assert.ok(pending.indexOf(START) < pending.indexOf('**Full Changelog**'));
  assert.equal(performanceNotes(pending), pending);
  const complete = performanceNotes(pending, 'Measured results');
  assert.ok(complete.includes('Owner edits.'));
  assert.ok(complete.includes('Measured results'));
  assert.match(complete, /<details>\n<summary>JVM benchmark results<\/summary>\n\nMeasured results\n\n<\/details>/);
  assert.equal(complete.split(START).length, 2);
});

test('restores a deleted placeholder and keeps performance as the last h2', () => {
  const body = '## Changes\nText\n## Notes\nMore text';
  const updated = performanceNotes(body, 'Table');
  assert.ok(updated.startsWith(body));
  assert.equal([...updated.matchAll(/^## (.+)$/gm)].at(-1)[1], 'Performance');
  const moved = performanceNotes(`Notes\n${START}\n## Performance\nOld\n${END}\n## Manual\nKept`, 'New');
  assert.ok(moved.indexOf('## Manual') < moved.indexOf(START));
});

test('malformed markers fail without overwriting notes', () => {
  for (const body of [START, END, END + START, START + START + END]) assert.throws(() => performanceNotes(body));
});

test('discovers cartesian parameters and rejects incomplete or invalid results', () => {
  assert.equal(workloadKeys(listing).size, 2);
  assert.equal(workloadKeys('Benchmarks:\nx.Y.z\n  param "x" = {a, b}\n  param "y" = {1, 2, 3}').size, 6);
  assert.equal(workloadKeys('Benchmarks:\nx.Y.z').size, 1);
  assert.throws(() => workloadKeys('No workloads'));
  assert.throws(() => validateResults(results.slice(0, 1), listing));
  assert.throws(() => validateResults([...results, results[0]], listing));
  for (const score of [NaN, Infinity, -1]) {
    const bad = structuredClone(results); bad[0].primaryMetric.score = score;
    assert.throws(() => validateResults(bad, listing));
  }
  const bad = structuredClone(results); delete bad[0].secondaryMetrics['gc.alloc.rate.norm'];
  assert.throws(() => validateResults(bad, listing));
});

test('tables derive rows and parameters from results and escape markup', () => {
  assert.equal(benchmarkTable(results, listing).split('\n').length, 4);
  assert.ok(benchmarkTable(results, listing).includes('size=large'));
  const rows = structuredClone(results); rows[0].params.size = '<small|x>';
  assert.ok(benchmarkTable(rows, listing.replace('small,', '<small|x>,')).includes('&lt;small&#124;x&gt;'));
});

test('release tags match the publication version', () => {
  assert.equal(checkVersion('v1.2.3-rc.1', 'version=1.2.3-rc.1\n'), '1.2.3-rc.1');
  assert.equal(checkVersion('1.2.3', 'version=1.2.3\n'), '1.2.3');
  assert.throws(() => checkVersion('v1.2.4', 'version=1.2.3'));
});

test('release comparison uses the Full Changelog range for this release and repository', () => {
  const repo = 'https://github.com/owner/repo';
  const tag = 'v1.2.3';
  const commit = 'c'.repeat(40);
  const footer = link => `Changes\n\n**Full Changelog**: ${link}\n`;
  assert.equal(releaseBaseline(footer(`${repo}/compare/v0.8.0...${tag}`), repo, tag, commit), 'v0.8.0');
  assert.equal(releaseBaseline(footer(`[Compare](${repo}/compare/release%2Fold...${commit})`), repo, tag, commit), 'release/old');
  for (const body of [
    null, `${repo}/compare/v0.8.0...${tag}`,
    footer(`${repo}/releases/tag/${tag}`), footer(`${repo}/compare/v0.8.0...v1.2.4`),
    footer(`${repo}/compare/${tag}...${tag}`),
    footer(`${repo}/compare/v0.8.0....${tag}`), footer(`https://github.com/owner/other/compare/v0.8.0...${tag}`),
  ]) {
    assert.equal(releaseBaseline(body, repo, tag, commit), null);
  }
});

test('updates use the latest remote body, including an independently deleted slot', async () => {
  const writes = [];
  const github = { rest: { repos: {
    getRelease: async () => ({ data: { body: 'Fresh owner edit\n**Full Changelog**: link' } }),
    updateRelease: async args => writes.push(args),
  } } };
  await updatePerformance(github, { owner: 'owner', repo: 'repo' }, 7, 'Results');
  assert.equal(writes[0].release_id, 7);
  assert.ok(writes[0].body.includes('Fresh owner edit'));
  assert.ok(writes[0].body.includes('Results'));
});

test('an explicit skip preserves the entire release body, including on late completion', async () => {
  const body = `## Changes\r\n\r\n  ${SKIP_BENCHMARKS}\r\n\r\n${START}\r\nPrevious measurement\r\n${END}\r\n`;
  assert.equal(skipsBenchmarks(body), true);
  assert.equal(skipsBenchmarks(`Mention ${SKIP_BENCHMARKS} inline`), false);
  assert.equal(performanceNotes(body), body);
  assert.equal(performanceNotes(body, 'New measurement'), body);
  const github = { rest: { repos: {
    getRelease: async () => ({ data: { body, tag_name: 'v2' } }),
    updateRelease: async () => assert.fail('The release body must remain untouched.'),
  } } };
  await updatePerformance(github, {}, 1, 'New measurement', 'v2');
});

test('draft creation uses generated notes and resumptions preserve manual edits', async () => {
  const calls = []; let releases = [];
  const version = readFileSync('gradle.properties', 'utf8').match(/^version=(.+)$/m)[1];
  const github = { paginate: async () => releases, rest: { git: { getRef: async () => ({ data: {} }) }, repos: {
    listReleases: () => {},
    generateReleaseNotes: async args => { calls.push('generate'); return { data: { name: args.tag_name, body: 'Generated changes\n**Full Changelog**: link' } }; },
    createRelease: async args => { calls.push(args); return { data: args }; },
    getRelease: async () => ({ data: releases[0] }),
    updateRelease: async args => { calls.push(args); },
  } } };
  await prepareDraft(github, { owner: 'owner', repo: 'repo' }, `v${version}`);
  assert.equal(calls[0], 'generate');
  assert.equal(calls[1].draft, true);
  assert.ok(calls[1].body.includes('Generated changes'));
  releases = [{ id: 1, tag_name: `v${version}`, draft: true, body: `Manual edit` }];
  await prepareDraft(github, { owner: 'owner', repo: 'repo' }, `v${version}`);
  assert.ok(calls.at(-1).body.includes('Manual edit'));
  releases[0].draft = false;
  await assert.rejects(() => prepareDraft(github, { owner: 'owner', repo: 'repo' }, `v${version}`));
});

test('a changed release tag prevents writing results to a different release', async () => {
  let written = false;
  const github = { rest: { repos: {
    getRelease: async () => ({ data: { tag_name: 'v2', body: 'Owner notes' } }),
    updateRelease: async () => { written = true; },
  } } };
  await assert.rejects(() => updatePerformance(github, {}, 1, 'Results', 'v1'));
  assert.equal(written, false);
});

test('failure and retry reuse the section without duplicating author content', () => {
  const original = '## Changes\nRetained edits\n\n**Full Changelog**: link';
  const failed = performanceNotes(performanceNotes(original), 'Benchmark execution did not complete.');
  const retried = performanceNotes(failed, benchmarkTable(results, listing));
  assert.equal(retried.split('Retained edits').length, 2);
  assert.equal(retried.split(START).length, 2);
  assert.ok(!retried.includes('did not complete'));
});

test('workflow JavaScript blocks compile without expression interpolation', async () => {
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  let count = 0;
  for (const name of readdirSync('.github/workflows').filter(name => name.endsWith('.yml'))) {
    const lines = readFileSync(`.github/workflows/${name}`, 'utf8').split('\n');
    for (let i = 0; i < lines.length; i++) {
      const header = /^(\s*)script: \|\s*$/.exec(lines[i]);
      if (!header) continue;
      const indent = header[1].length;
      const block = [];
      while (++i < lines.length && (!lines[i].trim() || /^\s*/.exec(lines[i])[0].length > indent)) {
        block.push(lines[i].slice(indent + 2));
      }
      i--;
      const source = block.join('\n');
      assert.ok(!source.includes('${{'), `Use env for workflow values in ${name}`);
      assert.doesNotThrow(() => new AsyncFunction('github', 'context', 'core', source), name);
      count++;
    }
  }
  assert.ok(count > 0);
});

function measuredRows(rows) {
  return rows.map(row => ({ ...row, jdkVersion: 'test-jdk', forks: 2, warmupIterations: 5, measurementIterations: 8, warmupTime: '1 s', measurementTime: '1 s', jvmArgs: ['-Ddiagnostic.path=private', '-Xms2g', '-Xmx2g'] }));
}

test('complete reports require measurement metadata and retain exact provenance', () => {
  const rows = measuredRows(results);
  const provenance = { commit: 'a'.repeat(40), runUrl: 'https://github.com/owner/repo/actions/runs/1' };
  const { summary, release } = benchmarkReports(rows, listing, provenance);
  assert.ok(summary.includes(provenance.commit));
  assert.ok(summary.includes('2 forks'));
  assert.ok(summary.includes('-Xms2g -Xmx2g'));
  assert.ok(summary.includes('2.500'));
  assert.ok(release.includes('No baseline was requested.'));
  assert.ok(!release.includes('2.500'));
  assert.ok(!summary.includes('diagnostic.path'));
  const mixed = structuredClone(rows); mixed[1].jvmArgs = ['-Xms512m', '-Xmx512m'];
  assert.throws(() => benchmarkReports(mixed, listing, provenance), /heaps differ/);
  const missing = structuredClone(rows); missing[0].jvmArgs = ['-Xmx2g'];
  assert.throws(() => benchmarkReports(missing, listing, provenance), /heap metadata/);
  assert.throws(() => benchmarkReports(results, listing, provenance));
  assert.throws(() => benchmarkReports(rows, listing, { ...provenance, commit: 'unknown' }));
});

test('release shows relative changes only for matched workloads and marks uncertainty', () => {
  const rows = structuredClone(measuredRows(results));
  rows[0].primaryMetric.score = 5;
  rows[0].secondaryMetrics['gc.alloc.rate.norm'].score = 32;
  rows[0].secondaryMetrics['gc.alloc.rate.norm'].scoreError = 1;
  const baseline = {
    ref: 'v0.1.2',
    commit: 'b'.repeat(40),
    listing: listing.replace('large', 'medium'),
    results: structuredClone(measuredRows(results)),
  };
  baseline.results[1].params.size = 'medium';
  baseline.results[0].secondaryMetrics['gc.alloc.rate.norm'].scoreError = 1;
  const options = { commit: 'a'.repeat(40), runUrl: 'https://github.com/owner/repo/actions/runs/1', baseline, harnessChanged: true };
  const { summary, release } = benchmarkReports(rows, listing, options);
  assert.match(release, /1 matched workloads, 1 candidate only, 1 baseline only/);
  assert.match(release, /\| frame \| size=small \| \+100\.0% \| -50\.0% \|/);
  assert.equal(release.split('Compare measurements only within the same CI run.').length, 2);
  assert.ok(release.includes('*Benchmark or scene sources differ'));
  assert.ok(summary.includes('5.000'));
  assert.ok(!release.includes('5.000'));
  rows[0].primaryMetric.scoreError = 10;
  assert.ok(benchmarkReports(rows, listing, options).release.includes('+100.0% †'));
  for (const row of baseline.results) row.jdkVersion = 'other';
  assert.ok(benchmarkReports(rows, listing, options).release.includes('measurement settings differ'));
});

test('draft preparation cannot create a missing tag or duplicate an artifact version', async () => {
  const version = readFileSync('gradle.properties', 'utf8').match(/^version=(.+)$/m)[1];
  let created = false;
  const github = { paginate: async () => [{ tag_name: version }], rest: { git: {
    getRef: async () => ({ data: {} }),
  }, repos: { listReleases: () => {}, createRelease: async () => { created = true; } } } };
  await assert.rejects(() => prepareDraft(github, {}, `v${version}`));
  assert.equal(created, false);
  github.rest.git.getRef = async () => { throw new Error('Missing tag'); };
  await assert.rejects(() => prepareDraft(github, {}, `v${version}`));
  assert.equal(created, false);
});

test('publication accepts an existing passing Test CI for the exact commit', async () => {
  const { requirePassingTests } = await import('./release-notes.mjs');
  const sha = 'a'.repeat(40); const requests = [];
  const passed = { id: 7, head_sha: sha, event: 'push', status: 'completed', conclusion: 'success' };
  const github = { rest: { actions: { listWorkflowRuns: async args => {
    requests.push(args); return { data: { workflow_runs: [passed] } };
  } } } };
  assert.equal(await requirePassingTests(github, {}, sha), passed);
  assert.equal(requests[0].workflow_id, 'test.yml');
  assert.equal(requests[0].head_sha, sha);
  assert.equal(requests[0].event, 'push');
});

test('publication rejects missing, foreign and unsuccessful Test CI', async () => {
  const { requirePassingTests } = await import('./release-notes.mjs');
  const sha = 'a'.repeat(40);
  const base = { id: 1, head_sha: sha, event: 'push', status: 'completed', conclusion: 'success' };
  for (const runs of [[], [{ ...base, head_sha: 'b'.repeat(40) }], [{ ...base, event: 'pull_request' }],
    ...['failure', 'cancelled', 'timed_out', 'skipped'].map(conclusion => [{ ...base, conclusion }]),
    [base, { ...base, id: 2, conclusion: 'failure' }]]) {
    const github = { rest: { actions: { listWorkflowRuns: async () => ({ data: { workflow_runs: runs } }) } } };
    await assert.rejects(() => requirePassingTests(github, {}, sha));
  }
});

test('publication observes pending CI and times out without starting another run', async () => {
  const { requirePassingTests } = await import('./release-notes.mjs');
  const sha = 'a'.repeat(40); let clock = 0; let calls = 0;
  const run = { id: 1, head_sha: sha, event: 'push', status: 'in_progress', conclusion: null };
  const github = { rest: { actions: { listWorkflowRuns: async () => {
    calls++; return { data: { workflow_runs: [{ ...run, ...(calls > 1 ? { status: 'completed', conclusion: 'success' } : {}) }] } };
  } } } };
  const options = { timeoutMs: 20, pollMs: 10, now: () => clock, wait: async ms => { clock += ms; } };
  assert.equal((await requirePassingTests(github, {}, sha, options)).conclusion, 'success');
  github.rest.actions.listWorkflowRuns = async () => ({ data: { workflow_runs: [run] } });
  await assert.rejects(() => requirePassingTests(github, {}, sha, options), /Timed out/);
});
