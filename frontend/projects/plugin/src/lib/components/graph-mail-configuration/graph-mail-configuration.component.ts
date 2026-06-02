import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {HttpClient} from '@angular/common/http';
import {PluginConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, InputModule} from '@valtimo/components';
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

  // Inline validation flags
  tenantIdInvalid = false;
  clientIdInvalid = false;

  // Aligned with the backend EMAIL_REGEX in GraphMailValidation.kt — keep in sync.
  private static readonly EMAIL_RE =
    /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,}$/;

  // Azure Tenant IDs and Client IDs are always GUIDs.
  private static readonly UUID_RE =
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

  constructor(private readonly http: HttpClient) {}

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
        });

      // Resolve the saved configuration UUID for the test-send endpoint.
      this.prefillConfiguration$
        .pipe(
          filter(config => !!config),
          take(1),
          switchMap((config: any) => {
            // 1. Some Valtimo versions include the id directly in the prefill object.
            if (config?.id) {
              return of(config.id as string);
            }

            // 2. Fallback: GET /api/v1/plugin/configuration and match by pluginId / title.
            // URL-based UUID extraction was removed — it is too fragile when the URL contains
            // multiple UUIDs (case IDs, document IDs, etc.) and could select the wrong config.
            return this.http.get<any[]>('/api/v1/plugin/configuration').pipe(
              map(configs => {
                const allForPlugin = configs.filter(c =>
                  c.pluginDefinitionKey === this.pluginId ||
                  c.definitionKey === this.pluginId ||
                  c.pluginDefinition?.key === this.pluginId
                );

                if (allForPlugin.length === 1) {
                  return (allForPlugin[0].id as string) ?? null;
                }

                const byTitle = allForPlugin.find(c =>
                  c.title === (config as any).configurationTitle ||
                  c.configurationTitle === (config as any).configurationTitle
                );
                return (byTitle?.id ?? allForPlugin[0]?.id ?? null) as string | null;
              }),
              catchError(() => of(null))
            );
          })
        )
        .subscribe(id => {
          this.savedConfigurationId = id;
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

    this.updateValidAndVisibility(formValue);
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
    const secretValid = isNewConfiguration ? !!this.clientSecretValue : true;

    const valid = !!(
      formValue.configurationTitle &&
      formValue.tenantId &&
      !this.tenantIdInvalid &&
      formValue.clientId &&
      !this.clientIdInvalid &&
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
    if (!form || !this.canSendTest || !this.savedConfigurationId) return;

    this.testLoading = true;
    this.testStatus = null;

    this.testEmailSubscription?.unsubscribe();
    this.testEmailSubscription = this.http
      .post<TestSendStatus>('/api/v1/plugin/entra/test-send', {
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
