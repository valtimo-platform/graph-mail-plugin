import {HttpClient} from '@angular/common/http';
import {ConfigService} from '@valtimo/shared';
import {of} from 'rxjs';
import {GraphMailPluginConfigurationComponent} from './graph-mail-configuration.component';
import {GraphMailPluginConfig} from '../../models';

// These specs instantiate the component directly rather than through TestBed: none of the
// behaviour under test touches the template, so a plain constructor call keeps the suite fast
// and avoids standing up Angular's DI/compiler machinery for logic-only assertions.
describe('GraphMailPluginConfigurationComponent', () => {
  let httpSpy: jasmine.SpyObj<HttpClient>;
  let configServiceStub: Pick<ConfigService, 'config'>;
  let component: GraphMailPluginConfigurationComponent;

  const baseFormValue: GraphMailPluginConfig = {
    configurationId: '00000000-0000-0000-0000-000000000000',
    configurationTitle: 'Test config',
    tenantId: '11111111-1111-1111-1111-111111111111',
    clientId: '22222222-2222-2222-2222-222222222222',
    clientSecret: undefined,
    allowedSenders: 'noreply@gemeente.nl',
  };

  beforeEach(() => {
    httpSpy = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    configServiceStub = {config: {valtimoApi: {endpointUri: '/api/'}} as any};
    component = new GraphMailPluginConfigurationComponent(httpSpy, configServiceStub as ConfigService);
    component.pluginId = 'entra';
  });

  // ── allowedSenders validation (mirrors GraphMailValidation.kt's EMAIL_REGEX) ──────────

  it('accepts a full email address in the allowlist', () => {
    component.formValueChange({...baseFormValue, allowedSenders: 'noreply@gemeente.nl'});
    expect(component.allowedSendersInvalid).toBeFalse();
  });

  it('accepts a domain entry in the allowlist', () => {
    component.formValueChange({...baseFormValue, allowedSenders: '@gemeente.nl'});
    expect(component.allowedSendersInvalid).toBeFalse();
  });

  it('accepts multiple comma-separated allowlist entries', () => {
    component.formValueChange({
      ...baseFormValue,
      allowedSenders: 'noreply@gemeente.nl, zaken@gemeente.nl, @vergunningen.gemeente.nl',
    });
    expect(component.allowedSendersInvalid).toBeFalse();
  });

  it('rejects an allowlist entry that is neither an email nor a @domain', () => {
    component.formValueChange({...baseFormValue, allowedSenders: 'not-an-address'});
    expect(component.allowedSendersInvalid).toBeTrue();
  });

  it('rejects a bare domain without the leading @', () => {
    component.formValueChange({...baseFormValue, allowedSenders: 'gemeente.nl'});
    expect(component.allowedSendersInvalid).toBeTrue();
  });

  it('rejects the allowlist when only blank entries remain after trimming', () => {
    component.formValueChange({...baseFormValue, allowedSenders: ' , , '});
    expect(component.allowedSendersInvalid).toBeTrue();
  });

  // ── tenantId / clientId UUID validation ────────────────────────────────────────────────

  it('flags a non-UUID tenantId as invalid', () => {
    component.formValueChange({...baseFormValue, tenantId: 'not-a-uuid'});
    expect(component.tenantIdInvalid).toBeTrue();
  });

  it('accepts a well-formed UUID tenantId', () => {
    component.formValueChange(baseFormValue);
    expect(component.tenantIdInvalid).toBeFalse();
  });

  // ── Changing the sender allowlist requires the client secret ───────────────────────────
  // The allowlist bounds which mailboxes this plugin may send as, so widening it is a privilege
  // escalation. The backend enforces the same rule in AllowedSendersChangeGuard; these specs cover
  // the immediate feedback in the form.

  // Emitted validity is what actually gates the save button.
  const validityOf = (comp: GraphMailPluginConfigurationComponent): boolean =>
    (comp as any).valid$.getValue();

  const existingConfigWithAllowlist = (allowlist: string): void => {
    component.savedConfigurationId = '33333333-3333-3333-3333-333333333333';
    (component as any).originalAllowedSenders = allowlist;
  };

  it('keeps an existing configuration saveable without the secret when nothing changed', () => {
    existingConfigWithAllowlist('noreply@gemeente.nl');
    component.formValueChange({...baseFormValue, allowedSenders: 'noreply@gemeente.nl'});

    expect(component.secretRequiredForAllowlistChange).toBeFalse();
    expect(validityOf(component)).toBeTrue();
  });

  it('does not treat reordering or respacing the allowlist as a change', () => {
    existingConfigWithAllowlist('noreply@gemeente.nl, @gemeente.nl');
    component.formValueChange({
      ...baseFormValue,
      allowedSenders: '@GEMEENTE.NL,  noreply@Gemeente.nl ',
    });

    expect(component.secretRequiredForAllowlistChange).toBeFalse();
    expect(validityOf(component)).toBeTrue();
  });

  it('does not treat a repeated allowlist entry as a change', () => {
    // Must agree with the backend, which compares as a Set. Disagreeing here means the form demands
    // the secret for a list the backend would have accepted unchanged.
    existingConfigWithAllowlist('noreply@gemeente.nl');
    component.formValueChange({
      ...baseFormValue,
      allowedSenders: 'noreply@gemeente.nl,noreply@gemeente.nl',
    });

    expect(component.secretRequiredForAllowlistChange).toBeFalse();
    expect(validityOf(component)).toBeTrue();
  });

  it('does not treat trailing or repeated separators as a change', () => {
    existingConfigWithAllowlist('noreply@gemeente.nl,@gemeente.nl');
    component.formValueChange({
      ...baseFormValue,
      allowedSenders: 'noreply@gemeente.nl,,@gemeente.nl,',
    });

    expect(component.secretRequiredForAllowlistChange).toBeFalse();
    expect(validityOf(component)).toBeTrue();
  });

  it('treats the bracketed list form as the same list as the comma-separated one', () => {
    // The backend's parseStringListParam accepts the JSON-array form. A stored value in that shape
    // must not make every subsequent edit look like a change.
    existingConfigWithAllowlist('["noreply@gemeente.nl","@gemeente.nl"]');
    component.formValueChange({
      ...baseFormValue,
      allowedSenders: 'noreply@gemeente.nl,@gemeente.nl',
    });

    expect(component.secretRequiredForAllowlistChange).toBeFalse();
    expect(validityOf(component)).toBeTrue();
  });

  it('blocks saving a changed allowlist while the secret is empty', () => {
    existingConfigWithAllowlist('noreply@gemeente.nl');
    component.formValueChange({
      ...baseFormValue,
      allowedSenders: 'noreply@gemeente.nl,ceo@gemeente.nl',
    });

    expect(component.secretRequiredForAllowlistChange).toBeTrue();
    expect(validityOf(component)).toBeFalse();
  });

  it('allows saving a changed allowlist once the secret is re-entered', () => {
    existingConfigWithAllowlist('noreply@gemeente.nl');
    component.formValueChange({
      ...baseFormValue,
      allowedSenders: 'noreply@gemeente.nl,ceo@gemeente.nl',
    });
    expect(validityOf(component)).toBeFalse();

    component.clientSecretValue = 's3cret';
    component.onSecretChange();

    expect(component.secretRequiredForAllowlistChange).toBeFalse();
    expect(validityOf(component)).toBeTrue();
  });

  it('treats removing an allowlist entry as a change too', () => {
    existingConfigWithAllowlist('noreply@gemeente.nl,ceo@gemeente.nl');
    component.formValueChange({...baseFormValue, allowedSenders: 'noreply@gemeente.nl'});

    expect(component.secretRequiredForAllowlistChange).toBeTrue();
    expect(validityOf(component)).toBeFalse();
  });

  it('captures the stored allowlist from the prefill so a change can be detected', () => {
    component.prefillConfiguration$ = of({
      ...baseFormValue,
      id: 'ccc',
      allowedSenders: 'noreply@gemeente.nl',
    } as any);
    component.save$ = of();
    httpSpy.get.and.returnValue(of([]));

    component.ngOnInit();

    expect((component as any).originalAllowedSenders).toBe('noreply@gemeente.nl');
  });

  it('still requires the secret for a brand new configuration', () => {
    component.savedConfigurationId = null;
    component.formValueChange({...baseFormValue, allowedSenders: 'noreply@gemeente.nl'});

    expect(validityOf(component)).toBeFalse();
  });

  // ── canSendTest: the ambiguous-configuration guard ─────────────────────────────────────
  // Regression coverage for the fix where an unresolved configuration match used to fall back
  // to "the first configuration for this plugin" — silently testing with the wrong credentials.

  it('blocks sending a test email while the configuration is ambiguous, even if otherwise valid', () => {
    component.savedConfigurationId = '33333333-3333-3333-3333-333333333333';
    component.configurationAmbiguous = true;
    (component as any).testSectionVisible = true;
    component.testSenderMailbox = 'noreply@gemeente.nl';
    component.testRecipient = 'burger@example.com';

    expect(component.canSendTest).toBeFalse();
  });

  it('allows sending a test email once the configuration is unambiguous', () => {
    component.savedConfigurationId = '33333333-3333-3333-3333-333333333333';
    component.configurationAmbiguous = false;
    (component as any).testSectionVisible = true;
    component.testSenderMailbox = 'noreply@gemeente.nl';
    component.testRecipient = 'burger@example.com';

    expect(component.canSendTest).toBeTrue();
  });

  it('sendTestEmail is a no-op while the configuration is ambiguous', () => {
    component.savedConfigurationId = '33333333-3333-3333-3333-333333333333';
    component.configurationAmbiguous = true;
    (component as any).testSectionVisible = true;
    component.testSenderMailbox = 'noreply@gemeente.nl';
    component.testRecipient = 'burger@example.com';
    (component as any).formValue$.next(baseFormValue);

    component.sendTestEmail();

    expect(httpSpy.post).not.toHaveBeenCalled();
  });

  // Regression coverage: the URL used to be hardcoded as '/api/v1/plugin/entra/test-send',
  // which silently breaks when frontend and backend are served from different origins.
  // Every first-party Valtimo plugin builds its API URLs from ConfigService instead.
  it('builds the test-send URL from ConfigService.config.valtimoApi.endpointUri', () => {
    configServiceStub.config.valtimoApi.endpointUri = 'https://backend.example.com/api/';
    httpSpy.post.and.returnValue(of({success: true, message: 'ok', statusCode: 202}));
    component.savedConfigurationId = '33333333-3333-3333-3333-333333333333';
    component.configurationAmbiguous = false;
    (component as any).testSectionVisible = true;
    component.testSenderMailbox = 'noreply@gemeente.nl';
    component.testRecipient = 'burger@example.com';
    (component as any).formValue$.next(baseFormValue);

    component.sendTestEmail();

    expect(httpSpy.post).toHaveBeenCalledWith(
      'https://backend.example.com/api/v1/plugin/entra/test-send',
      jasmine.any(Object),
    );
  });

  // ── ngOnInit: configuration resolution against Valtimo's plugin configuration API ──────

  it('marks the configuration ambiguous when multiple matches exist and none matches by title', done => {
    httpSpy.get.and.returnValue(of([
      {id: 'aaa', pluginDefinitionKey: 'entra', configurationTitle: 'Config A'},
      {id: 'bbb', pluginDefinitionKey: 'entra', configurationTitle: 'Config B'},
    ]));
    component.prefillConfiguration$ = of({...baseFormValue, configurationTitle: 'Config C'} as any);
    component.save$ = of();

    component.ngOnInit();

    setTimeout(() => {
      expect(component.configurationAmbiguous).toBeTrue();
      expect(component.savedConfigurationId).toBeNull();
      done();
    });
  });

  it('resolves the configuration unambiguously when the title matches exactly one', done => {
    httpSpy.get.and.returnValue(of([
      {id: 'aaa', pluginDefinitionKey: 'entra', configurationTitle: 'Config A'},
      {id: 'bbb', pluginDefinitionKey: 'entra', configurationTitle: 'Config B'},
    ]));
    component.prefillConfiguration$ = of({...baseFormValue, configurationTitle: 'Config B'} as any);
    component.save$ = of();

    component.ngOnInit();

    setTimeout(() => {
      expect(component.configurationAmbiguous).toBeFalse();
      expect(component.savedConfigurationId).toBe('bbb');
      done();
    });
  });

  it('resolves immediately when the prefill already carries an id', done => {
    component.prefillConfiguration$ = of({...baseFormValue, id: 'ccc'} as any);
    component.save$ = of();

    component.ngOnInit();

    setTimeout(() => {
      expect(httpSpy.get).not.toHaveBeenCalled();
      expect(component.configurationAmbiguous).toBeFalse();
      expect(component.savedConfigurationId).toBe('ccc');
      done();
    });
  });
});
