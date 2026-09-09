import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {HttpClient} from '@angular/common/http';
import {PluginConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, InputModule} from '@valtimo/components';
import {ConfigService} from '@valtimo/shared';
import {BehaviorSubject, combineLatest, Observable, of, Subscription, take} from 'rxjs';
import {catchError, filter, map, switchMap} from 'rxjs/operators';
import {GraphMailPluginConfig} from '../../models';

interface TestSendStatus {
  success: boolean;
  message: string;
  statusCode: number;
  isNetworkError?: boolean;
}

@Component({
  selector: 'valtimo-graph-mail-configuration',
  templateUrl: './graph-mail-configuration.component.html',
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule, FormModule, InputModule],
})
export class GraphMailPluginConfigurationComponent
  implements PluginConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<GraphMailPluginConfig>;

  @Output() valid = new EventEmitter<boolean>();
  @Output() configuration = new EventEmitter<GraphMailPluginConfig>();

  private saveSubscription!: Subscription;
  private testEmailSubscription: Subscription | undefined;
  private readonly formValue$ = new BehaviorSubject<GraphMailPluginConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  // UUID van de opgeslagen pluginconfiguratie. Null voor nieuwe (nog niet opgeslagen) configuraties.
  savedConfigurationId: string | null = null;

  // True when multiple configurations for this plugin exist and none can be uniquely
  // matched by title — sending a test email would silently use the wrong configuration's
  // credentials, so the test section is blocked with an explicit message instead.
  configurationAmbiguous = false;

  // clientSecret lives outside v-form so we can use a native <input type="password">.
  // The v-input component does not reliably mask password fields.
  clientSecretValue = '';

  // Test send
  testRecipient = '';
  testSenderMailbox = '';
  testLoading = false;
  testStatus: TestSendStatus | null = null;

  // True when the form has enough data to show the test section.
  testSectionVisible = false;

  // The allowlist as it was when the configuration was loaded, so a change can be detected.
  // Null for new configurations (nothing stored yet).
  private originalAllowedSenders: string | null = null;

  // True when the allowlist has been changed on an existing configuration and the secret has not
  // been re-entered. Drives the message next to the secret field.
  secretRequiredForAllowlistChange = false;

  // Inline validation flags
  tenantIdInvalid = false;
  clientIdInvalid = false;
  allowedSendersInvalid = false;

  // Aligned with the backend EMAIL_REGEX in GraphMailValidation.kt — keep in sync.
  private static readonly EMAIL_RE =
    /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,}$/;

  // Allowlist entry: a full email address or a domain entry such as '@gemeente.nl'.
  private static readonly DOMAIN_ENTRY_RE =
    /^@[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,}$/;

  // Azure Tenant IDs and Client IDs are always GUIDs.
  private static readonly UUID_RE =
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

  // Every first-party Valtimo plugin configuration component builds API URLs from
  // ConfigService.config.valtimoApi.endpointUri rather than hardcoding '/api/...' — this
  // keeps the plugin working when frontend and backend are served from different origins.
  constructor(
    private readonly http: HttpClient,
    private readonly configService: ConfigService,
  ) {}

  private get apiUrl(): string {
    return this.configService.config.valtimoApi.endpointUri;
  }

  ngOnInit(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid && formValue) {
            // clientSecret and testSenderMailbox live outside v-form; merge them in.
            this.configuration.emit({
              ...formValue,
              clientSecret: this.clientSecretValue || undefined,
              testSenderMailbox: this.testSenderMailbox || undefined,
            });
          }
        });
    });

    if (this.prefillConfiguration$) {
      // Initialise testSenderMailbox from the saved configuration.
      this.prefillConfiguration$
        .pipe(filter(config => !!config), take(1))
        .subscribe((config: any) => {
          this.testSenderMailbox = config?.testSenderMailbox ?? '';
          this.originalAllowedSenders = config?.allowedSenders ?? null;
        });

      // Resolve the saved configuration UUID for the test-send endpoint.
      this.prefillConfiguration$
        .pipe(
          filter(config => !!config),
          take(1),
          switchMap((config: any) => {
            // 1. Some Valtimo versions include the id directly in the prefill object.
            if (config?.id) {
              return of({id: config.id as string, ambiguous: false});
            }

            // 2. Fallback: GET /api/v1/plugin/configuration and match by pluginId / title.
            // URL-based UUID extraction was removed — it is too fragile when the URL contains
            // multiple UUIDs (case IDs, document IDs, etc.) and could select the wrong config.
            return this.http.get<any[]>(`${this.apiUrl}v1/plugin/configuration`).pipe(
              map(configs => {
                const allForPlugin = configs.filter(c =>
                  c.pluginDefinitionKey === this.pluginId ||
                  c.definitionKey === this.pluginId ||
                  c.pluginDefinition?.key === this.pluginId
                );

                if (allForPlugin.length === 1) {
                  return {id: (allForPlugin[0].id as string) ?? null, ambiguous: false};
                }

                const byTitle = allForPlugin.find(c =>
                  c.title === (config as any).configurationTitle ||
                  c.configurationTitle === (config as any).configurationTitle
                );
                if (byTitle?.id) {
                  return {id: byTitle.id as string, ambiguous: false};
                }

                // Multiple configurations exist and none matches by title — guessing here
                // (e.g. picking allForPlugin[0]) risks sending a test email using a *different*
                // configuration's credentials than the one the admin is looking at. Block
                // instead of guessing; see graph-mail-configuration.component.spec.ts.
                return {id: null, ambiguous: allForPlugin.length > 1};
              }),
              catchError(() => of({id: null, ambiguous: false}))
            );
          })
        )
        .subscribe(({id, ambiguous}) => {
          this.savedConfigurationId = id;
          this.configurationAmbiguous = ambiguous;
          // Both of the above land after the prefill has already been validated, and Save is gated
          // on that validity — so without recomputing here, Save stays disabled on an existing
          // configuration until the admin touches a field.
          const formValue = this.formValue$.getValue();
          if (formValue) this.updateValidAndVisibility(formValue);
        });
    }
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
    this.testEmailSubscription?.unsubscribe();
  }

  formValueChange(formValue: GraphMailPluginConfig): void {
    this.formValue$.next(formValue);
    this.testStatus = null;

    this.tenantIdInvalid = !!(
      formValue.tenantId &&
      !GraphMailPluginConfigurationComponent.UUID_RE.test(formValue.tenantId)
    );
    this.clientIdInvalid = !!(
      formValue.clientId &&
      !GraphMailPluginConfigurationComponent.UUID_RE.test(formValue.clientId)
    );
    this.allowedSendersInvalid = !!(
      formValue.allowedSenders &&
      !GraphMailPluginConfigurationComponent.isValidAllowlist(formValue.allowedSenders)
    );

    this.updateValidAndVisibility(formValue);
  }

  // Every comma-separated entry must be a full email address or an '@domain' entry.
  private static isValidAllowlist(value: string): boolean {
    const entries = value.split(',').map(entry => entry.trim()).filter(entry => !!entry);
    return (
      entries.length > 0 &&
      entries.every(
        entry =>
          GraphMailPluginConfigurationComponent.EMAIL_RE.test(entry) ||
          GraphMailPluginConfigurationComponent.DOMAIN_ENTRY_RE.test(entry)
      )
    );
  }

  // Compared as a set of trimmed, lowercased entries — the same normalisation the backend applies
  // in AllowedSendersChangeGuard, so the two cannot disagree about what counts as "changed".
  // Reordering or respacing the same addresses is not a change.
  private allowlistChanged(current: string | undefined): boolean {
    // Mirrors parseStringListParam in GraphMailPlugin.kt, which the backend guard uses: the
    // bracketed JSON-array form is accepted there, so `["a@x.nl"]` and `a@x.nl` are the same list.
    // Deduplicated and sorted because the guard compares as a Set. Any divergence here means the
    // form and the backend disagree about whether the list changed.
    const normalise = (value: string | null | undefined): string => {
      const raw = (value ?? '').trim();
      const entries = raw.startsWith('[')
        ? raw
            .replace(/^\[/, '')
            .replace(/]$/, '')
            .split(',')
            .map(entry => entry.trim().replace(/^["']|["']$/g, '').trim())
        : raw.split(',').map(entry => entry.trim());

      return Array.from(
        new Set(entries.map(entry => entry.toLowerCase()).filter(entry => !!entry)),
      )
        .sort()
        .join(',');
    };

    return normalise(current) !== normalise(this.originalAllowedSenders);
  }

  // Called when the password input changes so validity re-evaluates without a v-form event.
  onSecretChange(): void {
    const formValue = this.formValue$.getValue();
    if (formValue) this.updateValidAndVisibility(formValue);
  }

  private updateValidAndVisibility(formValue: GraphMailPluginConfig): void {
    // When editing an existing configuration the backend never returns the secret,
    // so an empty field means "unchanged" — the form is still valid without it.
    const isNewConfiguration = !this.savedConfigurationId;

    // ...except when the sender allowlist is being changed. That list bounds which mailboxes this
    // plugin may send as, so widening it is a privilege escalation and should be provable by
    // whoever holds the credential — not merely by whoever has the admin screen open. The backend
    // enforces the same rule (AllowedSendersChangeGuard); this is the immediate feedback.
    this.secretRequiredForAllowlistChange =
      !isNewConfiguration &&
      this.allowlistChanged(formValue.allowedSenders) &&
      !this.clientSecretValue;

    const secretValid = isNewConfiguration
      ? !!this.clientSecretValue
      : !this.secretRequiredForAllowlistChange;

    const valid = !!(
      formValue.configurationTitle &&
      formValue.tenantId &&
      !this.tenantIdInvalid &&
      formValue.clientId &&
      !this.clientIdInvalid &&
      formValue.allowedSenders &&
      !this.allowedSendersInvalid &&
      secretValid
    );
    this.valid$.next(valid);
    this.valid.emit(valid);

    // Show the test section for existing configs as soon as tenantId + clientId are valid.
    // For new configs the secret is required too (it isn't stored yet).
    const isExistingConfig = !!this.savedConfigurationId;
    this.testSectionVisible = !!(
      formValue.tenantId &&
      !this.tenantIdInvalid &&
      formValue.clientId &&
      !this.clientIdInvalid &&
      (this.clientSecretValue || isExistingConfig)
    );
  }

  get canSendTest(): boolean {
    return (
      !!this.savedConfigurationId &&
      !this.configurationAmbiguous &&
      this.testSectionVisible &&
      GraphMailPluginConfigurationComponent.EMAIL_RE.test(this.testSenderMailbox) &&
      GraphMailPluginConfigurationComponent.EMAIL_RE.test(this.testRecipient) &&
      !this.testLoading
    );
  }

  get testSenderInvalid(): boolean {
    return (
      this.testSectionVisible &&
      !!this.testSenderMailbox &&
      !GraphMailPluginConfigurationComponent.EMAIL_RE.test(this.testSenderMailbox)
    );
  }

  sendTestEmail(): void {
    const form = this.formValue$.getValue();
    if (!form || !this.canSendTest || !this.savedConfigurationId || this.configurationAmbiguous) return;

    this.testLoading = true;
    this.testStatus = null;

    this.testEmailSubscription?.unsubscribe();
    this.testEmailSubscription = this.http
      .post<TestSendStatus>(`${this.apiUrl}v1/plugin/entra/test-send`, {
        pluginConfigurationId: this.savedConfigurationId,
        recipient: this.testRecipient,
        senderMailbox: this.testSenderMailbox,
      })
      .pipe(take(1))
      .subscribe({
        next: result => {
          this.testLoading = false;
          this.testStatus = result;
        },
        error: err => {
          this.testLoading = false;
          // err.error contains the parsed JSON body for 4xx/5xx responses.
          // Prefer the backend's message over Angular's generic HTTP error string.
          const body = err.error;
          const hasBackendMessage = typeof body?.message === 'string' && body.message;
          this.testStatus = {
            success: false,
            message: hasBackendMessage ? body.message : (err.message ?? 'unknown'),
            statusCode: err.status ?? 0,
            isNetworkError: !hasBackendMessage,
          };
        },
      });
  }
}
