# Release contract

The version in `gradle.properties` identifies every published artifact. A release uses an existing
Git tag equal to that version, optionally prefixed with `v`. A version keeps one tag spelling
throughout the release lifecycle. Root tasks and their verification
boundaries are generated in [Development](development.md#build-and-release-tasks).

## Prepare and edit a draft

Commit the library and benchmark harness together, then let the owner create the release tag.
Use GitHub's **Generate release notes** when preparing the draft, then edit that generated text.
The optional [Prepare release draft](../.github/workflows/draft-release.yml) workflow performs the
same generation through GitHub's API. An optional previous tag controls the comparison range.
Repeating the workflow resumes an existing draft and preserves its edits, including drafts prepared
through the GitHub UI. The owner reviews and publishes the draft through GitHub.

Use generated notes as the authoring baseline. The performance marker pair identifies the section
managed by automation; keep author-owned text outside that section.

## Verify and publish artifacts

At publication, [Publish](../.github/workflows/publish.yml) first reads the latest release body and
ensures a performance placeholder exists. The tag must match the artifact version and the commit
captured by the release event. All subsequent jobs use that exact commit.

Publication checks the latest existing push run of [Test](../.github/workflows/test.yml) for the
exact release commit. Push CI tests that commit directly, while pull-request CI can use a merge
ref. A completed successful run satisfies the prerequisite. An in-progress run
is observed for up to 20 minutes. Missing, failed, cancelled or timed-out CI prevents publication;
the release workflow does not dispatch another test run. Test CI runs `verifyManaged` on Ubuntu
and `verifyTargets` on macOS for device and Kotlin target coverage. Both use Gradle's `--continue`
to collect independent failures while retaining a failing workflow conclusion.

`verifyRelease` checks version and clean-tag provenance and assembles library artifacts. Test CI
provides test verification. Publication uses in-memory signing and credentials scoped to upload steps.
GitHub Packages uses Gradle's `--continue` to attempt all independent publication tasks. Any
Packages publication failure fails the step and prevents Maven Central publication. Maven Central
runs after all Packages publications succeed. Publication failure records the workflow link in
the performance section and skips benchmarks.

`publishLocal` runs repository JVM tests and publishes to Maven Local for integration work.
`publishCentral` and `publishPackages` require `-PreleaseTag=<tag>` and depend on release verification.
Publication tasks depend directly on the relevant verification task, so a failed gate stops
uploads even when Gradle runs with `--continue`.

## Run and retain benchmarks

After successful Maven Central publication, the reusable benchmark workflow runs on the published
commit. It records environment and commit provenance, asks JMH for the workload matrix and runs
full JVM time/allocation measurements. The report validates every discovered parameter combination,
finite metrics, units and unique rows. Benchmark names, parameter values and table rows come from
JMH output; adding a benchmark updates discovery without a separate scenario inventory.

The managed table reports warmed time, JMH error and managed allocation. Motion workloads use
unpaced CPU raster rendering. Shared CI runner conditions vary; these values establish release
observations. Performance comparisons require matched workloads and measurement environments.
See [Performance verification](performance.md) for metric boundaries and comparison requirements.

Raw JSON, the discovered matrix, environment metadata and generated Markdown are retained as
workflow artifacts for 90 days. Release notes link to that run. Attach selected artifacts to the
release when longer retention is required. A manual benchmark run generates artifacts and a job
summary while leaving release notes unchanged.

Include local comparative results when establishing a harness baseline or when a specific change
warrants a separate investigation. Keep that comparison in an author-owned section outside the
managed performance block, with identified revisions, matched workloads, relevant environment
details and measurement limits. Routine release measurements belong to the managed CI section.

## Manage the performance section

The release body contains one managed block:

```markdown
<!-- backdrop:performance:start -->
## Performance

Publication and benchmark results are pending.
<!-- backdrop:performance:end -->
```

`Performance` is the last level-two section. GitHub's generated `**Full Changelog**` footer follows
it. Author-owned sections precede this block; manual text inside the block belongs to automation.
The startup and completion paths share the same insertion routine. When the marker pair is absent,
the routine inserts it before the changelog footer, or at the end when that footer is absent.
A retained block is moved after author-owned sections as needed. Duplicate or incomplete markers
fail validation to protect adjacent text. Keep the generated changelog footer after all sections.

Completion fetches the latest release body before replacing the block, preserving edits outside
it. If someone removes the whole placeholder during the run, completion recreates it with the
results. The completion job uses `always()` to record benchmark failure or cancellation in the same section.
If the completion job itself is interrupted, retry it to finish the update. Workflow
concurrency serializes release operations for each tag. Re-run failed benchmark jobs to retry
measurement without starting a new artifact publication.

The automation follows GitHub's official [generated release notes API](https://docs.github.com/en/rest/releases/releases#generate-release-notes-content-for-a-release),
[release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release),
[workflow run status API](https://docs.github.com/en/rest/actions/workflow-runs#list-workflow-runs-for-a-workflow),
and [reusable workflow contract](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows).
