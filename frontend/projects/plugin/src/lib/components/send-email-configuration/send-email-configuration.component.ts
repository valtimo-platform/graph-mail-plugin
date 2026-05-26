import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FunctionConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, InputModule} from '@valtimo/components';
import {BehaviorSubject, combineLatest, Observable, Subscription, take} from 'rxjs';
import {SendEmailActionConfig} from '../../models';

@Component({
  selector: 'valtimo-send-email-configuration',
  templateUrl: './send-email-configuration.component.html',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule],
})
export class SendEmailConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<SendEmailActionConfig>;

  @Output() valid = new EventEmitter<boolean>();
  @Output() configuration = new EventEmitter<SendEmailActionConfig>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<SendEmailActionConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  ngOnInit(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid && formValue) {
            this.configuration.emit(formValue);
          }
        });
    });
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
  }

  // Reject CR/LF in any header-bearing field — same guard exists server-side
  // (requireNoControlChars in GraphMailValidation.kt) for defence in depth.
  private hasControlChars(value: string | undefined | null): boolean {
    return !!value && /[\r\n]/.test(value);
  }

  formValueChange(formValue: SendEmailActionConfig): void {
    this.formValue$.next(formValue);
    const headerFields = [
      formValue.senderMailbox,
      formValue.recipients,
      formValue.cc,
      formValue.bcc,
      formValue.replyTo,
      formValue.subject,
    ];
    const noInjection = !headerFields.some(f => this.hasControlChars(f));
    const valid = !!(
      formValue.senderMailbox &&
      formValue.recipients &&
      formValue.subject &&
      formValue.contentId &&
      noInjection
    );
    this.valid$.next(valid);
    this.valid.emit(valid);
  }
}
