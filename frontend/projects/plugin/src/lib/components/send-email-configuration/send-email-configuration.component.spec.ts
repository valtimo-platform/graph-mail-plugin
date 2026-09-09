import {SendEmailConfigurationComponent} from './send-email-configuration.component';
import {SendEmailActionConfig} from '../../models';

// Instantiated directly (no TestBed) — formValueChange is pure logic and doesn't touch the
// template, so a plain constructor call is enough and keeps the suite fast.
describe('SendEmailConfigurationComponent', () => {
  let component: SendEmailConfigurationComponent;
  let lastEmittedValid: boolean | undefined;

  const validConfig: SendEmailActionConfig = {
    senderMailbox: 'pv:senderMailbox',
    recipients: 'pv:recipients',
    subject: 'pv:subject',
    contentId: 'pv:contentId',
  } as SendEmailActionConfig;

  beforeEach(() => {
    component = new SendEmailConfigurationComponent();
    lastEmittedValid = undefined;
    component.valid.subscribe(v => (lastEmittedValid = v));
  });

  it('is valid when all required fields are present without control characters', () => {
    component.formValueChange(validConfig);
    expect(lastEmittedValid).toBeTrue();
  });

  it('is invalid when senderMailbox is missing', () => {
    component.formValueChange({...validConfig, senderMailbox: ''});
    expect(lastEmittedValid).toBeFalse();
  });

  it('is invalid when recipients is missing', () => {
    component.formValueChange({...validConfig, recipients: ''});
    expect(lastEmittedValid).toBeFalse();
  });

  // Mirrors the backend guard (requireNoControlChars in GraphMailValidation.kt) — CR/LF in a
  // header-bearing field could be used for header injection against the Graph API request.
  it('is invalid when senderMailbox contains a CR', () => {
    component.formValueChange({...validConfig, senderMailbox: 'ok@test.nl\rBcc: evil@test.nl'});
    expect(lastEmittedValid).toBeFalse();
  });

  it('is invalid when subject contains an LF', () => {
    component.formValueChange({...validConfig, subject: 'Hello\nBcc: evil@test.nl'});
    expect(lastEmittedValid).toBeFalse();
  });

  it('is invalid when cc contains CRLF injection', () => {
    component.formValueChange({...validConfig, cc: 'ok@test.nl\r\nBcc: evil@test.nl'});
    expect(lastEmittedValid).toBeFalse();
  });

  it('is valid when optional fields (cc, bcc, replyTo) are absent', () => {
    component.formValueChange({...validConfig, cc: undefined, bcc: undefined, replyTo: undefined});
    expect(lastEmittedValid).toBeTrue();
  });
});
