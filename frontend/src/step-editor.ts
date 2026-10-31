import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnChanges,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Step, Check } from "./models";

@Component({
  selector: "step-editor",
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="request-line">
      <label class="method"
        >Method<select [(ngModel)]="step.method">
          @for (method of methods; track method) {
            <option>{{ method }}</option>
          }
        </select></label
      >
      <label class="grow"
        >URL<input
          [(ngModel)]="step.url"
          placeholder="http://127.0.0.1:8086/health"
          spellcheck="false"
      /></label>
    </div>
    <div class="form-row">
      <label class="grow"
        >Step name<input [(ngModel)]="step.name" maxlength="80"
      /></label>
      <label
        >Timeout (ms)<input
          type="number"
          [(ngModel)]="step.timeoutMs"
          min="100"
          max="10000"
      /></label>
      <label
        >Expected status<input
          type="number"
          [(ngModel)]="step.expectedStatus"
          min="0"
          max="599"
      /></label>
    </div>
    <details [open]="expanded">
      <summary>
        Headers, body, assertions and extraction
        <span class="muted">({{ step.checks.length }} assertions)</span>
      </summary>
      <div class="two-columns fields">
        <label
          >Headers · JSON object<textarea
            rows="4"
            [(ngModel)]="headersText"
            (ngModelChange)="updateMap('headers', $event)"
            spellcheck="false"
          ></textarea>
        </label>
        <label
          >Request body<textarea
            rows="4"
            [(ngModel)]="step.body"
            spellcheck="false"
            placeholder="Optional request body"
          ></textarea>
        </label>
      </div>
      <div class="section-line">
        <h3>Assertions</h3>
        <button type="button" class="small" (click)="addCheck()">
          Add assertion
        </button>
      </div>
      <p class="hint">
        JSON paths use JSON Pointer, e.g. /items/0/name. Expected status 0
        accepts any status.
      </p>
      @for (check of step.checks; track $index; let i = $index) {
        <div class="assertion-row">
          <label
            >Type<select [(ngModel)]="check.type">
              <option>JSON_EQUALS</option>
              <option>BODY_CONTAINS</option>
              <option>HEADER_EQUALS</option>
            </select></label
          >
          <label
            >Path / header<input [(ngModel)]="check.path" placeholder="/token"
          /></label>
          <label class="grow"
            >Expected value<input [(ngModel)]="check.expected"
          /></label>
          <button
            type="button"
            class="small"
            (click)="step.checks.splice(i, 1)"
            aria-label="Remove assertion"
          >
            Remove
          </button>
        </div>
      }
      <label class="fields"
        >Extract variables · JSON object<textarea
          rows="3"
          [(ngModel)]="extractText"
          (ngModelChange)="updateMap('extract', $event)"
          spellcheck="false"
        ></textarea>
      </label>
      <p class="hint">
        Map variable names to JSON Pointers, for example
        {{ extractionExample }}. Use variables in later steps with
        {{ variableExample }}.
      </p>
      @if (error) {
        <p class="error" role="alert">{{ error }}</p>
      }
    </details>
  `,
})
export class StepEditor implements OnChanges {
  @Input({ required: true }) step!: Step;
  @Input() expanded = false;
  @Output() validity = new EventEmitter<boolean>();
  methods = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
  headersText = "{}";
  extractText = "{}";
  error = "";
  extractionExample = '{"token":"/token"}';
  variableExample = "${token}";
  ngOnChanges() {
    this.headersText = JSON.stringify(this.step.headers, null, 2);
    this.extractText = JSON.stringify(this.step.extract, null, 2);
    this.error = "";
    this.validity.emit(true);
  }
  updateMap(field: "headers" | "extract", text: string) {
    try {
      const value: unknown = JSON.parse(text);
      if (
        !value ||
        Array.isArray(value) ||
        typeof value !== "object" ||
        Object.values(value).some((v) => typeof v !== "string")
      )
        throw new Error();
      this.step[field] = value as Record<string, string>;
      // Check both fields so fixing one does not hide an error in the other.
      for (const input of [this.headersText, this.extractText]) {
        const parsed = JSON.parse(input);
        if (
          !parsed ||
          Array.isArray(parsed) ||
          typeof parsed !== "object" ||
          Object.values(parsed).some((v) => typeof v !== "string")
        )
          throw new Error();
      }
      this.error = "";
      this.validity.emit(true);
    } catch {
      this.error =
        "Headers and extraction must be JSON objects with string values.";
      this.validity.emit(false);
    }
  }
  addCheck() {
    this.step.checks.push({ type: "JSON_EQUALS", path: "", expected: "" });
  }
}
