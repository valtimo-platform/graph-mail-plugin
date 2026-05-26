interface SendEmailActionConfig {
  senderMailbox: string;
  recipients: string;
  cc?: string;
  bcc?: string;
  replyTo?: string;
  subject: string;
  contentId: string;
  attachmentIds?: string;
}

export {SendEmailActionConfig};
