import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {HttpClient} from '@angular/common/http';
import {
  PluginConfiguration,
  PluginConfigurationComponent,
  PluginManagementService,
  PluginTranslatePipeModule,
} from '@valtimo/plugin';
import {FormModule, InputModule, TooltipIconModule} from '@valtimo/components';
import {ConfigService} from '@valtimo/shared';
import {BehaviorSubject, combineLatest, Observable, of, Subscription, take} from 'rxjs';
import {catchError, filter, map, switchMap} from 'rxjs/operators';
import {GraphMailPluginConfig} from '../../models';

interface LoginOption {
  id: string;
  title: string;
}

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
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule, FormModule, InputModule, TooltipIconModule],
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
  // login, so the test section is blocked with an explicit message instead.
  configurationAmbiguous = false;

  // Test send
  testRecipient = '';
  testSenderMailbox = '';
  testLoading = false;
  testStatus: TestSendStatus | null = null;

  // True when the form has enough data to show the test section.
  testSectionVisible = false;

  // authenticationPluginConfiguration lives outside v-form, in a native <select>: v-select is a combobox whose
  // text can be deleted without clearing the selection, so what it shows and what it saves could differ.
  loginConfigurationValue = '';

  // The Http Client Authentication configurations. Null while loading; empty when there are none or the request
  // fails.
  loginOptions: Array<LoginOption> | null = null;

  // The reference of the configuration being edited, when it is not among the options (deleted, or the module is
  // missing). Offered as an option of its own so it is not dropped unseen; while it is selected the form can't be
  // saved, as the backend would refuse it.
  unknownLoginConfiguration: string | null = null;
  private storedLoginConfiguration = '';

  // The Graph API URL (baseUrl); only shown, and saved, with a selected Http Client Authentication configuration. A
  // native input outside v-form, like the selection it belongs to.
  baseUrlValue = '';

  private loginSubscription: Subscription | undefined;
  private prefillSubscription: Subscription | undefined;

  // The deprecated client-secret login of 1.x, used while no Http Client Authentication configuration is selected,
  // so configurations from 1.x keep working and stay editable. Native inputs outside v-form: they are only shown
  // without a selection, and a native <input type="password"> reliably masks the secret.
  clientSecretTenantId = '';
  clientSecretClientId = '';
  clientSecretValue = '';
  // True when the configuration being edited has the client-secret login stored. The backend never returns the
  // secret, so an empty secret field then means "unchanged".
  clientSecretStored = false;

  // Inline validation flags
  allowedSendersInvalid = false;

  // Aligned with the backend EMAIL_REGEX in GraphMailValidation.kt — keep in sync.
  private static readonly EMAIL_RE =
    /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,}$/;

  // Allowlist entry: a full email address or a domain entry such as '@gemeente.nl'.
  private static readonly DOMAIN_ENTRY_RE =
    /^@[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,}$/;

  // Aligned with isValidTenantId in GraphMailValidation.kt: a GUID, a verified domain or an alias such as
  // 'organizations', without characters that would change the token URL.
  private static readonly TENANT_ID_RE = /^[^\s/?#\\@]{1,253}$/;

  // Every first-party Valtimo plugin configuration component builds API URLs from
  // ConfigService.config.valtimoApi.endpointUri rather than hardcoding '/api/...' — this
  // keeps the plugin working when frontend and backend are served from different origins.
  constructor(
    private readonly http: HttpClient,
    private readonly configService: ConfigService,
    private readonly pluginManagementService: PluginManagementService,
  ) {}

  private get apiUrl(): string {
    return this.configService.config.valtimoApi.endpointUri;
  }

  ngOnInit(): void {
    this.loginSubscription = this.loadLoginOptions();

    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid && formValue) {
            // testSenderMailbox and the login live outside v-form; merge them in.
            this.configuration.emit({
              ...formValue,
              testSenderMailbox: this.testSenderMailbox || undefined,
              authenticationPluginConfiguration: this.loginConfigurationValue,
              baseUrl: this.baseUrlSaved(),
              ...this.clientSecretLoginValue(),
            });
          }
        });
    });

    if (this.prefillConfiguration$) {
      // Every prefill replaces the state of the previous one: the screen is reused when the admin opens another
      // configuration, and state kept from the first would be saved onto the second.
      this.prefillSubscription = this.prefillConfiguration$
        .pipe(
          filter(config => !!config),
          switchMap((config: any) => {
            this.testSenderMailbox = config?.testSenderMailbox ?? '';
            this.storedLoginConfiguration = config?.authenticationPluginConfiguration ?? '';
            this.loginConfigurationValue = this.storedLoginConfiguration;
            this.baseUrlValue = config?.baseUrl ?? '';
            this.clientSecretTenantId = config?.tenantId ?? '';
            this.clientSecretClientId = config?.clientId ?? '';
            this.clientSecretValue = '';
            this.clientSecretStored = !!config?.tenantId && !!config?.clientId;
            this.savedConfigurationId = null;
            this.configurationAmbiguous = false;
            this.testStatus = null;
            this.updateUnknownLogin();
            return this.configurationIdOf(config);
          }),
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

  // The UUID of the saved configuration, for the test-send endpoint.
  private configurationIdOf(config: any): Observable<{id: string | null; ambiguous: boolean}> {
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
          c.title === config.configurationTitle ||
          c.configurationTitle === config.configurationTitle
        );
        if (byTitle?.id) {
          return {id: byTitle.id as string, ambiguous: false};
        }

        // Multiple configurations exist and none matches by title — guessing here
        // (e.g. picking allForPlugin[0]) risks sending a test email using a *different*
        // configuration's login than the one the admin is looking at. Block
        // instead of guessing; see graph-mail-configuration.component.spec.ts.
        return {id: null, ambiguous: allForPlugin.length > 1};
      }),
      catchError(() => of({id: null, ambiguous: false}))
    );
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
    this.testEmailSubscription?.unsubscribe();
    this.loginSubscription?.unsubscribe();
    this.prefillSubscription?.unsubscribe();
  }

  formValueChange(formValue: GraphMailPluginConfig): void {
    this.formValue$.next(formValue);
    this.testStatus = null;

    this.allowedSendersInvalid = !!(
      formValue.allowedSenders &&
      !GraphMailPluginConfigurationComponent.isValidAllowlist(formValue.allowedSenders)
    );

    this.updateValidAndVisibility(formValue);
  }

  get loginSelectionUnknown(): boolean {
    return !!this.loginConfigurationValue && this.loginConfigurationValue === this.unknownLoginConfiguration;
  }

  onLoginChange(): void {
    const formValue = this.formValue$.getValue();
    if (formValue) this.updateValidAndVisibility(formValue);
  }

  // The client-secret fields are shown, and saved, only while no Http Client Authentication configuration is selected.
  get clientSecretLoginShown(): boolean {
    return !this.loginConfigurationValue;
  }

  get baseUrlInvalid(): boolean {
    const value = this.baseUrlValue.trim();
    return (
      !this.clientSecretLoginShown &&
      !!value &&
      !GraphMailPluginConfigurationComponent.isValidBaseUrl(value)
    );
  }

  // Aligned with isValidBaseUrl in GraphMailValidation.kt: an https URL with a host and at most a path.
  private static isValidBaseUrl(value: string): boolean {
    if (/\s/.test(value) || value.includes('?') || value.includes('#')) return false;
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && !!url.hostname && !url.username && !url.password;
    } catch {
      return false;
    }
  }

  // Cleared without a selection: Valtimo overwrites properties that are not secret with what is saved, and the
  // backend refuses a Graph API URL with the client-secret login.
  private baseUrlSaved(): string | undefined {
    if (this.clientSecretLoginShown) return undefined;
    return this.baseUrlValue.trim() || undefined;
  }

  get tenantIdInvalid(): boolean {
    return (
      !!this.clientSecretTenantId &&
      !GraphMailPluginConfigurationComponent.TENANT_ID_RE.test(this.clientSecretTenantId)
    );
  }

  private get clientSecretLoginUsable(): boolean {
    return (
      this.clientSecretLoginShown &&
      !!this.clientSecretTenantId &&
      !this.tenantIdInvalid &&
      !!this.clientSecretClientId.trim() &&
      (this.clientSecretEntered || this.clientSecretStored)
    );
  }

  // A secret of only whitespace counts as not entered: the backend refuses a blank secret, and saving it would
  // replace a stored one.
  private get clientSecretEntered(): boolean {
    return !!this.clientSecretValue.trim();
  }

  // What to save of the client-secret login. With a selection, tenantId and clientId are cleared: Valtimo overwrites
  // properties that are not secret with what is saved, so leaving them out would not keep them either. The secret
  // can't be cleared (Valtimo keeps a stored secret when it is saved empty); the backend ignores it.
  private clientSecretLoginValue(): Partial<GraphMailPluginConfig> {
    if (!this.clientSecretLoginShown) return {tenantId: undefined, clientId: undefined, clientSecret: undefined};
    return {
      tenantId: this.clientSecretTenantId || undefined,
      clientId: this.clientSecretClientId || undefined,
      clientSecret: this.clientSecretEntered ? this.clientSecretValue : undefined,
    };
  }

  private loadLoginOptions(): Subscription {
    return this.pluginManagementService
      .getPluginConfigurationsByCategory('http-client-authentication')
      .pipe(catchError(() => of([] as Array<PluginConfiguration>)))
      .subscribe(configurations => {
        this.loginOptions = configurations
          .filter(configuration => !!configuration.id)
          .map(configuration => ({id: configuration.id as string, title: configuration.title}));
        this.updateUnknownLogin();
        this.onLoginChange();
      });
  }

  private updateUnknownLogin(): void {
    const stored = this.storedLoginConfiguration;
    this.unknownLoginConfiguration =
      stored && this.loginOptions && !this.loginOptions.some(option => option.id === stored) ? stored : null;
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

  private updateValidAndVisibility(formValue: GraphMailPluginConfig): void {
    const loginUsable =
      (!!this.loginConfigurationValue && !this.loginSelectionUnknown) ||
      this.clientSecretLoginUsable;

    const valid = !!(
      formValue.configurationTitle &&
      formValue.allowedSenders &&
      !this.allowedSendersInvalid &&
      !this.baseUrlInvalid &&
      loginUsable
    );
    this.valid$.next(valid);
    this.valid.emit(valid);

    this.testSectionVisible = loginUsable;
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
