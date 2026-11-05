import {CommonModule} from '@angular/common';
import {CUSTOM_ELEMENTS_SCHEMA} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {FormsModule} from '@angular/forms';
import {HttpClient} from '@angular/common/http';
import {ConfigService} from '@valtimo/shared';
import {PluginManagementService, PluginTranslatePipeModule, PluginTranslationService} from '@valtimo/plugin';
import {Observable, of, Subject, throwError} from 'rxjs';
import {GraphMailPluginConfigurationComponent} from './graph-mail-configuration.component';
import {GraphMailPluginConfig} from '../../models';

// These specs instantiate the component directly rather than through TestBed: none of the
// behaviour under test touches the template, so a plain constructor call keeps the suite fast
// and avoids standing up Angular's DI/compiler machinery for logic-only assertions.
describe('GraphMailPluginConfigurationComponent', () => {
  let httpSpy: jasmine.SpyObj<HttpClient>;
  let configServiceStub: Pick<ConfigService, 'config'>;
  let pluginManagementSpy: jasmine.SpyObj<PluginManagementService>;
  let component: GraphMailPluginConfigurationComponent;

  const createComponent = (): GraphMailPluginConfigurationComponent =>
    new GraphMailPluginConfigurationComponent(
      httpSpy,
      configServiceStub as ConfigService,
      pluginManagementSpy,
    );

  const baseFormValue: GraphMailPluginConfig = {
    configurationId: '00000000-0000-0000-0000-000000000000',
    configurationTitle: 'Test config',
    allowedSenders: 'noreply@gemeente.nl',
  };

  beforeEach(() => {
    httpSpy = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    configServiceStub = {config: {valtimoApi: {endpointUri: '/api/'}} as any};
    pluginManagementSpy = jasmine.createSpyObj<PluginManagementService>('PluginManagementService', [
      'getPluginConfigurationsByCategory',
    ]);
    pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(of([]));
    component = createComponent();
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

  // Emitted validity is what actually gates the save button.
  const validityOf = (comp: GraphMailPluginConfigurationComponent): boolean =>
    (comp as any).valid$.getValue();

  // ── canSendTest: the ambiguous-configuration guard ─────────────────────────────────────
  // Regression coverage for the fix where an unresolved configuration match used to fall back
  // to "the first configuration for this plugin" — silently testing with the wrong login.

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
  // ── The login (authenticationPluginConfiguration) ───────────────────────────────────────

  const entraLogin = {
    id: 'login-1',
    title: 'Graph login',
    properties: {
      authenticationType: 'TOKEN_EXCHANGE',
      tokenEndpoint: 'https://login.microsoftonline.com/tenant/oauth2/v2.0/token',
      clientId: 'client',
      scope: 'https://graph.microsoft.com/.default',
    },
    pluginDefinition: {key: 'http-client-authentication'} as any,
  };

  const loginWith = (id: string, properties: object) => ({...entraLogin, id, title: id, properties});

  // A component for a configuration with [reference] stored, whose select offers [configurations].
  const withStoredReference = (
    reference: string | undefined,
    configurations: Array<any> = [entraLogin],
    stored: object = {},
  ) => {
    pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(of(configurations));
    component = createComponent();
    component.pluginId = 'entra';
    component.prefillConfiguration$ = of(
      {...baseFormValue, id: 'ccc', authenticationPluginConfiguration: reference, ...stored} as any,
    );
    const save$ = new Subject<void>();
    component.save$ = save$;
    component.ngOnInit();
    const values: GraphMailPluginConfig[] = [];
    component.configuration.subscribe(value => values.push(value));
    return {save$, values};
  };

  it('offers the Http Client Authentication configurations, with the stored one selected', () => {
    withStoredReference('login-1');

    expect(pluginManagementSpy.getPluginConfigurationsByCategory).toHaveBeenCalledWith('http-client-authentication');
    expect(component.loginOptions).toEqual([{id: 'login-1', title: 'Graph login'}]);
    expect(component.loginConfigurationValue).toBe('login-1');
    expect(component.unknownLoginConfiguration).toBeNull();
  });

  it('requires a login: a new configuration without one cannot be saved', () => {
    const {save$, values} = withStoredReference(undefined);
    component.formValueChange({...baseFormValue});

    expect(validityOf(component)).toBeFalse();
    expect(component.testSectionVisible).toBeFalse();
    save$.next();
    expect(values).toEqual([]);

    component.loginConfigurationValue = 'login-1';
    component.onLoginChange();

    expect(validityOf(component)).toBeTrue();
    expect(component.testSectionVisible).toBeTrue();
  });

  it('saves the selected login, and nothing of the 1.x login', () => {
    const {save$, values} = withStoredReference('login-1');
    component.formValueChange({...baseFormValue});

    save$.next();

    expect(values[0].authenticationPluginConfiguration).toBe('login-1');
    expect(values[0].clientSecret).toBeUndefined();
    expect(values[0].tenantId).toBeUndefined();
    expect(values[0].clientId).toBeUndefined();
  });

  // ── The Graph API URL (baseUrl) ───────────────────────────────────────────────────

  it('saves no Graph API URL when none is filled in, so existing configurations keep using Microsoft', () => {
    const {save$, values} = withStoredReference('login-1');
    component.formValueChange({...baseFormValue});

    save$.next();

    expect(values[0].baseUrl).toBeUndefined();
  });

  it('shows and saves the stored Graph API URL, trimmed', () => {
    const {save$, values} = withStoredReference('login-1', [entraLogin], {baseUrl: 'https://gw.example.nl/graph'});
    component.formValueChange({...baseFormValue});

    expect(component.baseUrlValue).toBe('https://gw.example.nl/graph');

    component.baseUrlValue = ' https://gw.example.nl/other ';
    component.onLoginChange();
    save$.next();

    expect(values[0].baseUrl).toBe('https://gw.example.nl/other');
  });

  it('refuses a Graph API URL that is not a plain https URL', () => {
    withStoredReference('login-1');
    component.formValueChange({...baseFormValue});

    [
      'http://gw.example.nl',
      'gw.example.nl',
      'https://user@gw.example.nl',
      'https://gw.example.nl/graph?x=1',
      'https://gw.example.nl/graph#x',
      'https://gw.example.nl/gr aph',
    ].forEach(url => {
      component.baseUrlValue = url;
      component.onLoginChange();
      expect(component.baseUrlInvalid).withContext(url).toBeTrue();
      expect(validityOf(component)).withContext(url).toBeFalse();
    });
  });

  it('clears the Graph API URL when the client-secret login is chosen instead', () => {
    const {save$, values} = withStoredReference('login-1', [entraLogin], {baseUrl: 'https://gw.example.nl'});
    component.formValueChange({...baseFormValue});
    component.loginConfigurationValue = '';
    component.clientSecretTenantId = 'contoso.onmicrosoft.com';
    component.clientSecretClientId = 'client-1';
    component.clientSecretValue = 'secret-1';
    component.onLoginChange();

    save$.next();

    expect(values[0].baseUrl).toBeUndefined();
  });

  // ── The deprecated client-secret login of 1.x ──────────────────────────────────────────

  const clientSecretLogin = {tenantId: '00000000-0000-0000-0000-000000000001', clientId: 'client-1'};

  it('keeps a 1.x configuration valid and testable, without asking for the secret again', () => {
    const {save$, values} = withStoredReference(undefined, [entraLogin], clientSecretLogin);
    component.formValueChange({...baseFormValue, allowedSenders: 'noreply@gemeente.nl,ceo@gemeente.nl'});

    expect(component.clientSecretLoginShown).toBeTrue();
    expect(component.clientSecretStored).toBeTrue();
    expect(validityOf(component)).toBeTrue();
    expect(component.testSectionVisible).toBeTrue();

    save$.next();

    // Sent back unchanged: Valtimo overwrites the properties that are not secret with what is saved.
    expect(values[0].tenantId).toBe(clientSecretLogin.tenantId);
    expect(values[0].clientId).toBe('client-1');
    expect(values[0].clientSecret).toBeUndefined();
    expect(values[0].authenticationPluginConfiguration).toBe('');
    expect(values[0].allowedSenders).toBe('noreply@gemeente.nl,ceo@gemeente.nl');
  });

  it('saves a changed secret of a 1.x configuration', () => {
    const {save$, values} = withStoredReference(undefined, [entraLogin], clientSecretLogin);
    component.formValueChange({...baseFormValue});
    component.clientSecretValue = 'new-secret';
    component.onLoginChange();

    save$.next();

    expect(values[0].clientSecret).toBe('new-secret');
  });

  it('lets a new configuration use the client secret, with all three filled in', () => {
    const {save$, values} = withStoredReference(undefined);
    component.formValueChange({...baseFormValue});
    component.clientSecretTenantId = 'contoso.onmicrosoft.com';
    component.clientSecretClientId = 'client-1';
    component.onLoginChange();

    expect(validityOf(component)).toBeFalse();

    component.clientSecretValue = 'secret-1';
    component.onLoginChange();

    expect(validityOf(component)).toBeTrue();
    save$.next();
    expect(values[0].clientSecret).toBe('secret-1');
  });

  it('does not accept a secret of only whitespace for a new configuration', () => {
    withStoredReference(undefined);
    component.formValueChange({...baseFormValue});
    component.clientSecretTenantId = 'contoso.onmicrosoft.com';
    component.clientSecretClientId = 'client-1';
    component.clientSecretValue = '   ';
    component.onLoginChange();

    expect(validityOf(component)).toBeFalse();
  });

  it('does not replace the stored secret of a 1.x configuration with whitespace', () => {
    const {save$, values} = withStoredReference(undefined, [entraLogin], clientSecretLogin);
    component.formValueChange({...baseFormValue});
    component.clientSecretValue = '   ';
    component.onLoginChange();

    save$.next();

    expect(values[0].clientSecret).toBeUndefined();
  });

  it('refuses a tenant ID that would change the token URL', () => {
    withStoredReference(undefined, [entraLogin], {...clientSecretLogin, tenantId: 'tenant/../other'});
    component.formValueChange({...baseFormValue});

    expect(component.tenantIdInvalid).toBeTrue();
    expect(validityOf(component)).toBeFalse();
  });

  it('migrates a 1.x configuration by selecting a login: the client-secret fields go, and are cleared', () => {
    const {save$, values} = withStoredReference(undefined, [entraLogin], clientSecretLogin);
    component.formValueChange({...baseFormValue});

    component.loginConfigurationValue = 'login-1';
    component.onLoginChange();

    expect(component.clientSecretLoginShown).toBeFalse();
    expect(validityOf(component)).toBeTrue();
    save$.next();
    expect(values[0].authenticationPluginConfiguration).toBe('login-1');
    expect(values[0].tenantId).toBeUndefined();
    expect(values[0].clientId).toBeUndefined();
  });

  it('keeps a 1.x configuration working when no Http Client Authentication configuration exists', () => {
    withStoredReference(undefined, [], clientSecretLogin);
    component.formValueChange({...baseFormValue});

    expect(component.loginOptions).toEqual([]);
    expect(validityOf(component)).toBeTrue();
  });

  it('changing the allowlist of a saved configuration needs no secret', () => {
    const {save$, values} = withStoredReference('login-1');
    component.formValueChange({...baseFormValue, allowedSenders: 'noreply@gemeente.nl,ceo@gemeente.nl'});

    expect(validityOf(component)).toBeTrue();
    save$.next();
    expect(values[0].allowedSenders).toBe('noreply@gemeente.nl,ceo@gemeente.nl');
  });

  it('accepts a configuration of every authentication type', () => {
    const cases: Array<object> = [
      {authenticationType: 'NONE'},
      {authenticationType: 'BEARER'},
      {authenticationType: 'HEADER', authHeaderName: 'X-Api-Key'},
      {...entraLogin.properties, audience: 'graph'},
      {...entraLogin.properties, scope: ' '},
    ];
    cases.forEach(properties => {
      withStoredReference('other', [loginWith('other', properties)]);
      component.formValueChange({...baseFormValue});

      const context = JSON.stringify(properties);
      expect(validityOf(component)).withContext(context).toBeTrue();
      expect(component.testSectionVisible).withContext(context).toBeTrue();
    });
  });

  it('offers nothing, without failing, when the configurations cannot be loaded', () => {
    pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(throwError(() => new Error('500')));
    component = createComponent();
    component.prefillConfiguration$ = of(undefined as any);
    component.save$ = of();
    component.ngOnInit();

    expect(component.loginOptions).toEqual([]);
    expect(component.loginConfigurationValue).toBe('');
  });

  it('keeps a stored reference that is not offered visible, and blocks saving until another is chosen', () => {
    const {save$, values} = withStoredReference('gone');
    component.formValueChange({...baseFormValue});

    expect(component.unknownLoginConfiguration).toBe('gone');
    expect(component.loginSelectionUnknown).toBeTrue();
    expect(validityOf(component)).toBeFalse();
    save$.next();
    expect(values).toEqual([]);

    component.loginConfigurationValue = 'login-1';
    component.onLoginChange();

    expect(component.loginSelectionUnknown).toBeFalse();
    expect(validityOf(component)).toBeTrue();
    save$.next();
    expect(values[0].authenticationPluginConfiguration).toBe('login-1');
  });

  it('accepts the selected login when the configurations arrive after the prefill', () => {
    const options$ = new Subject<Array<any>>();
    pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(options$);
    component = createComponent();
    component.pluginId = 'entra';
    component.prefillConfiguration$ = of({...baseFormValue, id: 'ccc', authenticationPluginConfiguration: 'other'} as any);
    component.save$ = of();
    component.ngOnInit();
    component.formValueChange({...baseFormValue});

    options$.next([loginWith('other', {authenticationType: 'BEARER'})]);

    expect(validityOf(component)).toBeTrue();
  });

  it('marks a stored reference unknown when the configurations arrive after the prefill', () => {
    const options$ = new Subject<Array<any>>();
    pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(options$);
    component = createComponent();
    component.pluginId = 'entra';
    component.prefillConfiguration$ = of({...baseFormValue, id: 'ccc', authenticationPluginConfiguration: 'gone'} as any);
    component.save$ = of();
    component.ngOnInit();
    component.formValueChange({...baseFormValue});

    options$.next([entraLogin]);

    expect(component.unknownLoginConfiguration).toBe('gone');
    expect(validityOf(component)).toBeFalse();
  });

  it('forgets the saved id of the previous configuration while the next one is being resolved', () => {
    // Otherwise a test mail sent meanwhile would use the configuration the admin left.
    pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(of([entraLogin]));
    httpSpy.get.and.returnValue(new Subject<any[]>());
    const prefill$ = new Subject<GraphMailPluginConfig>();
    component = createComponent();
    component.pluginId = 'entra';
    component.prefillConfiguration$ = prefill$;
    component.save$ = of();
    component.ngOnInit();

    prefill$.next({...baseFormValue, id: 'aaa', authenticationPluginConfiguration: 'login-1'} as any);
    expect(component.savedConfigurationId).toBe('aaa');
    prefill$.next({...baseFormValue, configurationTitle: 'B', authenticationPluginConfiguration: 'login-1'} as any);

    expect(component.savedConfigurationId).toBeNull();
  });

  it('a second prefill replaces the state of the first', () => {
    // Valtimo reuses the screen when the admin opens another configuration: what was read from the
    // first must not be saved onto the second.
    pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(of([entraLogin]));
    const prefill$ = new Subject<GraphMailPluginConfig>();
    component = createComponent();
    component.pluginId = 'entra';
    component.prefillConfiguration$ = prefill$;
    component.save$ = of();
    component.ngOnInit();

    prefill$.next({...baseFormValue, id: 'aaa', authenticationPluginConfiguration: 'login-1', testSenderMailbox: 'a@gemeente.nl'} as any);
    component.testStatus = {success: true, message: 'sent with A', statusCode: 202};
    component.configurationAmbiguous = true;
    prefill$.next({...baseFormValue, id: 'bbb', authenticationPluginConfiguration: 'gone'} as any);

    expect(component.testStatus).toBeNull();
    expect(component.configurationAmbiguous).toBeFalse();

    expect(component.savedConfigurationId).toBe('bbb');
    expect(component.loginConfigurationValue).toBe('gone');
    expect(component.unknownLoginConfiguration).toBe('gone');
    expect(component.testSenderMailbox).toBe('');
  });

  // ── What the screen shows ───────────────────────────────────────────────────────────────

  describe('template', () => {
    // Renders the login select and its messages; v-form and v-input are left out (custom elements), and
    // translations come back as their keys.
    const render = async (
      reference: string,
      configurations: Array<any> | Observable<Array<any>>,
      disabled = false,
    ): Promise<{element: HTMLElement; fixture: any}> => {
      pluginManagementSpy.getPluginConfigurationsByCategory.and.returnValue(
        Array.isArray(configurations) ? of(configurations) : configurations,
      );
      httpSpy.get.and.returnValue(of([]));
      await TestBed.configureTestingModule({
        imports: [GraphMailPluginConfigurationComponent],
        providers: [
          {provide: HttpClient, useValue: httpSpy},
          {provide: ConfigService, useValue: configServiceStub},
          {provide: PluginManagementService, useValue: pluginManagementSpy},
          {provide: PluginTranslationService, useValue: {translate: (key: string) => of(key)}},
        ],
      })
        .overrideComponent(GraphMailPluginConfigurationComponent, {
          set: {imports: [CommonModule, FormsModule, PluginTranslatePipeModule], schemas: [CUSTOM_ELEMENTS_SCHEMA]},
        })
        .compileComponents();
      const fixture = TestBed.createComponent(GraphMailPluginConfigurationComponent);
      fixture.componentInstance.pluginId = 'entra';
      fixture.componentInstance.disabled$ = of(disabled);
      fixture.componentInstance.save$ = of();
      fixture.componentInstance.prefillConfiguration$ = of(
        {...baseFormValue, id: 'ccc', authenticationPluginConfiguration: reference} as any,
      );
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      return {element: fixture.nativeElement as HTMLElement, fixture};
    };

    it('offers a stored reference that is not in the list as an unknown configuration', async () => {
      const {element} = await render('gone', [entraLogin]);

      const options = Array.from(element.querySelectorAll('option')).map(option => option.textContent ?? '');
      expect(options.some(text => text.includes('authenticationPluginConfigurationUnknown') && text.includes('gone')))
        .withContext(options.join(' | '))
        .toBeTrue();
      expect(element.textContent).toContain('authenticationPluginConfigurationMissing');
    });

    it('says so when there are no Http Client Authentication configurations', async () => {
      const {element} = await render('', []);

      expect(element.textContent).toContain('authenticationPluginConfigurationNone');
    });

    it('keeps the select disabled until the configurations are loaded', async () => {
      const configurations$ = new Subject<Array<any>>();
      const {element, fixture} = await render('login-1', configurations$);
      const select = element.querySelector('select') as HTMLSelectElement;
      expect(select.disabled).toBeTrue();

      configurations$.next([entraLogin]);
      fixture.detectChanges();
      await fixture.whenStable();

      expect(select.disabled).toBeFalse();
    });

    it('disables the select when the form is disabled', async () => {
      const {element} = await render('login-1', [entraLogin], true);

      expect((element.querySelector('select') as HTMLSelectElement).disabled).toBeTrue();
    });
  });
});
