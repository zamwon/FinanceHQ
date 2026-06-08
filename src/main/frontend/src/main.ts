import { bootstrapApplication } from '@angular/platform-browser';
import * as Sentry from '@sentry/angular';
import { captureConsoleIntegration } from '@sentry/browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { environment } from './environments/environment';

if (environment.sentryDsn) {
  Sentry.init({
    dsn: environment.sentryDsn,
    tunnel: '/sentry-tunnel',
    environment: 'production',
    release: environment.sentryRelease || undefined,
    tracesSampleRate: 0,
    integrations: [captureConsoleIntegration({ levels: ['warn', 'error'] })],
    beforeSend(event) {
      const emailRegex = /[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}/g;
      if (event.message) {
        event.message = event.message.replace(emailRegex, '[scrubbed]');
      }
      return event;
    }
  });
}

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
