import "zone.js";
import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient } from "@angular/common/http";
import {
  Component,
  OnInit,
  OnDestroy,
  ChangeDetectorRef,
  provideZoneChangeDetection,
} from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { HttpClient } from "@angular/common/http";
import { firstValueFrom } from "rxjs";
import { StepEditor } from "./step-editor";
import {
  Configuration,
  History,
  Reply,
  SavedScenario,
  Scenario,
  Snapshot,
  Step,
  newStep,
} from "./models";

@Component({
  selector: "app-root",
  standalone: true,
  imports: [CommonModule, FormsModule, StepEditor],
  templateUrl: "./app.html",
})
export class App implements OnInit, OnDestroy {
  tab: "request" | "scenario" | "runs" = "request";
  config: Configuration | null = null;
  connected = false;
  request = newStep();
  requestValid = true;
  reply: Reply | null = null;
  scenario: Scenario = {
    name: "Untitled scenario",
    variables: {},
    steps: [newStep("Step 1")],
  };
  variablesText = "{}";
  selectedScenario = "";
  saved: SavedScenario[] = [];
  invalidSteps = new Set<Step>();
  mode = "COUNT";
  concurrency = 4;
  iterations = 10;
  duration = 10;
  runs: History[] = [];
  selectedRun = "";
  run: Snapshot | null = null;
  compareId = "";
  comparison: Snapshot | null = null;
  busy = false;
  notice = "";
  error = "";
  polling = false;
  private timer?: ReturnType<typeof setInterval>;
  private runGeneration = 0;
  constructor(
    private http: HttpClient,
    private changes: ChangeDetectorRef,
  ) {}
  async ngOnInit() {
    try {
      this.config = await this.get<Configuration>("/config");
      this.connected = true;
      this.request.url = this.config.demoOrigin + "/health";
      await this.loadExample(false);
      await this.refresh();
      this.timer = setInterval(() => void this.tick(), 800);
    } catch (error) {
      this.showError(error);
    } finally {
      this.changes.markForCheck();
    }
  }
  ngOnDestroy() {
    if (this.timer) clearInterval(this.timer);
  }
  get<T>(path: string) {
    return firstValueFrom(this.http.get<T>("/api" + path));
  }
  post<T>(path: string, body: unknown) {
    return firstValueFrom(this.http.post<T>("/api" + path, body));
  }
  async refresh() {
    [this.saved, this.runs] = await Promise.all([
      this.get<SavedScenario[]>("/scenarios"),
      this.get<History[]>("/runs"),
    ]);
  }
  async tick() {
    if (this.polling) return;
    this.polling = true;
    try {
      this.runs = await this.get<History[]>("/runs");
      this.connected = true;
      if (this.run && this.active(this.run.state))
        await this.loadRun(this.run.id, false);
    } catch (error) {
      this.connected = false;
      this.showError(error);
    } finally {
      this.polling = false;
      this.changes.markForCheck();
    }
  }
  active(state: string) {
    return state === "RUNNING" || state === "CANCELLING";
  }
  clearMessages() {
    this.error = "";
    this.notice = "";
  }
  showError(error: unknown) {
    const value = error as {
      error?: { error?: string; message?: string };
      message?: string;
    };
    this.error =
      value.error?.error ||
      value.error?.message ||
      value.message ||
      "Request failed. Check that the backend is running.";
  }
  async perform(action: () => Promise<void>) {
    this.clearMessages();
    this.busy = true;
    try {
      await action();
    } catch (error) {
      this.showError(error);
    } finally {
      this.busy = false;
      this.changes.markForCheck();
    }
  }
  async send() {
    await this.perform(async () => {
      this.reply = await this.post<Reply>("/requests/send", {
        step: this.request,
        variables: this.parseVariables(),
      });
    });
  }
  async loadExample(showNotice = true) {
    const example = await this.get<Scenario>("/example");
    this.applyScenario(example);
    this.selectedScenario = "";
    if (showNotice)
      this.notice = "Loaded a three-step demo. Save it or run it directly.";
    this.changes.markForCheck();
  }
  applyScenario(value: Scenario) {
    this.scenario = structuredClone(value);
    this.variablesText = JSON.stringify(value.variables, null, 2);
    this.invalidSteps.clear();
  }
  chooseScenario() {
    const value = this.saved.find((s) => s.id === this.selectedScenario);
    if (value) this.applyScenario(value.definition);
  }
  async save(copy = false) {
    await this.perform(async () => {
      this.prepareScenario();
      const id = copy ? "" : this.selectedScenario;
      const result = id
        ? await firstValueFrom(
            this.http.put<SavedScenario>("/api/scenarios/" + id, this.scenario),
          )
        : await this.post<SavedScenario>("/scenarios", this.scenario);
      this.selectedScenario = result.id;
      await this.refresh();
      this.notice = "Scenario saved.";
    });
  }
  async deleteScenario() {
    await this.perform(async () => {
      await firstValueFrom(
        this.http.delete("/api/scenarios/" + this.selectedScenario),
      );
      this.selectedScenario = "";
      await this.refresh();
      this.notice =
        "Saved scenario deleted. The editor still contains your copy.";
    });
  }
  prepareScenario() {
    if (this.invalidSteps.size)
      throw new Error("Fix invalid JSON fields before saving or running.");
    this.scenario.variables = this.parseVariables();
  }
  parseVariables(): Record<string, string> {
    let value: unknown;
    try {
      value = JSON.parse(this.variablesText);
    } catch {
      throw new Error("Variables must be a JSON object.");
    }
    if (
      !value ||
      Array.isArray(value) ||
      typeof value !== "object" ||
      Object.values(value).some((v) => typeof v !== "string")
    )
      throw new Error("Variables must map names to string values.");
    return value as Record<string, string>;
  }
  setValidity(step: Step, valid: boolean) {
    if (valid) this.invalidSteps.delete(step);
    else this.invalidSteps.add(step);
  }
  addStep() {
    this.scenario.steps.push(
      newStep("Step " + (this.scenario.steps.length + 1)),
    );
  }
  removeStep(index: number) {
    const [removed] = this.scenario.steps.splice(index, 1);
    this.invalidSteps.delete(removed);
  }
  moveStep(index: number, direction: number) {
    const steps = [...this.scenario.steps];
    [steps[index], steps[index + direction]] = [
      steps[index + direction],
      steps[index],
    ];
    this.scenario.steps = steps;
  }
  async startRun() {
    await this.perform(async () => {
      this.prepareScenario();
      const result = await this.post<Snapshot>("/runs", {
        scenario: this.scenario,
        mode: this.mode,
        concurrency: this.concurrency,
        iterations: this.iterations,
        durationSeconds: this.duration,
      });
      this.run = result;
      this.selectedRun = result.id;
      this.runGeneration++;
      this.tab = "runs";
      await this.refresh();
    });
  }
  async loadRun(id = this.selectedRun, reportErrors = true) {
    if (!id) return;
    const generation = ++this.runGeneration;
    try {
      const result = await this.get<Snapshot>("/runs/" + id);
      if (generation === this.runGeneration) {
        this.run = result;
        this.selectedRun = id;
      }
    } catch (error) {
      if (reportErrors) this.showError(error);
    } finally {
      this.changes.markForCheck();
    }
  }
  async cancelRun() {
    if (!this.run) return;
    const id = this.run.id;
    await this.perform(async () => {
      const result = await this.post<Snapshot>("/runs/" + id + "/cancel", {});
      if (this.selectedRun === id) this.run = result;
    });
  }
  async compare() {
    if (!this.compareId) {
      this.comparison = null;
      return;
    }
    try {
      this.comparison = await this.get<Snapshot>("/runs/" + this.compareId);
    } catch (error) {
      this.showError(error);
    } finally {
      this.changes.markForCheck();
    }
  }
  async importScenario(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    await this.perform(async () => {
      if (file.size > 2_097_152)
        throw new Error("Scenario file exceeds 2 MiB.");
      const value = JSON.parse(await file.text()) as Scenario;
      // Validate with the same backend rules; import creates a saved scenario.
      const saved = await this.post<SavedScenario>("/scenarios", value);
      this.applyScenario(saved.definition);
      this.selectedScenario = saved.id;
      await this.refresh();
      this.notice = "Scenario imported.";
    });
    input.value = "";
  }
  exportScenario() {
    try {
      this.prepareScenario();
      this.download("scenario.json", this.scenario);
    } catch (error) {
      this.showError(error);
    }
  }
  download(name: string, value: unknown) {
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
    URL.revokeObjectURL(url);
  }
  chartPoints(run: Snapshot) {
    const maximum = Math.max(1, ...run.series.map((b) => b.meanMs));
    const last = Math.max(1, ...run.series.map((b) => b.second));
    return run.series
      .map(
        (b) =>
          `${20 + (b.second / last) * 760},${145 - (b.meanMs / maximum) * 125}`,
      )
      .join(" ");
  }
  chartMaximum(run: Snapshot) {
    return Math.max(1, ...run.series.map((b) => b.meanMs));
  }
  delta(current: number, previous: number) {
    return previous === 0
      ? "n/a"
      : (((current - previous) / previous) * 100).toFixed(1) + "%";
  }
}

bootstrapApplication(App, {
  providers: [provideHttpClient(), provideZoneChangeDetection()],
}).catch((error) => console.error(error));
