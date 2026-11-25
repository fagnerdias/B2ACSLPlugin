# gui: Frama-C WP wizard (Plugin · ATL Rules · compare)

A Swing desktop tool that verifies the C functions of an Atelier B project with Frama-C WP.

It works in three steps. First it generates the ACSL specification of the project with the B2ACSL **Plugin**, with the **ATL Rules** (the [`rules/`](../rules) submodule), or with both. Then it runs WP on each function, one `frama-c` run per function. Finally it shows a report. With both generators, the report compares the two specifications: their results per function and their contracts side by side.

## Requirements

- Java 21 and Maven, to build.
- `frama-c` on the `PATH`.
- At least one prover known to Why3: Alt-Ergo, CVC5 or Z3.
- `rules/dist/b2acsl.jar` present when building. The ATL Rules are bundled into the GUI jar, so the user never selects them. To rebuild them, run `make build-rules`.

## Build and run

As in the pipeline (`com.example.Main`), the project comes from the command line: the project root or its `bdp/` folder, as Atelier B passes it.

```sh
mvn -DskipTests package                          # builds every module, including gui/target/b2acsl-gui-0.1.0-all.jar
make gui PROJECT=examples/AddRunner              # runs it (builds the jar first if it is missing)
java -jar gui/target/b2acsl-gui-0.1.0-all.jar examples/AddRunner/bdp
```

Without a project, or with a folder that is not a B project, the wizard opens with an error dialog that says how to start it.

These system properties are optional (`make gui GUI_ARGS="-D..."`):

| Property | Effect |
|---|---|
| `b2acsl.gui.rulesJar` | Use this ATL Rules jar instead of the bundled one. |
| `b2acsl.gui.libDir` | Use this B2ACSLLib folder for the ATL side instead of the bundled one. |
| `b2acsl.wp.functionTimeout` | Wall-clock limit for one `frama-c -wp` run, in seconds (default 600). |

## How to use it

1. **Project.** Shows the project given on the command line: its folder (which contains `bdp/` with the `.bxml` files and `lang/c/` with the generated C code), its name (editable) and the functions named `<Machine>__<Operation>` defined in `lang/c/*.c`. As in the pipeline, all of them are verified.
2. **Specification.** Pick Plugin, ATL Rules, or Plugin + ATL Rules (compare), then click **Generate**. The log streams the generator output. If a generator fails, the error dialog shows:
   - which step failed;
   - the details: stage, function, rule and exit code;
   - the generator output.

   In compare mode, when only one generator failed, you can **continue with the generator that worked**.
3. **WP Config.** Choose the provers, the timeout per goal, the output type (`status` → `-wp-status`, `print` → `-wp-print`), and whether to run smoke tests and counterexamples. Provers that are not installed are disabled.
4. **Progress.** One row per function and specification, in a list that scrolls to the running function. Each row has the same statuses as the pipeline's progress dialog: Pending, Running…, Proved, Partial, Timeout and Failed. While a function runs, the row shows how many goals WP scheduled for it (`[wp] N goals scheduled`); when it ends, the row shows proved / total goals. The raw Frama-C output is shown below the list during the verification and scrolls by itself; **Hide Logs** hides it and **Save Log (.txt)** saves it. **Close** stops the run: it kills `frama-c` together with the provers it started.
5. **Report.** The single-generator report shows the summary, one block per function and the relevant WP output. The comparison report has two tabs:
   - **Results:** a verdict, the summaries side by side, and a per-function table with a difference sentence. Rows whose results differ are highlighted.
   - **Specifications:** the contract of the selected function from both files, aligned with line numbers and scrolling together. Below them come the differences explained in plain language and their impact on the verification.

   Closing a report takes you back to step 3, where the generated specifications are kept so you can re-run with other settings.

Every window (the three wizard steps, the progress window, the reports, the error dialog and the log viewer) opens with the same size, 960 × 720 (smaller on a smaller screen), centred on the screen, so going from one window to the next keeps the same frame. Lists, logs and diff panes take the free height; the wizard's forms sit in a centred column.

The last generation mode, provers, timeout and options are saved between sessions with `java.util.prefs`.

## Where files go

Each generation creates a run folder inside the project. The tool never writes to the project's `bdp/` or `lang/c/`.

```
<project>/b2acsl-wp/<yyyyMMdd_HHmmss>/
  merged_code.c                  single mode (plugin/ and atl/merged_code.c in compare mode)
  generation.log  verification.log
  plugin-generation.json  atl-generation.json
  work-plugin/  work-atl/        isolated copies of bdp/*.bxml and lang/c/*.c, plus the generated .acsl files
```

## What it runs

- **Generation.** Each generator runs in its own headless child JVM, `com.example.gui.generation.GenerationChildMain`. This keeps the translator's static state, and the existing Swing dialogs of the Frama-C runner, out of the GUI process, and lets the GUI kill a run. The child:
  1. copies the project with the comparator's `Workspace`;
  2. runs `PluginSpecGenerator` or `RulesSpecGenerator`;
  3. runs `FramaCRunner.buildMergedCode` (`-acsl-import` plus post-processing);
  4. publishes `merged_code.c`.
- **Verification.** The GUI uses the same per-function command as the existing pipeline (`FramaCRunner.buildWpCommand`):

  ```
  frama-c -wp merged_code.c -wp-fct F -wp-prover P1,P2 [-wp-smoke-tests] [-wp-counter-examples] -wp-rte -wp-timeout S -wp-status|-wp-print
  ```

  Goal counts are parsed by the same `VerificationReportData` the CLI uses, and each function is classified as the pipeline does (`FramaCRunner.markCompletedFromSummary`): all goals proved (or no goals) → Proved; timeouts and no failures → Timeout; some goals proved → Partial; otherwise Failed. A Frama-C error is Failed and exceeding the wall-clock limit is Timeout. A smoke test passes when WP **cannot** prove the smoke goal.

  The GUI does not pass `-wp-split`, the same default as the CLI's options dialog. The comparator does pass it by default. So counts can differ from `comparator-out` for functions with goals that can be split. For example, `count_array`'s `CountArray__count` gives 27/30 here and 34/38 with `-wp-split`, from the same `merged_code.c`.
- **Tool check at start-up.** The tool runs `frama-c -version` and `why3 config list-provers`. It does **not** run `why3 config detect`, which rewrites `~/.why3.conf`.

## Code layout (`com.example.gui`)

| Package | Contents |
|---|---|
| `model` | Records and enums: generators, modes, project, WP settings, results, verdict, error report. |
| `generation` | `SpecificationGenerator` (Plugin and ATL), the child JVM, `GenerationService`, `RunDirectory`, `BundledTools`. |
| `verification` | `VerificationService`, `ProcessSupervisor` (streaming, wall-clock limit, cancellation of the process tree), `ToolDetector`, `WpCommandFactory`. |
| `parsing` | `CFunctionScanner`/`ProjectScanner` (project folder or `bdp/`, functions), `WpOutputParser` (unproved goals, timeouts, smoke tests, counterexamples), `ContractExtractor`, `GenerationErrorParser`. |
| `comparison` | `ResultComparator` (same/different, difference sentences, verdict), `SpecificationDiffer` (java-diff-utils, aligned rows), `ChangeDescriber`, `CsvExporter`. |
| `ui` | `Theme`/`Palette`; components (`SummaryCard`, `StatusBadge`, `OperationRow`, `TitledGroup`, `Callout`, `WizardStepIndicator`, `DiffPane`, `LinkLabel`); the wizard steps; `ProgressWindow`; the reports; `ErrorDialog`. |

External processes never run on the Event Dispatch Thread. They run in `SwingWorker`s, and the UI is updated through `publish`/`process`.

## Tests

```sh
mvn -pl gui -am test
```

The unit tests cover the WP output parser, function detection, contract extraction, the contract diff and its sentences, the result comparison and verdict, the CSV export and the prover detection. They use real samples from `comparator-out/` and `examples/`.
